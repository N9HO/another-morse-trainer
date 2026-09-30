package app.anothermorsetrainer

/**
 * Copy that exists only on desktop: what the app says where the desktop does
 * something differently from the phone apps. Kept apart from
 * values/strings.xml (the file forked from android/) so the shared-origin copy
 * and the desktop-only copy can be told apart at a glance.
 */
object DesktopCopy {
    /** Shown wherever a ranked mode would submit to the shared leaderboard. */
    const val UNRANKED_NOTE =
        "Desktop scores are unranked. The shared leaderboard only ranks runs a phone can prove came " +
            "from a genuine install, and a desktop app has no way to prove that, so runs here count " +
            "toward your personal bests but are not submitted."

    /** Short form, for a post-run line. */
    const val UNRANKED_SHORT = "Not ranked: desktop scores stay on this computer."

    const val COPY_IMAGE = "Copy image"
    const val SAVE_IMAGE = "Save image…"
    const val COPIED = "Copied to the clipboard"
    const val SAVED = "Saved"

    /** Under the hardware-key settings: what a desktop can and cannot connect. */
    const val KEY_CONNECT_HINT =
        "Plug in a USB MIDI key such as the Vail Adapter; the keyboard works too. Bluetooth MIDI " +
            "keys are not supported on Windows or Linux."

    /** In place of the voice-answer switch. */
    const val VOICE_UNAVAILABLE = "Spoken answers are not available on desktop."

    /** In place of the daily reminder switch. */
    const val REMINDER_UNAVAILABLE =
        "Daily reminders are not available on desktop yet: the app cannot schedule a notification " +
            "while it is closed."

    /** Keyboard help shown on keyed screens. */
    const val KEYBOARD_KEY_HINT = "Keyboard: Space is a straight key; [ and ] are the dit and dah paddles."
}
