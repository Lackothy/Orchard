import AppKit
import OrchardKit
import SwiftUI

struct SettingsView: View {

    @Environment(Launcher.self) private var launcher
    @AppStorage(Preferences.memoryKey) private var memory = Preferences.defaultMemory
    @AppStorage(Preferences.afterLaunchKey) private var afterLaunch = Preferences.AfterLaunch.keepOpen
    @AppStorage(Preferences.javaArgumentsKey) private var javaArguments = ""
    @AppStorage(Preferences.sceneKey) private var scene = SceneTime.timeOfDay
    @AppStorage(Preferences.openingKey) private var opening = Opening.iconFirst

    var body: some View {
        Form {
            Section {
                HStack(spacing: 16) {
                    ForEach(SceneTime.allCases) { choice in
                        SceneChoice(choice: choice, selected: scene == choice) { scene = choice }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
            } header: {
                Text("Background")
            } footer: {
                Text("The orchard behind each instance, and behind Minecraft's menus from the next time it starts. Auto follows the Mac's clock.")
            }

            Section {
                Picker("When Orchard opens", selection: $opening) {
                    ForEach(Opening.allCases) { choice in
                        Text(choice.label).tag(choice)
                    }
                }
                .pickerStyle(.radioGroup)
                LabeledContent {
                    Button("Preview") { launcher.opening.play(opening) }
                } label: {
                    Text(opening.description)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text("The window’s opening animation. It doesn’t play while the Mac’s Reduce Motion is on.")
            }

            Section {
                LabeledContent("Memory") {
                    HStack(spacing: 10) {
                        Slider(value: memoryValue, in: 2...Double(Preferences.maximumMemory), step: 1)
                            .frame(width: 200)
                        Text("\(memory) GB")
                            .monospacedDigit()
                            .frame(width: 46, alignment: .trailing)
                    }
                }
            } footer: {
                Text("The most Minecraft may use. 4 GB suits most players; more helps at long render distances.")
            }

            Section {
                Picker("When Minecraft starts", selection: $afterLaunch) {
                    ForEach(Preferences.AfterLaunch.allCases) { choice in
                        Text(choice.label).tag(choice)
                    }
                }
                .pickerStyle(.radioGroup)
            }

            Section("Java") {
                LabeledContent("Runtime", value: "Java 25, from Mojang")
                TextField("Extra arguments", text: $javaArguments, prompt: Text("None"))
            }

            Section("Instances") {
                LabeledContent {
                    Button("Show in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting([launcher.paths.instances])
                    }
                } label: {
                    Text(launcher.paths.instances.path.replacingOccurrences(of: NSHomeDirectory(), with: "~"))
                        .truncationMode(.middle)
                        .lineLimit(1)
                        .help(launcher.paths.instances.path)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 500)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var memoryValue: Binding<Double> {
        Binding(get: { Double(min(memory, Preferences.maximumMemory)) }, set: { memory = Int($0) })
    }
}

/// One Background choice, shown as the orchard it gives, as the Mac's Appearance pane shows its
/// themes: Auto is day and night split across the corner, as the Mac's is light and dark.
private struct SceneChoice: View {

    let choice: SceneTime
    let selected: Bool
    let action: () -> Void

    private static let size = CGSize(width: 80, height: 50)

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                preview
                    .frame(width: Self.size.width, height: Self.size.height)
                    .clipShape(.rect(cornerRadius: 7))
                    .overlay {
                        RoundedRectangle(cornerRadius: 7)
                            .strokeBorder(.primary.opacity(0.12), lineWidth: 0.5)
                    }
                    .overlay {
                        if selected {
                            RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(Color.accentColor, lineWidth: 3)
                                .padding(-4)
                        }
                    }
                Text(choice.label)
                    .font(.subheadline)
                    .foregroundStyle(selected ? .primary : .secondary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(choice.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private var preview: some View {
        if let hour = choice.hour {
            scene(at: hour)
        } else {
            ZStack {
                scene(at: SceneTime.day.hour ?? 12)
                scene(at: SceneTime.night.hour ?? 23)
                    .mask(LowerCorner())
            }
        }
    }

    /// The scene laid out at a page's size and shrunk, so its blocks and trees keep their proportions.
    private func scene(at hour: Double) -> some View {
        let scale: CGFloat = 0.25
        return OrchardScene(hour: hour, moving: false)
            .frame(width: Self.size.width / scale, height: Self.size.height / scale)
            .scaleEffect(scale, anchor: .topLeading)
            .frame(width: Self.size.width, height: Self.size.height, alignment: .topLeading)
    }
}

/// The half below the diagonal from the bottom left corner to the top right one.
private struct LowerCorner: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            path.closeSubpath()
        }
    }
}
