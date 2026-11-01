// Draws Orchard's app icon, an apple tree on a 16-block grid under a day sky, and writes it as
// an .icns. Run by build-app.sh:  swift Scripts/make-icon.swift <out.icns>
// Given a .png, writes the 64-pixel picture alone: the Orchard Pack's icon,
// src/main/resources/assets/orchard/pack.png.
import AppKit

let output = URL(filePath: CommandLine.arguments[1])
let grid = 16

func color(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}

/// The same scene the window shows, reduced to its emblem. Rows count from the top.
func draw(_ context: CGContext, size: CGFloat) {
    let b = size / CGFloat(grid)
    func block(_ column: Int, _ row: Int, _ hex: UInt32) {
        context.setFillColor(color(hex))
        context.fill(CGRect(x: CGFloat(column) * b, y: size - CGFloat(row + 1) * b, width: b, height: b))
    }

    let sky = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                         colors: [color(0x5B9BEA), color(0xBFDDF8)] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(sky, start: CGPoint(x: 0, y: size), end: CGPoint(x: 0, y: size * 0.25), options: [])

    for column in 0..<grid {
        block(column, 12, 0x79C25A)
        for row in 13..<grid {
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

func png(size: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    let context = NSGraphicsContext(bitmapImageRep: rep)!.cgContext
    context.interpolationQuality = .none
    context.setShouldAntialias(false)
    draw(context, size: CGFloat(size))
    return rep.representation(using: .png, properties: [:])!
}

if output.pathExtension == "png" {
    try png(size: 64).write(to: output)
    exit(0)
}

let iconset = FileManager.default.temporaryDirectory.appending(path: "Orchard-\(UUID().uuidString).iconset")
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for points in [16, 32, 128, 256, 512] {
    try png(size: points).write(to: iconset.appending(path: "icon_\(points)x\(points).png"))
    try png(size: points * 2).write(to: iconset.appending(path: "icon_\(points)x\(points)@2x.png"))
}
let iconutil = Process()
iconutil.executableURL = URL(filePath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", output.path]
try iconutil.run()
iconutil.waitUntilExit()
try? FileManager.default.removeItem(at: iconset)
exit(iconutil.terminationStatus)
