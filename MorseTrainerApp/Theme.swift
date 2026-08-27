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
