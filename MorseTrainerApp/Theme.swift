import SwiftUI

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
