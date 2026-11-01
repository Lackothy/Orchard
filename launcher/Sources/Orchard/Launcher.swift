import AppKit
import AuthenticationServices
import Observation
import OrchardKit

/// Where the window is: an instance, or a page of Discover.
enum Destination: Hashable {
    case instance(Instance.ID)
    case recommended
    case browse(ContentKind)
}

/// How a new instance starts out.
enum NewInstanceStart: Hashable {
    case orchardOnly
    case performanceSet
    case copy(Instance.ID)
}

/// The app's model: the instances, and installing, starting and watching the game. One game
/// runs at a time.
@MainActor @Observable
final class Launcher {

    enum Phase: Equatable {
        case idle
        case installing(InstallStage)
        case starting
        case playing
        case failed(String)
    }

    private(set) var phase: Phase = .idle
    /// The instance the phase is about: installing, starting, running, or the one that failed.
    private(set) var activeInstance: Instance.ID?
    /// What the game printed, for the Game Output window: the running session, the last one,
    /// or one read back from an instance's latest.log.
    private(set) var log = GameLog()
    /// The instance the log is from.
    private(set) var logInstance: Instance.ID?
    /// The log was read from latest.log rather than printed by a game this launcher started.
    private(set) var logFromFile = false
    /// The crash report the session in the log ended with.
    private(set) var crash: CrashReport?
    private(set) var edition: Edition?
    private(set) var instances: [Instance] = []
    var selection: Destination? = .instance(Instance.defaultID)
    /// Where Discover installs to.
    var discoverTarget: Instance.ID = Instance.defaultID
    var showingNewInstance = false
    var account: OfflineAccount {
        didSet { UserDefaults.standard.set(account.name, forKey: Preferences.playerName) }
    }
    /// The Microsoft account the game is started as; nil plays offline as `account`.
    var microsoft: MicrosoftAccount? {
        didSet { UserDefaults.standard.set(microsoft.flatMap { try? JSONEncoder().encode($0) }, forKey: Preferences.microsoftAccount) }
    }
    /// Between the Microsoft page closing and the Minecraft profile arriving.
    private(set) var signingIn = false
    var signInError: String?

    /// The name the game is started with.
    var playerName: String { microsoft?.name ?? account.name }
    var playingAs: String { microsoft.map { "Playing as \($0.name)" } ?? "Playing offline as \(account.name)" }

    let paths = Paths.standard
    let store: InstanceStore
    let modrinth: Modrinth
    let discover: DiscoverModel
    /// The window's opening animation, played as the app opens.
    let opening = OpeningRun()
    private var libraries: [Instance.ID: InstanceLibrary] = [:]
    private var game: GameProcess?
    private var startedAt: Date?

    init() {
        let saved = UserDefaults.standard.string(forKey: Preferences.playerName)
        account = OfflineAccount(name: saved ?? Preferences.defaultName)
        microsoft = UserDefaults.standard.data(forKey: Preferences.microsoftAccount)
            .flatMap { try? JSONDecoder().decode(MicrosoftAccount.self, from: $0) }
        let edition = try? Edition.current()
        self.edition = edition
        store = InstanceStore(paths: paths)
        let modrinth = Modrinth(launcherVersion: Self.version)
        self.modrinth = modrinth
        discover = DiscoverModel(modrinth: modrinth, minecraft: edition?.minecraft)
        instances = store.load()
        openScreenshotPage()
        // For testing the whole path from a script: press Play as soon as the app opens.
        if ProcessInfo.processInfo.environment["ORCHARD_AUTOPLAY"] == "1", let first = instances.first {
            play(first)
        }
    }

    /// A page to open on, for screenshots: `ORCHARD_PAGE` is `recommended`, `browse/<kind>`,
    /// `tab/<tab>` (of the first instance), `project/<id>/<kind>`, `new` or `output` (the Game
    /// Output window, showing the default instance's last session).
    private(set) var openingTab: InstanceTab?
    private(set) var openingProject: ProjectRoute?
    private(set) var openingOutput = false
    private(set) var openingSettings = false

    private func openScreenshotPage() {
        guard let page = ProcessInfo.processInfo.environment["ORCHARD_PAGE"] else { return }
        let parts = page.split(separator: "/").map(String.init)
        switch parts.first {
        case "recommended":
            selection = .recommended
        case "browse":
            selection = .browse(parts.count > 1 ? ContentKind(rawValue: parts[1]) ?? .mod : .mod)
        case "tab":
            openingTab = parts.count > 1 ? InstanceTab(rawValue: parts[1]) : nil
        case "project" where parts.count > 2:
            selection = .recommended
            openingProject = ProjectRoute(id: parts[1], kind: ContentKind(rawValue: parts[2]) ?? .mod)
        case "new":
            showingNewInstance = true
        case "settings":
            openingSettings = true
        case "output":
            openingOutput = true
            showLastSession(of: Instance.defaultID)
        default:
            break
        }
    }

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    // MARK: - Instances

    func instance(_ id: Instance.ID) -> Instance? {
        instances.first { $0.id == id }
    }

    var selectedInstance: Instance? {
        if case .instance(let id) = selection { instance(id) } else { nil }
    }

    /// The instance's mods, packs and worlds, loaded on first use.
    func library(_ id: Instance.ID) -> InstanceLibrary {
        if let library = libraries[id] {
            return library
        }
        let library = InstanceLibrary(content: InstanceContent(directory: paths.instance(id)),
                                      modrinth: modrinth, minecraft: edition?.minecraft)
        libraries[id] = library
        return library
    }

    func createInstance(name: String, icon: InstanceIcon, start: NewInstanceStart) async throws {
        let store = store
        let source = if case .copy(let id) = start { instance(id) } else { Instance?.none }
        let minecraft = edition?.minecraft
        let made = try await Task.detached { try store.create(name: name, icon: icon, minecraft: minecraft, copying: source) }.value
        instances = store.load()
        selection = .instance(made.id)
        discoverTarget = made.id
        if start == .performanceSet {
            let library = library(made.id)
            for pick in Recommended.performance.picks {
                await library.install(pick.id, kind: .mod)
            }
        }
    }

    func update(_ instance: Instance) {
        try? store.save(instance)
        instances = store.load()
    }

    func delete(_ instance: Instance) {
        guard !instance.isDefault, activeInstance != instance.id || canPlay else { return }
        do {
            try store.delete(instance)
        } catch {
            NSAlert(error: error).runModal()
        }
        libraries[instance.id] = nil
        instances = store.load()
        if selection == .instance(instance.id) {
            selection = .instance(Instance.defaultID)
        }
        if discoverTarget == instance.id {
            discoverTarget = Instance.defaultID
        }
    }

    func revealFolder(of id: Instance.ID) {
        let folder = paths.instance(id)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.activateFileViewerSelecting([folder])
    }

    // MARK: - Playing

    var canPlay: Bool {
        switch phase {
        case .idle, .failed: true
        default: false
        }
    }

    var isPlaying: Bool { phase == .playing }

    func play(_ instance: Instance, quickPlay: QuickPlay? = nil, verify: Bool = false) {
        guard canPlay else { return }
        activeInstance = instance.id
        phase = .installing(.checking)
        Task { await run(instance, quickPlay: quickPlay, verify: verify) }
    }

    func stop() {
        game?.terminate()
    }

    func clearOutput() {
        log.removeAll()
        crash = nil
    }

    /// The instance's latest.log, if it has been played.
    func logFile(of id: Instance.ID) -> URL? {
        let file = paths.instance(id).appending(path: "logs/latest.log")
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    /// Reads the instance's last session into the log, to look back at after the game quit.
    func showLastSession(of id: Instance.ID) {
        guard let file = logFile(of: id), let text = try? String(contentsOf: file, encoding: .utf8) else { return }
        let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        log.removeAll()
        logInstance = id
        logFromFile = true
        // A report written as that log stopped is the one that session ended with.
        crash = CrashReport.newest(in: paths.instance(id), since: (modified ?? .now).addingTimeInterval(-10))
        let name = instance(id)?.name ?? "this instance"
        let when = modified.map { ", \(Formats.lastPlayed($0))" } ?? ""
        log.note("Last session of \(name)\(when), from logs/latest.log", at: modified ?? .now)
        for line in text.split(separator: "\n", omittingEmptySubsequences: false).dropLast(text.hasSuffix("\n") ? 1 : 0) {
            log.append(String(line))
        }
    }

    // MARK: - Microsoft sign-in

    /// Signs in through Microsoft's page, which `open` shows (the window's web sign-in sheet),
    /// returning the address the page sent back.
    func signIn(open: (URL) async throws -> URL) async {
        guard let auth = MicrosoftAuth.configured else {
            signInError = MicrosoftAuthError.notSetUp.localizedDescription
            return
        }
        let attempt = auth.attempt()
        do {
            let callback = try await open(attempt.url)
            signingIn = true
            defer { signingIn = false }
            let (account, tokens) = try await auth.finish(attempt, callback: callback)
            try TokenKeychain.save(tokens, for: account.uuid)
            if let old = microsoft, old.uuid != account.uuid {
                TokenKeychain.delete(for: old.uuid)
            }
            microsoft = account
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            // The page was closed: nothing to say.
        } catch {
            signInError = error.localizedDescription
        }
    }

    func signOut() {
        if let microsoft {
            TokenKeychain.delete(for: microsoft.uuid)
        }
        microsoft = nil
    }

    /// Who this Play starts the game as. Signed in, the session is renewed when it is close to
    /// running out; when Microsoft can't be reached, the account plays offline under its name.
    private func player() async throws -> Player {
        guard let account = microsoft else { return .offline(self.account) }
        guard let auth = MicrosoftAuth.configured else { throw MicrosoftAuthError.notSetUp }
        guard let tokens = TokenKeychain.load(for: account.uuid) else { throw MicrosoftAuthError.signInAgain }
        do {
            let (fresh, renewed) = try await auth.session(for: account, tokens: tokens)
            if renewed != tokens {
                try TokenKeychain.save(renewed, for: fresh.uuid)
            }
            if fresh != account {
                microsoft = fresh
            }
            return Player(name: fresh.name, uuid: fresh.uuid, accessToken: renewed.minecraftToken, online: true,
                          clientID: auth.clientID)
        } catch is URLError {
            return Player(name: account.name, uuid: account.uuid, accessToken: "0", online: false, clientID: nil)
        }
    }

    private func run(_ instance: Instance, quickPlay: QuickPlay?, verify: Bool) async {
        do {
            let edition = try Edition.current()
            self.edition = edition
            let (stages, report) = AsyncStream.makeStream(of: InstallStage.self, bufferingPolicy: .bufferingNewest(1))
            let watcher = Task {
                for await stage in stages {
                    phase = .installing(stage)
                }
            }
            let installer = Installer(paths: paths, edition: edition)
            let directory = store.directory(of: instance)
            let install: Installation
            let moved: VersionMove.Report?
            var added: [String]?
            var fixed: [String]?
            var packed: [String]?
            let needsMojang: Bool
            let metal: Bool
            do {
                moved = try await move(instance, in: directory, to: edition.minecraft) { report.yield(.downloading($0)) }
                if !instance.speedUps {
                    // Offline, it waits for the next Play rather than stopping this one.
                    added = try? await SpeedUps(installer: ContentInstaller(modrinth: modrinth, minecraft: edition.minecraft))
                        .add(to: InstanceContent(directory: directory)) { report.yield(.downloading($0)) }
                }
                if !instance.macFixes {
                    fixed = try? await SpeedUps(installer: ContentInstaller(modrinth: modrinth, minecraft: edition.minecraft),
                                                set: SpeedUps.macFixes)
                        .add(to: InstanceContent(directory: directory)) { report.yield(.downloading($0)) }
                }
                // The Orchard Pack's packs, at each Play until they are all there; offline, at the next.
                packed = try? await OrchardPack(installer: ContentInstaller(modrinth: modrinth, minecraft: edition.minecraft))
                    .ensure(in: directory) { report.yield(.downloading($0)) }
                needsMojang = InstanceContent(directory: directory).needsMojangRenderer
                metal = instance.renderer == .metal && !needsMojang
                install = try await installer.install(into: directory, verify: verify) { report.yield($0) }
                report.finish()
                await watcher.value
            } catch {
                report.finish()
                throw error
            }

            if instance.minecraft != edition.minecraft || added != nil || fixed != nil || packed?.isEmpty == false {
                var current = instance
                current.minecraft = edition.minecraft
                current.speedUps = current.speedUps || added != nil
                current.macFixes = current.macFixes || fixed != nil
                update(current)
                library(instance.id).reload()
            }

            let player = try await player()
            phase = .starting
            let options = LaunchOptions(
                player: player, memory: Preferences.memory, extraJVMArguments: Preferences.javaArguments,
                launcherVersion: Self.version, quickPlay: quickPlay, scene: Preferences.scene,
                reduceMotion: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion, metal: metal)
            let arguments = LaunchCommand.arguments(for: install, options: options)
            log.removeAll()
            logInstance = instance.id
            logFromFile = false
            crash = nil
            log.note("Starting \(install.fabric.id) in \(instance.name) as \(player.name)\(player.online ? "" : ", offline")")
            if let microsoft, !player.online {
                log.note("Couldn’t reach Microsoft, so \(microsoft.name) is playing offline: singleplayer works, online servers won’t.")
            }
            log.note(metal ? "Drawing with Metal"
                : instance.renderer == .metal && needsMojang ? "Drawing with Mojang’s renderer, because Sodium or Iris is on"
                : "Drawing with Mojang’s renderer")
            if let moved {
                note(moved, from: instance.minecraft ?? Edition.firstMinecraft, to: edition.minecraft)
            }
            if let added, !added.isEmpty {
                log.note("Added the performance set: \(added.joined(separator: ", ")). Turn any of them off in Mods.")
            }
            if let fixed, !fixed.isEmpty {
                log.note("Added the Mac fixes: \(fixed.joined(separator: ", ")), for trackpad scrolling and Ctrl keys. Turn them off in Mods.")
            }

            let (events, emit) = AsyncStream.makeStream(of: GameEvent.self)
            game = try GameProcess.start(
                java: install.java, arguments: arguments, directory: directory,
                output: { emit.yield(.line($0)) },
                exited: { emit.yield(.exit($0)); emit.finish() })
            phase = .playing
            startedAt = .now
            afterLaunch()
            for await event in events {
                switch event {
                case .line(let text): log.append(text)
                case .exit(let status): finished(status)
                }
            }
        } catch {
            phase = .failed(error.localizedDescription)
            logInstance = instance.id
            logFromFile = false
            log.note("Couldn’t start: \(error)")
            FileHandle.standardError.write(Data("Orchard: couldn’t start: \(error)\n".utf8))
        }
    }

    /// Takes the instance to this Minecraft if it last ran another.
    private func move(_ instance: Instance, in directory: URL, to minecraft: String,
                      progress: @Sendable (DownloadProgress) -> Void) async throws -> VersionMove.Report? {
        let from = instance.minecraft ?? Edition.firstMinecraft
        guard from != minecraft else { return nil }
        let installer = ContentInstaller(modrinth: modrinth, minecraft: minecraft)
        let move = VersionMove(installer: installer, from: from, keepSodium: instance.renderer == .mojang)
        return try await move.run(in: directory, progress: progress)
    }

    private func note(_ moved: VersionMove.Report, from: String, to: String) {
        log.note("Moved from Minecraft \(from) to \(to). Old mods are in mods-\(from), and a copy of the worlds is in saves-\(from).")
        if !moved.updated.isEmpty {
            log.note("Updated for \(to): \(moved.updated.joined(separator: ", "))")
        }
        if !moved.replaced.isEmpty {
            log.note("Not needed with Metal: \(moved.replaced.joined(separator: ", "))")
        }
        if !moved.leftBehind.isEmpty {
            log.note("Left in mods-\(from): \(moved.leftBehind.joined(separator: ", "))")
        }
    }

    private enum GameEvent: Sendable {
        case line(String)
        case exit(Int32)
    }

    private func finished(_ status: Int32) {
        game = nil
        // 143 is SIGTERM, from Quit Minecraft.
        let clean = status == 0 || status == 143
        // A crash report from this session means a crash, whatever the status said.
        if status != 143, let id = activeInstance {
            crash = CrashReport.newest(in: paths.instance(id), since: startedAt ?? .distantPast)
        }
        if let crash {
            log.note("Minecraft crashed: \(crash.description). The report is \(crash.file.lastPathComponent).")
            phase = .failed("Minecraft crashed: \(crash.description).")
        } else {
            log.note(clean ? "Minecraft quit." : "Minecraft quit with status \(status).")
            phase = clean ? .idle : .failed("Minecraft quit unexpectedly.")
        }
        if let id = activeInstance, var played = instance(id), let startedAt {
            played.lastPlayed = .now
            played.playTime += Date.now.timeIntervalSince(startedAt)
            update(played)
            library(id).reload()
        }
        startedAt = nil
        if Preferences.afterLaunch == .hide {
            NSApp.unhide(nil)
            NSApp.activate()
        }
    }

    private func afterLaunch() {
        switch Preferences.afterLaunch {
        case .keepOpen: break
        case .hide: NSApp.hide(nil)
        case .quit: NSApp.terminate(nil)
        }
    }
}
