// Generate a square, opaque (no alpha), full-bleed iOS app icon from the
// macOS-style rounded icon art. iOS rounds corners itself and dislikes alpha.
//
// Approach (no cropping of the artwork):
//   1. Find the alpha bounding box of the art (the rounded-rect squircle).
//   2. Inset it slightly to drop the lighter-blue rim/border.
//   3. Sample the four interior corners (pure background) → a vertical gradient.
//   4. Fill a 1024² opaque canvas with that gradient (content-aware-ish fill of
//      the removed border + the padding that squares up a taller-than-wide art).
//   5. Draw the de-bordered art "contained" (uniform scale, centered, no crop);
//      its own background blends into the matching gradient on the padded sides.
//   6. Write a PNG with no alpha channel.
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

let W = rep.pixelsWide, H = rep.pixelsHigh   // colorAt uses a top-left origin

func colorAt(_ x: Int, _ y: Int) -> NSColor {
    let cx = min(max(x, 0), W - 1), cy = min(max(y, 0), H - 1)
    return (rep.colorAt(x: cx, y: cy) ?? .systemBlue).usingColorSpace(.sRGB) ?? .systemBlue
}

// 1. Alpha bounding box of the artwork (strided scan is plenty for an edge find).
var minX = W, minY = H, maxX = 0, maxY = 0
let cutoff: CGFloat = 0.5, step = 2
for y in stride(from: 0, to: H, by: step) {
    for x in stride(from: 0, to: W, by: step) where colorAt(x, y).alphaComponent > cutoff {
        if x < minX { minX = x }
        if x > maxX { maxX = x }
        if y < minY { minY = y }
        if y > maxY { maxY = y }
    }
}
if maxX <= minX || maxY <= minY { minX = 0; minY = 0; maxX = W - 1; maxY = H - 1 }

// 2. Inset to drop the rim/border.
let bw = maxX - minX, bh = maxY - minY
let inset = Int(CGFloat(min(bw, bh)) * 0.04)
let ix = minX + inset, iy = minY + inset
let iw = max(1, bw - 2 * inset), ih = max(1, bh - 2 * inset)
guard let art = cg.cropping(to: CGRect(x: ix, y: iy, width: iw, height: ih)) else {
    fputs("crop failed\n", stderr); exit(1)
}

// 3. Background gradient from the four interior corners (away from the art).
let pad = max(2, min(iw, ih) / 12)
func mix(_ a: NSColor, _ b: NSColor) -> NSColor {
    NSColor(srgbRed: (a.redComponent + b.redComponent) / 2,
            green: (a.greenComponent + b.greenComponent) / 2,
            blue: (a.blueComponent + b.blueComponent) / 2, alpha: 1)
}
let topColor = mix(colorAt(ix + pad, iy + pad), colorAt(ix + iw - pad, iy + pad)).cgColor
let bottomColor = mix(colorAt(ix + pad, iy + ih - pad), colorAt(ix + iw - pad, iy + ih - pad)).cgColor

// 4. Opaque canvas + vertical gradient (CG y=0 is bottom, so bottomColor first).
let cs = CGColorSpaceCreateDeviceRGB()
guard let ctx = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { fputs("context failed\n", stderr); exit(1) }
if let grad = CGGradient(colorsSpace: cs, colors: [bottomColor, topColor] as CFArray, locations: [0, 1]) {
    ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0),
                           end: CGPoint(x: 0, y: CGFloat(size)), options: [])
}

// 5. Draw the de-bordered art "contained": uniform scale, centered, never cropped.
let scale = CGFloat(size) / CGFloat(max(iw, ih))
let dw = CGFloat(iw) * scale, dh = CGFloat(ih) * scale
ctx.interpolationQuality = .high
ctx.draw(art, in: CGRect(x: (CGFloat(size) - dw) / 2, y: (CGFloat(size) - dh) / 2, width: dw, height: dh))

// 6. Encode with no alpha.
guard let img = ctx.makeImage(),
      let pngData = NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])
else { fputs("render failed\n", stderr); exit(1) }
try! pngData.write(to: outURL)
print("wrote \(size)x\(size) opaque icon (art bbox \(bw)x\(bh), inset \(inset)) to \(outURL.lastPathComponent)")
