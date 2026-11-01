import Foundation
import Testing
@testable import OrchardKit

@Suite struct MicrosoftAuthTests {

    private func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
    }

    @Test func signInAsksPersonalAccountsForXboxLiveWithAProofOfTheCode() {
        let attempt = MicrosoftAuth(clientID: "abc").attempt()
        let items = query(attempt.url)
        #expect(attempt.url.host == "login.microsoftonline.com")
        #expect(attempt.url.path == "/consumers/oauth2/v2.0/authorize")
        #expect(items["client_id"] == "abc")
        #expect(items["scope"] == "XboxLive.signin offline_access")
        #expect(items["redirect_uri"] == "orchard://auth")
        #expect(items["code_challenge_method"] == "S256")
        #expect(items["code_challenge"]?.count == 43)
        #expect(items["state"] == attempt.state)
    }

    @Test func aRefusedSignInSaysWhy() async {
        let auth = MicrosoftAuth(clientID: "abc")
        let attempt = auth.attempt()
        let callback = URL(string: "orchard://auth?error=access_denied&error_description=The%20user%20said%20no&state=\(attempt.state)")!
        await #expect(throws: MicrosoftAuthError.refused("The user said no")) {
            _ = try await auth.finish(attempt, callback: callback)
        }
    }

    @Test func aReplyForAnotherSignInIsTurnedAway() async {
        let auth = MicrosoftAuth(clientID: "abc")
        await #expect(throws: MicrosoftAuthError.unexpectedReply("Microsoft")) {
            _ = try await auth.finish(auth.attempt(), callback: URL(string: "orchard://auth?code=x&state=someone-else")!)
        }
    }

    @Test func formValuesAreEncodedWhole() {
        #expect(MicrosoftAuth.formEncoded("M.C1_a+b/c=d!*~") == "M.C1_a%2Bb%2Fc%3Dd%21%2A~")
        #expect(MicrosoftAuth.base64URL(Data([0xFB, 0xFF])) == "-_8")
    }

    @Test func offlinePlayersHaveNoSession() {
        let player = Player.offline(OfflineAccount(name: "Notch"))
        #expect(!player.online)
        #expect(player.accessToken == "0")
        #expect(player.uuid == OfflineAccount(name: "Notch").uuid)
    }
}
