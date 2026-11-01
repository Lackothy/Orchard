import OrchardKit
import SwiftUI

/// Makes an instance: a name, an icon, and what it starts with.
struct NewInstanceSheet: View {

    @Environment(Launcher.self) private var launcher
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var icon = InstanceIcon.sun
    @State private var start = Start.orchardOnly
    @State private var source = Instance.defaultID
    @State private var creating = false
    @State private var problem: String?

    enum Start: Hashable {
        case orchardOnly, performanceSet, copy
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("New Instance")
                .font(.title2.weight(.semibold))

            Form {
                LabeledContent("Icon") {
                    HStack(spacing: 6) {
                        ForEach(InstanceIcon.allCases, id: \.self) { choice in
                            Button { icon = choice } label: {
                                PixelIcon(icon: choice, size: 28)
                                    .padding(3)
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                                            .strokeBorder(icon == choice ? Accent.color : .clear, lineWidth: 2)
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(choice.label)
                            .accessibilityAddTraits(icon == choice ? .isSelected : [])
                            .help(choice.label)
                        }
                    }
                    // Line the label up with the middle of the icons, which have no baseline.
                    .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
                }
                TextField("Name", text: $name, prompt: Text("Survival with Shaders"))
                    .onSubmit(create)
                LabeledContent("Game") {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Minecraft \(launcher.edition?.minecraft ?? "") with Fabric")
                        Text("Orchard is made for this version, so every instance runs it.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                LabeledContent("Start with") {
                    VStack(spacing: 8) {
                        StartOption(title: "Orchard only", detail: "Orchard and Fabric API. Add more from Discover.",
                                    selected: start == .orchardOnly) { start = .orchardOnly }
                        StartOption(title: "Orchard and the performance set",
                                    detail: "Lithium, FerriteCore, Entity Culling and ImmediatelyFast.",
                                    selected: start == .performanceSet) { start = .performanceSet }
                        StartOption(title: "A copy of another instance", detail: "Its mods, packs, worlds and settings.",
                                    selected: start == .copy) { start = .copy } accessory: {
                            Picker("Instance", selection: $source) {
                                ForEach(launcher.instances) { instance in
                                    Text(instance.name).tag(instance.id)
                                }
                            }
                            .pickerStyle(.menu)
                            .labelsHidden()
                            .fixedSize()
                            .onChange(of: source) { start = .copy }
                        }
                    }
                }
            }
            .formStyle(.columns)

            if let problem {
                Label(problem, systemImage: "exclamationmark.triangle.fill")
                    .symbolRenderingMode(.multicolor)
                    .font(.callout)
            }

            HStack {
                if creating {
                    ProgressView().controlSize(.small)
                    Text(start == .performanceSet ? "Adding the performance set…" : "Making the instance…")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Create", action: create)
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.glassProminent)
                    .tint(Accent.color)
                    .foregroundStyle(Accent.onAccent)
                    .disabled(trimmed.isEmpty || creating)
            }
            .controlSize(.large)
        }
        .padding(24)
        .frame(width: 520)
        .disabled(creating)
    }

    private func create() {
        guard !trimmed.isEmpty, !creating else { return }
        creating = true
        let choice: NewInstanceStart = switch start {
        case .orchardOnly: .orchardOnly
        case .performanceSet: .performanceSet
        case .copy: .copy(source)
        }
        Task {
            do {
                try await launcher.createInstance(name: trimmed, icon: icon, start: choice)
                dismiss()
            } catch {
                problem = error.localizedDescription
                creating = false
            }
        }
    }
}

/// One way to start an instance: a card that shows it's chosen with the accent.
private struct StartOption<Accessory: View>: View {

    let title: String
    let detail: String
    let selected: Bool
    let choose: () -> Void
    @ViewBuilder var accessory: Accessory

    init(title: String, detail: String, selected: Bool, choose: @escaping () -> Void,
         @ViewBuilder accessory: () -> Accessory = { EmptyView() }) {
        self.title = title
        self.detail = detail
        self.selected = selected
        self.choose = choose
        self.accessory = accessory()
    }

    var body: some View {
        HStack(spacing: 10) {
            Button(action: choose) {
                HStack(spacing: 10) {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(selected ? AnyShapeStyle(Accent.color) : AnyShapeStyle(.tertiary))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
            accessory
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .card(radius: 14)
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(selected ? Accent.color : .clear, lineWidth: 1.5)
        }
    }
}
