import SwiftUI

enum MuralColor {
    static let cream = Color(red: 1, green: 0.975, blue: 0.933)
    static let ink = Color(red: 0.212, green: 0.165, blue: 0.133)
    static let secondary = Color(red: 0.45, green: 0.355, blue: 0.29)
    static let orange = Color(red: 1, green: 0.54, blue: 0.30)
    static let peach = Color(red: 1, green: 0.89, blue: 0.81)
    static let lilac = Color(red: 0.932, green: 0.902, blue: 0.98)
    static let sage = Color(red: 0.917, green: 0.937, blue: 0.84)
    static let butter = Color(red: 1, green: 0.944, blue: 0.78)
    static let panels: [Color] = [peach, lilac, sage, butter]
}

struct Brand: View {
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(RadialGradient(colors: [MuralColor.butter, MuralColor.orange], center: .topLeading, startRadius: 0, endRadius: 18)).frame(width: 17, height: 17)
            Text("mural").font(.system(size: 30, weight: .bold, design: .rounded)).tracking(-1.6)
        }.foregroundStyle(MuralColor.ink).accessibilityLabel("Mural")
    }
}

struct SoftGlass: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    var tint: Color = .white.opacity(0.45)
    func body(content: Content) -> some View {
        if reduceTransparency { content.background(.white, in: Capsule()) }
        else { content.glassEffect(.regular.tint(tint).interactive(), in: .capsule) }
    }
}

struct OrbShape: Shape {
    var phase: Double
    var energy: Double

    /// Radius factor for one of the twelve control points, computed in Double only.
    private func wave(at angle: Double) -> Double {
        let ripple: Double = sin(angle * 3 + phase) * 0.021
        let amplitude: Double = 0.012 + energy * 0.025
        let swell: Double = cos(angle * 2 - phase * 0.7) * amplitude
        return 0.47 + ripple + swell
    }

    private func point(_ index: Int, in rect: CGRect) -> CGPoint {
        let angle: Double = Double(index) / 12 * Double.pi * 2
        let size: Double = Double(min(rect.width, rect.height))
        let radius: Double = size * wave(at: angle)
        let x: Double = Double(rect.midX) + cos(angle) * radius
        let y: Double = Double(rect.midY) + sin(angle) * radius
        return CGPoint(x: x, y: y)
    }

    private static func midpoint(_ a: CGPoint, _ b: CGPoint) -> CGPoint {
        CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
    }

    func path(in rect: CGRect) -> Path {
        var points: [CGPoint] = []
        for index in 0..<12 { points.append(point(index, in: rect)) }
        var p = Path()
        p.move(to: Self.midpoint(points[11], points[0]))
        for i in 0..<12 {
            let current: CGPoint = points[i]
            let next: CGPoint = points[(i + 1) % 12]
            p.addQuadCurve(to: Self.midpoint(current, next), control: current)
        }
        p.closeSubpath()
        return p
    }
}

struct MuralOrb: View {
    var energy: Double = 0
    var listening = false
    var active = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        let paused: Bool = reduceMotion || !active || scenePhase != .active
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
            let time: Double = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate
            let level: Double = reduceMotion ? 0 : min(1, max(0, energy))
            GeometryReader { geometry in
                let side: CGFloat = min(geometry.size.width, geometry.size.height)
                OrbLayers(time: time, energy: level, side: side, listening: listening, reduceMotion: reduceMotion)
                    .frame(width: side, height: side)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }.accessibilityHidden(true)
    }
}

/// One animation frame of the orb. Kept separate from MuralOrb so each piece type-checks quickly.
private struct OrbLayers: View {
    var time: Double
    var energy: Double
    var side: CGFloat
    var listening: Bool
    var reduceMotion: Bool

    private var phase: Double { time * 0.72 }
    private var tilt: Double { sin(phase * 0.5) * 3 }
    private var scale: CGFloat { CGFloat(1 + energy * 0.045) }
    private var bob: CGFloat { reduceMotion ? 0 : CGFloat(sin(time * 0.9) * 4 - 5) }
    private var ringOpacity: (inner: Double, outer: Double) { listening ? (0.18, 0.10) : (0, 0) }

    var body: some View {
        ZStack {
            shadow
            Circle().stroke(MuralColor.orange.opacity(ringOpacity.inner), lineWidth: 1).padding(-6)
            Circle().stroke(MuralColor.orange.opacity(ringOpacity.outer), lineWidth: 1).padding(-16)
            orb
            sparkles
        }
    }

    private var shadow: some View {
        Ellipse().fill(MuralColor.orange.opacity(0.14))
            .frame(width: side * 0.57, height: side * 0.075)
            .blur(radius: 10)
            .offset(y: side * 0.47)
    }

    private var orb: some View {
        OrbFill(phase: phase, side: side)
            .mask(OrbShape(phase: phase, energy: energy))
            .shadow(color: MuralColor.orange.opacity(0.12), radius: 16, y: 10)
            .rotationEffect(.degrees(tilt))
            .scaleEffect(scale)
            .offset(y: bob)
    }

    private var sparkles: some View {
        let gradient = RadialGradient(colors: [.white, MuralColor.peach, MuralColor.orange.opacity(0.5)],
                                      center: .topLeading, startRadius: 0, endRadius: 12)
        return ZStack {
            Circle().fill(gradient).frame(width: 12, height: 12).offset(x: side * 0.55, y: -side * 0.24)
            Circle().fill(MuralColor.peach).frame(width: 7, height: 7).offset(x: -side * 0.54, y: side * 0.26)
        }
    }
}

/// The orb's gradient and highlights, split out so the compiler type-checks it separately.
private struct OrbFill: View {
    var phase: Double
    var side: CGFloat
    private static let colors: [Color] = [
        Color(red: 1, green: 0.97, blue: 0.82), MuralColor.butter, MuralColor.peach,
        Color(red: 1, green: 0.70, blue: 0.42), MuralColor.orange, Color(red: 0.80, green: 0.68, blue: 0.93),
        Color(red: 0.96, green: 0.42, blue: 0.35), Color(red: 0.99, green: 0.62, blue: 0.46), Color(red: 0.86, green: 0.75, blue: 0.95)
    ]
    private var points: [SIMD2<Float>] {
        let centerX = Float(0.5 + sin(phase) * 0.08)
        let centerY = Float(0.5 + cos(phase) * 0.06)
        return [
            SIMD2<Float>(0, 0), SIMD2<Float>(0.5, 0), SIMD2<Float>(1, 0),
            SIMD2<Float>(0, 0.5), SIMD2<Float>(centerX, centerY), SIMD2<Float>(1, 0.5),
            SIMD2<Float>(0, 1), SIMD2<Float>(0.5, 1), SIMD2<Float>(1, 1)
        ]
    }
    var body: some View {
        ZStack {
            MeshGradient(width: 3, height: 3, points: points, colors: Self.colors)
            highlight
            glow
        }
    }
    private var highlight: some View {
        Ellipse().fill(Color.white.opacity(0.65))
            .frame(width: side * 0.48, height: side * 0.15)
            .blur(radius: 13)
            .rotationEffect(.degrees(-28))
            .offset(x: side * -0.17, y: side * -0.28)
    }
    private var glow: some View {
        Ellipse().stroke(MuralColor.butter.opacity(0.48), lineWidth: 16)
            .frame(width: side * 1.2, height: side * 0.5)
            .blur(radius: 12)
            .rotationEffect(.degrees(-15))
            .offset(y: side * 0.54)
    }
}

struct RecallBars: View {
    let count: Int
    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3) { index in Capsule().fill(index < count ? MuralColor.orange : MuralColor.peach).frame(width: 18, height: 6) }
        }.accessibilityLabel("\(count) of 3 recall bars")
    }
}

struct PageHeading: View {
    var eyebrow: String
    var title: String
    var subtitle: String = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(eyebrow.uppercased()).font(.system(.caption, design: .rounded, weight: .medium)).tracking(1.5).foregroundStyle(MuralColor.secondary)
            Text(title).font(.system(.largeTitle, design: .rounded, weight: .semibold)).tracking(-1).foregroundStyle(MuralColor.ink)
            if !subtitle.isEmpty { Text(subtitle).font(.subheadline).foregroundStyle(MuralColor.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
