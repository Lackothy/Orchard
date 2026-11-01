import AppKit
import OrchardKit
import SwiftUI

/// Everything the game prints, newest at the bottom: what went wrong stands out, stack traces
/// fold away under their message, and what Minecraft always logs when playing offline says so.
struct OutputView: View {

    @Environment(Launcher.self) private var launcher
    @State private var show = Show.all
    @State private var query = ""
    /// At the bottom, so new lines scroll into view; scrolled up to read, so they don't.
    @State private var following = true
    @State private var expanded: Set<LogEntry.ID> = []

    enum Show: Hashable {
        case all, warnings, errors
    }

    var body: some View {
        let log = launcher.log
        let shown = visible(log.entries)
        VStack(spacing: 0) {
            if let crash = launcher.crash, !log.isEmpty {
                CrashBanner(crash: crash)
                    .padding([.horizontal, .top], 12)
            }
            content(log, shown)
        }
        .frame(minWidth: 560, minHeight: 320)
        .navigationSubtitle(subtitle)
        .searchable(text: $query, placement: .toolbar, prompt: "Search Output")
        .toolbar { toolbar(shown) }
        .onChange(of: launcher.logInstance) { expanded.removeAll() }
    }

    @ViewBuilder private func content(_ log: GameLog, _ shown: [LogEntry]) -> some View {
        Group {
            if log.isEmpty {
                empty
            } else if shown.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView(show == .errors ? "No Errors" : "No Warnings", systemImage: "checkmark.circle",
                                           description: Text("Nothing like that in this session."))
                } else {
                    ContentUnavailableView.search(text: query)
                }
            } else {
                entries(shown, lines: log.lines)
            }
        }
        .frame(maxHeight: .infinity)
    }

    // MARK: - The log

    private func entries(_ shown: [LogEntry], lines: Int) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 1) {
                    ForEach(shown) { entry in
                        EntryRow(entry: entry, query: query, expanded: Binding(
                            get: { expanded.contains(entry.id) },
                            set: { if $0 { expanded.insert(entry.id) } else { expanded.remove(entry.id) } }))
                            .id(entry.id)
                    }
                }
                .padding(8)
            }
            .defaultScrollAnchor(.bottom)
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.visibleRect.maxY >= geometry.contentSize.height - 30
            } action: { _, atBottom in
                following = atBottom
            }
            .onAppear {
                if let last = shown.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
            .onChange(of: lines) {
                if following, let last = shown.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
            .overlay(alignment: .bottom) {
                if !following {
                    Button("Jump to Latest", systemImage: "arrow.down") {
                        if let last = shown.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .padding(.bottom, 16)
                    .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.2), value: following)
        }
    }

    @ViewBuilder private var empty: some View {
        let last = lastSessionInstance
        ContentUnavailableView {
            Label("Nothing Yet", systemImage: "text.alignleft")
        } description: {
            Text("Press Play, and what Minecraft prints while it runs shows here.")
        } actions: {
            if let last {
                Button("Show Last Session of \(last.name)") { launcher.showLastSession(of: last.id) }
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
            }
        }
    }

    /// The instance whose last session the empty window offers: the one on screen, if played.
    private var lastSessionInstance: Instance? {
        let instance = launcher.selectedInstance ?? launcher.instance(Instance.defaultID)
        guard let instance, launcher.logFile(of: instance.id) != nil else { return nil }
        return instance
    }

    private func visible(_ entries: [LogEntry]) -> [LogEntry] {
        entries.filter { entry in
            switch show {
            case .all: true
            case .warnings: entry.level == .warning
            case .errors: entry.level == .error
            }
        }.filter { entry in
            query.isEmpty || entry.message.localizedCaseInsensitiveContains(query)
                || entry.detail.contains { $0.localizedCaseInsensitiveContains(query) }
        }
    }

    // MARK: - Toolbar

    private var subtitle: String {
        guard let id = launcher.logInstance, let name = launcher.instance(id)?.name else { return "" }
        var parts = [name]
        if launcher.logFromFile {
            parts.append("Last session")
        } else if launcher.activeInstance == id, launcher.isPlaying {
            parts.append("Running")
        }
        return parts.joined(separator: " · ")
    }

    @ToolbarContentBuilder private func toolbar(_ shown: [LogEntry]) -> some ToolbarContent {
        if launcher.isPlaying {
            ToolbarItem {
                Button("Quit Minecraft", systemImage: "stop.fill") { launcher.stop() }
                    .help("Quit Minecraft")
            }
        }
        ToolbarItem {
            Menu {
                Picker("Show", selection: $show) {
                    Text("All Messages").tag(Show.all)
                    Text("Warnings (\(launcher.log.warnings))").tag(Show.warnings)
                    Text("Errors (\(launcher.log.errors))").tag(Show.errors)
                }
                .pickerStyle(.inline)
            } label: {
                Label("Show", systemImage: show == .all
                      ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
            }
            .help("Show every message, or only the warnings or errors")
        }
        ToolbarItemGroup {
            Button("Copy", systemImage: "doc.on.doc") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(shown.map(\.text).joined(separator: "\n"), forType: .string)
            }
            .help("Copy what’s shown, as the game printed it")
            .disabled(shown.isEmpty)
            Button("Show Log File", systemImage: "doc.text.magnifyingglass") {
                if let id = launcher.logInstance, let file = launcher.logFile(of: id) {
                    NSWorkspace.shared.activateFileViewerSelecting([file])
                }
            }
            .help("Show latest.log in the Finder")
            .disabled(launcher.logInstance.flatMap(launcher.logFile(of:)) == nil)
            Button("Clear", systemImage: "trash") { launcher.clearOutput() }
                .keyboardShortcut("k")
                .help("Clear the window (the log file stays)")
                .disabled(launcher.log.isEmpty)
        }
    }
}

// MARK: - A crash

/// What the crash report says, at the top of the window, with the report one click away.
private struct CrashBanner: View {

    let crash: CrashReport

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "xmark.octagon.fill")
                .font(.title2)
                .foregroundStyle(.red)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("Minecraft Crashed: \(crash.description)")
                    .font(.headline)
                Text(crash.cause)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .textSelection(.enabled)
                if let mod = crash.mod {
                    Text("It happened in the mod “\(mod)”.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            Button("Show Crash Report") { NSWorkspace.shared.open(crash.file) }
                .buttonBorderShape(.capsule)
                .help(crash.file.lastPathComponent)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.red.opacity(0.13), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - An entry

private struct EntryRow: View {

    let entry: LogEntry
    let query: String
    @Binding var expanded: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(entry.time ?? "")
                .foregroundStyle(.tertiary)
                .frame(width: 62, alignment: .leading)
            marker
                .frame(width: 14)
            VStack(alignment: .leading, spacing: 3) {
                Text(highlighted(entry.message))
                    .fontWeight(entry.level == .launcher ? .semibold : .regular)
                    .foregroundStyle(entry.level == .debug ? .secondary : .primary)
                if let hint = Hint.for(entry) {
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !entry.detail.isEmpty {
                    detail
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 12, design: .monospaced))
        .textSelection(.enabled)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(tint, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        .help(entry.thread.map { "Thread: \($0)" } ?? "")
        .contextMenu {
            Button("Copy") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(entry.text, forType: .string)
            }
        }
    }

    @ViewBuilder private var detail: some View {
        // A search that matches inside a folded trace opens it.
        let open = expanded || (!query.isEmpty && entry.detail.contains { $0.localizedCaseInsensitiveContains(query) })
        let lines = open ? entry.detail : Array(entry.detail.prefix(1))
        Text(highlighted(lines.joined(separator: "\n").replacingOccurrences(of: "\t", with: "    ")))
            .foregroundStyle(.secondary)
        if entry.detail.count > 1 {
            Button(open ? "Show Less" : "Show \(entry.detail.count - 1) More \(entry.detail.count == 2 ? "Line" : "Lines")") {
                expanded = !open
            }
            .buttonStyle(.link)
            .font(.caption)
        }
    }

    @ViewBuilder private var marker: some View {
        switch entry.level {
        case .warning:
            Image(systemName: "exclamationmark.triangle.fill")
                .symbolRenderingMode(.multicolor)
                .font(.system(size: 11))
                .accessibilityLabel("Warning")
        case .error:
            Image(systemName: "xmark.octagon.fill")
                .foregroundStyle(.red)
                .font(.system(size: 11))
                .accessibilityLabel("Error")
        case .launcher:
            PixelIcon(icon: .tree, size: 13)
                .accessibilityLabel("Orchard")
        case .info, .debug:
            Color.clear.frame(height: 1)
        }
    }

    private var tint: Color {
        switch entry.level {
        case .error: .red.opacity(0.13)
        case .warning: .yellow.opacity(0.09)
        case .launcher: Accent.color.opacity(0.12)
        case .info, .debug: .clear
        }
    }

    /// The text with every match of the search marked, as Find marks it.
    private func highlighted(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard !query.isEmpty else { return attributed }
        var start = attributed.startIndex
        while start < attributed.endIndex,
              let range = attributed[start...].range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) {
            attributed[range].backgroundColor = Color(nsColor: .findHighlightColor)
            attributed[range].foregroundColor = .black
            start = range.upperBound
        }
        return attributed
    }
}

/// Messages Minecraft prints on an ordinary start, explained, so they aren't taken for the
/// problem when something else goes wrong.
private enum Hint {
    static func `for`(_ entry: LogEntry) -> String? {
        let message = entry.message
        if message.hasPrefix("Failed to fetch user properties") {
            return "Expected when playing offline: there’s no Microsoft account to look up."
        }
        if message.hasPrefix("Failed to fetch Realms") {
            return "Expected when playing offline: Realms needs a Microsoft account."
        }
        if message == "Failed to get system info for Render Extensions" {
            return "Minecraft logs this at every start. It’s harmless."
        }
        if message.contains("could not be read. If this is a development environment you can ignore this message") {
            return "Harmless: the mod works without this file."
        }
        return nil
    }
}
