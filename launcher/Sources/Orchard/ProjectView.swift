import OrchardKit
import SwiftUI

/// A Modrinth project's page: what it is, its pictures and description, what it needs, and the
/// button that installs it into the chosen instance.
struct ProjectView: View {

    let route: ProjectRoute
    @Environment(Launcher.self) private var launcher
    @State private var project: ModrinthProject?
    @State private var owner: String?
    @State private var newest: ModrinthVersion?
    @State private var noVersion = false
    @State private var needs: [ModrinthProject] = []
    @State private var problem: String?

    var body: some View {
        Group {
            if let project {
                page(project)
            } else if let problem {
                ContentUnavailableView("Couldn’t Load", systemImage: "wifi.exclamationmark", description: Text(problem))
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(project?.title ?? "")
        .task(id: route) { await load() }
    }

    private func page(_ project: ModrinthProject) -> some View {
        let library = launcher.library(launcher.discoverTarget)
        return ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                HStack(alignment: .top, spacing: 18) {
                    ProjectIcon(url: project.iconURL, size: 96)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(project.title)
                            .font(.largeTitle.bold())
                        if let owner {
                            Text("by \(owner)")
                                .foregroundStyle(.secondary)
                        }
                        Text(project.description)
                            .fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: 16) {
                            Label("\(Formats.count(project.downloads)) downloads", systemImage: "arrow.down.circle")
                            Label("\(Formats.count(project.followers)) followers", systemImage: "heart")
                            if let updated = project.updatedDate {
                                Label("Updated \(updated.formatted(date: .abbreviated, time: .omitted))", systemImage: "clock")
                            }
                        }
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                    }
                    Spacer(minLength: 16)
                    VStack(alignment: .trailing, spacing: 6) {
                        TargetPicker()
                            .padding(.bottom, 4)
                        InstallButton(project: project, kind: route.kind, library: library, available: !noVersion)
                        if let newest {
                            Text(newest.versionNumber)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                        } else if noVersion {
                            Text("Nothing for Minecraft \(launcher.edition?.minecraft ?? "") yet")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if !project.gallery.isEmpty {
                    ScrollView(.horizontal) {
                        HStack(spacing: 12) {
                            ForEach(gallery(project), id: \.url) { image in
                                AsyncImage(url: URL(string: image.url)) { picture in
                                    picture.resizable().aspectRatio(contentMode: .fill)
                                } placeholder: {
                                    Rectangle().fill(.quaternary)
                                }
                                .frame(width: 380, height: 214)
                                .clipShape(RoundedRectangle(cornerRadius: Card.radius, style: .continuous))
                                .help(image.title ?? "")
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                }

                HStack(alignment: .top, spacing: 30) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("About")
                            .font(.title3.weight(.semibold))
                        MarkdownView(project.body)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    VStack(alignment: .leading, spacing: 14) {
                        InfoBox(title: "Works with") {
                            row("Minecraft \(launcher.edition?.minecraft ?? "")", ok: !noVersion)
                            row(loader, ok: true)
                            if project.clientSide == "required" && project.serverSide == "unsupported" {
                                row("Only on your side, so any server", ok: true)
                            }
                        }
                        if !needs.isEmpty {
                            InfoBox(title: "Needs") {
                                ForEach(needs) { need in
                                    let there = library.status(of: need.id) != .notInstalled
                                    HStack(spacing: 8) {
                                        ProjectIcon(url: need.iconURL, size: 22)
                                        VStack(alignment: .leading, spacing: 0) {
                                            Text(need.title).font(.callout)
                                            Text(there ? "Already in \(targetName)" : "Comes with it")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                        InfoBox(title: "License") {
                            if let address = project.license.url, let url = URL(string: address) {
                                Link(project.license.title, destination: url)
                            } else {
                                Text(project.license.title)
                            }
                        }
                        InfoBox(title: "Links") {
                            Link("View on Modrinth", destination: Modrinth.page(of: project.slug, kind: route.kind))
                            if let url = project.sourceURL { Link("Source code", destination: url) }
                            if let url = project.issuesURL { Link("Report an issue", destination: url) }
                            if let url = project.discordURL { Link("Discord", destination: url) }
                            if let url = project.donationURL { Link("Support the author", destination: url) }
                        }
                    }
                    .frame(width: 260)
                }
            }
            .padding(28)
            .frame(maxWidth: 1100, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var targetName: String {
        launcher.instance(launcher.discoverTarget)?.name ?? ""
    }

    private var loader: String {
        switch route.kind {
        case .mod: "Fabric"
        case .resourcePack: "Any instance, as a resource pack"
        case .shader: "Iris, which comes with it"
        }
    }

    private func row(_ text: String, ok: Bool) -> some View {
        Label {
            Text(text).font(.callout)
        } icon: {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle")
                .foregroundStyle(ok ? Color.green : Color.secondary)
        }
    }

    private func gallery(_ project: ModrinthProject) -> [ModrinthProject.Image] {
        project.gallery.sorted { a, b in
            if a.featured != b.featured { return a.featured }
            return (a.ordering ?? 0) < (b.ordering ?? 0)
        }
    }

    private func load() async {
        let discover = launcher.discover
        do {
            let project = try await discover.project(route.id)
            self.project = project
            problem = nil
            owner = await discover.owner(of: route.id)
            guard let minecraft = launcher.edition?.minecraft else { return }
            let installer = ContentInstaller(modrinth: launcher.modrinth, minecraft: minecraft)
            do {
                let version = try await installer.newest(route.id, kind: route.kind)
                newest = version
                var found: [ModrinthProject] = []
                for (id, _) in try await installer.requirements(of: version, kind: route.kind) {
                    if let need = try? await discover.project(id) {
                        found.append(need)
                    }
                }
                needs = found
            } catch LauncherError.noCompatibleVersion {
                noVersion = true
            }
        } catch {
            problem = DiscoverModel.describe(error)
        }
    }
}

private struct InfoBox<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .card(radius: 16)
    }
}

/// Install to the chosen instance, in the accent: the page's one prominent button.
private struct InstallButton: View {

    let project: ModrinthProject
    let kind: ContentKind
    let library: InstanceLibrary
    let available: Bool
    @Environment(Launcher.self) private var launcher

    var body: some View {
        let name = launcher.instance(launcher.discoverTarget)?.name ?? ""
        let status = library.status(of: project.id)
        Button {
            switch status {
            case .notInstalled:
                Task { await library.install(project.id, kind: kind) }
            case .updateAvailable:
                if let item = library.item(for: project.id) {
                    Task { await library.update(item) }
                }
            default:
                break
            }
        } label: {
            HStack(spacing: 8) {
                switch status {
                case .notInstalled:
                    Image(systemName: "arrow.down.circle.fill")
                    Text("Install to \(name)")
                case .installing(let fraction):
                    ProgressView(value: max(0.02, fraction))
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                    Text("Installing…")
                case .installed:
                    Image(systemName: "checkmark")
                    Text("In \(name)")
                case .updateAvailable:
                    Image(systemName: "arrow.down.circle.fill")
                    Text("Update in \(name)")
                }
            }
            .font(.headline)
            .foregroundStyle(status == .installed ? Color.secondary : Accent.onAccent)
            .padding(.horizontal, 4)
        }
        .buttonStyle(.glassProminent)
        .tint(status == .installed ? Color.gray.opacity(0.3) : Accent.color)
        .controlSize(.large)
        .disabled(!available || status == .installed || { if case .installing = status { true } else { false } }())
    }
}
