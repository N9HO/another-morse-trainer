import SwiftUI
import CoreText

/// Brand palette + reusable styling, derived from the "Another Morse Trainer"
/// logo: a deep navy field, a bright teal accent, and white marks.
///
/// NOTE: the actual logo artwork (app icon + welcome image) is wired separately
/// once the PNG is added to the asset catalog. This file only carries colors so
/// the whole app can adopt the brand look immediately.
enum Theme {
    /// Deep navy background (the logo's field).
    static let navy          = Color(red: 0.043, green: 0.102, blue: 0.176)  // #0B1A2D
    /// Slightly lighter navy for cards / elevated surfaces.
    static let navyElevated  = Color(red: 0.078, green: 0.149, blue: 0.235)  // #14263C
    /// A touch brighter again, for surfaces resting on an elevated card.
    static let navyRaised    = Color(red: 0.110, green: 0.196, blue: 0.298)  // #1C324C
    /// Primary teal accent (the logo ring + "MORSE").
    static let teal          = Color(red: 0.173, green: 0.753, blue: 0.820)  // #2CC0D1
    /// Brighter teal for highlights.
    static let tealBright    = Color(red: 0.275, green: 0.839, blue: 0.890)  // #46D6E3
    /// Muted blue-grey for secondary text on navy.
    static let textSecondary = Color(red: 0.616, green: 0.698, blue: 0.776)  // #9DB2C6
    /// Hairline stroke colour for card / tile borders on the navy field.
    static let hairline      = Color.white.opacity(0.08)

    /// Standard corner radius used across cards, tiles, and prominent buttons,
    /// so curvature stays consistent everywhere.
    static let cornerRadius: CGFloat = 16

    /// Label color for a filled (prominent) control with the given tint. The
    /// brand teal is light enough that the default white label fails WCAG
    /// contrast (issue #59) — the deep navy reads on it at ~7:1. Darker fills
    /// (stop-red, correct-green, disabled gray) keep the conventional white.
    static func prominentLabel(on tint: Color) -> Color {
        tint == teal || tint == tealBright ? navy : .white
    }

    // MARK: - Slashed zero (issue #62)

    /// A system font for displayed copy text, rendering the digit 0 with a
    /// slash — the operator's handwriting convention for telling 0 from O —
    /// when `slashedZero` is on. SF Pro and SF Mono both carry the alternate
    /// glyph (Typographic Extras ▸ Slashed Zero); a face without it silently
    /// keeps its plain zero, so this can never break rendering.
    static func copyFont(size: CGFloat, weight: UIFont.Weight = .regular,
                         monospaced: Bool = false, slashedZero: Bool) -> Font {
        let base = monospaced
            ? UIFont.monospacedSystemFont(ofSize: size, weight: weight)
            : UIFont.systemFont(ofSize: size, weight: weight)
        return Font(slashedZero ? slashed(base) : base)
    }

    /// Dynamic-Type–scaled variant for text-style-based copy displays.
    static func copyFont(style: UIFont.TextStyle, weight: UIFont.Weight = .regular,
                         monospaced: Bool = false, slashedZero: Bool) -> Font {
        let size = UIFont.preferredFont(forTextStyle: style).pointSize
        let base = monospaced
            ? UIFont.monospacedSystemFont(ofSize: size, weight: weight)
            : UIFont.systemFont(ofSize: size, weight: weight)
        let font = slashedZero ? slashed(base) : base
        return Font(UIFontMetrics(forTextStyle: style).scaledFont(for: font))
    }

    private static func slashed(_ base: UIFont) -> UIFont {
        let feature: [UIFontDescriptor.FeatureKey: Int] = [
            .type: kTypographicExtrasType,
            .selector: kSlashedZeroOnSelector
        ]
        let descriptor = base.fontDescriptor.addingAttributes([.featureSettings: [feature]])
        return UIFont(descriptor: descriptor, size: base.pointSize)
    }

    /// Phone-first layouts stretch ugly on iPad; cap readable content to this
    /// width (matches Android's Responsive.CONTENT_MAX_WIDTH).
    static let contentMaxWidth: CGFloat = 640

    /// The wider cap for the few screens that re-flow rather than just centre
    /// on a big iPad window (the home grid). See `WideLayoutReader`.
    static let wideContentMaxWidth: CGFloat = 960

    /// The narrowest window that gets the wide layout: an 11" iPad in
    /// portrait (834 pt) qualifies; a 13" iPad split in half (about 680 pt),
    /// a Slide Over panel, and a narrow Mac window do not, and keep the
    /// phone layout.
    static let wideLayoutMinWidth: CGFloat = 760

    /// Full-bleed brand background: a subtle top-to-bottom navy gradient with a
    /// faint teal glow up top, echoing the logo's lit ring.
    struct Background: View {
        var body: some View {
            ZStack {
                LinearGradient(
                    colors: [Color(red: 0.020, green: 0.055, blue: 0.110), navy],
                    startPoint: .top, endPoint: .bottom
                )
                RadialGradient(
                    colors: [teal.opacity(0.16), .clear],
                    center: .top, startRadius: 0, endRadius: 420
                )
            }
            .ignoresSafeArea()
        }
    }

    /// A rounded card surface in elevated navy, for grouping content on the
    /// brand background.
    struct Card<Content: View>: View {
        @ViewBuilder var content: Content
        var body: some View {
            content
                .padding()
                .frame(maxWidth: .infinity)
                .brandCard()
        }
    }
}

// MARK: - Reusable surface modifier

private struct BrandCard: ViewModifier {
    var cornerRadius: CGFloat
    func body(content: Content) -> some View {
        content
            .background(Theme.navyElevated,
                        in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Theme.hairline, lineWidth: 1)
            )
    }
}

extension View {
    /// Apply the standard brand card surface: elevated navy fill + hairline edge.
    func brandCard(cornerRadius: CGFloat = Theme.cornerRadius) -> some View {
        modifier(BrandCard(cornerRadius: cornerRadius))
    }

    /// Centre this view and cap it at a readable column width, so phone-first
    /// screens don't stretch edge to edge on iPad / in landscape.
    func readableWidth(_ maxWidth: CGFloat = Theme.contentMaxWidth) -> some View {
        self
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }

    /// Size a sheet for the iPad. Unmodified, an iPad sheet is a small form
    /// card in the middle of the screen, which on a 13" iPad leaves Stats or
    /// Settings a phone's width with a scrollbar. `.page` makes it a
    /// page-sized sheet instead. iOS 18+ only; a no-op on iPhone, where every
    /// sheet is full width anyway, and on older systems.
    @ViewBuilder
    func pageSizedSheet() -> some View {
        if #available(iOS 18.0, *) {
            self.presentationSizing(.page)
        } else {
            self
        }
    }

    /// A gentle repeating pulse on SF Symbols where supported (iOS 17+); a
    /// no-op on earlier systems so the call site stays clean.
    @ViewBuilder
    func symbolEffectPulseIfAvailable() -> some View {
        if #available(iOS 17.0, *) {
            self.symbolEffect(.pulse, options: .repeating)
        } else {
            self
        }
    }
}


// MARK: - Wide (iPad) layout

private struct WideLayoutKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True when the window is big enough to re-flow a screen rather than
    /// centre a phone column in it: at least `Theme.wideLayoutMinWidth` wide
    /// with a regular vertical size class. That is a full-screen iPad in
    /// either orientation, a big Stage Manager window, or a wide Mac window;
    /// never an iPhone (landscape is vertically compact), a Split View half
    /// on a smaller iPad, or Slide Over. Set once at the root by
    /// `WideLayoutReader`; it measures the window, not the sheet a view is in.
    var wideLayout: Bool {
        get { self[WideLayoutKey.self] }
        set { self[WideLayoutKey.self] = newValue }
    }
}

/// Measures the window's width at the root and publishes `wideLayout`. Width
/// rather than the horizontal size class alone: a Mac window and a 13" iPad
/// split in half both report `.regular` at widths the wide layouts do not fit.
private struct WideLayoutReader: ViewModifier {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .environment(\.wideLayout,
                         width >= Theme.wideLayoutMinWidth && verticalSizeClass == .regular)
    }
}

extension View {
    /// Publish `wideLayout` to everything below, measured on this view.
    func readsWideLayout() -> some View {
        modifier(WideLayoutReader())
    }
}

// MARK: - iPad window controls

extension View {
    /// Keep a custom top bar clear of the window controls (close, minimise,
    /// tile) that iPadOS 26 draws in the top-leading corner of a resizable
    /// window. A navigation bar moves aside for them by itself; the home
    /// screen's hand-made bar did not, and the controls sat on its Vail
    /// button. The bar drops below them rather than sliding right, because
    /// a narrow window has no width to spare. Zero everywhere else: full
    /// screen, iPhone, the Mac (whose controls live in the title bar), and
    /// before iPadOS 26.
    func clearsWindowControls() -> some View {
        modifier(WindowControlsClearance())
    }
}

private struct WindowControlsClearance: ViewModifier {
    @State private var top: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .padding(.top, top)
            .background(WindowControlsProbe(top: $top))
    }
}

/// Reads how far the window controls reach down into this view: the
/// vertically corner-adapted safe area (UIKit's `LayoutRegion`, iOS 26) less
/// the plain one. SwiftUI has no equivalent to read.
private struct WindowControlsProbe: UIViewRepresentable {
    @Binding var top: CGFloat

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.onChange = { top = $0 }
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onChange = { top = $0 }
    }

    final class ProbeView: UIView {
        var onChange: ((CGFloat) -> Void)?
        private var reported: CGFloat = 0

        override func layoutSubviews() {
            super.layoutSubviews()
            report()
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            report()
        }

        private func report() {
            guard #available(iOS 26.0, *) else { return }
            let adapted = edgeInsets(for: .safeArea(cornerAdaptation: .vertical)).top
            let plain = edgeInsets(for: .safeArea()).top
            let extra = max(0, (adapted - plain).rounded())
            guard extra != reported else { return }
            reported = extra
            // Out of the layout pass: this changes the padding it measured.
            Task { @MainActor [weak self] in self?.onChange?(extra) }
        }
    }
}
