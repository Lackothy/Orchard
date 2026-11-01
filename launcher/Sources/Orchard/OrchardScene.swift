import SwiftUI

/// The launcher's backdrop: a blocky orchard on rolling hills at the time of day it is on this
/// Mac, with clouds drifting over. Drawn rather than an image, so it is Orchard's own and fits
/// any window. Only the clouds move, and not under Reduce Motion.
struct OrchardScene: View {

    /// An hour to show instead of the clock's: the Background setting's, or for screenshots
    /// `ORCHARD_SCENE_HOUR`.
    var hour: Double?
    /// Whether the clouds drift. Settings' small previews hold still.
    var moving = true
    /// How far it has grown in, while the window opens with the orchard growing.
    var growth = SceneGrowth()

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.everyMinute) { minute in
            let painter = ScenePainter(hour: hour ?? Self.hour(of: minute.date), growth: growth)
            ZStack {
                Canvas { context, size in painter.paintSky(&context, size) }
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || !moving)) { frame in
                    Canvas { context, size in
                        painter.paintClouds(&context, size, drift: frame.date.timeIntervalSinceReferenceDate)
                    }
                }
                Canvas { context, size in painter.paintLand(&context, size) }
            }
        }
        .accessibilityHidden(true)
    }

    static func hour(of date: Date) -> Double {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return Double(parts.hour ?? 12) + Double(parts.minute ?? 0) / 60
    }
}

// MARK: - Painting

private struct ScenePainter {

    let hour: Double
    let growth: SceneGrowth
    let sky: (top: RGB, bottom: RGB)
    /// 1 in full day, 0 at night.
    let daylight: Double
    /// Peaks at sunrise and sunset.
    let golden: Double

    init(hour: Double, growth: SceneGrowth) {
        self.hour = hour
        self.growth = growth
        sky = Self.sky(at: hour)
        daylight = Self.ramp(hour, 5, 7) * (1 - Self.ramp(hour, 17.5, 19.75))
        golden = max(Self.bump(hour, centre: 6.4), Self.bump(hour, centre: 18.4))
    }

    private func block(_ size: CGSize) -> CGFloat {
        max(6, (size.height / 46).rounded())
    }

    private func horizon(_ size: CGSize) -> CGFloat {
        (size.height * 0.64).rounded()
    }

    /// A terrain colour under this hour's light: dimmer and bluer at night, warmer at the ends of the day.
    private func lit(_ color: RGB) -> RGB {
        let light = 0.26 + 0.74 * daylight
        return color.scaled(light)
            .mixed(with: RGB(0x2A3C7A).scaled(0.6), 0.22 * (1 - daylight))
            .mixed(with: RGB(0xF19A5B), 0.18 * golden)
    }

    // MARK: Sky

    func paintSky(_ context: inout GraphicsContext, _ size: CGSize) {
        let rect = CGRect(origin: .zero, size: size)
        context.fill(Path(rect), with: .linearGradient(
            Gradient(colors: [sky.top.color, sky.bottom.color]),
            startPoint: .zero, endPoint: CGPoint(x: 0, y: horizon(size))))
        let b = block(size)

        let night = 1 - daylight
        if night > 0.05 {
            var random = SeededRandom(seed: 7)
            for _ in 0..<90 {
                let x = random.unit() * size.width
                let y = random.unit() * horizon(size) * 0.8
                let s = (random.unit() < 0.2 ? 0.34 : 0.22) * b
                let a = night * (0.35 + 0.65 * random.unit())
                context.fill(Path(CGRect(x: x.rounded(), y: y.rounded(), width: s, height: s)),
                             with: .color(.white.opacity(a)))
            }
        }

        // The sun by day, the moon by night, each rising on the left and setting on the right.
        let isSun = hour >= 5 && hour < 19.5
        let progress = isSun ? (hour - 5) / 14.5 : ((hour + 24 - 19.5).truncatingRemainder(dividingBy: 24)) / 9.5
        let x = size.width * (0.12 + 0.76 * progress)
        let y = horizon(size) - sin(progress * .pi) * size.height * 0.5 + b * 1.5
        let side = b * (isSun ? 3.5 : 3)
        let disc = CGRect(x: x - side / 2, y: y - side / 2, width: side, height: side)
        let glow = side * (isSun ? 4 : 3)
        context.fill(Path(ellipseIn: disc.insetBy(dx: -glow, dy: -glow)), with: .radialGradient(
            Gradient(colors: [(isSun ? RGB(0xFFE9B8) : RGB(0xC9D6FF)).color.opacity(isSun ? 0.45 : 0.18), .clear]),
            center: CGPoint(x: x, y: y), startRadius: side * 0.3, endRadius: side / 2 + glow))
        context.fill(Path(roundedRect: disc, cornerRadius: b * 0.3),
                     with: .color((isSun ? RGB(0xFFF4D8) : RGB(0xE6EAF4)).color))
        if !isSun {
            let crater = RGB(0xC2C9DA).color
            context.fill(Path(CGRect(x: disc.minX + b * 0.6, y: disc.minY + b * 0.7, width: b * 0.7, height: b * 0.7)),
                         with: .color(crater))
            context.fill(Path(CGRect(x: disc.minX + b * 1.8, y: disc.minY + b * 1.7, width: b * 0.5, height: b * 0.5)),
                         with: .color(crater))
        }
    }

    // MARK: Clouds

    func paintClouds(_ context: inout GraphicsContext, _ size: CGSize, drift: TimeInterval) {
        let b = block(size)
        var random = SeededRandom(seed: 21)
        let white = RGB(0xFFFFFF).mixed(with: RGB(0xFFB89A), 0.35 * golden).scaled(0.35 + 0.65 * daylight)
        // Growing in, the clouds drift in from the left.
        let arriving = (1 - Ease.out(growth.clouds)) * size.width * 0.6
        context.opacity = growth.clouds
        for _ in 0..<6 {
            let width = (5 + random.unit() * 9).rounded() * b
            let y = (random.unit() * horizon(size) * 0.55 + b * 2).rounded()
            let speed = (0.25 + random.unit() * 0.35) * b / 6
            let span = size.width + width * 2
            let x = (random.unit() * span + drift * speed).truncatingRemainder(dividingBy: span) - width - arriving
            let alpha = 0.55 + 0.35 * daylight
            context.fill(Path(CGRect(x: x.rounded(), y: y, width: width, height: b)),
                         with: .color(white.color.opacity(alpha)))
            let topWidth = (width * (0.4 + random.unit() * 0.3) / b).rounded() * b
            let topX = x + ((width - topWidth) * random.unit() / b).rounded() * b
            context.fill(Path(CGRect(x: topX.rounded(), y: y - b, width: topWidth, height: b)),
                         with: .color(white.color.opacity(alpha)))
        }
    }

    // MARK: Land

    func paintLand(_ context: inout GraphicsContext, _ size: CGSize) {
        let b = block(size)
        let top = horizon(size)
        // Growing in, the hills rise from behind the field.
        let risen = ((1 - Ease.out(growth.hills)) * size.height * 0.4 / b).rounded() * b

        hills(&context, size, base: top + b + risen, amplitude: 3, seed: 3,
              color: lit(RGB(0x6F9F6A)).mixed(with: sky.bottom, 0.45))
        let backRow = hills(&context, size, base: top + b * 3 + risen, amplitude: 2, seed: 5, color: lit(RGB(0x4F8E46)))

        // The orchard's back row stands on the near hills, smaller and hazier.
        var random = SeededRandom(seed: 11)
        let small = (b * 0.62).rounded()
        var x = -small * 2
        while x < size.width {
            let ground = backRow(x + small * 2)
            tree(&context, x: x, ground: ground, block: small, random: &random, haze: 0.28)
            x += small * (6 + (random.unit() * 4).rounded())
        }

        // The field, with a path down the middle and the front row either side of it.
        let field = top + b * 5
        context.fill(Path(CGRect(x: 0, y: field, width: size.width, height: size.height - field)),
                     with: .color(lit(RGB(0x5DA544)).color))
        context.fill(Path(CGRect(x: 0, y: field, width: size.width, height: b)),
                     with: .color(lit(RGB(0x79C25A)).color))
        var shade = SeededRandom(seed: 17)
        let columns = Int(size.width / b) + 1
        let rows = Int((size.height - field) / b) + 1
        for row in 1..<rows {
            for column in 0..<columns where shade.unit() < 0.22 {
                let tone = shade.unit() < 0.5 ? RGB(0x56993F) : RGB(0x66AE4B)
                context.fill(Path(CGRect(x: CGFloat(column) * b, y: field + CGFloat(row) * b, width: b, height: b)),
                             with: .color(lit(tone).color))
            }
        }
        let centre = (size.width / 2 / b).rounded() * b
        for row in 0..<rows {
            let half = (CGFloat(row) * 0.9 + 1).rounded() * b
            let y = field + CGFloat(row) * b
            context.fill(Path(CGRect(x: centre - half, y: y, width: half * 2, height: b)),
                         with: .color(lit(row == 0 ? RGB(0xB79466) : RGB(0xA4825A)).color))
        }

        var front = SeededRandom(seed: 29)
        var left = centre - b * 5
        while left > -b * 6 {
            tree(&context, x: left - b * 5, ground: field, block: b, random: &front, haze: 0)
            left -= b * (9 + (front.unit() * 3).rounded())
        }
        var right = centre + b * 5
        while right < size.width + b * 6 {
            tree(&context, x: right, ground: field, block: b, random: &front, haze: 0)
            right += b * (9 + (front.unit() * 3).rounded())
        }
    }

    /// A stepped ridge; returns its height at any x, for standing things on it.
    @discardableResult
    private func hills(_ context: inout GraphicsContext, _ size: CGSize, base: CGFloat, amplitude: Double,
                       seed: UInt64, color: RGB) -> (CGFloat) -> CGFloat {
        let b = block(size)
        var random = SeededRandom(seed: seed)
        let phases = (random.unit() * 6.3, random.unit() * 6.3, random.unit() * 6.3)
        let height: (CGFloat) -> CGFloat = { x in
            let i = Double(x / (b * 2)).rounded(.down)
            let wave = sin(i * 0.21 + phases.0) * 0.6 + sin(i * 0.47 + phases.1) * 0.3 + sin(i * 1.1 + phases.2) * 0.1
            return base - (CGFloat(wave * amplitude).rounded()) * b
        }
        var path = Path()
        var x: CGFloat = 0
        while x < size.width {
            let y = height(x)
            path.addRect(CGRect(x: x, y: y, width: b * 2, height: size.height - y))
            x += b * 2
        }
        context.fill(path, with: .color(color.color))
        return height
    }

    /// A fruit tree five blocks wide whose trunk stands on `ground`, with a few apples, one
    /// sometimes golden.
    private func tree(_ context: inout GraphicsContext, x: CGFloat, ground: CGFloat, block b: CGFloat,
                      random: inout SeededRandom, haze: Double) {
        func paint(_ color: RGB) -> Color {
            lit(color).mixed(with: sky.bottom, haze).color
        }
        // Growing in, each tree scales up from the foot of its trunk, left to right. The random
        // numbers are taken the same either way, so every tree keeps its shape.
        let foot = x + b * 2.5
        let k = grown(at: foot, width: context.clipBoundingRect.width)
        func fill(_ rect: CGRect, _ color: Color) {
            guard k > 0 else { return }
            let scaled = k >= 1 ? rect : CGRect(x: foot + (rect.minX - foot) * k, y: ground + (rect.minY - ground) * k,
                                                width: rect.width * k, height: rect.height * k)
            context.fill(Path(scaled), with: .color(color))
        }
        let trunkHeight = (2 + (random.unit() * 1.6).rounded(.down)) * b
        fill(CGRect(x: x + b * 2, y: ground - trunkHeight, width: b, height: trunkHeight), paint(RGB(0x6E4B2E)))
        let leaves = [RGB(0x3F8A36), RGB(0x4B9A3E), RGB(0x367A30)]
        let crownTop = ground - trunkHeight - b * 3
        for row in 0..<3 {
            for column in 0..<5 {
                // Rounded off: the top corners and one bottom corner are left out.
                if (row == 0 && (column == 0 || column == 4)) || (row == 2 && column == 4 && random.unit() < 0.5) {
                    continue
                }
                let cell = CGRect(x: x + CGFloat(column) * b, y: crownTop + CGFloat(row) * b, width: b, height: b)
                fill(cell, paint(leaves[Int(random.unit() * 3) % 3]))
                if row > 0 && random.unit() < 0.16 {
                    let apple = random.unit() < 0.08 ? RGB(0xF2B53A) : RGB(0xD7362F)
                    let s = (b * 0.45).rounded()
                    fill(CGRect(x: cell.midX - s / 2, y: cell.midY - s / 2, width: s, height: s), paint(apple))
                }
            }
        }
    }

    /// How grown the tree standing at `x` is, overshooting a little as it pops up.
    private func grown(at x: CGFloat, width: CGFloat) -> CGFloat {
        guard growth.trees < 1 else { return 1 }
        let k = min(1, max(0, growth.trees * 1.6 - Double(x / max(width, 1)) * 0.6))
        return k <= 0 ? 0 : CGFloat(Ease.back(k))
    }

    // MARK: Time of day

    private static let keyframes: [(Double, UInt32, UInt32)] = [
        (0, 0x070B1F, 0x16224A),
        (5, 0x0D1530, 0x2A2F5C),
        (6, 0x34407A, 0xF3A27A),
        (7.25, 0x5F8FD6, 0xF2D3B0),
        (9, 0x5B9BEA, 0xBFDDF8),
        (16.5, 0x5B9BEA, 0xBFDDF8),
        (18, 0x4A64B0, 0xF4B183),
        (19, 0x2C3570, 0xE77F6B),
        (20.25, 0x121A3D, 0x3B3566),
        (24, 0x070B1F, 0x16224A),
    ]

    private static func sky(at hour: Double) -> (top: RGB, bottom: RGB) {
        let h = hour.truncatingRemainder(dividingBy: 24)
        for (a, b) in zip(keyframes, keyframes.dropFirst()) where h >= a.0 && h <= b.0 {
            let t = (h - a.0) / (b.0 - a.0)
            return (RGB(a.1).mixed(with: RGB(b.1), t), RGB(a.2).mixed(with: RGB(b.2), t))
        }
        return (RGB(keyframes[0].1), RGB(keyframes[0].2))
    }

    private static func ramp(_ x: Double, _ from: Double, _ to: Double) -> Double {
        min(1, max(0, (x - from) / (to - from)))
    }

    private static func bump(_ x: Double, centre: Double) -> Double {
        exp(-pow((x - centre) / 0.9, 2))
    }
}

private struct RGB {
    var r, g, b: Double

    init(_ hex: UInt32) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
    }

    private init(r: Double, g: Double, b: Double) {
        self.r = r
        self.g = g
        self.b = b
    }

    func mixed(with other: RGB, _ t: Double) -> RGB {
        RGB(r: r + (other.r - r) * t, g: g + (other.g - g) * t, b: b + (other.b - b) * t)
    }

    func scaled(_ k: Double) -> RGB {
        RGB(r: r * k, g: g * k, b: b * k)
    }

    var color: Color { Color(red: r, green: g, blue: b) }
}

/// SplitMix64: the same scene every launch, without storing it.
private struct SeededRandom {
    var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func unit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return Double((z ^ (z >> 31)) >> 11) / Double(1 << 53)
    }
}
