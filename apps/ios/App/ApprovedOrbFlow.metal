#include <metal_stdlib>
#include <SwiftUI/SwiftUI.h>
using namespace metal;

// The displacement is deliberately confined to the generated orb's interior.
// The source artwork remains the colour and shape authority; this only gives
// its material a slow, flowing motion and a small audio response.
[[ stitchable ]] half4 approvedOrbFlow(
    float2 position,
    SwiftUI::Layer layer,
    float2 layerSize,
    float2 maskSize,
    float time,
    float energy,
    float moving,
    float seed
) {
    float2 safeLayerSize = max(layerSize, float2(1.0));
    float2 safeMaskSize = max(maskSize, float2(1.0));
    float2 uv = position / safeLayerSize;
    float maskRadius = length((position - safeLayerSize * 0.5) / safeMaskSize);
    float sourceRadius = length(uv - float2(0.5));

    // The PNGs have a cream square around a round orb. A radial feather removes
    // only that outer area, preserving white highlights inside the pearl skin.
    float feather = 1.0 - smoothstep(0.460, 0.495, maskRadius);

    float activity = clamp(moving, 0.0, 1.0);
    float phase = time * 0.42 + seed;
    float2 drift = float2(
        sin(uv.y * 9.0 + phase) + 0.45 * cos(uv.x * 7.0 - time * 0.31),
        cos(uv.x * 8.0 - time * 0.35 + seed) + 0.40 * sin(uv.y * 10.0 + time * 0.29)
    );
    float inner = 1.0 - smoothstep(0.20, 0.305, sourceRadius);
    float2 samplePosition = position + drift * (0.018 * activity * inner) * safeLayerSize;

    // Audio gently scales sampling around the orb edge without moving the page.
    float audioEdge = 1.0 - smoothstep(0.36, 0.43, sourceRadius);
    samplePosition = float2(0.5) * safeLayerSize +
        (samplePosition - float2(0.5) * safeLayerSize) / (1.0 + clamp(energy, 0.0, 1.0) * 0.023 * audioEdge);

    half4 colour = layer.sample(clamp(samplePosition, float2(0.0), safeLayerSize));
    // Layer.sample is premultiplied; fade all channels together to avoid a
    // bright fringe on a dark page.
    return colour * half(feather);
}
