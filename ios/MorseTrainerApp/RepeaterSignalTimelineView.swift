import SwiftUI

/// A scrolling activity timeline for the live repeater: keyed tone bursts (sent
/// and received) drawn as bars on a time axis, plus chat markers. Shows the most
/// recent `window` seconds, advancing in real time. An AMT-styled functional
/// reimplementation of vail-ios's SignalTimelineView (which is bound to that
/// app's theme-token system).
struct RepeaterSignalTimelineView: View {
    @EnvironmentObject var model: RepeaterModel

    /// Seconds of history shown across the width.
    private let window: Double = 12

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
            let now = Int64(context.date.timeIntervalSince1970 * 1000)
            GeometryReader { geo in
                Canvas { ctx, size in
                    draw(into: &ctx, size: size, nowMs: now)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
        .frame(height: 64)
        .padding(10)
        .brandCard()
        .overlay(alignment: .topLeading) {
            Text("ACTIVITY")
                .font(.system(size: 9, weight: .bold)).tracking(1.5)
                .foregroundStyle(Theme.textSecondary)
                .padding(8)
        }
        .accessibilityLabel("Activity timeline")
    }

    private func draw(into ctx: inout GraphicsContext, size: CGSize, nowMs: Int64) {
        let windowMs = window * 1000
        let startMs = Double(nowMs) - windowMs
        let midY = size.height / 2

        // Centre divider: sent above, received below.
        var divider = Path()
        divider.move(to: CGPoint(x: 0, y: midY))
        divider.addLine(to: CGPoint(x: size.width, y: midY))
        ctx.stroke(divider, with: .color(Theme.hairline), lineWidth: 1)

        func x(forMs ms: Int64) -> Double {
            (Double(ms) - startMs) / windowMs * size.width
        }

        for event in model.signalEvents {
            guard Double(event.endLocalMs) >= startMs else { continue }
            let isSent = event.origin == .sent
            let color = isSent ? Theme.teal : Theme.tealBright
            switch event.kind {
            case let .tone(durationMs, _):
                let x0 = max(0, x(forMs: event.startLocalMs))
                let x1 = min(size.width, x(forMs: event.startLocalMs + Int64(durationMs)))
                guard x1 > x0 else { continue }
                let barH: Double = 10
                let y = isSent ? midY - barH - 3 : midY + 3
                let rect = CGRect(x: x0, y: y, width: max(2, x1 - x0), height: barH)
                ctx.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(color))
            case .chat:
                let cx = x(forMs: event.startLocalMs)
                guard cx >= 0, cx <= size.width else { continue }
                let dot = CGRect(x: cx - 2.5, y: midY - 2.5, width: 5, height: 5)
                ctx.fill(Path(ellipseIn: dot), with: .color(Color.orange))
            }
        }

        // Live, growing bars for keys held down right now (own transmission).
        for begin in model.liveOwnKeyStarts {
            let x0 = max(0, x(forMs: begin))
            let x1 = min(size.width, x(forMs: nowMs))
            guard x1 > x0 else { continue }
            let barH: Double = 10
            let rect = CGRect(x: x0, y: midY - barH - 3, width: max(2, x1 - x0), height: barH)
            ctx.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(Theme.tealBright))
        }
    }
}
