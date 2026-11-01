import AppKit
import SwiftUI

enum Opening: String, CaseIterable, Identifiable {
    case iconFirst, quiet, orchardGrows

    var id: Self { self }

    var label: String {
        switch self {
        case .iconFirst: "Icon first"
        case .quiet: "Quiet"
        case .orchardGrows: "The orchard grows"
        }
    }

    var description: String {
        switch self {
        case .iconFirst: "The icon appears, then shrinks into the sidebar as the window fills in."
        case .quiet: "The window fades in and grows a little; its contents follow."
        case .orchardGrows: "The hills rise, the trees pop up and the clouds drift in."
        }
    }
}

@MainActor @Observable
final class OpeningRun {

    static let seconds = 1.6

    private(set) var style: Opening?
    private(set) var start: Date?
    private(set) var number = 0
    private var held: Double?
    var target = CGRect(x: 20, y: 78, width: 18, height: 18)

    init() {
        let environment = ProcessInfo.processInfo.environment
        if !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion && environment["ORCHARD_SNAPSHOT"] == nil {
            style = Preferences.opening
            number = 1
        }
        #if DEBUG
        if let parts = environment["ORCHARD_OPENING"]?.split(separator: ":"), parts.count == 2,
           let chosen = Opening(rawValue: String(parts[0])), let moment = Double(parts[1]) {
            style = chosen
            number = 1
            held = moment
        }
        #endif
    }

    var playing: Bool { style != nil }

    func begin() {
        guard style != nil, start == nil else { return }
        start = .now
        finish(number)
    }

    func play(_ style: Opening) {
        self.style = style
        number += 1
        start = .now
        finish(number)
    }

    private func finish(_ number: Int) {
        guard held == nil else { return }
        Task {
            try? await Task.sleep(for: .seconds(Self.seconds + 0.1))
            if self.number == number { style = nil }
        }
    }

    func look(at date: Date) -> OpeningLook {
        guard let style else { return OpeningLook() }
        if let held { return OpeningLook(style: style, k: held) }
        let k = start.map { min(1, max(0, date.timeIntervalSince($0) / Self.seconds)) } ?? 0
        return OpeningLook(style: style, k: k)
    }
}

struct OpeningLook {

    var windowAlpha = 1.0
    var contentScale = 1.0
    var sceneAlpha = 1.0
    var growth = SceneGrowth()
    var controls = 1.0
    var icon: (grow: Double, go: Double, alpha: Double)?

    init() {}

    init(style: Opening, k: Double) {
        switch style {
        case .iconFirst:
            windowAlpha = Ease.out(Ease.part(k, 0.05, 0.4))
            sceneAlpha = Ease.part(k, 0.5, 0.85)
            controls = Ease.part(k, 0.55, 1)
            icon = (Ease.back(Ease.part(k, 0, 0.32)), Ease.inOut(Ease.part(k, 0.42, 0.72)), 1 - Ease.part(k, 0.66, 0.74))
        case .quiet:
            windowAlpha = Ease.out(Ease.part(k, 0, 0.35))
            contentScale = 0.95 + 0.05 * Ease.out(Ease.part(k, 0, 0.45))
            controls = Ease.part(k, 0.2, 0.85)
        case .orchardGrows:
            sceneAlpha = Ease.out(Ease.part(k, 0, 0.25))
            growth = SceneGrowth(hills: Ease.part(k, 0.1, 0.45), trees: Ease.part(k, 0.3, 0.75), clouds: Ease.part(k, 0.45, 0.8))
            controls = Ease.part(k, 0.62, 1)
        }
    }

    func controls(_ step: Int) -> Double {
        min(1, max(0, controls * 1.7 - Double(step) * 0.08))
    }
}

struct SceneGrowth: Equatable {
    var hills = 1.0
    var trees = 1.0
    var clouds = 1.0

    var whole: Bool { hills >= 1 && trees >= 1 && clouds >= 1 }
}

enum Ease {
    static func part(_ k: Double, _ a: Double, _ b: Double) -> Double { min(1, max(0, (k - a) / (b - a))) }
    static func out(_ t: Double) -> Double { 1 - pow(1 - t, 3) }
    static func inOut(_ t: Double) -> Double { t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2 }
    static func back(_ t: Double) -> Double {
        let c1 = 1.70158
        return 1 + (c1 + 1) * pow(t - 1, 3) + c1 * pow(t - 1, 2)
    }
}

struct OpeningWindow: ViewModifier {

    @Environment(Launcher.self) private var launcher

    func body(content: Content) -> some View {
        let run = launcher.opening
        content
            .background {
                TimelineView(.animation(paused: !run.playing)) { context in
                    let look = run.look(at: context.date)
                    WindowHook(alpha: look.windowAlpha, scale: look.contentScale, number: run.number,
                               flies: run.style == .iconFirst, run: run)
                }
            }
            .onAppear { run.begin() }
    }
}

private struct WindowHook: NSViewRepresentable {

    let alpha: Double
    let scale: Double
    let number: Int
    let flies: Bool
    let run: OpeningRun

    func makeNSView(context: Context) -> HookView { HookView() }

    func updateNSView(_ view: HookView, context: Context) {
        view.run = run
        view.update(alpha: alpha, scale: scale, number: number, flies: flies)
    }

    final class HookView: NSView {
        var run: OpeningRun?
        private var alpha: CGFloat = 1
        private var flown = 0
        private var panel: NSPanel?

        func update(alpha: Double, scale: Double, number: Int, flies: Bool) {
            self.alpha = alpha
            window?.alphaValue = alpha
            grow(scale)
            if flies && flown != number, let window, let run {
                flown = number
                fly(over: window, run: run)
            }
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.alphaValue = alpha
        }

        private func grow(_ scale: Double) {
            guard let frame = window?.contentView?.superview, let layer = frame.layer else { return }
            guard scale < 1 else {
                if !CATransform3DIsIdentity(layer.sublayerTransform) { layer.sublayerTransform = CATransform3DIdentity }
                return
            }
            let middle = CGPoint(x: frame.bounds.midX, y: frame.bounds.midY)
            var transform = CATransform3DMakeTranslation(middle.x, middle.y, 0)
            transform = CATransform3DScale(transform, scale, scale, 1)
            transform = CATransform3DTranslate(transform, -middle.x, -middle.y, 0)
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.sublayerTransform = transform
            CATransaction.commit()
        }

        private func fly(over window: NSWindow, run: OpeningRun) {
            panel?.close()
            let panel = NSPanel(contentRect: window.frame, styleMask: [.borderless, .nonactivatingPanel],
                                backing: .buffered, defer: false)
            panel.isOpaque = false
            panel.backgroundColor = .clear
            panel.hasShadow = false
            panel.ignoresMouseEvents = true
            panel.level = .floating
            panel.isReleasedWhenClosed = false
            panel.contentView = NSHostingView(rootView: IconFlight(run: run))
            panel.orderFront(nil)
            self.panel = panel
            Task { @MainActor [weak self, weak panel] in
                try? await Task.sleep(for: .seconds(OpeningRun.seconds + 0.2))
                panel?.close()
                if self?.panel === panel { self?.panel = nil }
            }
        }
    }
}

private struct IconFlight: View {

    let run: OpeningRun

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation) { context in
                if let icon = run.look(at: context.date).icon {
                    let size = proxy.size
                    let target = CGPoint(x: run.target.midX, y: run.target.midY)
                    let side = (128 * icon.grow) + (run.target.width - 128 * icon.grow) * icon.go
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .frame(width: max(1, side), height: max(1, side))
                        .shadow(color: .black.opacity(0.3), radius: 12 * (1 - icon.go), y: 6 * (1 - icon.go))
                        .opacity(icon.alpha)
                        .position(x: size.width / 2 + (target.x - size.width / 2) * icon.go,
                                  y: size.height / 2 + (target.y - size.height / 2) * icon.go)
                }
            }
        }
        .ignoresSafeArea()
    }
}

struct OpeningFade: ViewModifier {

    let step: Int
    @Environment(Launcher.self) private var launcher

    func body(content: Content) -> some View {
        let run = launcher.opening
        TimelineView(.animation(paused: !run.playing)) { context in
            let shown = run.look(at: context.date).controls(step)
            content
                .opacity(shown)
                .offset(y: (1 - shown) * 6)
        }
    }
}
