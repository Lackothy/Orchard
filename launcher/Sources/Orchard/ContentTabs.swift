import OrchardKit
import SwiftUI
import UniformTypeIdentifiers

/// An instance's mods, resource packs or shader packs: on and off, updates, and files dropped in.
struct ContentTab: View {

    let instance: Instance
    let library: InstanceLibrary
    let kind: ContentKind
    /// What's typed in the toolbar's search field.
    let query: String
    @Environment(Launcher.self) private var launcher
    @State private var filter = Filter.all
    @State private var dropping = false

    enum Filter: Hashable {
        case all, updates, off
    }

    var body: some View {
        let all = library.items(kind)
        let shown = visible(all)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Text("\(all.count) \(noun(all.count).capitalizedFirst)")
                    .font(.title3.weight(.semibold))
                Spacer()
                if kind == .mod {
                    Picker("Show", selection: $filter) {
                        Text("All Mods").tag(Filter.all)
                        Text(library.updates.isEmpty ? "Updates" : "Updates (\(library.updates.count))").tag(Filter.updates)
                        Text("Turned Off").tag(Filter.off)
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .fixedSize()
                    .help("Which mods to show")
                }
                if kind == .mod, !library.updates.isEmpty {
                    Button("Update All", systemImage: "arrow.down.circle") {
                        Task { await library.updateAll() }
                    }
                }
                Button(addTitle, systemImage: "plus") {
                    launcher.discoverTarget = instance.id
                    launcher.selection = .browse(kind)
                }
            }
            .buttonBorderShape(.capsule)
            .controlSize(.large)

            if all.isEmpty {
                empty
            } else if shown.isEmpty {
                Text("Nothing matches.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 80)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                        if index > 0 {
                            Divider().padding(.leading, 62)
                        }
                        ContentRow(item: item, library: library)
                    }
                }
                .card()
            }

            Text(footnote)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .overlay {
            if dropping {
                RoundedRectangle(cornerRadius: Card.radius + 6, style: .continuous)
                    .strokeBorder(Accent.color, lineWidth: 2)
                    .padding(-6)
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            let accepted = urls.filter { ["jar", "zip"].contains($0.pathExtension.lowercased()) }
            guard !accepted.isEmpty else { return false }
            library.add(accepted, as: kind)
            return true
        } isTargeted: { dropping = $0 }
    }

    private func visible(_ items: [ContentItem]) -> [ContentItem] {
        items.filter { item in
            switch filter {
            case .all: true
            case .updates: library.updates[item.fileName] != nil
            case .off: !item.enabled
            }
        }.filter { item in
            query.isEmpty || item.name.localizedCaseInsensitiveContains(query)
                || item.fileName.localizedCaseInsensitiveContains(query)
        }
    }

    private func noun(_ count: Int) -> String {
        switch kind {
        case .mod: count == 1 ? "mod" : "mods"
        case .resourcePack: count == 1 ? "pack" : "packs"
        case .shader: count == 1 ? "shader" : "shaders"
        }
    }

    private var addTitle: String {
        switch kind {
        case .mod: "Add Mods…"
        case .resourcePack: "Add Resource Packs…"
        case .shader: "Add Shaders…"
        }
    }

    private var footnote: String {
        switch kind {
        case .mod:
            "Drop .jar files here to add your own. A switch renames the file to .jar.disabled, so nothing is deleted. Orchard and Fabric API come with every instance."
        case .resourcePack:
            "Packs turned on here are on the next time Minecraft starts; the last one turned on goes on top. Drop .zip files here to add your own."
        case .shader:
            "One shader pack is used at a time. They run on Iris, which comes with the first one you add, and an instance with Iris runs on Mojang’s renderer instead of Metal."
        }
    }

    @ViewBuilder private var empty: some View {
        let (title, symbol, message): (String, String, String) = switch kind {
        case .mod: ("No Mods Yet", "shippingbox", "Find mods in Discover, or drop .jar files here.")
        case .resourcePack: ("No Resource Packs", "photo.on.rectangle", "Find packs in Discover, or drop .zip files here.")
        case .shader: ("No Shaders", "sun.max", "Shader packs change the light, sky and water. Find them in Discover.")
        }
        ContentUnavailableView(title, systemImage: symbol, description: Text(message))
            .frame(maxWidth: .infinity, minHeight: 200)
    }
}

private struct ContentRow: View {

    let item: ContentItem
    let library: InstanceLibrary
    @Environment(Launcher.self) private var launcher

    var body: some View {
        let project = library.project(of: item)
        let update = library.updates[item.fileName]
        let projectID = library.versions[item.fileName]?.projectID
        HStack(spacing: 12) {
            if item.managed, item.fileName == "orchard.jar" {
                PixelIcon(icon: .tree, size: 36)
            } else {
                ProjectIcon(url: project?.iconURL, data: item.icon, size: 36, dimmed: !item.enabled)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(project?.title ?? item.name)
                        .font(.headline)
                        .lineLimit(1)
                    if let version = item.version ?? library.versions[item.fileName]?.versionNumber {
                        Text(version)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Text(subtitle(project))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 12)
            if item.managed {
                Label("Included with Orchard", systemImage: "lock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let projectID, case .installing(let fraction) = library.status(of: projectID) {
                ProgressView(value: fraction)
                    .frame(width: 80)
            } else if let update {
                Button("Update to \(update.versionNumber)", systemImage: "arrow.down.circle") {
                    Task { await library.update(item) }
                }
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                .lineLimit(1)
            }
            Toggle(item.name, isOn: Binding(get: { item.enabled }, set: { library.setEnabled(item, $0) }))
                .toggleStyle(.switch)
                .labelsHidden()
                .controlSize(.small)
                .tint(Accent.color)
                .disabled(item.managed)
                .help(item.managed ? "Orchard needs this, so it’s always on" : (item.enabled ? "Turn off" : "Turn on"))
            Menu {
                Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([item.file]) }
                if let project {
                    Button("View on Modrinth") {
                        NSWorkspace.shared.open(Modrinth.page(of: project.slug, kind: item.kind))
                    }
                }
                if !item.managed {
                    Divider()
                    Button("Move to Trash", role: .destructive) { library.remove(item) }
                }
            } label: {
                Label("More", systemImage: "ellipsis")
                    .labelStyle(.iconOnly)
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.borderless)
            .fixedSize()
            .accessibilityLabel("More for \(item.name)")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contextMenu {
            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([item.file]) }
            if !item.managed {
                Button("Move to Trash", role: .destructive) { library.remove(item) }
            }
        }
    }

    private func subtitle(_ project: ModrinthHit?) -> String {
        if item.managed {
            return item.summary ?? "Part of Orchard"
        }
        if !item.enabled {
            return switch item.kind {
            case .mod: "Turned off"
            case .resourcePack: "Not in use"
            case .shader: "Not in use"
            }
        }
        let authors = project.map { [$0.author] } ?? item.authors
        let by = authors.isEmpty ? nil : "by " + authors.prefix(2).joined(separator: ", ")
        let about = item.summary ?? project?.description
        if project == nil, item.kind == .mod, library.versions.isEmpty == false {
            return [by, "Added by you · \(item.fileName)"].compactMap(\.self).joined(separator: " · ")
        }
        return [by, about].compactMap(\.self).joined(separator: " · ")
    }
}

// MARK: - Worlds

struct WorldsTab: View {

    let instance: Instance
    let library: InstanceLibrary
    @Environment(Launcher.self) private var launcher

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            if library.worlds.isEmpty && library.servers.isEmpty {
                ContentUnavailableView("No Worlds Yet", systemImage: "globe.americas",
                                       description: Text("Worlds you make in Minecraft show up here."))
                    .frame(maxWidth: .infinity, minHeight: 200)
            }
            if !library.worlds.isEmpty {
                list("Worlds", library.worlds.map { world in
                    Entry(id: world.id, title: world.name, subtitle: subtitle(world),
                          image: world.icon.flatMap { NSImage(contentsOf: $0) }, action: "Play",
                          play: { launcher.play(instance, quickPlay: .world(world.folder)) })
                })
            }
            if !library.servers.isEmpty {
                list("Servers", library.servers.map { server in
                    Entry(id: server.id, title: server.name, subtitle: server.address,
                          image: server.icon.flatMap { NSImage(data: $0) }, action: "Join",
                          play: { launcher.play(instance, quickPlay: .server(server.address)) })
                })
            }
        }
    }

    private struct Entry: Identifiable {
        let id: String
        let title: String
        let subtitle: String
        let image: NSImage?
        let action: String
        let play: () -> Void
    }

    private func list(_ title: String, _ entries: [Entry]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.weight(.semibold))
            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    if index > 0 {
                        Divider().padding(.leading, 66)
                    }
                    HStack(spacing: 12) {
                        Group {
                            if let image = entry.image {
                                Image(nsImage: image).resizable().interpolation(.none)
                            } else {
                                PixelIcon(icon: .grass, size: 40)
                            }
                        }
                        .frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.title).font(.headline)
                            Text(entry.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(entry.action, action: entry.play)
                            .buttonBorderShape(.capsule)
                            .disabled(!launcher.canPlay)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }
            .card()
        }
    }

    private func subtitle(_ world: World) -> String {
        var parts = [world.hardcore ? "Hardcore" : world.mode?.label ?? "Singleplayer"]
        if let last = world.lastPlayed {
            parts.append("Played \(Formats.lastPlayed(last))")
        }
        parts.append(world.folder)
        return parts.joined(separator: " · ")
    }
}

// MARK: - Screenshots

struct ScreenshotsTab: View {

    let library: InstanceLibrary

    var body: some View {
        if library.screenshots.isEmpty {
            ContentUnavailableView("No Screenshots", systemImage: "camera",
                                   description: Text("Press F2 in Minecraft to take one."))
                .frame(maxWidth: .infinity, minHeight: 200)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 12)], spacing: 12) {
                ForEach(library.screenshots, id: \.self) { file in
                    Button { NSWorkspace.shared.open(file) } label: {
                        AsyncImage(url: file) { image in
                            image.resizable().aspectRatio(16 / 9, contentMode: .fill)
                        } placeholder: {
                            Rectangle().fill(.quaternary)
                        }
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .help(file.lastPathComponent)
                    .contextMenu {
                        Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([file]) }
                    }
                }
            }
        }
    }
}
