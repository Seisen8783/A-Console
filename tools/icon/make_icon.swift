#!/usr/bin/env swift

// 生成 UnReal Console 的应用图标（白底黑色定位针）。
//
// 用 CoreGraphics 直接绘制 PNG，再交给 iconutil 合成 .icns。
// 不依赖 rsvg-convert / ImageMagick 等外部工具。

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

/// 白底。
let background = NSColor.white
/// 墨色 #16181D，与设计系统的强调色一致。
let foreground = NSColor(srgbRed: 0.086, green: 0.094, blue: 0.114, alpha: 1)

/// 画一个 size×size 的图标。
func makeIcon(size: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                               isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let scale = CGFloat(size) / 512.0
    func point(_ x: CGFloat, _ y: CGFloat) -> NSPoint {
        NSPoint(x: x * scale, y: (512 - y) * scale)
    }

    // 白底
    background.setFill()
    NSRect(x: 0, y: 0, width: size, height: size).fill()

    // 定位针：尖端朝上的水滴轮廓。
    let path = NSBezierPath()
    path.move(to: point(256, 96))
    path.curve(to: point(148, 288),
               controlPoint1: point(148, 214),
               controlPoint2: point(148, 288))
    path.curve(to: point(256, 400),
               controlPoint1: point(148, 351),
               controlPoint2: point(196, 400))
    path.curve(to: point(364, 288),
               controlPoint1: point(316, 400),
               controlPoint2: point(364, 351))
    path.curve(to: point(256, 96),
               controlPoint1: point(364, 288),
               controlPoint2: point(364, 214))
    path.close()
    foreground.setStroke()
    path.lineWidth = 26 * scale
    path.lineCapStyle = .round
    path.lineJoinStyle = .round
    path.stroke()

    // 中心圆点：实心墨色 + 白色内芯。
    let outer = NSBezierPath(ovalIn: NSRect(x: (256 - 52) * scale, y: (512 - 286 - 52) * scale,
                                           width: 104 * scale, height: 104 * scale))
    foreground.setFill()
    outer.fill()
    let inner = NSBezierPath(ovalIn: NSRect(x: (256 - 20) * scale, y: (512 - 286 - 20) * scale,
                                           width: 40 * scale, height: 40 * scale))
    background.setFill()
    inner.fill()

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
