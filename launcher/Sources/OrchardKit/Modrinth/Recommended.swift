/// Discover's Recommended page: projects picked because they run well with Orchard on its
/// Minecraft version and do something Orchard itself doesn't. Modrinth supplies everything else
/// shown about them (author, counts, icon), so only the choice and a short reason live here.
///
/// IDs, not slugs: an ID never changes.
public enum Recommended {

    public struct Pick: Hashable, Sendable {
        public var id: String
        public var reason: String
    }

    public struct Shelf: Identifiable, Hashable, Sendable {
        public var id: String { title }
        public var title: String
        public var kind: ContentKind
        public var picks: [Pick]
    }

    /// Shown as one card with a button that gets the lot: they're made to be used together.
    public static let performance = Shelf(title: "The performance set", kind: .mod, picks: [
        Pick(id: "gvQqBUqZ", reason: "Faster game logic"),
        Pick(id: "uXXizFIs", reason: "Uses less memory"),
        Pick(id: "NNAgCjsB", reason: "Skips drawing what you can’t see"),
        Pick(id: "5ZwdcRci", reason: "Faster HUD and text drawing"),
    ])

    /// Put in each instance once as well, like the performance set: fixes for how Minecraft takes
    /// input on a Mac. MacOS Input Fixes (BSD-3-Clause, hamarb123) makes trackpad scrolling move
    /// one hotbar slot at a time rather than flying past them, scrolls with Shift held, brings back
    /// momentum in menus, and makes Ctrl+Tab, Ctrl+Esc and Ctrl+Q work under 26.3's SDL3.
    public static let macFixes = Shelf(title: "Made for the Mac", kind: .mod, picks: [
        Pick(id: "x9BrsVME", reason: "Trackpad scrolling and Ctrl keys that work"),
    ])

    public static let shelves: [Shelf] = [
        macFixes,
        Shelf(title: "Looks better", kind: .mod, picks: [
            Pick(id: "YL57xq9U", reason: "Runs shader packs, without Metal"),
            Pick(id: "1IjD5062", reason: "Connected glass and bookshelves"),
            Pick(id: "zV5r3pPn", reason: "Skins with depth"),
        ]),
        Shelf(title: "Handy", kind: .mod, picks: [
            Pick(id: "EsAfCjCV", reason: "Food and saturation on the HUD"),
            Pick(id: "1bokaNcj", reason: "A minimap with waypoints"),
        ]),
        Shelf(title: "Shaders", kind: .shader, picks: [
            Pick(id: "HVnmMxH1", reason: "Rich, still faithful to the game"),
            Pick(id: "Q1vvjJYV", reason: "Bright and colourful"),
            Pick(id: "lLqFfGNs", reason: "Soft, semi-realistic light"),
        ]),
        Shelf(title: "Resource packs", kind: .resourcePack, picks: [
            Pick(id: "50dA9Sha", reason: "Mobs that move like the trailers"),
            Pick(id: "uvpymuxq", reason: "Bushier leaves"),
            Pick(id: "dspVZXKP", reason: "Grass on the sides of blocks"),
        ]),
    ]

    public static var all: [Shelf] { [performance] + shelves }
}
