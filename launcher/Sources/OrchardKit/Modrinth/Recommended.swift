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

    public static let performance = Shelf(title: "The performance set", kind: .mod, picks: [
        Pick(id: "gvQqBUqZ", reason: "Faster game logic"),
        Pick(id: "uXXizFIs", reason: "Uses less memory"),
        Pick(id: "NNAgCjsB", reason: "Skips drawing what you can’t see"),
        Pick(id: "5ZwdcRci", reason: "Faster HUD and text drawing"),
    ])

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
