import CryptoKit
import Foundation
import Security

/// Who the game is started as: a Microsoft account's Minecraft profile and session, or an
/// offline name.
public struct Player: Sendable, Equatable {
    public var name: String
    /// The profile's UUID, without dashes.
    public var uuid: String
    public var accessToken: String
    /// Signed in, so the game talks to Mojang's services and can join online servers.
    public var online: Bool
    /// The Microsoft app the session came through, for `--clientId`.
    public var clientID: String?

    public init(name: String, uuid: String, accessToken: String, online: Bool, clientID: String?) {
        self.name = name
        self.uuid = uuid
        self.accessToken = accessToken
        self.online = online
        self.clientID = clientID
    }

    public static func offline(_ account: OfflineAccount) -> Player {
        Player(name: account.name, uuid: account.uuid, accessToken: "0", online: false, clientID: nil)
    }
}

/// A Microsoft account signed in to Orchard, by its Minecraft profile. What keeps it signed in is
/// in the Keychain (`TokenKeychain`).
public struct MicrosoftAccount: Codable, Sendable, Equatable {
    public var name: String
    public var uuid: String

    public init(name: String, uuid: String) {
        self.name = name
        self.uuid = uuid
    }
}

/// Microsoft's refresh token and the last Minecraft session made with it.
public struct MicrosoftTokens: Codable, Sendable, Equatable {
    public var refreshToken: String
    public var minecraftToken: String
    public var minecraftExpiry: Date
}

/// Signing in as Mojang's launcher does: Microsoft (OAuth with PKCE, personal accounts), then
/// Xbox Live, XSTS for Minecraft's services, the Minecraft session, and its profile. Minecraft's
/// services only accept Microsoft apps Mojang has approved (aka.ms/mce-reviewappid); others get a
/// 403 at the Minecraft step, which is `notApproved`.
public struct MicrosoftAuth: Sendable {

    /// Orchard's own Azure app: personal Microsoft accounts only, with `redirectURI` as a
    /// "Mobile and desktop applications" redirect. Empty until it exists.
    public static let builtInClientID = ""
    /// Where a client ID can be tried before it is built in:
    /// `defaults write com.lackothy.Orchard microsoftClientID <id>`.
    public static let clientIDKey = "microsoftClientID"
    public static let callbackScheme = "orchard"
    static let redirectURI = "orchard://auth"
    static let scope = "XboxLive.signin offline_access"

    public let clientID: String
    private let session: URLSession

    public init(clientID: String, session: URLSession = .shared) {
        self.clientID = clientID
        self.session = session
    }

    /// The app to sign in through, or nil when Orchard has none yet.
    public static var configured: MicrosoftAuth? {
        let id = UserDefaults.standard.string(forKey: clientIDKey)?.trimmingCharacters(in: .whitespaces).nilIfBlank
            ?? builtInClientID.nilIfBlank
        return id.map { MicrosoftAuth(clientID: $0) }
    }

    /// One sign-in: the page to open, and what the page's reply has to match.
    public struct Attempt: Sendable {
        public let url: URL
        let verifier: String
        let state: String
    }

    public func attempt() -> Attempt {
        let verifier = Self.random(32)
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let state = Self.random(16)
        var url = URLComponents(string: "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")!
        url.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "scope", value: Self.scope),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        return Attempt(url: url.url!, verifier: verifier, state: state)
    }

    /// Finishes a sign-in from the address the page sent the browser back to.
    public func finish(_ attempt: Attempt, callback: URL) async throws -> (MicrosoftAccount, MicrosoftTokens) {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func item(_ name: String) -> String? { items.first(where: { $0.name == name })?.value }
        if let error = item("error") {
            throw MicrosoftAuthError.refused(item("error_description") ?? error)
        }
        guard item("state") == attempt.state, let code = item("code") else {
            throw MicrosoftAuthError.unexpectedReply("Microsoft")
        }
        let tokens = try await microsoftTokens([
            "grant_type": "authorization_code", "code": code, "redirect_uri": Self.redirectURI,
            "code_verifier": attempt.verifier,
        ])
        return try await minecraft(tokens)
    }

    /// A session to play with: the saved one while it has ten minutes left, else a new one made
    /// with the refresh token. Save the tokens it returns: Microsoft renews the refresh token.
    public func session(for account: MicrosoftAccount, tokens: MicrosoftTokens) async throws -> (MicrosoftAccount, MicrosoftTokens) {
        if tokens.minecraftExpiry.timeIntervalSinceNow > 10 * 60 {
            return (account, tokens)
        }
        return try await minecraft(microsoftTokens(["grant_type": "refresh_token", "refresh_token": tokens.refreshToken]))
    }

    // MARK: - The steps

    private struct OAuthTokens: Decodable {
        var access_token: String
        var refresh_token: String
    }

    private struct OAuthError: Decodable {
        var error: String
        var error_description: String?
    }

    private func microsoftTokens(_ form: [String: String]) async throws -> OAuthTokens {
        var request = URLRequest(url: URL(string: "https://login.microsoftonline.com/consumers/oauth2/v2.0/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let fields = form.merging(["client_id": clientID, "scope": Self.scope]) { mine, _ in mine }
        request.httpBody = Data(fields.map { "\(Self.formEncoded($0.key))=\(Self.formEncoded($0.value))" }
            .joined(separator: "&").utf8)
        do {
            return try await send(request, as: OAuthTokens.self, from: "Microsoft")
        } catch HTTPFailure.status(_, let data) {
            let reply = try? JSONDecoder().decode(OAuthError.self, from: data)
            // The refresh token was revoked or has expired (90 days unused).
            if reply?.error == "invalid_grant" { throw MicrosoftAuthError.signInAgain }
            throw MicrosoftAuthError.refused(reply?.error_description ?? reply?.error ?? "no reason given")
        }
    }

    private struct XboxReply: Decodable {
        struct Claims: Decodable {
            struct User: Decodable { var uhs: String }
            var xui: [User]
        }
        var Token: String
        var DisplayClaims: Claims
    }

    private struct XboxError: Decodable {
        var XErr: Int64
    }

    private struct MinecraftLogin: Decodable {
        var access_token: String
        var expires_in: Double
    }

    private struct Profile: Decodable {
        var id: String
        var name: String
    }

    private func minecraft(_ microsoft: OAuthTokens) async throws -> (MicrosoftAccount, MicrosoftTokens) {
        let xbox = try await send(json(URL(string: "https://user.auth.xboxlive.com/user/authenticate")!, [
            "Properties": ["AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": "d=\(microsoft.access_token)"],
            "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT",
        ]), as: XboxReply.self, from: "Xbox Live")
        let xsts: XboxReply
        do {
            xsts = try await send(json(URL(string: "https://xsts.auth.xboxlive.com/xsts/authorize")!, [
                "Properties": ["SandboxId": "RETAIL", "UserTokens": [xbox.Token]],
                "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT",
            ]), as: XboxReply.self, from: "Xbox Live")
        } catch HTTPFailure.status(401, let data) {
            throw MicrosoftAuthError.xbox((try? JSONDecoder().decode(XboxError.self, from: data))?.XErr ?? 0)
        }
        guard let hash = xsts.DisplayClaims.xui.first?.uhs ?? xbox.DisplayClaims.xui.first?.uhs else {
            throw MicrosoftAuthError.unexpectedReply("Xbox Live")
        }

        let login: MinecraftLogin
        do {
            login = try await send(json(URL(string: "https://api.minecraftservices.com/authentication/login_with_xbox")!, [
                "identityToken": "XBL3.0 x=\(hash);\(xsts.Token)",
            ]), as: MinecraftLogin.self, from: "Minecraft")
        } catch HTTPFailure.status(403, _) {
            throw MicrosoftAuthError.notApproved
        }

        var request = URLRequest(url: URL(string: "https://api.minecraftservices.com/minecraft/profile")!)
        request.setValue("Bearer \(login.access_token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let profile: Profile
        do {
            profile = try await send(request, as: Profile.self, from: "Minecraft")
        } catch HTTPFailure.status(404, _) {
            throw MicrosoftAuthError.noMinecraft
        }
        return (MicrosoftAccount(name: profile.name, uuid: profile.id),
                MicrosoftTokens(refreshToken: microsoft.refresh_token, minecraftToken: login.access_token,
                                minecraftExpiry: Date(timeIntervalSinceNow: login.expires_in)))
    }

    // MARK: - HTTP

    private enum HTTPFailure: Error {
        case status(Int, Data)
    }

    private func json(_ url: URL, _ body: [String: Any]) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return request
    }

    /// The reply decoded; an error status throws `HTTPFailure` for the step to say what it means,
    /// and a step that doesn't turns it into `service`.
    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type, from service: String) async throws -> T {
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw HTTPFailure.status(status, data) }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw MicrosoftAuthError.unexpectedReply(service)
        }
    }

    static func formEncoded(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private static func random(_ count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return base64URL(Data(bytes))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

public enum MicrosoftAuthError: LocalizedError, Equatable {
    case notSetUp
    case refused(String)
    case signInAgain
    case xbox(Int64)
    case notApproved
    case noMinecraft
    case unexpectedReply(String)
    case keychain(Int32)

    public var errorDescription: String? {
        switch self {
        case .notSetUp:
            "Signing in with Microsoft needs Orchard’s own Microsoft app, which isn’t set up yet."
        case .refused(let reason):
            "Microsoft didn’t sign you in: \(reason)"
        case .signInAgain:
            "Your Microsoft sign-in has expired. Sign in again from the account menu."
        case .xbox(let code):
            switch code {
            case 2148916233: "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to make one, then try again."
            case 2148916227: "This Microsoft account is banned from Xbox Live."
            case 2148916235: "Xbox Live isn’t available in this account’s country or region."
            case 2148916236, 2148916237: "This account needs adult verification on xbox.com before it can play."
            case 2148916238: "This is a child account: an adult has to add it to a Microsoft family before it can play."
            default: "Xbox Live turned the sign-in down (error \(code))."
            }
        case .notApproved:
            "Minecraft’s services don’t accept Orchard’s Microsoft app yet: Mojang has to approve it first."
        case .noMinecraft:
            "This Microsoft account doesn’t own Minecraft: Java Edition, or hasn’t picked a player name yet "
                + "(open Mojang’s launcher once to pick one)."
        case .unexpectedReply(let service):
            "\(service) sent a reply Orchard couldn’t read."
        case .keychain(let status):
            "Orchard couldn’t keep your sign-in in the Keychain (error \(status))."
        }
    }
}

/// Each signed-in account's tokens, as a generic password in the login keychain.
public enum TokenKeychain {

    static let service = "com.lackothy.Orchard.microsoft"

    public static func save(_ tokens: MicrosoftTokens, for uuid: String) throws {
        let data = try JSONEncoder().encode(tokens)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: uuid]
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrLabel as String] = "Orchard Microsoft sign-in"
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw MicrosoftAuthError.keychain(status) }
    }

    public static func load(for uuid: String) -> MicrosoftTokens? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: uuid, kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(MicrosoftTokens.self, from: data)
    }

    public static func delete(for uuid: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: uuid]
        SecItemDelete(query as CFDictionary)
    }
}
