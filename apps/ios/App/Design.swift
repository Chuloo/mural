import SwiftUI
import PhotosUI
import UIKit
import ImageIO
import MuralCore

enum MuralColor {
    fileprivate static func adaptive(_ light: UIColor, _ dark: UIColor) -> Color {
        Color(uiColor: UIColor { traits in traits.userInterfaceStyle == .dark ? dark : light })
    }
    // Original Mural warm palette, with readable counterparts for dark mode.
    static let background = adaptive(UIColor(red: 1, green: 0.975, blue: 0.933, alpha: 1), UIColor(red: 0.12, green: 0.10, blue: 0.085, alpha: 1))
    static let surface = adaptive(UIColor(red: 1, green: 0.965, blue: 0.925, alpha: 1), UIColor(red: 0.19, green: 0.16, blue: 0.13, alpha: 1))
    static let elevatedSurface = adaptive(.white, UIColor(red: 0.24, green: 0.20, blue: 0.17, alpha: 1))
    static let ink = adaptive(UIColor(red: 0.212, green: 0.165, blue: 0.133, alpha: 1), UIColor(red: 1, green: 0.95, blue: 0.89, alpha: 1))
    static let secondary = adaptive(UIColor(red: 0.45, green: 0.355, blue: 0.29, alpha: 1), UIColor(red: 0.78, green: 0.70, blue: 0.62, alpha: 1))
    static let muted = Color(uiColor: .tertiaryLabel)
    static let cream = background
    static let orange = Color(red: 1, green: 0.54, blue: 0.30)
    // Deeper orange keeps white button labels and small interactive text legible.
    static let buttonFill = Color(red: 0.69, green: 0.29, blue: 0.10)
    static let accent = adaptive(UIColor(red: 0.69, green: 0.29, blue: 0.10, alpha: 1), UIColor(red: 1, green: 0.67, blue: 0.43, alpha: 1))
    static let peach = adaptive(UIColor(red: 1, green: 0.89, blue: 0.81, alpha: 1), UIColor(red: 0.32, green: 0.21, blue: 0.16, alpha: 1))
    static let lilac = adaptive(UIColor(red: 0.932, green: 0.902, blue: 0.98, alpha: 1), UIColor(red: 0.24, green: 0.20, blue: 0.32, alpha: 1))
    static let sage = adaptive(UIColor(red: 0.917, green: 0.937, blue: 0.84, alpha: 1), UIColor(red: 0.20, green: 0.26, blue: 0.18, alpha: 1))
    static let butter = adaptive(UIColor(red: 1, green: 0.944, blue: 0.78, alpha: 1), UIColor(red: 0.32, green: 0.26, blue: 0.14, alpha: 1))
    static let panels = [peach, lilac, sage, butter]
}

enum PageBackground: String, CaseIterable, Identifiable {
    case original, iridescent, peach, mint, sky, lavender
    var id: String { rawValue }
    var title: LocalizedStringKey { LocalizedStringKey("Page background " + rawValue) }
    var colors: [Color] {
        let light: [(Double, Double, Double)]
        let dark: [(Double, Double, Double)]
        switch self {
        case .original: return [MuralColor.background, MuralColor.background, MuralColor.background]
        case .iridescent:
            light = [(0.96, 0.92, 1), (0.87, 0.95, 1), (1, 0.91, 0.86)]
            dark = [(0.12, 0.09, 0.20), (0.08, 0.17, 0.22), (0.24, 0.13, 0.16)]
        case .peach:
            light = [(1, 0.96, 0.87), (1, 0.89, 0.81), (1, 0.91, 0.91)]
            dark = [(0.23, 0.17, 0.10), (0.28, 0.16, 0.12), (0.24, 0.13, 0.17)]
        case .mint:
            light = [(0.96, 0.98, 0.86), (0.85, 0.96, 0.91), (0.87, 0.95, 0.95)]
            dark = [(0.16, 0.21, 0.12), (0.09, 0.22, 0.18), (0.09, 0.18, 0.22)]
        case .sky:
            light = [(0.95, 0.98, 1), (0.84, 0.93, 1), (0.90, 0.92, 0.99)]
            dark = [(0.10, 0.16, 0.22), (0.09, 0.19, 0.28), (0.16, 0.15, 0.25)]
        case .lavender:
            light = [(0.98, 0.94, 1), (0.92, 0.88, 0.98), (0.99, 0.90, 0.94)]
            dark = [(0.19, 0.13, 0.25), (0.22, 0.16, 0.30), (0.27, 0.14, 0.22)]
        }
        return zip(light, dark).map { l, d in
            MuralColor.adaptive(UIColor(red: l.0, green: l.1, blue: l.2, alpha: 1),
                                UIColor(red: d.0, green: d.1, blue: d.2, alpha: 1))
        }
    }
}

private struct PageBackgroundKey: EnvironmentKey {
    static let defaultValue: PageBackground = .original
}
extension EnvironmentValues {
    var muralPageBackground: PageBackground {
        get { self[PageBackgroundKey.self] }
        set { self[PageBackgroundKey.self] = newValue }
    }
}

struct MuralBackdrop: View {
    @State private var visible = false
    @Environment(\.muralPageBackground) private var background
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        Group {
            if background == .original {
                MuralColor.background
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 15.0, paused: reduceMotion || !visible || scenePhase != .active)) { timeline in
                    let phase = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate * 0.10
                    let colors = background.colors
                    ZStack {
                        LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                        MeshGradient(width: 3, height: 3, points: [
                            [0, 0], [Float(0.5 + sin(phase) * 0.08), 0], [1, 0],
                            [0, 0.5], [0.5, Float(0.5 + cos(phase * 0.8) * 0.08)], [1, 0.5],
                            [0, 1], [Float(0.5 + cos(phase * 0.6) * 0.06), 1], [1, 1]
                        ], colors: [colors[0], colors[1], colors[2], colors[2], colors[0], colors[1], colors[1], colors[2], colors[0]])
                            .opacity(0.48)
                        RadialGradient(colors: [colors[0].opacity(0.6), .clear], center: .center, startRadius: 0, endRadius: 230)
                            .offset(x: sin(phase * 1.3) * 34, y: cos(phase) * 24)
                    }
                }
            }
        }.ignoresSafeArea().allowsHitTesting(false)
            .onAppear { visible = true }.onDisappear { visible = false }
    }
}

struct Brand: View {
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(RadialGradient(colors: [MuralColor.butter, MuralColor.orange], center: .topLeading, startRadius: 0, endRadius: 18)).frame(width: 17, height: 17)
            Text("Mural").font(.title2.weight(.semibold))
        }.foregroundStyle(MuralColor.ink).accessibilityLabel("Mural")
    }
}

struct SoftGlass: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    var tint: Color = .white.opacity(0.45)
    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(MuralColor.elevatedSurface, in: Capsule())
        } else if #available(iOS 26, *) {
            content.glassEffect(.regular.tint(tint).interactive(), in: .capsule)
        } else {
            content.background(.ultraThinMaterial, in: Capsule())
        }
    }
}

struct OrbShape: Shape {
    var phase: Double
    var energy: Double
    func path(in rect: CGRect) -> Path {
        let points = (0..<12).map { index -> CGPoint in
            let a = Double(index) / 12 * .pi * 2
            let wave = sin(a * 3 + phase) * 0.021 + cos(a * 2 - phase * 0.7) * (0.012 + energy * 0.025)
            let radius = min(rect.width, rect.height) * (0.47 + wave)
            return CGPoint(x: rect.midX + cos(a) * radius, y: rect.midY + sin(a) * radius)
        }
        var p = Path()
        for i in 0..<12 {
            let current = points[i], next = points[(i + 1) % 12]
            let midpoint = CGPoint(x: (current.x + next.x) / 2, y: (current.y + next.y) / 2)
            if i == 0 {
                let previous = points[11]
                p.move(to: CGPoint(x: (previous.x + current.x) / 2, y: (previous.y + current.y) / 2))
            }
            p.addQuadCurve(to: midpoint, control: current)
        }
        p.closeSubpath(); return p
    }
}

struct MuralOrb: View {
    var energy: Double = 0
    var listening = false
    var active = true
    var skin: OrbSkin = .classic
    var avatar: AvatarSelection = .init()
    var speechEnergy: Double = 0
    var listeningEnergy: Double = 0
    @State private var visible = false
    @State private var elapsed: TimeInterval = 0
    @State private var animationStarted: Date?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    private var animating: Bool { active && visible && !reduceMotion && scenePhase == .active }
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || !active || !visible || scenePhase != .active)) { timeline in
            let running = active && visible && scenePhase == .active
            let moving = running && !reduceMotion
            let t = elapsed + (animationStarted.map { max(0, timeline.date.timeIntervalSince($0)) } ?? 0)
            OrbArtwork(time: t, energy: moving && energy.isFinite ? min(1, max(0, energy)) : 0,
                       listening: listening && running, moving: moving, skin: skin, avatar: avatar,
                       speechEnergy: running ? speechEnergy : 0,
                       listeningEnergy: running ? listeningEnergy : 0, characterActive: running)
        }.accessibilityHidden(true)
            .onAppear { visible = true }
            .onDisappear { visible = false; updateAnimationClock(running: false) }
            .onChange(of: animating) { _, running in updateAnimationClock(running: running) }
    }
    private func updateAnimationClock(running: Bool) {
        let now = Date()
        if let start = animationStarted { elapsed += max(0, now.timeIntervalSince(start)) }
        animationStarted = running ? now : nil
    }
}

/// The picker and the live teacher use this same drawing and supplied animation clock.
private struct OrbArtwork: View {
    let time: Double
    var energy: Double = 0
    var listening = false
    var moving = true
    var skin: OrbSkin = .classic
    var avatar: AvatarSelection = .init()
    var speechEnergy: Double = 0
    var listeningEnergy: Double = 0
    var characterActive = false
    var body: some View {
        let phase = time * 0.72
        let palette = skin.palette
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            ZStack {
                if let artwork = skin.conceptArtwork {
                    ConceptOrbArtwork(assetName: artwork.assetName, style: artwork.style,
                                      time: time, energy: moving ? energy : 0, moving: moving)
                    if avatar.mode == .custom {
                        AvatarFace(selection: avatar, side: side * 0.72).clipShape(Circle())
                            .overlay(Circle().stroke(.white.opacity(0.52), lineWidth: 2))
                    }
                } else if let artwork = skin.approvedArtwork {
                    ApprovedOrbArtwork(assetName: artwork.assetName, time: time,
                                       energy: moving ? energy : 0, moving: moving, seed: artwork.seed)
                    if avatar.mode == .custom {
                        AvatarFace(selection: avatar, side: side * 0.72).clipShape(Circle())
                            .overlay(Circle().stroke(.white.opacity(0.52), lineWidth: 2))
                    }
                } else {
                Ellipse().fill(palette.accent.opacity(0.14))
                    .frame(width: side * 0.57, height: side * 0.075)
                    .blur(radius: 10).offset(y: side * 0.47)
                Circle().stroke(palette.accent.opacity(listening ? 0.18 : 0), lineWidth: 1).padding(-6)
                Circle().stroke(palette.accent.opacity(listening ? 0.10 : 0), lineWidth: 1).padding(-16)
                ZStack {
                    MeshGradient(width: 3, height: 3, points: [
                        [0,0], [0.5,0], [1,0],
                        [0,0.5], [Float(0.5 + sin(phase) * 0.08),
                                   Float(0.5 + cos(phase) * 0.06)], [1,0.5],
                        [0,1], [0.5,1], [1,1]
                    ], colors: palette.mesh)
                    Ellipse().fill(.white.opacity(0.65)).frame(width: side * 0.48, height: side * 0.15).blur(radius: 13)
                        .rotationEffect(.degrees(-28)).offset(x: -side * 0.17, y: -side * 0.28)
                    Ellipse().stroke(palette.light.opacity(0.48), lineWidth: 16).frame(width: side * 1.2, height: side * 0.5)
                        .blur(radius: 12).rotationEffect(.degrees(-15)).offset(y: side * 0.54)
                    if avatar.mode == .custom {
                        AvatarFace(selection: avatar, side: side * 0.72).clipShape(Circle())
                            .overlay(Circle().stroke(.white.opacity(0.52), lineWidth: 2))
                    }
                }
                .mask {
                    OrbShape(phase: phase, energy: energy)
                }
                .shadow(color: palette.accent.opacity(0.12), radius: 16, y: 10)
                .rotationEffect(.degrees(sin(phase * 0.5) * 3))
                .scaleEffect(1 + energy * 0.045)
                .offset(y: moving ? sin(time * 0.9) * 4 - 5 : 0)
                Circle().fill(RadialGradient(colors: [.white, Color(red: 1, green: 0.89, blue: 0.81), palette.accent.opacity(0.5)], center: .topLeading, startRadius: 0, endRadius: 12))
                    .frame(width: 12, height: 12).offset(x: side * 0.55, y: -side * 0.24)
                Circle().fill(Color(red: 1, green: 0.89, blue: 0.81)).frame(width: 7, height: 7).offset(x: -side * 0.54, y: side * 0.26)
                }
            }.frame(width: side, height: side).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct AvatarFace: View {
    let selection: AvatarSelection
    let side: CGFloat
    @State private var customImage: UIImage?
    var body: some View {
        Group {
            switch selection.mode {
            case .cartoon, .animal:
                EmptyView()
            case .custom:
                if let customImage { Image(uiImage: customImage).resizable().scaledToFill() }
                else { Image(systemName: "person.crop.circle").resizable().scaledToFit().padding(side * 0.2) }
            case .animated: EmptyView()
            }
        }.frame(width: side, height: side).clipped()
            .task(id: selection.customImageFilename) { customImage = AvatarImageStore.load(filename: selection.customImageFilename) }
    }
}

enum AvatarImageStore {
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mural", isDirectory: true)
    static func url(for filename: String) -> URL? {
        guard AvatarSelection.validCustomImageFilename(filename) else { return nil }
        return directory.appendingPathComponent(filename)
    }
    static func save(data: Data) throws -> String {
        guard data.count <= 20 * 1024 * 1024, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 1024, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        let image = UIImage(cgImage: cgImage)
        guard let resized = image.jpegData(compressionQuality: 0.86), !resized.isEmpty else { throw CocoaError(.fileWriteUnknown) }
        let filename = UUID().uuidString + ".jpg"; try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try resized.write(to: directory.appendingPathComponent(filename), options: .atomic)
        return filename
    }
    static func load(filename: String?) -> UIImage? {
        guard let filename, let url = url(for: filename), let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 1024, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}

struct AvatarPicker: View {
    @Binding var selection: AvatarSelection
    @State private var photoItem: PhotosPickerItem?
    @State private var error: String?
    @State private var draftMode: AvatarMode = .animated
    @State private var importGeneration = UUID()
    @State private var importing = false
    @State private var customImageMissing = false
    var body: some View {
        VStack(spacing: 14) {
            Picker("Avatar type", selection: $draftMode) {
                Text("Animated").tag(AvatarMode.animated)
                Text("Custom image").tag(AvatarMode.custom)
            }.pickerStyle(.menu).frame(minHeight: 44)
            if draftMode == .custom {
                PhotosPicker(selection: $photoItem, matching: .images) { Label("Choose one image", systemImage: "photo") }
                    .onChange(of: photoItem) { _, item in
                        guard let item else { return }
                        let generation = UUID(); importGeneration = generation; error = nil; importing = true
                        Task {
                            defer { if importGeneration == generation { importing = false } }
                            do {
                                guard let data = try await item.loadTransferable(type: Data.self) else { throw CocoaError(.fileReadCorruptFile) }
                                let filename = try await Task.detached(priority: .userInitiated) { try AvatarImageStore.save(data: data) }.value
                                guard importGeneration == generation, draftMode == .custom else { return }
                                var next = selection; next.mode = .custom; next.customImageFilename = filename; selection = next
                            } catch { if importGeneration == generation { self.error = "avatar_error_unable_to_save_image" } }
                        }
                    }
                if importing { ProgressView("Preparing image…") }
                if let error { Text(LocalizedStringKey(error)).font(.footnote).foregroundStyle(.red) }
                if selection.mode == .custom, customImageMissing {
                    Text("Your saved image is unavailable. Choose another image.").font(.footnote).foregroundStyle(MuralColor.secondary)
                }
            }
        }
            .onAppear { draftMode = selection.mode == .custom ? .custom : .animated }
            .task(id: selection.customImageFilename) {
                customImageMissing = AvatarImageStore.load(filename: selection.customImageFilename) == nil
            }
            .onDisappear { importGeneration = UUID(); importing = false }
            .onChange(of: draftMode) { _, mode in
                importGeneration = UUID(); importing = false; error = nil
                guard mode != .custom else { return }
                var next = selection; next.mode = .animated; selection = next
            }
    }
}

enum OrbSkin: String, CaseIterable, Identifiable, Hashable {
    case classic, ocean, aurora, forest
    case flowSunrise, flowRose, flowJade, flowGlacier, flowLilac
    case flowOcean, flowAmber, flowMatcha, flowPearl, flowMidnight
    case conceptNebula, conceptHalo, conceptParticles, conceptRipples
    case conceptAurora, conceptCrystal, conceptOrbits, conceptBreath
    case conceptWave, conceptNature, conceptNight, conceptMorph
    var id: String { rawValue }
    var title: LocalizedStringKey { LocalizedStringKey("Orb skin " + rawValue) }
    var approvedArtwork: (assetName: String, seed: Double)? {
        guard let style = approvedStyle else { return nil }
        return (String(format: "ApprovedOrb%02d", style.index), Double(style.index) * 0.47)
    }
    var conceptArtwork: (assetName: String, style: Int)? {
        guard let style = conceptStyle else { return nil }
        return (String(format: "ConceptOrb%02d", style.index), style.index)
    }
    private var conceptStyle: (index: Int, hex: UInt32)? {
        switch self {
        case .conceptNebula: return (1, 0xd17c78)
        case .conceptHalo: return (2, 0xc89a63)
        case .conceptParticles: return (3, 0xc87f72)
        case .conceptRipples: return (4, 0x829bbf)
        case .conceptAurora: return (5, 0xa98dc0)
        case .conceptCrystal: return (6, 0xc79265)
        case .conceptOrbits: return (7, 0xb68994)
        case .conceptBreath: return (8, 0xcb9587)
        case .conceptWave: return (9, 0xc88890)
        case .conceptNature: return (10, 0x609fc1)
        case .conceptNight: return (11, 0x8171b8)
        case .conceptMorph: return (12, 0xc290b0)
        default: return nil
        }
    }
    private var approvedStyle: (index: Int, hex: UInt32)? {
        switch self {
        case .flowSunrise: return (1, 0xec8b55)
        case .flowRose: return (2, 0xc77791)
        case .flowJade: return (3, 0x509c88)
        case .flowGlacier: return (4, 0x4298be)
        case .flowLilac: return (5, 0x9481be)
        case .flowOcean: return (6, 0x376fba)
        case .flowAmber: return (7, 0xb78337)
        case .flowMatcha: return (8, 0x899a59)
        case .flowPearl: return (9, 0x9392ac)
        case .flowMidnight: return (10, 0x595382)
        default: return nil
        }
    }
    fileprivate struct Palette { let accent: Color; let light: Color; let mesh: [Color] }
    fileprivate var palette: Palette {
        if let style = approvedStyle ?? conceptStyle {
            let color = Color(red: Double((style.hex >> 16) & 255) / 255,
                              green: Double((style.hex >> 8) & 255) / 255,
                              blue: Double(style.hex & 255) / 255)
            return Palette(accent: color, light: color.opacity(0.25), mesh: [])
        }
        switch self {
        case .classic: return Palette(accent: Color(red: 1, green: 0.54, blue: 0.30), light: Color(red: 1, green: 0.944, blue: 0.78),
            mesh: [Color(red: 1, green: 0.97, blue: 0.82), Color(red: 1, green: 0.944, blue: 0.78), Color(red: 1, green: 0.89, blue: 0.81),
                   Color(red: 1, green: 0.70, blue: 0.42), Color(red: 1, green: 0.54, blue: 0.30), Color(red: 0.80, green: 0.68, blue: 0.93),
                   Color(red: 0.96, green: 0.42, blue: 0.35), Color(red: 0.99, green: 0.62, blue: 0.46), Color(red: 0.86, green: 0.75, blue: 0.95)])
        case .ocean: return Palette(accent: Color(red: 0.16, green: 0.48, blue: 0.78), light: Color(red: 0.62, green: 0.89, blue: 0.96),
            mesh: [Color(red: 0.83, green: 0.96, blue: 1), Color(red: 0.46, green: 0.82, blue: 0.94), Color(red: 0.40, green: 0.64, blue: 0.90), Color(red: 0.18, green: 0.57, blue: 0.80), Color(red: 0.16, green: 0.38, blue: 0.73), Color(red: 0.52, green: 0.78, blue: 0.94), Color(red: 0.10, green: 0.32, blue: 0.66), Color(red: 0.25, green: 0.63, blue: 0.82), Color(red: 0.69, green: 0.91, blue: 0.96)])
        case .aurora: return Palette(accent: Color(red: 0.35, green: 0.42, blue: 0.80), light: Color(red: 0.75, green: 0.78, blue: 0.98),
            mesh: [Color(red: 0.88, green: 0.90, blue: 1), Color(red: 0.56, green: 0.82, blue: 0.91), Color(red: 0.80, green: 0.63, blue: 0.94), Color(red: 0.35, green: 0.75, blue: 0.81), Color(red: 0.38, green: 0.44, blue: 0.82), Color(red: 0.76, green: 0.57, blue: 0.93), Color(red: 0.26, green: 0.58, blue: 0.72), Color(red: 0.42, green: 0.78, blue: 0.65), Color(red: 0.90, green: 0.77, blue: 0.95)])
        case .forest: return Palette(accent: Color(red: 0.19, green: 0.49, blue: 0.35), light: Color(red: 0.71, green: 0.88, blue: 0.65),
            mesh: [Color(red: 0.91, green: 0.98, blue: 0.80), Color(red: 0.67, green: 0.88, blue: 0.59), Color(red: 0.47, green: 0.74, blue: 0.46), Color(red: 0.32, green: 0.66, blue: 0.43), Color(red: 0.16, green: 0.45, blue: 0.31), Color(red: 0.58, green: 0.77, blue: 0.42), Color(red: 0.12, green: 0.37, blue: 0.27), Color(red: 0.32, green: 0.60, blue: 0.35), Color(red: 0.76, green: 0.91, blue: 0.57)])
        default: return OrbSkin.classic.palette
        }
    }
}

struct OrbSkinPicker: View {
    @Binding var selection: OrbSkin
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                MuralOrb(energy: 0.45, listening: true, skin: selection).frame(width: 190, height: 190)
                SkinGrid(selection: $selection, minimum: typeSize.isAccessibilitySize ? 150 : 105)
            }.padding(20)
        }.background(MuralColor.cream).navigationTitle("Orb skins").navigationBarTitleDisplayMode(.inline)
    }
}

private struct SkinGrid: View {
    @Binding var selection: OrbSkin
    let minimum: CGFloat
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: minimum), spacing: 12)], spacing: 12) {
            ForEach(OrbSkin.allCases) { skin in
                Button { selection = skin } label: {
                    VStack(spacing: 5) {
                        OrbArtwork(time: 0, moving: false, skin: skin)
                            .frame(width: 64, height: 64).accessibilityHidden(true)
                        Text(skin.title).font(.caption).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                        Image(systemName: selection == skin ? "checkmark.circle.fill" : "circle")
                            .font(.caption2).opacity(selection == skin ? 1 : 0)
                    }.frame(maxWidth: .infinity).padding(10)
                        .background(selection == skin ? MuralColor.accent.opacity(0.18) : skin.palette.light.opacity(0.20),
                                    in: RoundedRectangle(cornerRadius: 14))
                }.buttonStyle(.plain).accessibilityAddTraits(selection == skin ? .isSelected : [])
                    .accessibilityIdentifier("orb-skin-" + skin.rawValue)
            }
        }
    }
}

private enum AppearanceSection: Hashable { case background, skin, avatar }

struct AppearancePicker: View {
    @Binding var skin: OrbSkin
    @Binding var avatar: AvatarSelection
    @Binding var background: PageBackground
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var section: AppearanceSection = .background
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                MuralOrb(energy: 0.15, listening: false, skin: skin, avatar: avatar)
                    .frame(width: 180, height: 180).frame(maxWidth: .infinity)
                if typeSize.isAccessibilitySize {
                    sections.pickerStyle(.menu)
                } else {
                    sections.pickerStyle(.segmented)
                }
                switch section {
                case .background: backgroundChoices
                case .skin: SkinGrid(selection: $skin, minimum: typeSize.isAccessibilitySize ? 140 : 100)
                case .avatar: AvatarPicker(selection: $avatar)
                }
            }.padding(20)
        }.background { MuralBackdrop() }
            .environment(\.muralPageBackground, background)
    }
    private var sections: some View {
        Picker("Appearance", selection: $section) {
            Text("Page background").tag(AppearanceSection.background)
            Text("Orb skins").tag(AppearanceSection.skin)
            Text("Avatar").tag(AppearanceSection.avatar)
        }
    }
    private var backgroundChoices: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 180 : 140), spacing: 14)], spacing: 14) {
            ForEach(PageBackground.allCases) { option in
                Button { background = option } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(LinearGradient(colors: option.colors, startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(height: 78)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(MuralColor.secondary.opacity(0.16), lineWidth: 1))
                        HStack(alignment: .top) {
                            Text(option.title).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 4)
                            if background == option { Image(systemName: "checkmark.circle.fill").foregroundStyle(MuralColor.accent) }
                        }
                    }.padding(12)
                        .background(MuralColor.elevatedSurface.opacity(0.88), in: RoundedRectangle(cornerRadius: 20))
                        .overlay(RoundedRectangle(cornerRadius: 20).stroke(background == option ? MuralColor.accent : .clear, lineWidth: 2))
                }.buttonStyle(.plain).accessibilityAddTraits(background == option ? .isSelected : [])
            }
        }
    }
}

struct RecallBars: View {
    @Environment(\.locale) private var interfaceLocale
    let count: Int
    var body: some View {
        let _ = interfaceLocale
        HStack(spacing: 4) {
            ForEach(0..<3) { index in Capsule().fill(index < count ? MuralColor.orange : MuralColor.peach).frame(width: 18, height: 6) }
        }.accessibilityLabel(L10n.format("%lld of 3 recall bars", count))
    }
}

struct PageHeading: View {
    var eyebrow: String
    var title: String
    var subtitle: String = ""
    var body: some View {
        Text(LocalizedStringKey(title))
            .font(.largeTitle.weight(.semibold))
            .foregroundStyle(MuralColor.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}


/// App-owned search controls follow the selected interface language immediately.
struct LibrarySearchField: View {
    let prompt: LocalizedStringKey
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(MuralColor.secondary)
            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focused).submitLabel(.search)
                .onSubmit { focused = false }
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .foregroundStyle(MuralColor.secondary).frame(minWidth: 44, minHeight: 44).accessibilityLabel("Clear search")
            }
        }
        .padding(14).background(MuralColor.surface, in: RoundedRectangle(cornerRadius: 12))
    }
}
