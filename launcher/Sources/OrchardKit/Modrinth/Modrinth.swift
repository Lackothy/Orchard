import Foundation

/// Modrinth's API (v2): search, projects, versions, and identifying files by hash, which is how
/// the launcher knows what a mod in the folder is and whether it has an update.
///
/// Requests carry a User-Agent naming the launcher, as Modrinth asks of every client.
public struct Modrinth: Sendable {

    public static let api = URL(string: "https://api.modrinth.com/v2/")!
    public static let site = URL(string: "https://modrinth.com/")!

    public enum Sort: String, CaseIterable, Sendable {
        case relevance, downloads, follows, newest, updated
    }

    public struct Page: Sendable {
        public var hits: [ModrinthHit]
        public var total: Int
    }

    public var session: URLSession
    public var userAgent: String

    public init(session: URLSession = .shared, launcherVersion: String = "dev") {
        self.session = session
        userAgent = "lackothy/Orchard-launcher/\(launcherVersion)"
    }

    /// Projects of one kind that have a version for this Minecraft: Fabric mods that run on the
    /// client, resource packs, or shader packs for Iris.
    public func search(_ query: String, kind: ContentKind, minecraft: String, sort: Sort = .relevance,
                       offset: Int = 0, limit: Int = 30) async throws -> Page {
        var facets = [["project_type:\(kind.projectType)"], ["versions:\(minecraft)"]]
        switch kind {
        case .mod: facets += [["categories:fabric"], ["client_side!=unsupported"]]
        case .shader: facets += [["categories:iris"]]
        case .resourcePack: break
        }
        let result: SearchResult = try await get("search", [
            "query": query, "facets": Self.json(facets), "index": sort.rawValue,
            "offset": String(offset), "limit": String(limit),
        ])
        return Page(hits: result.hits, total: result.totalHits)
    }

    /// Search results for exactly these projects, which carry the author and counts a shelf shows.
    public func hits(for projectIDs: [String]) async throws -> [ModrinthHit] {
        let result: SearchResult = try await get("search", [
            "facets": Self.json([projectIDs.map { "project_id:\($0)" }]), "limit": "100",
        ])
        return result.hits
    }

    public func project(_ idOrSlug: String) async throws -> ModrinthProject {
        try await get("project/\(idOrSlug)")
    }

    /// The username of the project's owner.
    public func owner(of project: String) async throws -> String? {
        let members: [ModrinthMember] = try await get("project/\(project)/members")
        return (members.first { $0.role == "Owner" } ?? members.first)?.user.username
    }

    /// The project's versions for this Minecraft and kind, newest first.
    public func versions(of project: String, kind: ContentKind, minecraft: String) async throws -> [ModrinthVersion] {
        try await get("project/\(project)/version", [
            "loaders": Self.json(kind.loaders), "game_versions": Self.json([minecraft]),
        ])
    }

    public func version(_ id: String) async throws -> ModrinthVersion {
        try await get("version/\(id)")
    }

    /// The version each file belongs to, by its SHA-1. Files Modrinth doesn't know are left out.
    public func versions(sha1 hashes: [String]) async throws -> [String: ModrinthVersion] {
        guard !hashes.isEmpty else { return [:] }
        return try await post("version_files", ["hashes": hashes, "algorithm": "sha1"])
    }

    /// For each file, by SHA-1, the newest version of its project for this Minecraft and kind.
    public func latest(sha1 hashes: [String], kind: ContentKind, minecraft: String) async throws -> [String: ModrinthVersion] {
        guard !hashes.isEmpty else { return [:] }
        return try await post("version_files/update", [
            "hashes": hashes, "algorithm": "sha1", "loaders": kind.loaders, "game_versions": [minecraft],
        ])
    }

    /// The project's page on modrinth.com.
    public static func page(of project: String, kind: ContentKind) -> URL {
        site.appending(path: "\(kind.projectType)/\(project)")
    }

    // MARK: - Requests

    private func get<T: Decodable>(_ path: String, _ query: [String: String] = [:]) async throws -> T {
        var components = URLComponents(url: Self.api.appending(path: path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return try await send(URLRequest(url: components.url!))
    }

    private func post<T: Decodable>(_ path: String, _ body: [String: Any]) async throws -> T {
        var request = URLRequest(url: Self.api.appending(path: path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        var request = request
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw LauncherError.download("Modrinth \(request.url?.path() ?? "")", status: http.statusCode)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    static func json(_ value: Any) -> String {
        String(decoding: (try? JSONSerialization.data(withJSONObject: value, options: .withoutEscapingSlashes)) ?? Data(), as: UTF8.self)
    }

    private struct SearchResult: Decodable {
        var hits: [ModrinthHit]
        var totalHits: Int

        enum CodingKeys: String, CodingKey {
            case hits
            case totalHits = "total_hits"
        }
    }
}

/// A project as search lists it.
public struct ModrinthHit: Decodable, Identifiable, Hashable, Sendable {
    public var id: String { projectID }
    public var projectID: String
    public var slug: String
    public var title: String
    public var description: String
    public var author: String
    public var downloads: Int
    public var follows: Int
    public var projectType: String
    public var categories: [String]
    private var iconAddress: String?
    private var featuredGalleryAddress: String?

    public var iconURL: URL? { iconAddress.flatMap(URL.init(string:)) }
    public var featuredImage: URL? { featuredGalleryAddress.flatMap(URL.init(string:)) }
    public var kind: ContentKind? { ContentKind(projectType: projectType) }

    enum CodingKeys: String, CodingKey {
        case slug, title, description, author, downloads, follows, categories
        case projectID = "project_id"
        case projectType = "project_type"
        case iconAddress = "icon_url"
        case featuredGalleryAddress = "featured_gallery"
    }
}

/// A project's page.
public struct ModrinthProject: Decodable, Identifiable, Hashable, Sendable {
    public var id: String
    public var slug: String
    public var title: String
    public var description: String
    /// Markdown, with some HTML.
    public var body: String
    public var projectType: String
    public var downloads: Int
    public var followers: Int
    public var categories: [String]
    public var clientSide: String
    public var serverSide: String
    public var license: License
    public var gallery: [Image]
    public var updated: String
    private var iconAddress: String?
    private var sourceAddress: String?
    private var issuesAddress: String?
    private var discordAddress: String?
    private var donations: [Donation]?

    public var iconURL: URL? { iconAddress.flatMap(URL.init(string:)) }
    public var sourceURL: URL? { sourceAddress.flatMap(URL.init(string:)) }
    public var issuesURL: URL? { issuesAddress.flatMap(URL.init(string:)) }
    public var discordURL: URL? { discordAddress.flatMap(URL.init(string:)) }
    public var donationURL: URL? { donations?.first.flatMap { URL(string: $0.url) } }
    public var kind: ContentKind? { ContentKind(projectType: projectType) }
    public var updatedDate: Date? { ISO8601DateFormatter.modrinth.date(from: updated) }

    public struct License: Decodable, Hashable, Sendable {
        public var id: String
        public var name: String
        public var url: String?

        /// A readable name: Modrinth often leaves `name` empty and puts an SPDX id in `id`.
        public var title: String {
            if !name.isEmpty { return name }
            return id.replacingOccurrences(of: "LicenseRef-", with: "").replacingOccurrences(of: "-", with: " ")
        }
    }

    public struct Image: Decodable, Hashable, Sendable {
        public var url: String
        public var featured: Bool
        public var title: String?
        public var ordering: Int?
    }

    struct Donation: Decodable, Hashable, Sendable {
        var url: String
    }

    enum CodingKeys: String, CodingKey {
        case id, slug, title, description, body, downloads, followers, categories, license, gallery, updated
        case projectType = "project_type"
        case clientSide = "client_side"
        case serverSide = "server_side"
        case iconAddress = "icon_url"
        case sourceAddress = "source_url"
        case issuesAddress = "issues_url"
        case discordAddress = "discord_url"
        case donations = "donation_urls"
    }
}

public struct ModrinthVersion: Decodable, Identifiable, Hashable, Sendable {
    public var id: String
    public var projectID: String
    public var name: String
    public var versionNumber: String
    public var versionType: String
    public var datePublished: String
    public var loaders: [String]
    public var gameVersions: [String]
    public var dependencies: [Dependency]
    public var files: [File]

    public var primaryFile: File? { files.first(where: \.primary) ?? files.first }
    public var published: Date? { ISO8601DateFormatter.modrinth.date(from: datePublished) }

    public struct Dependency: Decodable, Hashable, Sendable {
        public var versionID: String?
        public var projectID: String?
        public var dependencyType: String

        enum CodingKeys: String, CodingKey {
            case versionID = "version_id"
            case projectID = "project_id"
            case dependencyType = "dependency_type"
        }
    }

    public struct File: Decodable, Hashable, Sendable {
        public var url: String
        public var filename: String
        public var primary: Bool
        public var size: Int64
        public var hashes: [String: String]
    }

    enum CodingKeys: String, CodingKey {
        case id, name, loaders, dependencies, files
        case projectID = "project_id"
        case versionNumber = "version_number"
        case versionType = "version_type"
        case datePublished = "date_published"
        case gameVersions = "game_versions"
    }
}

struct ModrinthMember: Decodable {
    var role: String
    var user: User

    struct User: Decodable {
        var username: String
    }
}

extension ISO8601DateFormatter {
    /// Modrinth's timestamps carry fractional seconds.
    nonisolated(unsafe) static let modrinth: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
