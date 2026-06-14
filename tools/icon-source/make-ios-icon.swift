// Generate a square, opaque (no alpha), full-bleed iOS app icon from the
// macOS-style rounded icon art. iOS rounds corners itself and dislikes alpha,
// so we: (1) fill a vertical gradient sampled from the art's blue, (2) draw the
// art aspect-fill with slight overscan so its own rounded corners / transparent
// margin spill past the canvas edge, (3) write a PNG with no alpha channel.
//
// Usage: swift make-ios-icon.swift <source.png> <out.png>
import AppKit
import CoreGraphics

let args = CommandLine.arguments
guard args.count == 3 else { fputs("usage: make-ios-icon <src> <out>\n", stderr); exit(2) }
let srcURL = URL(fileURLWithPath: args[1])
let outURL = URL(fileURLWithPath: args[2])
let size = 1024

guard let data = try? Data(contentsOf: srcURL),
      let rep = NSBitmapImageRep(data: data),
      let cg = rep.cgImage else { fputs("load failed\n", stderr); exit(1) }

func sample(_ nx: CGFloat, _ ny: CGFloat) -> CGColor {
    let x = min(max(Int(CGFloat(rep.pixelsWide) * nx), 0), rep.pixelsWide - 1)
    let y = min(max(Int(CGFloat(rep.pixelsHigh) * ny), 0), rep.pixelsHigh - 1)
    let c = (rep.colorAt(x: x, y: y) ?? .systemBlue).usingColorSpace(.sRGB) ?? .systemBlue
    return c.cgColor
}
// Sample inside the blue field, away from the centered artwork.
let topColor = sample(0.5, 0.12)
let bottomColor = sample(0.5, 0.88)

let cs = CGColorSpaceCreateDeviceRGB()
guard let ctx = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { fputs("context failed\n", stderr); exit(1) }

// Background: vertical gradient (CG y=0 is bottom).
if let grad = CGGradient(colorsSpace: cs, colors: [bottomColor, topColor] as CFArray, locations: [0, 1]) {
    ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0),
                           end: CGPoint(x: 0, y: CGFloat(size)), options: [])
}

// Art, aspect-fill with overscan so rounded corners / margin spill off-canvas.
let overscan: CGFloat = 1.16
let w = CGFloat(cg.width), h = CGFloat(cg.height)
let scale = max(CGFloat(size) / w, CGFloat(size) / h) * overscan
let dw = w * scale, dh = h * scale
ctx.interpolationQuality = .high
ctx.draw(cg, in: CGRect(x: (CGFloat(size) - dw) / 2, y: (CGFloat(size) - dh) / 2, width: dw, height: dh))

guard let img = ctx.makeImage() else { fputs("render failed\n", stderr); exit(1) }
guard let pngData = NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:]) else {
    fputs("encode failed\n", stderr); exit(1)
}
try! pngData.write(to: outURL)
print("wrote \(size)x\(size) opaque icon to \(outURL.lastPathComponent)")
