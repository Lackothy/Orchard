import AppKit
import SwiftUI

/// The accent: the one chosen in System Settings, or under Multicolor, where macOS lets apps
/// bring their own, golden-apple gold, as in the Orchard mod.
enum Accent {

    static var isMulticolor: Bool {
        UserDefaults.standard.object(forKey: "AppleAccentColor") == nil
    }

    static var color: Color {
        isMulticolor ? gold : .accentColor
    }

    /// Labels on a filled accent: gold is light, so they go dark; every system accent takes white.
    static var onAccent: Color {
        isMulticolor ? Color.black.opacity(0.82) : .white
    }

    /// The mod's gold, deeper in Light so it holds up on light backgrounds.
    static let gold = Color(nsColor: NSColor(name: "OrchardGold") { appearance in
        let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        return dark
            ? NSColor(srgbRed: 0xF2 / 255.0, green: 0xB5 / 255.0, blue: 0x3A / 255.0, alpha: 1)
            : NSColor(srgbRed: 0xDF / 255.0, green: 0xA1 / 255.0, blue: 0x26 / 255.0, alpha: 1)
    })
}
