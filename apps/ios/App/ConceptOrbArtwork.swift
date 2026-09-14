import SwiftUI

/// Renders one of the twelve approved concept skins. The parent owns the clock;
/// this view contains no timer and is safe to freeze for reduced motion.
struct ConceptOrbArtwork: View {
    let assetName: String
    let style: Int
    let time: Double
    let energy: Double
    let moving: Bool

    private var safeStyle: Int { min(12, max(1, style)) }
    private var finiteEnergy: Double {
        guard energy.isFinite else { return 0 }
        return min(1, max(0, energy))
    }
    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let phase = time.isFinite ? time : 0
            ZStack {
            Image(assetName)
                .resizable()
                .scaledToFit()
                .frame(width: size.width, height: size.height)
                .layerEffect(
                    ShaderLibrary.default.conceptOrbFlow(
                        .float2(Float(size.width), Float(size.height)),
                        .float2(Float(size.width), Float(size.height)),
                        .float(Float(phase)),
                        .float(Float(finiteEnergy)),
                        .float(Float(safeStyle)),
                        .float(moving ? 1 : 0)
                    ),
                    // Rotating a ring can sample across the entire source.
                    maxSampleOffset: safeStyle == 2 || safeStyle == 7
                        ? size : CGSize(width: size.width * 0.06, height: size.height * 0.06),
                    // The shader also removes the opaque cream source surround,
                    // so it must run for still frames as well.
                    isEnabled: true
                )
            if safeStyle == 3 {
                particles(time: phase, energy: moving ? finiteEnergy : 0)
            }
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }

    /// A small independent particle layer follows the generated sphere's
    /// palette. The parent clock freezes these trajectories with the artwork.
    private func particles(time: Double, energy: Double) -> some View {
        Canvas { context, size in
            let side = min(size.width, size.height)
            for index in 0..<64 {
                let seed = Double(index)
                let latitude = (seed + 0.5) / 64 * 2 - 1
                let orbit = sqrt(max(0, 1 - latitude * latitude)) * 0.31
                let angle = seed * 2.399963 + time * (0.065 + Double(index % 5) * 0.009)
                let depth = sin(angle)
                let x = size.width * 0.5 + cos(angle) * orbit * side
                let y = size.height * 0.5 + latitude * side * 0.30
                let diameter = side * (0.0035 + (depth + 1) * 0.0013 + energy * 0.001)
                let tint = index.isMultiple(of: 3)
                    ? Color(red: 0.69, green: 0.50, blue: 0.85)
                    : Color(red: 1, green: 0.57, blue: 0.36)
                let dot = Path(ellipseIn: CGRect(x: x - diameter / 2, y: y - diameter / 2,
                                                width: diameter, height: diameter))
                context.fill(dot, with: .color(tint.opacity(0.25 + (depth + 1) * 0.15 + energy * 0.12)))
            }
        }
    }
}
