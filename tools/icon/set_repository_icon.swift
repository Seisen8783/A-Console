import AppKit

let path = CommandLine.arguments[1]
let image = NSImage(size: NSSize(width: 128, height: 128), flipped: false) { rect in
    NSGraphicsContext.saveGraphicsState()
    defer { NSGraphicsContext.restoreGraphicsState() }
    let transform = NSAffineTransform()
    transform.translateX(by: 11, yBy: 11)
    transform.scale(by: 106.0 / 128.0)
    transform.concat()
    NSColor(white: 0.97, alpha: 1).setFill()
    NSBezierPath(roundedRect: rect.insetBy(dx: 4, dy: 4), xRadius: 28, yRadius: 28).fill()
    NSColor.black.setFill()
    NSBezierPath(ovalIn: rect.insetBy(dx: 19, dy: 19)).fill()

    let head = NSBezierPath()
    head.move(to: NSPoint(x: 39, y: 65))
    head.line(to: NSPoint(x: 37, y: 88))
    head.line(to: NSPoint(x: 53, y: 81))
    head.curve(to: NSPoint(x: 75, y: 81), controlPoint1: NSPoint(x: 58, y: 83), controlPoint2: NSPoint(x: 70, y: 83))
    head.line(to: NSPoint(x: 91, y: 88))
    head.line(to: NSPoint(x: 89, y: 65))
    head.curve(to: NSPoint(x: 73, y: 49), controlPoint1: NSPoint(x: 89, y: 55), controlPoint2: NSPoint(x: 82, y: 50))
    head.line(to: NSPoint(x: 76, y: 31))
    head.line(to: NSPoint(x: 54, y: 31))
    head.line(to: NSPoint(x: 55, y: 42))
    head.curve(to: NSPoint(x: 35, y: 52), controlPoint1: NSPoint(x: 45, y: 40), controlPoint2: NSPoint(x: 44, y: 49))
    head.curve(to: NSPoint(x: 54, y: 49), controlPoint1: NSPoint(x: 44, y: 53), controlPoint2: NSPoint(x: 46, y: 45))
    head.curve(to: NSPoint(x: 39, y: 65), controlPoint1: NSPoint(x: 45, y: 50), controlPoint2: NSPoint(x: 39, y: 56))
    head.close()
    NSColor.white.setFill()
    head.fill()
    return true
}
guard NSWorkspace.shared.setIcon(image, forFile: path, options: []) else { exit(1) }
