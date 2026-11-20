#!/usr/bin/env swift

// 生成 UnReal Console 的应用图标。
//
// 图案：白底 + 蓝色实心圆 + 深色导航箭头（Apple Maps 风格，尖端朝右上）。
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

/// 圆与箭头的颜色取自原始图标：蓝 #4479FB，箭头 #333333。
let circleBlue = NSColor(srgbRed: 68.0 / 255.0, green: 121.0 / 255.0, blue: 251.0 / 255.0, alpha: 1)
let arrowInk = NSColor(srgbRed: 51.0 / 255.0, green: 51.0 / 255.0, blue: 51.0 / 255.0, alpha: 1)
let background = NSColor.white

/// 圆心与半径（以 512 的画布为单位）。
///
/// 比例取自原始图标：蓝圆直径约占画布宽度的 69%，四周留白，
/// 圆心基本居中（纵向略偏上一点点）。
let center = 256.0
let circleCenterY = 248.0
let radius = 176.0

/// 导航箭头的顶点，单位为「圆半径的比例」，原点在圆心，y 轴向上。
///
/// 数值由原始图标的像素轮廓反推得到：
/// 尖端 (51,23)、左侧末端 (24,36)、尾翼折角 (38,39)、尾针末端 (40,51)，
/// 圆心 (40,36)、半径 27。
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

    // 白底
    background.setFill()
    NSRect(x: 0, y: 0, width: size, height: size).fill()

    // 蓝色实心圆
    circleBlue.setFill()
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
    arrowInk.setFill()
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
