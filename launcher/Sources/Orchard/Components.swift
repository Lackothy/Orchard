import AppKit
import OrchardKit
import SwiftUI

// MARK: - Instance icons

/// An instance's picture, drawn block by block like the game. The tree is the app icon; the rest
/// are 8 blocks square, most on a coloured tile.
struct PixelIcon: View {

    let icon: InstanceIcon
    var size: CGFloat = 20

    var body: some View {
        Canvas { context, canvas in
            if icon == .tree {
                Self.drawTree(&context, canvas.width)
                return
            }
            let art = Self.art[icon] ?? Self.art[.grass]!
            let block = canvas.width / CGFloat(art.rows.count)
            if let tile = art.tile {
                context.fill(Path(CGRect(origin: .zero, size: canvas)), with: .color(Color(hex: tile)))
            }
            for (row, line) in art.rows.enumerated() {
                for (column, character) in line.enumerated() {
                    guard let hex = art.palette[character] else { continue }
                    let rect = CGRect(x: CGFloat(column) * block, y: CGFloat(row) * block, width: block, height: block)
                    context.fill(Path(rect.insetBy(dx: -0.25, dy: -0.25)), with: .color(Color(hex: hex)))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
        .accessibilityHidden(true)
    }

    /// The app icon, block for block as Scripts/make-icon.swift draws it (rows from the top).
    private static func drawTree(_ context: inout GraphicsContext, _ size: CGFloat) {
        let b = size / 16
        func block(_ column: Int, _ row: Int, _ hex: UInt32) {
            let rect = CGRect(x: CGFloat(column) * b, y: CGFloat(row) * b, width: b, height: b)
            context.fill(Path(rect.insetBy(dx: -0.25, dy: -0.25)), with: .color(Color(hex: hex)))
        }
        context.fill(Path(CGRect(x: 0, y: 0, width: size, height: size)), with: .linearGradient(
            Gradient(colors: [Color(hex: 0x5B9BEA), Color(hex: 0xBFDDF8)]),
            startPoint: .zero, endPoint: CGPoint(x: 0, y: size * 0.75)))
        for column in 0..<16 {
            block(column, 12, 0x79C25A)
            for row in 13..<16 {
                block(column, row, (column * 7 + row * 3) % 5 == 0 ? 0x56993F : 0x5DA544)
            }
        }
        for row in 9...12 {
            block(7, row, 0x6E4B2E)
            block(8, row, 0x5E3F26)
        }
        let crown: [(Int, ClosedRange<Int>)] = [(2, 5...10), (3, 4...11), (4, 3...12), (5, 3...12), (6, 3...12),
                                                 (7, 3...12), (8, 4...11)]
        let leaves: [UInt32] = [0x3F8A36, 0x4B9A3E, 0x367A30]
        for (row, columns) in crown {
            for column in columns {
                block(column, row, leaves[(column * 5 + row * 7) % 3])
            }
        }
        for (column, row) in [(5, 4), (10, 3), (11, 6), (4, 7), (9, 8)] {
            block(column, row, 0xD7362F)
        }
        block(7, 5, 0xF2B53A)
    }

    private struct Art {
        var tile: UInt32?
        var palette: [Character: UInt32]
        var rows: [String]
    }

    private static let art: [InstanceIcon: Art] = [
        .sun: Art(tile: 0x27407E, palette: ["y": 0xFFD34D, "Y": 0xFFC21F, "w": 0xFFE89A], rows: [
            "........",
            "...yy...",
            "..YYYY..",
            ".yYwYYy.",
            ".yYYYYy.",
            "..YYYY..",
            "...yy...",
            "........",
        ]),
        .pearl: Art(tile: 0x123A3E, palette: ["e": 0x2E9C8C, "E": 0x1F7F73, "w": 0x7FE3C9], rows: [
            "........",
            "...ee...",
            "..EEEE..",
            ".eEwwEe.",
            ".eEwwEe.",
            "..EEEE..",
            "...ee...",
            "........",
        ]),
        .grass: Art(tile: nil, palette: ["g": 0x79C25A, "G": 0x5DA544, "d": 0x8A5E3B, "D": 0x6E4B2E], rows: [
            "gGgggGgg",
            "ggGgggGg",
            "dGddgdGd",
            "ddDddddD",
            "dddDdddd",
            "dDddddDd",
            "ddddDddd",
            "DdddddDd",
        ]),
        .diamond: Art(tile: 0x1E2A44, palette: ["c": 0x4AEDD9, "C": 0x2BC7AC, "w": 0xD5FFF6], rows: [
            "........",
            "..cccc..",
            ".cwcCCc.",
            "cwcCCCCc",
            ".cCCCCc.",
            "..cCCc..",
            "...cc...",
            "........",
        ]),
        .heart: Art(tile: 0x3A1520, palette: ["r": 0xE0303A, "R": 0xB3202A, "w": 0xFF9AA0], rows: [
            "........",
            ".rr..rr.",
            "rwrrrrRr",
            "rrrrrrRr",
            ".rrrrRr.",
            "..rrRr..",
            "...rr...",
            "........",
        ]),
        .moon: Art(tile: 0x14183A, palette: ["m": 0xF2E6B0, "M": 0xD9CB8A, "*": 0xBFC8FF], rows: [
            "......*.",
            "..mmm...",
            ".mMm....",
            ".mM...*.",
            ".mM.....",
            ".mMm....",
            "..mmm...",
            "*.......",
        ]),
        .sword: Art(tile: 0x2B2B30, palette: ["i": 0xE8E8EE, "I": 0xA9A9B3, "h": 0x8A5E3B, "g": 0xF2B53A], rows: [
            "......ii",
            ".....iIi",
            "....iIi.",
            "...iIi..",
            ".g.Ii...",
            "..gi....",
            ".hgg....",
            "hh......",
        ]),
    ]
}

extension InstanceIcon {
    var label: String {
        switch self {
        case .tree: "Apple Tree"
        case .sun: "Sun"
        case .pearl: "Ender Pearl"
        case .grass: "Grass Block"
        case .diamond: "Diamond"
        case .heart: "Heart"
        case .moon: "Moon"
        case .sword: "Sword"
        }
    }
}

// MARK: - Project icons

/// A mod's or pack's icon: from inside its file, else from Modrinth, else a plain tile.
struct ProjectIcon: View {

    var url: URL?
    var data: Data?
    var size: CGFloat = 40
    var dimmed = false

    var body: some View {
        Group {
            if let data, let image = NSImage(data: data) {
                Image(nsImage: image).resizable().interpolation(image.size.width < 64 ? .none : .high)
            } else if let url {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().interpolation(.high)
                    } else {
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .aspectRatio(contentMode: .fill)
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
        .opacity(dimmed ? 0.5 : 1)
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            Image(systemName: "shippingbox")
                .font(.system(size: size * 0.42))
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Cards

/// The page's grouped surfaces: a soft fill and round corners, no outline, as macOS draws a
/// grouped form.
enum Card {
    static let radius: CGFloat = 18
}

extension View {
    func card(radius: CGFloat = Card.radius) -> some View {
        background(.fill.quaternary, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

// MARK: - Formatting

enum Formats {
    /// 257M, 42.3K: how a shelf or a project page counts.
    static func count(_ value: Int) -> String {
        value.formatted(.number.notation(.compactName).precision(.significantDigits(1...3)))
    }

    static func playTime(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        return minutes < 60 ? "\(max(1, minutes)) min" : "\(minutes / 60) h \(minutes % 60) min"
    }

    static func lastPlayed(_ date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return "today at \(date.formatted(date: .omitted, time: .shortened))"
        }
        if Calendar.current.isDateInYesterday(date) {
            return "yesterday"
        }
        return date.formatted(.relative(presentation: .named))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - Markdown

/// A Modrinth description: headings, paragraphs and lists, with inline links and emphasis.
/// Images and raw HTML are left out; the gallery shows the pictures.
struct MarkdownView: View {

    let blocks: [Block]

    init(_ markdown: String) {
        blocks = Self.parse(markdown)
    }

    enum Block: Hashable {
        case heading(String, level: Int)
        case paragraph(String)
        case bullet(String)
        case rule
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .heading(let text, let level):
                    inline(text)
                        .font(level <= 1 ? .title2.weight(.semibold) : .title3.weight(.semibold))
                        .padding(.top, 8)
                case .paragraph(let text):
                    inline(text)
                case .bullet(let text):
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").foregroundStyle(.secondary)
                        inline(text)
                    }
                case .rule:
                    Divider()
                }
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func inline(_ text: String) -> Text {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return Text((try? AttributedString(markdown: text, options: options)) ?? AttributedString(text))
    }

    static func parse(_ markdown: String) -> [Block] {
        var blocks: [Block] = []
        var paragraph: [String] = []
        func flush() {
            let text = paragraph.joined(separator: " ").trimmingCharacters(in: .whitespaces)
            if !text.isEmpty { blocks.append(.paragraph(text)) }
            paragraph.removeAll()
        }
        for raw in markdown.components(separatedBy: .newlines) {
            var line = raw.trimmingCharacters(in: .whitespaces)
            // A heading written as HTML keeps its level; other tags and images go.
            var htmlLevel: Int?
            if let match = line.firstMatch(of: #/<h([1-6])[^>]*>/#), let level = Int(match.1) {
                htmlLevel = level
            }
            line = line.replacing(#/!\[[^\]]*\]\([^)]*\)/#, with: "")
                .replacing(#/\[\s*\]\([^)]*\)/#, with: "")
                .replacing(#/<[^>]+>/#, with: "")
                .trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                flush()
                continue
            }
            if let htmlLevel {
                flush()
                blocks.append(.heading(line, level: htmlLevel))
            } else if let match = line.firstMatch(of: #/^(#{1,6})\s+(.+)$/#) {
                flush()
                blocks.append(.heading(String(match.2), level: match.1.count))
            } else if line.wholeMatch(of: #/^(-{3,}|\*{3,}|_{3,})$/#) != nil {
                flush()
                blocks.append(.rule)
            } else if let match = line.firstMatch(of: #/^[-*+]\s+(.+)$/#) {
                flush()
                blocks.append(.bullet(String(match.1)))
            } else {
                paragraph.append(line)
            }
        }
        flush()
        return blocks
    }
}
