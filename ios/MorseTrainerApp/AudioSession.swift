// AudioSession.swift
// One owner for the shared AVAudioSession, and one place that hears about it
// breaking.
//
// Before this file, five call sites configured the session independently —
// MorsePlayer, VoiceRecognizer (twice), CWDecoderEngine (twice) and KeyerEngine
// — and each of the recording ones restored "back to .playback" by hand on the
// way out. That works only while exactly one of them is up. Two overlapping
// subsystems race, and the loser silently gets a category it did not ask for:
// stop the CW decoder while voice answers are listening and the mic goes away
// mid-recognition, because the decoder's teardown hardcodes the restore.
//
// Nothing anywhere observed the three notifications that tell an audio app its
// world has changed, so:
//
//   * a phone call left the Listen loop advancing through items in silence —
//     the timers do not care that the audio stopped,
//   * unplugging headphones kept playing rather than pausing,
//   * a media-services reset left every engine dead with no recovery short of
//     force-quitting the app.
//
// The model here is a claim stack. A subsystem claims the profile it needs and
// releases it when done; the strongest live claim configures the session. That
// deletes the hand-rolled restores: when the recogniser releases, the session
// drops back to whatever is *still claimed* rather than to a hardcoded guess.
//
// Deliberately not an actor and not @MainActor. Claims have to be synchronous —
// CWDecoderEngine installs a tap on the input node immediately after claiming,
// and reads a zero-rate format if the category has not landed yet — and
// MorsePlayer claims from its initialiser, which is not main-actor isolated.
// A plain lock gives both. It is an NSLock rather than the os_unfair_lock
// wrapper used on the realtime paths precisely because it is held across the
// AVAudioSession calls, which cross to mediaserverd: that is a wait, not a spin.

import AVFoundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "audio-session")

final class AudioSession: @unchecked Sendable {

    static let shared = AudioSession()

    // MARK: - Profiles

    /// What a subsystem needs the session configured for. Ordered: the strongest
    /// live claim wins, so while the microphone is up the session stays
    /// record-capable no matter who else is playing tones.
    enum Profile: Int, Comparable, CustomStringConvertible {
        /// Tones and speech. Survives the screen locking, which is what makes
        /// hands-free Listen work (paired with UIBackgroundModes = audio).
        case playback = 0
        /// The Vail repeater: playback that *mixes* rather than taking the route,
        /// so it can run alongside a radio app.
        case repeaterMix = 1
        /// Microphone up — voice answers and the live CW decoder.
        case recording = 2

        static func < (a: Profile, b: Profile) -> Bool { a.rawValue < b.rawValue }

        var category: AVAudioSession.Category {
            self == .recording ? .playAndRecord : .playback
        }

        var mode: AVAudioSession.Mode {
            self == .recording ? .measurement : .default
        }

        var options: AVAudioSession.CategoryOptions {
            switch self {
            case .playback:    return [.duckOthers]
            case .repeaterMix: return [.mixWithOthers, .duckOthers]
            case .recording:   return [.duckOthers, .defaultToSpeaker, .allowBluetooth]
            }
        }

        var description: String {
            switch self {
            case .playback:    return "playback"
            case .repeaterMix: return "repeaterMix"
            case .recording:   return "recording"
            }
        }
    }

    /// Handle for a live claim. Release it when the subsystem stops needing the
    /// session. Holding one for the app's lifetime is legitimate — MorsePlayer
    /// does, which is what makes `.playback` the effective resting state.
    struct Claim: Hashable {
        fileprivate let id: UUID
    }

    // MARK: - Events

    /// Something happened to the session that the audio subsystems must react to.
    enum Event: Sendable {
        /// A call or another app took the session. Playback has *already*
        /// stopped; this is notification, not permission.
        case interruptionBegan
        /// The interruption ended. `shouldResume` carries the system's hint —
        /// false means another app is still holding audio and we should stay put.
        case interruptionEnded(shouldResume: Bool)
        /// The route we were playing to went away: headphones unplugged, or a
        /// Bluetooth device disconnected. The iOS convention is to pause rather
        /// than blast out of the speaker.
        case routeLost
        /// The media server restarted. Every engine, node and tap in the process
        /// is dead and has to be rebuilt from scratch.
        case mediaServicesWereReset
    }

    /// Handle for an event subscription. Long-lived subsystems can simply never
    /// remove theirs; anything shorter-lived should.
    struct Observation: Hashable {
        fileprivate let id: Int
    }

    // MARK: - State

    private let lock = NSLock()
    private var claims: [Claim: Profile] = [:]
    /// Last profile successfully pushed to the session, so a repeat claim of the
    /// same profile is a no-op. Re-poking the session on every play was causing
    /// intermittent dropouts, which is why MorsePlayer only ever configured once.
    private var applied: Profile?
    private var observers: [Observation: @MainActor @Sendable (Event) -> Void] = [:]
    private var nextObservationID = 0
    private var resets = 0

    /// Bumped every time the media server restarts. Anything holding audio
    /// objects can compare this against the value it last built at and rebuild
    /// lazily, which is cheaper to reason about than a callback: a class that is
    /// not `Sendable` — an engine wrapper, typically — cannot be captured in an
    /// event handler without dragging the whole type across an isolation
    /// boundary. See MorsePlayer.activate().
    var resetGeneration: Int {
        lock.lock(); defer { lock.unlock() }
        return resets
    }

    private init() {
        let center = NotificationCenter.default

        // Each block pulls the Sendable payload out on whatever thread the
        // notification arrives on, then hops. The Notification itself is not
        // Sendable and must not cross.
        center.addObserver(forName: AVAudioSession.interruptionNotification,
                           object: nil, queue: nil) { note in
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let options = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            AudioSession.shared.handleInterruption(type: type, options: options)
        }

        center.addObserver(forName: AVAudioSession.routeChangeNotification,
                           object: nil, queue: nil) { note in
            let reason = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            AudioSession.shared.handleRouteChange(reason: reason)
        }

        center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
                           object: nil, queue: nil) { _ in
            AudioSession.shared.handleMediaServicesReset()
        }
    }

    // MARK: - Claims

    /// Configure the session for `profile` and keep it there until the returned
    /// claim is released. Synchronous: on return the category is in effect, so a
    /// caller may install a tap or start an engine on the next line.
    @discardableResult
    func claim(_ profile: Profile) -> Claim {
        let claim = Claim(id: UUID())
        lock.lock()
        claims[claim] = profile
        applyLocked()
        lock.unlock()
        return claim
    }

    /// Give up a claim. The session falls back to the strongest one still held —
    /// or to `.playback`, the resting state, if this was the last.
    func release(_ claim: Claim) {
        lock.lock()
        claims[claim] = nil
        applyLocked()
        lock.unlock()
    }

    /// Push the strongest live claim to the session. Caller holds `lock`.
    private func applyLocked() {
        let wanted = claims.values.max() ?? .playback
        guard wanted != applied else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(wanted.category, mode: wanted.mode, options: wanted.options)
            // No `.notifyOthersOnDeactivation`, and no deactivation on release:
            // that option explicitly hands audio focus back to other apps, which
            // was killing background/locked-screen playback in hands-free Listen
            // mode. The session stays ours until the app goes away.
            try session.setActive(true)
            applied = wanted
            log.info("Audio session → \(wanted.description, privacy: .public)")
        } catch {
            // Leave `applied` alone so the next claim retries rather than
            // believing a configuration that never landed.
            log.error("Audio session → \(wanted.description, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Force the next claim to reconfigure even if the profile is unchanged.
    /// Used after the session has been reset out from under us.
    private func invalidateLocked() {
        applied = nil
    }

    // MARK: - Events

    /// Subscribe to session events. `handler` runs on the main actor.
    @discardableResult
    func observe(_ handler: @escaping @MainActor @Sendable (Event) -> Void) -> Observation {
        lock.lock()
        nextObservationID += 1
        let token = Observation(id: nextObservationID)
        observers[token] = handler
        lock.unlock()
        return token
    }

    func removeObserver(_ token: Observation) {
        lock.lock()
        observers[token] = nil
        lock.unlock()
    }

    private func broadcast(_ event: Event) {
        lock.lock()
        let handlers = Array(observers.values)
        lock.unlock()
        // Hop once, then run them all in order. Handlers touch main-actor state
        // (the listen loop, the published decoder flags), so this is where they
        // belong regardless of which thread the notification arrived on.
        Task { @MainActor in
            for handler in handlers { handler(event) }
        }
    }

    private func handleInterruption(type rawType: UInt?, options rawOptions: UInt?) {
        guard let rawType, let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }
        switch type {
        case .began:
            log.info("Audio session interrupted")
            // The session is no longer ours; whatever we push next has to be a
            // fresh configuration, not a no-op against a stale cache.
            lock.lock(); invalidateLocked(); lock.unlock()
            broadcast(.interruptionBegan)
        case .ended:
            let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions ?? 0)
            let shouldResume = options.contains(.shouldResume)
            log.info("Audio session interruption ended, shouldResume=\(shouldResume)")
            if shouldResume {
                // Re-establish the category *before* anyone tries to restart an
                // engine on it.
                lock.lock(); applyLocked(); lock.unlock()
            }
            broadcast(.interruptionEnded(shouldResume: shouldResume))
        @unknown default:
            break
        }
    }

    private func handleRouteChange(reason rawReason: UInt?) {
        guard let rawReason,
              let reason = AVAudioSession.RouteChangeReason(rawValue: rawReason) else { return }
        // Only the disappearing route matters. `.newDeviceAvailable` and the
        // category-change echo of our own `setCategory` are noise here — acting
        // on the latter would pause the app every time it claimed the session.
        guard reason == .oldDeviceUnavailable else { return }
        log.info("Audio route lost")
        broadcast(.routeLost)
    }

    private func handleMediaServicesReset() {
        log.error("Media services were reset; rebuilding audio")
        lock.lock()
        resets += 1
        invalidateLocked()
        // Reconfigure from the claims we still hold before telling anyone to
        // rebuild, so the engines come up against a live session.
        applyLocked()
        lock.unlock()
        broadcast(.mediaServicesWereReset)
    }
}
