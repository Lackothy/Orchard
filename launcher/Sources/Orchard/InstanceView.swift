import OrchardKit
import SwiftUI

enum InstanceTab: String, CaseIterable, Identifiable {
    case overview, mods, resourcePacks, shaders, worlds, screenshots

    var id: Self { self }

    var kind: ContentKind? {
        switch self {
        case .mods: .mod
        case .resourcePacks: .resourcePack
        case .shaders: .shader
        default: nil
        }
    }

    var title: String {
        switch self {
        case .overview: "Overview"
        case .mods: "Mods"
        case .resourcePacks: "Resource Packs"
        case .shaders: "Shaders"
        case .worlds: "Worlds"
        case .screenshots: "Screenshots"
        }
    }
}

/// One instance: the orchard and its Play button on top, then what's in it.
struct InstanceView: View {

    let instance: Instance
    @Environment(Launcher.self) private var launcher
    @State private var tab: InstanceTab = .overview
    @State private var query = ""
    @State private var underToolbar: CGFloat = 0

    var body: some View {
        let library = launcher.library(instance.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Hero(instance: instance, library: library, underToolbar: underToolbar)
                VStack(alignment: .leading, spacing: 22) {
                    Picker("Show", selection: $tab) {
                        ForEach(InstanceTab.allCases) { tab in
                            Text(tab.title).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .controlSize(.large)
                    // Every segment is as wide as the widest; this keeps them from stretching
                    // across a wide window, and lets them give a little in a narrow one.
                    .frame(maxWidth: 720, alignment: .leading)

                    if let problem = library.problem {
                        Label(problem, systemImage: "exclamationmark.triangle.fill")
                            .symbolRenderingMode(.multicolor)
                            .font(.callout)
                    }

                    switch tab {
                    case .overview: OverviewTab(instance: instance, library: library, tab: $tab)
                    case .mods: ContentTab(instance: instance, library: library, kind: .mod, query: query)
                    case .resourcePacks: ContentTab(instance: instance, library: library, kind: .resourcePack, query: query)
                    case .shaders: ContentTab(instance: instance, library: library, kind: .shader, query: query)
                    case .worlds: WorldsTab(instance: instance, library: library)
                    case .screenshots: ScreenshotsTab(library: library)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.top, 4)
                .padding(.bottom, 32)
                .modifier(OpeningFade(step: 7))
            }
        }
        // The scroll view reaches the top of the window, and the hero's scene reaches up into
        // that, so the sky runs under the toolbar and the glass buttons float on it, no band.
        .ignoresSafeArea(.container, edges: .top)
        .onGeometryChange(for: CGFloat.self, of: { $0.safeAreaInsets.top }) { inset in
            underToolbar = inset
        }
        .scrollEdgeEffectHidden(true, for: .top)
        .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
        .modifier(SearchContent(kind: tab.kind, query: $query))
        .onChange(of: tab) { query = "" }
        .navigationTitle(instance.name)
        .onAppear {
            library.loadIfNeeded()
            if let opening = launcher.openingTab { tab = opening }
        }
    }
}

/// The toolbar's search field, on the sections that hold mods, packs or shaders.
private struct SearchContent: ViewModifier {

    let kind: ContentKind?
    @Binding var query: String

    func body(content: Content) -> some View {
        if let kind {
            content.searchable(text: $query, placement: .toolbar, prompt: "Search \(kind.title)")
        } else {
            content
        }
    }
}

// MARK: - Hero

private struct Hero: View {

    let instance: Instance
    let library: InstanceLibrary
    /// How far the toolbar overlaps the top of the page: the scene is drawn that much taller,
    /// up behind it, so the sky reaches the top of the window.
    let underToolbar: CGFloat
    @AppStorage(Preferences.sceneKey) private var scene = SceneTime.timeOfDay
    @Environment(Launcher.self) private var launcher
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            TimelineView(.animation(paused: !launcher.opening.playing)) { context in
                let look = launcher.opening.look(at: context.date)
                // (the clouds hold still while the game runs, so the launcher draws nothing behind it)
                OrchardScene(hour: ProcessInfo.processInfo.environment["ORCHARD_SCENE_HOUR"].flatMap(Double.init) ?? scene.hour,
                             moving: launcher.phase != .starting && launcher.phase != .playing,
                             growth: look.growth)
                    .opacity(look.sceneAlpha)
            }
                .frame(height: 340 + underToolbar)
                .backgroundExtensionEffect()
                .frame(height: 340, alignment: .bottom)
            LinearGradient(stops: [
                .init(color: .clear, location: 0.25),
                .init(color: Color(nsColor: .windowBackgroundColor).opacity(0.55), location: 0.6),
                .init(color: Color(nsColor: .windowBackgroundColor), location: 1),
            ], startPoint: .top, endPoint: .bottom)
            .allowsHitTesting(false)

            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(instance.name)
                        .font(.system(size: 40, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(versionLine)
                        .font(.title3.weight(.medium))
                        .foregroundStyle(.white.opacity(0.92))
                }
                .shadow(color: .black.opacity(0.45), radius: 10, y: 2)
                .modifier(OpeningFade(step: 5))

                HStack(spacing: 10) {
                    PlayButton(instance: instance)
                    Button("Show in Finder", systemImage: "folder") { launcher.revealFolder(of: instance.id) }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .controlSize(.extraLarge)
                        .help("Show this instance’s folder in the Finder")
                    Menu {
                        Button("Verify Game Files") { launcher.play(instance, verify: true) }
                            .disabled(!launcher.canPlay)
                        Button("Show Game Output") { openWindow(id: "output") }
                        Picker("Renderer", selection: renderer) {
                            ForEach(InstanceRenderer.allCases) { choice in
                                Text(choice.label).tag(choice)
                            }
                        }
                        .pickerStyle(.menu)
                    } label: {
                        Label("More", systemImage: "ellipsis")
                            .labelStyle(.iconOnly)
                    }
                    .menuIndicator(.hidden)
                    .buttonStyle(.glass)
                    .controlSize(.extraLarge)
                    .fixedSize()
                    Spacer(minLength: 16)
                    StatusLine(instance: instance)
                }
                .modifier(OpeningFade(step: 6))
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 22)
        }
    }

    private var renderer: Binding<InstanceRenderer> {
        Binding(get: { instance.renderer }, set: { choice in
            var changed = instance
            changed.renderer = choice
            launcher.update(changed)
        })
    }

    private var versionLine: String {
        var parts: [String] = []
        if let edition = launcher.edition {
            parts.append("Minecraft \(edition.minecraft)")
            parts.append("Fabric \(edition.loader)")
        }
        let mods = library.items(.mod).count
        if mods > 0 {
            parts.append(mods == 1 ? "1 mod" : "\(mods) mods")
        }
        return parts.joined(separator: " · ")
    }
}

/// Play, in the accent: the one prominent button on the page.
struct PlayButton: View {

    let instance: Instance
    @Environment(Launcher.self) private var launcher

    var body: some View {
        Button { launcher.play(instance) } label: {
            Label(title, systemImage: "play.fill")
                .font(.title3.weight(.semibold))
                .frame(minWidth: 96)
                .foregroundStyle(Accent.onAccent)
        }
        .buttonStyle(.glassProminent)
        .tint(Accent.color)
        .controlSize(.extraLarge)
        .keyboardShortcut(.defaultAction)
        .disabled(!launcher.canPlay)
        .help(launcher.canPlay ? "Play \(instance.name)" : "Minecraft is already running")
    }

    private var title: String {
        guard launcher.activeInstance == instance.id else { return "Play" }
        return switch launcher.phase {
        case .idle, .failed: "Play"
        case .installing: "Installing…"
        case .starting: "Starting…"
        case .playing: "Playing"
        }
    }
}

/// What the launcher is doing for this instance, or when it was last played.
private struct StatusLine: View {

    let instance: Instance
    @Environment(Launcher.self) private var launcher
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            if launcher.activeInstance == instance.id {
                active
            } else {
                resting
            }
        }
        .font(.callout)
        .foregroundStyle(.white.opacity(0.9))
        .shadow(color: .black.opacity(0.4), radius: 6)
    }

    @ViewBuilder private var active: some View {
        switch launcher.phase {
        case .idle:
            resting
        case .installing(.checking):
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Checking for updates…")
            }
        case .installing(.downloading(let progress)):
            VStack(alignment: .trailing, spacing: 5) {
                ProgressView(value: progress.fraction)
                    .frame(width: 220)
                Text("Downloading \(bytes(progress.completedBytes)) of \(bytes(progress.totalBytes))")
                    .monospacedDigit()
            }
        case .installing(.finishing):
            Text("Getting ready…")
        case .starting:
            Text("Starting Minecraft…")
        case .playing:
            HStack(spacing: 10) {
                Text("Minecraft is running")
                Button("Show Output") { openWindow(id: "output") }
                    .buttonStyle(.link)
            }
        case .failed(let message):
            HStack(spacing: 10) {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .symbolRenderingMode(.multicolor)
                Button("Show Output") { openWindow(id: "output") }
                    .buttonStyle(.link)
            }
        }
    }

    @ViewBuilder private var resting: some View {
        if let last = instance.lastPlayed {
            Text("Last played \(Formats.lastPlayed(last)) · \(Formats.playTime(instance.playTime))")
        } else {
            Text(launcher.playingAs)
        }
    }

    private func bytes(_ count: Int64) -> String {
        count.formatted(.byteCount(style: .file))
    }
}

// MARK: - Overview

private struct OverviewTab: View {

    let instance: Instance
    let library: InstanceLibrary
    @Binding var tab: InstanceTab
    @Environment(Launcher.self) private var launcher

    private let columns = [GridItem(.adaptive(minimum: 260), spacing: 16)]

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Jump back in", action: library.worlds.isEmpty ? nil : ("Show All", { tab = .worlds }))
                if library.worlds.isEmpty && library.servers.isEmpty {
                    Text("Worlds you make and servers you add in Minecraft show up here, to go straight back into.")
                        .foregroundStyle(.secondary)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                        ForEach(library.worlds.prefix(3)) { world in
                            JumpCard(title: world.name, subtitle: subtitle(world), image: world.icon.flatMap { NSImage(contentsOf: $0) },
                                     help: "Play \(world.name)") {
                                launcher.play(instance, quickPlay: .world(world.folder))
                            }
                        }
                        ForEach(library.servers.prefix(max(1, 3 - min(3, library.worlds.count)))) { server in
                            JumpCard(title: server.name, subtitle: "Multiplayer · \(server.address)",
                                     image: server.icon.flatMap { NSImage(data: $0) }, help: "Join \(server.name)") {
                                launcher.play(instance, quickPlay: .server(server.address))
                            }
                        }
                    }
                }
            }

            let suggestions = suggested
            if !suggestions.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(title: "Suggested for \(instance.name)", action: ("See All in Discover", {
                        launcher.discoverTarget = instance.id
                        launcher.selection = .recommended
                    }))
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                        ForEach(suggestions, id: \.0.id) { pick, hit in
                            Lockup(hit: hit, reason: pick.reason, library: library) {
                                launcher.discoverTarget = instance.id
                                launcher.selection = .recommended
                            }
                        }
                    }
                }
            }
        }
        .task { await launcher.discover.loadPicks() }
    }

    /// Recommended mods not in this instance yet, three at most.
    private var suggested: [(Recommended.Pick, ModrinthHit)] {
        let picks = (Recommended.performance.picks + Recommended.shelves.filter { $0.kind == .mod }.flatMap(\.picks))
        return Array(picks.compactMap { pick in
            launcher.discover.picks[pick.id].map { (pick, $0) }
        }.filter { library.status(of: $0.1.projectID) == .notInstalled }.prefix(3))
    }

    private func subtitle(_ world: World) -> String {
        var parts = [world.hardcore ? "Hardcore" : world.mode?.label ?? "Singleplayer"]
        if let last = world.lastPlayed {
            parts.append(last.formatted(.relative(presentation: .named)).capitalizedFirst)
        }
        return parts.joined(separator: " · ")
    }
}

struct SectionHeader: View {
    let title: String
    var action: (String, () -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.title3.weight(.semibold))
            Spacer()
            if let action {
                Button(action.0, action: action.1)
                    .buttonStyle(.link)
            }
        }
    }
}

/// A world or server to go straight back into: its icon, sharp as the game draws it, and Play.
private struct JumpCard: View {
    let title: String
    let subtitle: String
    let image: NSImage?
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Group {
                    if let image {
                        Image(nsImage: image)
                            .resizable()
                            .interpolation(.none)
                    } else {
                        PixelIcon(icon: .grass, size: 52)
                    }
                }
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "play.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .background(.fill.tertiary, in: Circle())
            }
            .padding(12)
            .card()
            .contentShape(RoundedRectangle(cornerRadius: Card.radius, style: .continuous))
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

extension String {
    var capitalizedFirst: String {
        prefix(1).uppercased() + dropFirst()
    }
}
