import AppKit
import OrchardKit
#if DEBUG
import ScreenCaptureKit
#endif
import SwiftUI

@main
struct OrchardApp: App {

    @State private var launcher = Launcher()

    init() {
        // The launcher is one window, opened fresh at every launch. Without this, macOS brings the
        // app back with no window at all if that window was closed when it quit. Registered, not
        // saved: nothing goes into the player's preferences.
        UserDefaults.standard.register(defaults: ["ApplePersistenceIgnoreState": true])
        #if DEBUG
        Snapshot.scheduleIfAsked()
        #endif
    }

    var body: some Scene {
        Window("Orchard", id: "main") {
            RootView()
                .environment(launcher)
        }
        .defaultSize(width: 1180, height: 760)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Instance…") { launcher.showingNewInstance = true }
                    .keyboardShortcut("n")
            }
            GameCommands(launcher: launcher)
        }

        Window("Game Output", id: "output") {
            OutputView()
                .environment(launcher)
        }
        .defaultSize(width: 760, height: 460)
        .keyboardShortcut("l", modifiers: [.command, .shift])

        Settings {
            SettingsView()
                .environment(launcher)
        }
    }
}

private struct GameCommands: Commands {
    let launcher: Launcher
    @Environment(\.openWindow) private var openWindow

    /// The instance on screen, or the default one while Discover is.
    private var instance: Instance? {
        launcher.selectedInstance ?? launcher.instance(Instance.defaultID)
    }

    var body: some Commands {
        CommandMenu("Game") {
            Button(instance.map { "Play \($0.name)" } ?? "Play") {
                if let instance { launcher.play(instance) }
            }
            .keyboardShortcut("p", modifiers: [.command, .shift])
            .disabled(!launcher.canPlay || instance == nil)
            Button("Quit Minecraft") { launcher.stop() }
                .keyboardShortcut(".", modifiers: .command)
                .disabled(!launcher.isPlaying)
            Divider()
            Button("Show Instance Folder") {
                if let instance { launcher.revealFolder(of: instance.id) }
            }
            Button("Verify Game Files") {
                if let instance { launcher.play(instance, verify: true) }
            }
            .disabled(!launcher.canPlay || instance == nil)
        }
    }
}

#if DEBUG
/// For reviewing the UI from a script: `ORCHARD_SNAPSHOT=<png>` captures the main window (and
/// `-sheet.png` beside it, the sheet if one is up) after `ORCHARD_SNAPSHOT_DELAY` seconds
/// (default 6), then quits. The window floats off-screen meanwhile, so nothing comes in front of
/// whatever the Mac is doing and Stage Manager leaves it alone; its saved frame is not touched.
/// Captured with ScreenCaptureKit, as Liquid Glass is drawn by the window server. Debug builds only.
@MainActor
enum Snapshot {
    static func scheduleIfAsked() {
        let environment = ProcessInfo.processInfo.environment
        guard let path = environment["ORCHARD_SNAPSHOT"] else { return }
        let delay = environment["ORCHARD_SNAPSHOT_DELAY"].flatMap(Double.init) ?? 6
        // Never outlive the capture, whatever happens to it.
        DispatchQueue.main.asyncAfter(deadline: .now() + delay + 20) {
            print("Snapshot timed out")
            exit(2)
        }
        Task { @MainActor in
            guard let window = await firstWindow(environment["ORCHARD_SNAPSHOT_WINDOW"] ?? "main") else {
                print("Snapshot found no window among", NSApp.windows.map { "\($0.identifier?.rawValue ?? "nil") \($0.isVisible) \($0.className)" })
                exit(1)
            }
            window.orderFrontRegardless()
            try? await Task.sleep(for: .seconds(delay))
            print("Snapshot capturing", window.identifier?.rawValue ?? "?", window.frame)
            await capture(window, to: path)
            if let sheet = window.attachedSheet {
                await capture(sheet, to: path.replacingOccurrences(of: ".png", with: "-sheet.png"))
            }
            exit(0)
        }
    }

    /// The window to capture (`main`, or `output`), as soon as SwiftUI has made it. Every window
    /// is moved off-screen the moment it appears, so none shows on the Mac's screen.
    private static func firstWindow(_ prefix: String) async -> NSWindow? {
        var moved: Set<ObjectIdentifier> = []
        for _ in 0..<300 {
            for window in NSApp.windows where !moved.contains(ObjectIdentifier(window)) && window.identifier != nil {
                moved.insert(ObjectIdentifier(window))
                window.setFrameAutosaveName("")
                window.level = .floating
                let screen = window.screen?.frame ?? NSScreen.main?.frame ?? .zero
                window.setFrameOrigin(NSPoint(x: screen.maxX + 200, y: screen.minY))
            }
            if let window = NSApp.windows.first(where: { $0.identifier?.rawValue.hasPrefix(prefix) == true }) {
                return window
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return nil
    }

    private static func capture(_ window: NSWindow, to path: String, attempts: Int = 3) async {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            guard let target = content.windows.first(where: { $0.windowID == CGWindowID(window.windowNumber) }) else {
                print("Snapshot: window \(window.windowNumber) is not shareable")
                return
            }
            let filter = SCContentFilter(desktopIndependentWindow: target)
            let config = SCStreamConfiguration()
            config.width = Int(window.frame.width * window.backingScaleFactor)
            config.height = Int(window.frame.height * window.backingScaleFactor)
            config.showsCursor = false
            config.ignoreShadowsSingleWindow = true
            let image: CGImage = try await withCheckedThrowingContinuation { continuation in
                SCScreenshotManager.captureImage(contentFilter: filter, configuration: config) { image, error in
                    if let image { continuation.resume(returning: image) } else { continuation.resume(throwing: error ?? CancellationError()) }
                }
            }
            try NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])?.write(to: URL(filePath: path))
            print("Snapshot wrote", path)
        } catch {
            print("Snapshot failed: \(error)")
            if attempts > 1 {
                try? await Task.sleep(for: .seconds(1))
                await capture(window, to: path, attempts: attempts - 1)
            } else {
                drawn(window, to: path)
            }
        }
    }

    /// When ScreenCaptureKit can't be had (in a sandbox, say), the window draws itself instead:
    /// everything but the Liquid Glass, which only the window server draws.
    private static func drawn(_ window: NSWindow, to path: String) {
        guard let view = window.contentView?.superview ?? window.contentView,
              let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        try? bitmap.representation(using: .png, properties: [:])?.write(to: URL(filePath: path))
        print("Snapshot drew", path, "itself, without Liquid Glass")
    }
}
#endif
