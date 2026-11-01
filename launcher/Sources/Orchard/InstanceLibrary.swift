import Foundation
import Observation
import OrchardKit

@MainActor @Observable
final class InstanceLibrary {

    enum Status: Equatable {
        case notInstalled
        case installing(Double)
        case installed
        case updateAvailable
    }

    let content: InstanceContent
    private let modrinth: Modrinth
    private let minecraft: String?

    private(set) var items: [ContentKind: [ContentItem]] = [:]
    private(set) var worlds: [World] = []
    private(set) var servers: [Server] = []
    private(set) var screenshots: [URL] = []
    private(set) var versions: [String: ModrinthVersion] = [:]
    private(set) var projects: [String: ModrinthHit] = [:]
    private(set) var updates: [String: ModrinthVersion] = [:]
    private(set) var busy: [String: Double] = [:]
    var problem: String?
    private var installedNow: Set<String> = []
    private var loaded = false
    private var generation = 0

    init(content: InstanceContent, modrinth: Modrinth, minecraft: String?) {
        self.content = content
        self.modrinth = modrinth
        self.minecraft = minecraft
    }

    func items(_ kind: ContentKind) -> [ContentItem] {
        items[kind] ?? []
    }

    var installedProjects: Set<String> {
        Set(versions.values.map(\.projectID)).union(installedNow)
    }

    func status(of project: String) -> Status {
        if let progress = busy[project] { return .installing(progress) }
        if versions.contains(where: { updates[$0.key] != nil && $0.value.projectID == project }) { return .updateAvailable }
        return installedProjects.contains(project) ? .installed : .notInstalled
    }

    func item(for project: String) -> ContentItem? {
        ContentKind.allCases.lazy.flatMap { self.items($0) }.first { versions[$0.fileName]?.projectID == project }
    }

    func project(of item: ContentItem) -> ModrinthHit? {
        versions[item.fileName].flatMap { projects[$0.projectID] }
    }

    func loadIfNeeded() {
        if !loaded {
            reload()
        }
    }

    func reload() {
        loaded = true
        generation += 1
        let generation = generation
        let content = content
        Task {
            let scan = await Task.detached { Scan(content) }.value
            guard generation == self.generation else { return }
            items = scan.items
            worlds = scan.worlds
            servers = scan.servers
            screenshots = scan.screenshots
            await identify(generation)
        }
    }

    private struct Scan: Sendable {
        var items: [ContentKind: [ContentItem]] = [:]
        var worlds: [World]
        var servers: [Server]
        var screenshots: [URL]

        init(_ content: InstanceContent) {
            for kind in ContentKind.allCases {
                items[kind] = content.items(kind)
            }
            worlds = content.worlds()
            servers = content.servers()
            screenshots = content.screenshots()
        }
    }

    private func identify(_ generation: Int) async {
        guard let minecraft else { return }
        let all = ContentKind.allCases.flatMap { items($0) }
        let hashes: [String: ContentItem] = await Task.detached {
            var hashes: [String: ContentItem] = [:]
            for item in all where item.fileName != "orchard.jar" {
                if let hash = try? Downloader.sha1(of: item.file) {
                    hashes[hash] = item
                }
            }
            return hashes
        }.value
        do {
            let known = try await modrinth.versions(sha1: Array(hashes.keys))
            let mods = hashes.filter { $0.value.kind == .mod && !$0.value.managed }
            let latest = try await modrinth.latest(sha1: Array(mods.keys), kind: .mod, minecraft: minecraft)
            let ids = Array(Set(known.values.map(\.projectID)))
            let hits = ids.isEmpty ? [] : try await modrinth.hits(for: ids)
            guard generation == self.generation else { return }
            versions = Dictionary(known.compactMap { hash, version in hashes[hash].map { ($0.fileName, version) } },
                                  uniquingKeysWith: { first, _ in first })
            updates = Dictionary(latest.compactMap { hash, version -> (String, ModrinthVersion)? in
                guard let item = mods[hash], version.primaryFile?.hashes["sha1"] != hash,
                      version.id != known[hash]?.id else { return nil }
                return (item.fileName, version)
            }, uniquingKeysWith: { first, _ in first })
            projects = Dictionary(hits.map { ($0.projectID, $0) }, uniquingKeysWith: { first, _ in first })
            installedNow.removeAll()
        } catch {
        }
    }

    func setEnabled(_ item: ContentItem, _ on: Bool) {
        perform { try $0.setEnabled(item, on) }
    }

    func remove(_ item: ContentItem) {
        perform { try $0.remove(item) }
    }

    func add(_ files: [URL], as kind: ContentKind) {
        perform { content in
            let jars = files.filter { $0.pathExtension == "jar" }
            try content.add(jars, as: .mod)
            if kind != .mod {
                try content.add(files.filter { $0.pathExtension == "zip" }, as: kind)
            }
        }
    }

    private func perform(_ change: (InstanceContent) throws -> Void) {
        do {
            try change(content)
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
        reload()
    }

    func install(_ project: String, kind: ContentKind) async {
        guard busy[project] == nil, let minecraft else { return }
        busy[project] = 0
        defer { busy[project] = nil }
        do {
            let installer = ContentInstaller(modrinth: modrinth, minecraft: minecraft)
            let steps = try await installer.plan(project, kind: kind, installed: installedProjects)
            let files = try await download(steps, with: installer, for: project)
            for (step, file) in zip(steps, files) where step.version.projectID == project {
                try content.use(file, as: kind)
            }
            if kind == .shader {
                for item in items(.mod) where !item.enabled {
                    let id = versions[item.fileName]?.projectID
                    if id == ContentInstaller.iris || id == "AANobbMI" {
                        try content.setEnabled(item, true)
                    }
                }
            }
            installedNow.formUnion(steps.map(\.version.projectID))
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
        reload()
    }

    func update(_ item: ContentItem) async {
        guard let project = versions[item.fileName]?.projectID, updates[item.fileName] != nil,
              busy[project] == nil, let minecraft else { return }
        busy[project] = 0
        defer { busy[project] = nil }
        do {
            let installer = ContentInstaller(modrinth: modrinth, minecraft: minecraft)
            let steps = try await installer.plan(project, kind: .mod, installed: installedProjects)
            try await download(steps, with: installer, for: project, replacing: [project: item.file])
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
        reload()
    }

    func updateAll() async {
        for item in items(.mod) where updates[item.fileName] != nil {
            await update(item)
        }
    }

    @discardableResult
    private func download(_ steps: [ContentInstaller.Step], with installer: ContentInstaller, for project: String,
                          replacing: [String: URL] = [:]) async throws -> [URL] {
        let (fractions, report) = AsyncStream.makeStream(of: Double.self, bufferingPolicy: .bufferingNewest(1))
        let watcher = Task {
            for await fraction in fractions {
                busy[project] = fraction
            }
        }
        defer {
            report.finish()
            watcher.cancel()
        }
        return try await installer.install(steps, into: content, replacing: replacing) { report.yield($0.fraction) }
    }
}
