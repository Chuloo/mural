import SwiftUI

/// The approved generated orb artwork.  The parent supplies the same clock used
/// by the conversation view; this view deliberately owns no timer or animation
/// state of its own.
struct ApprovedOrbArtwork: View {
    let assetName: String
    let time: Double
    let energy: Double
    let moving: Bool
    let seed: Double

    private var finiteEnergy: Double {
        guard energy.isFinite else { return 0 }
        return min(1, max(0, energy))
    }

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let renderedSize = CGSize(width: size.width * 1.32, height: size.height * 1.32)
            Image(assetName)
                .resizable()
                .scaledToFit()
                .frame(width: renderedSize.width, height: renderedSize.height)
                .layerEffect(
                    ShaderLibrary.default.approvedOrbFlow(
                        .float2(Float(renderedSize.width), Float(renderedSize.height)),
                        .float2(Float(size.width), Float(size.height)),
                        .float(Float(time.isFinite ? time : 0)),
                        .float(Float(finiteEnergy)),
                        .float(moving ? 1 : 0),
                        .float(Float(seed.isFinite ? seed : 0))
                    ),
                    maxSampleOffset: CGSize(width: renderedSize.width * 0.04, height: renderedSize.height * 0.04),
                    // Keep the shader enabled while still: it also removes the
                    // generated PNG's square background.
                    isEnabled: true
                )
                .frame(width: size.width, height: size.height)
        }
        .clipped()
        .accessibilityHidden(true)
    }
}
