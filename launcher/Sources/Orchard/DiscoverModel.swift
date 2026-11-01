import Foundation
import Observation
import OrchardKit

/// Discover's data from Modrinth: the Recommended picks, a search per kind, and project pages,
/// each fetched once and kept while the app is open.
@MainActor @Observable
final class DiscoverModel {

    struct Search {
        var query = ""
        var sort: Modrinth.Sort = .relevance
        var hits: [ModrinthHit] = []
        var total = 0
        var loading = false
        var problem: String?
        /// The query and sort the hits are for.
        fileprivate var shown: (String, Modrinth.Sort)?
    }

    let modrinth: Modrinth
    let minecraft: String?

    /// The Recommended projects, by ID.
    private(set) var picks: [String: ModrinthHit] = [:]
    private(set) var picksProblem: String?
    var searches: [ContentKind: Search] = Dictionary(uniqueKeysWithValues: ContentKind.allCases.map { ($0, Search()) })
    private var projects: [String: ModrinthProject] = [:]
    private var owners: [String: String] = [:]

    init(modrinth: Modrinth, minecraft: String?) {
        self.modrinth = modrinth
        self.minecraft = minecraft
    }

    func loadPicks() async {
        guard picks.isEmpty else { return }
        do {
            let hits = try await modrinth.hits(for: Recommended.all.flatMap { $0.picks.map(\.id) })
            picks = Dictionary(hits.map { ($0.projectID, $0) }, uniquingKeysWith: { first, _ in first })
            picksProblem = nil
        } catch {
            picksProblem = Self.describe(error)
        }
    }

    /// Runs the kind's search for its current query and sort, unless those are already shown.
    func search(_ kind: ContentKind) async {
        guard let minecraft else {
            searches[kind]?.problem = LauncherError.missingEdition("Minecraft version").localizedDescription
            return
        }
        guard var state = searches[kind] else { return }
        if let shown = state.shown, shown == (state.query, state.sort) { return }
        state.loading = true
        searches[kind] = state
        do {
            let page = try await modrinth.search(state.query, kind: kind, minecraft: minecraft, sort: state.sort)
            guard searches[kind]?.query == state.query, searches[kind]?.sort == state.sort else { return }
            searches[kind]?.hits = page.hits
            searches[kind]?.total = page.total
            searches[kind]?.shown = (state.query, state.sort)
            searches[kind]?.problem = nil
        } catch is CancellationError {
        } catch {
            searches[kind]?.problem = Self.describe(error)
        }
        searches[kind]?.loading = false
    }

    func loadMore(_ kind: ContentKind) async {
        guard let minecraft, let state = searches[kind], !state.loading, state.hits.count < state.total else { return }
        searches[kind]?.loading = true
        if let page = try? await modrinth.search(state.query, kind: kind, minecraft: minecraft, sort: state.sort,
                                                 offset: state.hits.count) {
            searches[kind]?.hits += page.hits.filter { hit in !state.hits.contains { $0.id == hit.id } }
        }
        searches[kind]?.loading = false
    }

    func project(_ id: String) async throws -> ModrinthProject {
        if let project = projects[id] { return project }
        let project = try await modrinth.project(id)
        projects[id] = project
        return project
    }

    func owner(of id: String) async -> String? {
        if let owner = owners[id] { return owner }
        let owner = try? await modrinth.owner(of: id)
        owners[id] = owner
        return owner
    }

    static func describe(_ error: Error) -> String {
        if let error = error as? URLError, error.code == .notConnectedToInternet {
            return "Discover needs the internet."
        }
        return error.localizedDescription
    }
}
