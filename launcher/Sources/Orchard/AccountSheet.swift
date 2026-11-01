import OrchardKit
import SwiftUI

/// The player name for offline play. Online, the name is the Microsoft account's.
struct AccountSheet: View {

    @Environment(Launcher.self) private var launcher
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    private var valid: Bool { OfflineAccount.isValid(name) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Player Name")
                .font(.headline)
            VStack(alignment: .leading, spacing: 6) {
                TextField("Name", text: $name, prompt: Text("Steve"))
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(save)
                Text(valid || name.isEmpty
                     ? "Used in singleplayer and on servers in offline mode."
                     : "Use 3 to 16 letters, numbers or underscores.")
                    .font(.caption)
                    .foregroundStyle(valid || name.isEmpty ? .secondary : Color.red)
            }
            Text(MicrosoftAuth.configured == nil
                 ? "Signing in with Microsoft, for online servers, comes once Orchard’s Microsoft app is approved."
                 : "To play on online servers, sign in with Microsoft from the account menu.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Save", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!valid)
            }
        }
        .padding(20)
        .frame(width: 340)
        .onAppear { name = launcher.account.name }
    }

    private func save() {
        guard valid else { return }
        launcher.account = OfflineAccount(name: name)
        dismiss()
    }
}
