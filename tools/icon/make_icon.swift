#!/usr/bin/env swift

// Render the shared A Console icon master to a macOS iconset.

import AppKit
import Foundation

/// 图标规格：文件名 -> 像素尺寸。
let sizes: [(String, Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]

let circleInk = NSColor(srgbRed: 0.025, green: 0.032, blue: 0.035, alpha: 1)
let background = NSColor(srgbRed: 0.97, green: 0.97, blue: 0.97, alpha: 1)
let center = 256.0
let circleCenterY = 256.0
let radius = 176.0

private let arrowPoints: [(x: Double, y: Double)] = [
    (0.407, 0.481),   // 尖端（右上）
    (-0.593, 0.000),  // 左侧斜边末端
    (-0.074, -0.111), // 尾翼折角
    (0.000, -0.556),  // 尾针末端
]

/// 画一个 size×size 的图标。
func makeIcon(size: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                               isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let scale = CGFloat(size) / 512.0

    background.setFill()
    let tile = NSRect(x: 12 * scale, y: 12 * scale, width: 488 * scale, height: 488 * scale)
    NSBezierPath(roundedRect: tile, xRadius: 112 * scale, yRadius: 112 * scale).fill()

    circleInk.setFill()
    NSBezierPath(ovalIn: NSRect(x: (center - radius) * scale, y: (circleCenterY - radius) * scale,
                                width: radius * 2 * scale, height: radius * 2 * scale)).fill()

    // 导航箭头。顶点按半径比例给出，映射到画布坐标。
    let path = NSBezierPath()
    for (index, point) in arrowPoints.enumerated() {
        let x = (center + point.x * radius) * scale
        let y = (circleCenterY + point.y * radius) * scale
        if index == 0 {
            path.move(to: NSPoint(x: x, y: y))
        } else {
            path.line(to: NSPoint(x: x, y: y))
        }
    }
    path.close()
    background.setFill()
    path.fill()

    NSGraphicsContext.restoreGraphicsState()
    return rep
}

let arguments = CommandLine.arguments
guard arguments.count > 1 else {
    FileHandle.standardError.write(Data("用法：make_icon.swift <输出目录>\n".utf8))
    exit(1)
}
let outputDirectory = URL(fileURLWithPath: arguments[1])
try? FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

for (name, size) in sizes {
    let rep = makeIcon(size: size)
    guard let data = rep.representation(using: .png, properties: [:]) else {
        FileHandle.standardError.write(Data("生成 \(name) 失败\n".utf8))
        exit(1)
    }
    try data.write(to: outputDirectory.appendingPathComponent(name))
    print("  \(name)  \(size)×\(size)")
}
print("完成")
