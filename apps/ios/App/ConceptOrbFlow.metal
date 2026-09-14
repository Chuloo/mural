#include <metal_stdlib>
#include <SwiftUI/SwiftUI.h>
using namespace metal;

[[ stitchable ]] half4 conceptOrbFlow(
    float2 position,
    SwiftUI::Layer layer,
    float2 layerSize,
    float2 maskSize,
    float time,
    float energy,
    float style,
    float moving
) {
    float2 safeLayer = max(layerSize, float2(1.0));
    float2 safeMask = max(maskSize, float2(1.0));
    float2 uv = position / safeLayer;
    float2 center = safeLayer * 0.5;
    float2 sourceUV = uv;
    float2 local = (position - center) / safeMask;
    float radius = length(local);
    float active = clamp(moving, 0.0, 1.0);
    float e = clamp(energy, 0.0, 1.0) * active;
    // The parent freezes this elapsed clock when motion is paused. Preserve
    // the phase. Only audio intensity changes when motion is disabled.
    float t = time;

    // Keep a small canvas edge guard. Background matting below follows the
    // source pixels so the ring and horizontal wave retain their silhouettes.
    float edge = max(abs(local.x), abs(local.y));
    float edgeMask = 1.0 - smoothstep(0.485, 0.505, edge);

    float2 samplePosition = position;
    float2 d = float2(
        sin(sourceUV.y * 9.0 + t * 0.42) + 0.45 * cos(sourceUV.x * 7.0 - t * 0.31),
        cos(sourceUV.x * 8.0 - t * 0.35 + style * 0.47) + 0.40 * sin(sourceUV.y * 10.0 + t * 0.29)
    );
    float inner = 1.0 - smoothstep(0.20, 0.305, length(sourceUV - 0.5));

    if (style < 1.5) {
        // Nebula: slow internal cloud advection.
        samplePosition += d * 0.018 * inner * safeLayer;
    } else if (style < 2.5) {
        // Hollow halo: angular motion is restricted to the ring pixels.
        float a = t * 0.09;
        float2 q = position - center;
        samplePosition = center + float2(cos(a) * q.x - sin(a) * q.y, sin(a) * q.x + cos(a) * q.y);
    } else if (style < 3.5) {
        // Particle sphere: flow texture plus sparse moving glints, rather than
        // rotating the complete bitmap.
        samplePosition += d * (0.012 + e * 0.008) * inner * safeLayer;
    } else if (style < 4.5) {
        // Ripples: radial phase travels outwards from the source centre.
        float ripple = sin(radius * 34.0 - t * 1.7) * (0.006 + e * 0.004) * (1.0 - smoothstep(0.12, 0.45, radius));
        samplePosition += normalize(local + float2(0.0001)) * ripple * safeMask;
    } else if (style < 5.5) {
        // Aurora: curved ribbons drift internally along the source material.
        samplePosition += d * 0.020 * inner * safeLayer;
    } else if (style < 6.5) {
        // Keep the original facet boundaries fixed. A light band is applied
        // after sampling; the crystal's geometry never melts.
    } else if (style < 7.5) {
        // Orbit artwork keeps the central globe stable; the polar warp only
        // advances the outer ring/satellite region.
        float a = t * 0.10 * smoothstep(0.27, 0.40, radius);
        float2 q = position - center;
        samplePosition = center + float2(cos(a) * q.x - sin(a) * q.y, sin(a) * q.x + cos(a) * q.y);
    } else if (style < 8.5) {
        // Breath: gentle radial scale and warm glow, bounded below 3%.
        float breath = sin(t * 0.75) * 0.012 + e * 0.016;
        samplePosition = center + (position - center) / (1.0 + breath);
    } else if (style < 9.5) {
        // Horizontal waveform: vertical amplitude around a fixed centre line.
        float waveScale = 1.0 + (0.010 + e * 0.026) * sin(sourceUV.x * 24.0 + t * 1.5);
        samplePosition.y = center.y + (samplePosition.y - center.y) * waveScale;
    } else if (style < 10.5) {
        // Nature: only water below the horizon moves; sun and horizon are fixed.
        float water = smoothstep(0.50, 0.58, sourceUV.y);
        samplePosition.x += sin(sourceUV.y * 31.0 + t * 0.5) * (0.012 + e * 0.008) * water * safeLayer.x;
    } else if (style < 11.5) {
        // Night: restrained internal star drift; edge glow is added below.
        samplePosition += d * (0.008 + e * 0.006) * inner * safeLayer;
    } else {
        // Morph concept: one flattened artwork gets a subtle contour pulse,
        // explicitly not a claim of multi-frame shape morphing.
        float contour = sin(t * 0.48 + radius * 16.0) * (0.010 + e * 0.006) * smoothstep(0.25, 0.44, radius);
        samplePosition += normalize(local + float2(0.0001)) * contour * safeMask;
    }

    samplePosition = clamp(samplePosition, float2(0.0), safeLayer);
    half4 colour = layer.sample(samplePosition);
    // Sample the actual source backdrop in the same colour space as the
    // artwork. A hard-coded sRGB cream would leave squares on dark pages.
    float3 backdrop = float3(layer.sample(safeLayer * float2(0.015, 0.015)).rgb);
    float colourDistance = distance(float3(colour.rgb), backdrop);
    float matte = smoothstep(0.009, 0.065, colourDistance);
    float core = 0.0;
    if (style < 1.5) core = 0.23;
    else if (style > 3.5 && style < 4.5) core = 0.20;
    else if (style > 4.5 && style < 6.5) core = 0.32;
    else if (style > 6.5 && style < 7.5) core = 0.24;
    else if (style > 7.5 && style < 8.5) core = 0.34;
    else if (style > 9.5 && style < 11.5) core = 0.32;
    else if (style > 11.5) core = 0.23;
    // Protect pale highlights inside solid subjects; hollow rings, individual
    // particles and the waveform deliberately receive no solid centre mask.
    if (core > 0.0) {
        float sampledRadius = length(samplePosition / safeLayer - 0.5);
        matte = max(matte, 1.0 - smoothstep(core - 0.025, core, sampledRadius));
    }
    float subject = matte * edgeMask;
    colour.rgb *= half(1.0 + e * 0.065);
    if (style > 5.5 && style < 6.5) {
        float band = pow(max(0.0, cos(sourceUV.x * 6.0 + sourceUV.y * 2.0 - t * 0.32)), 18.0);
        colour.rgb += half3(0.055, 0.041, 0.025) * half(band * (0.55 + e * 0.45));
    }
    if (style > 2.5 && style < 3.5) {
        // Continuous low-frequency particle glint; no 2 Hz pixel flashing.
        float glint = 0.5 + 0.5 * sin(sourceUV.x * 16.0 + sourceUV.y * 13.0 + t * 0.7);
        colour.rgb += half(glint * 0.025 * e);
    }
    if (style > 7.5 && style < 8.5) {
        colour.rgb += half3(0.025, 0.012, 0.005) * half(e);
    }
    if (style > 10.5) {
        float rim = smoothstep(0.28, 0.44, radius);
        colour.rgb += half3(0.06, 0.025, 0.10) * half(rim * (0.25 + e * 0.75));
    }
    return colour * half(clamp(subject, 0.0, 1.0));
}
