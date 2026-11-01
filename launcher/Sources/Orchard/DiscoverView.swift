import OrchardKit
import SwiftUI

enum DiscoverPage: Hashable {
    case recommended
    case browse(ContentKind)
}

struct ProjectRoute: Hashable {
    let id: String
    let kind: ContentKind
}

/// Modrinth, inside the launcher: Recommended, or every mod, pack or shader for Orchard's
/// Minecraft, each installing straight into the instance picked in the toolbar.
struct DiscoverView: View {

    let page: DiscoverPage
    @Environment(Launcher.self) private var launcher
    @State private var path: [ProjectRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch page {
                case .recommended: RecommendedPage(path: $path)
                case .browse(let kind): BrowsePage(kind: kind, path: $path)
                }
            }
            .navigationDestination(for: ProjectRoute.self) { route in
                ProjectView(route: route)
            }
        }
        .onAppear {
            launcher.library(launcher.discoverTarget).loadIfNeeded()
            if let opening = launcher.openingProject, page == .recommended, path.isEmpty { path = [opening] }
        }
        .onChange(of: launcher.discoverTarget) { _, id in launcher.library(id).loadIfNeeded() }
    }
}

/// Which instance Discover installs to, beside each page's title.
struct TargetPicker: View {

    @Environment(Launcher.self) private var launcher

    var body: some View {
        @Bindable var launcher = launcher
        Picker("Installing to", selection: $launcher.discoverTarget) {
            ForEach(launcher.instances) { instance in
                Text(instance.name).tag(instance.id)
            }
        }
        .pickerStyle(.menu)
        .controlSize(.large)
        .fixedSize()
        .help("The instance Get and Install put things in")
    }
}

// MARK: - Recommended

private struct RecommendedPage: View {

    @Binding var path: [ProjectRoute]
    @Environment(Launcher.self) private var launcher
    @State private var query = ""

    private let columns = [GridItem(.adaptive(minimum: 270), spacing: 16)]

    var body: some View {
        let discover = launcher.discover
        let library = launcher.library(launcher.discoverTarget)
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Recommended")
                            .font(.largeTitle.bold())
                        Text("Picked to run well with Orchard on Minecraft \(launcher.edition?.minecraft ?? ""). Everything here comes from Modrinth.")
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 16)
                    TargetPicker()
                }

                if let problem = discover.picksProblem {
                    HStack {
                        Label(problem, systemImage: "wifi.exclamationmark")
                        Button("Try Again") { Task { await discover.loadPicks() } }
                            .buttonBorderShape(.capsule)
                    }
                    .foregroundStyle(.secondary)
                }

                PerformanceCard(library: library)

                ForEach(Recommended.shelves) { shelf in
                    VStack(alignment: .leading, spacing: 12) {
                        SectionHeader(title: shelf.title, action: ("See All", {
                            launcher.selection = .browse(shelf.kind)
                        }))
                        LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                            ForEach(shelf.picks, id: \.id) { pick in
                                if let hit = discover.picks[pick.id] {
                                    Lockup(hit: hit, reason: pick.reason, library: library) {
                                        path.append(ProjectRoute(id: hit.projectID, kind: shelf.kind))
                                    }
                                } else {
                                    RoundedRectangle(cornerRadius: Card.radius, style: .continuous)
                                        .fill(.fill.quaternary)
                                        .frame(height: 104)
                                }
                            }
                        }
                    }
                }
            }
            .padding(28)
            .frame(maxWidth: 1100, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Recommended")
        .searchable(text: $query, placement: .toolbar, prompt: "Search Modrinth")
        .onSubmit(of: .search) {
            discover.searches[.mod]?.query = query
            launcher.selection = .browse(.mod)
        }
        .task { await discover.loadPicks() }
    }
}

/// The performance set, as one card with one button for all of it.
private struct PerformanceCard: View {

    let library: InstanceLibrary
    @Environment(Launcher.self) private var launcher

    var body: some View {
        let picks = Recommended.performance.picks
        let missing = picks.filter { library.status(of: $0.id) == .notInstalled }
        let have = picks.filter { library.status(of: $0.id) != .notInstalled }
        let working = picks.contains { if case .installing = library.status(of: $0.id) { true } else { false } }
        let target = launcher.instance(launcher.discoverTarget)?.name ?? ""
        HStack(alignment: .center, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text(Recommended.performance.title)
                    .font(.title2.weight(.semibold))
                Text("Four mods that make Minecraft run faster and use less memory. They’re made to work together, and with Orchard.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !have.isEmpty, !missing.isEmpty {
                    Text("\(names(have)) \(have.count == 1 ? "is" : "are") already in \(target).")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Button {
                    Task {
                        for pick in missing {
                            await library.install(pick.id, kind: .mod)
                        }
                    }
                } label: {
                    Text(buttonTitle(missing: missing.count, working: working, target: target))
                        .font(.headline)
                        .foregroundStyle(Accent.onAccent)
                        .padding(.horizontal, 6)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.color)
                .controlSize(.large)
                .disabled(missing.isEmpty || working)
                .padding(.top, 4)
            }
            Spacer(minLength: 0)
            HStack(spacing: 14) {
                ForEach(picks, id: \.id) { pick in
                    let hit = launcher.discover.picks[pick.id]
                    VStack(spacing: 6) {
                        ProjectIcon(url: hit?.iconURL, size: 52)
                        Text(hit?.title ?? " ")
                            .font(.caption)
                            .lineLimit(1)
                            .frame(width: 76)
                    }
                }
            }
        }
        .padding(24)
        .card(radius: 24)
    }

    private func names(_ picks: [Recommended.Pick]) -> String {
        picks.map { launcher.discover.picks[$0.id]?.title ?? "" }.formatted(.list(type: .and))
    }

    private func buttonTitle(missing: Int, working: Bool, target: String) -> String {
        if working { return "Installing…" }
        if missing == 0 { return "All in \(target)" }
        if missing == Recommended.performance.picks.count { return "Get All \(missing)" }
        return missing == 1 ? "Get the Last One" : "Get the Other \(missing)"
    }
}

/// A project on a shelf: icon, name, author, why it's here, and Get.
struct Lockup: View {

    let hit: ModrinthHit
    var reason: String?
    let library: InstanceLibrary
    let open: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 12) {
                    ProjectIcon(url: hit.iconURL, size: 52)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(hit.title)
                            .font(.headline)
                            .lineLimit(1)
                        Text(hit.author)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(reason ?? hit.description)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        Label(Formats.count(hit.downloads), systemImage: "arrow.down.circle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 2)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Show \(hit.title)")
            GetButton(project: hit.projectID, kind: hit.kind ?? .mod, library: library)
        }
        .padding(14)
        .card()
    }
}

/// Get, or how far along it is, or that it's already there.
struct GetButton: View {

    let project: String
    let kind: ContentKind
    let library: InstanceLibrary

    var body: some View {
        switch library.status(of: project) {
        case .notInstalled:
            Button("Get") { Task { await library.install(project, kind: kind) } }
                .buttonStyle(.glass)
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                .fontWeight(.semibold)
        case .installing(let fraction):
            ProgressView(value: max(0.02, fraction))
                .progressViewStyle(.circular)
                .controlSize(.small)
                .help("Downloading")
        case .installed:
            Label("Installed", systemImage: "checkmark")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        case .updateAvailable:
            Button("Update") {
                if let item = library.item(for: project) {
                    Task { await library.update(item) }
                }
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.capsule)
            .controlSize(.small)
        }
    }
}

// MARK: - Browse

private struct BrowsePage: View {

    let kind: ContentKind
    @Binding var path: [ProjectRoute]
    @Environment(Launcher.self) private var launcher

    var body: some View {
        @Bindable var discover = launcher.discover
        let state = discover.searches[kind] ?? .init()
        let library = launcher.library(launcher.discoverTarget)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(kind.title)
                            .font(.largeTitle.bold())
                        Text(subtitle(total: state.total))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    TargetPicker()
                    Picker("Sort by", selection: Binding(
                        get: { discover.searches[kind]?.sort ?? .relevance },
                        set: { discover.searches[kind]?.sort = $0 })) {
                        Text("Relevance").tag(Modrinth.Sort.relevance)
                        Text("Downloads").tag(Modrinth.Sort.downloads)
                        Text("Followers").tag(Modrinth.Sort.follows)
                        Text("Newest").tag(Modrinth.Sort.newest)
                        Text("Recently Updated").tag(Modrinth.Sort.updated)
                    }
                    .controlSize(.large)
                    .fixedSize()
                }
                .padding(.bottom, 18)

                if let problem = state.problem {
                    Label(problem, systemImage: "wifi.exclamationmark")
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 20)
                } else if state.hits.isEmpty, !state.loading {
                    ContentUnavailableView.search(text: state.query)
                }

                ForEach(Array(state.hits.enumerated()), id: \.element.id) { index, hit in
                    if index > 0 {
                        Divider().padding(.leading, 70)
                    }
                    ResultRow(hit: hit, kind: kind, library: library) {
                        path.append(ProjectRoute(id: hit.projectID, kind: kind))
                    }
                    .onAppear {
                        if index == state.hits.count - 5 {
                            Task { await discover.loadMore(kind) }
                        }
                    }
                }
                if state.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(20)
                }
            }
            .padding(28)
            .frame(maxWidth: 1100, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(kind.title)
        .searchable(text: Binding(get: { discover.searches[kind]?.query ?? "" }, set: { discover.searches[kind]?.query = $0 }),
                    placement: .toolbar, prompt: "Search \(kind.title)")
        .task(id: "\(state.query)|\(state.sort.rawValue)") {
            if !state.query.isEmpty {
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
            }
            await discover.search(kind)
        }
    }

    private func subtitle(total: Int) -> String {
        let what = switch kind {
        case .mod: "Fabric mods"
        case .resourcePack: "Resource packs"
        case .shader: "Shader packs for Iris"
        }
        let count = total > 0 ? "\(total.formatted()) " : ""
        return "\(count)\(what.lowercased().capitalizedFirst) for Minecraft \(launcher.edition?.minecraft ?? ""), from Modrinth."
    }
}

private struct ResultRow: View {

    let hit: ModrinthHit
    let kind: ContentKind
    let library: InstanceLibrary
    let open: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 14) {
                    ProjectIcon(url: hit.iconURL, size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(hit.title).font(.headline)
                            Text("by \(hit.author)").font(.callout).foregroundStyle(.secondary)
                        }
                        Text(hit.description)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                        HStack(spacing: 14) {
                            Label(Formats.count(hit.downloads), systemImage: "arrow.down.circle")
                            Label(Formats.count(hit.follows), systemImage: "heart")
                            Text(categories)
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            GetButton(project: hit.projectID, kind: kind, library: library)
                .frame(minWidth: 70)
        }
        .padding(.vertical, 12)
    }

    private var categories: String {
        let hidden: Set<String> = ["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "minecraft", "canvas", "babric"]
        return hit.categories.filter { !hidden.contains($0) }.prefix(3)
            .map { $0.replacingOccurrences(of: "-", with: " ").capitalizedFirst }.joined(separator: " · ")
    }
}
