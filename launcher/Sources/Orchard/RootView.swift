import AuthenticationServices
import OrchardKit
import SwiftUI

/// The window: instances and Discover in the sidebar, the chosen one beside it.
struct RootView: View {

    @Environment(Launcher.self) private var launcher
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var editingName = false

    var body: some View {
        @Bindable var launcher = launcher
        NavigationSplitView {
            Sidebar()
                .navigationSplitViewColumnWidth(min: 200, ideal: 230, max: 300)
        } detail: {
            Group {
                switch launcher.selection {
                case .instance(let id):
                    if let instance = launcher.instance(id) {
                        InstanceView(instance: instance)
                            .id(id)
                    }
                case .recommended:
                    DiscoverView(page: .recommended)
                case .browse(let kind):
                    DiscoverView(page: .browse(kind))
                        .id(kind)
                case nil:
                    ContentUnavailableView("Choose an Instance", systemImage: "square.stack")
                }
            }
            .toolbar {
                ToolbarSpacer(.flexible)
                ToolbarItemGroup {
                    Button("Game Output", systemImage: "text.alignleft") { openWindow(id: "output") }
                        .help("Show what Minecraft prints while it runs")
                    Menu {
                        Text(launcher.playingAs)
                        if launcher.microsoft != nil {
                            Button("Sign Out") { launcher.signOut() }
                        } else {
                            Button("Change Name…") { editingName = true }
                            Divider()
                            Button("Sign In with Microsoft…", action: signIn)
                        }
                    } label: {
                        Label(launcher.signingIn ? "Signing In…" : launcher.playerName,
                              systemImage: launcher.microsoft != nil ? "person.crop.circle.badge.checkmark" : "person.crop.circle")
                            .labelStyle(.titleAndIcon)
                    }
                    .disabled(launcher.signingIn)
                    .help(launcher.microsoft != nil ? "Your Microsoft account" : "Your player name")
                }
            }
        }
        .frame(minWidth: 900, minHeight: 600)
        .modifier(OpeningWindow())
        .onAppear {
            if launcher.openingOutput { openWindow(id: "output") }
            if launcher.openingSettings { openSettings() }
        }
        // Every page says what it is in its own large title or hero.
        .toolbar(removing: .title)
        .sheet(isPresented: $editingName) {
            AccountSheet()
        }
        .alert("Couldn’t Sign In", isPresented: Binding(get: { launcher.signInError != nil },
                                                        set: { if !$0 { launcher.signInError = nil } })) {
            Button("OK") {}
        } message: {
            Text(launcher.signInError ?? "")
        }
        .sheet(isPresented: $launcher.showingNewInstance) {
            NewInstanceSheet()
        }
    }

    /// Microsoft's page in the system's sign-in sheet; a private browser session each time, so
    /// another account can be picked.
    private func signIn() {
        Task {
            await launcher.signIn { url in
                try await webAuthenticationSession.authenticate(
                    using: url, callback: .customScheme(MicrosoftAuth.callbackScheme),
                    preferredBrowserSession: .ephemeral, additionalHeaderFields: [:])
            }
        }
    }
}

private struct Sidebar: View {

    @Environment(Launcher.self) private var launcher
    @State private var renaming: Instance?
    @State private var newName = ""
    @State private var deleting: Instance?

    var body: some View {
        @Bindable var launcher = launcher
        List(selection: $launcher.selection) {
            Section("Instances") {
                ForEach(launcher.instances) { instance in
                    Label {
                        Text(instance.name)
                    } icon: {
                        PixelIcon(icon: instance.icon, size: 18)
                            .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { frame in
                                if instance.id == launcher.instances.first?.id { launcher.opening.target = frame }
                            }
                    }
                    .modifier(OpeningFade(step: min(3, launcher.instances.firstIndex { $0.id == instance.id } ?? 0)))
                    .tag(Destination.instance(instance.id))
                    .contextMenu { menu(for: instance) }
                }
            }
            Section("Discover") {
                Label("Recommended", systemImage: "star")
                    .modifier(OpeningFade(step: 4))
                    .tag(Destination.recommended)
                Label("Mods", systemImage: "shippingbox")
                    .modifier(OpeningFade(step: 4))
                    .tag(Destination.browse(.mod))
                Label("Resource Packs", systemImage: "photo.on.rectangle")
                    .modifier(OpeningFade(step: 4))
                    .tag(Destination.browse(.resourcePack))
                Label("Shaders", systemImage: "sun.max")
                    .modifier(OpeningFade(step: 4))
                    .tag(Destination.browse(.shader))
            }
        }
        .toolbar {
            ToolbarItem {
                Button("New Instance", systemImage: "plus") { launcher.showingNewInstance = true }
                    .help("Make a new instance, with its own mods and worlds")
            }
        }
        .alert("Rename Instance", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Rename") {
                if var instance = renaming, !newName.trimmingCharacters(in: .whitespaces).isEmpty {
                    instance.name = newName.trimmingCharacters(in: .whitespaces)
                    launcher.update(instance)
                }
                renaming = nil
            }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog(deleting.map { "Delete “\($0.name)”?" } ?? "",
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Move to Trash", role: .destructive) {
                if let deleting { launcher.delete(deleting) }
                deleting = nil
            }
        } message: {
            Text("Its worlds, mods and screenshots go to the Trash with it.")
        }
    }

    @ViewBuilder private func menu(for instance: Instance) -> some View {
        Button("Play") { launcher.play(instance) }
            .disabled(!launcher.canPlay)
        Divider()
        Button("Rename…") {
            newName = instance.name
            renaming = instance
        }
        Menu("Icon") {
            ForEach(InstanceIcon.allCases, id: \.self) { icon in
                Button(icon.label) {
                    var changed = instance
                    changed.icon = icon
                    launcher.update(changed)
                }
            }
        }
        Button("Show in Finder") { launcher.revealFolder(of: instance.id) }
        if !instance.isDefault {
            Divider()
            Button("Delete…", role: .destructive) { deleting = instance }
        }
    }
}
