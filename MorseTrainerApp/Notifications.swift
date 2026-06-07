import Foundation
import UserNotifications

/// A single repeating local notification that nudges the learner to practice so
/// their streak (#20) stays alive. Local-only — no push entitlement required.
enum PracticeReminders {
    private static let identifier = "MorseTrainer.dailyReminder"

    /// Ask the user for notification permission. `completion` is called on the
    /// main queue with whether it was granted.
    static func requestAuthorization(_ completion: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                DispatchQueue.main.async { completion(granted) }
            }
    }

    /// Schedule (replacing any existing) a daily reminder at `hour` local time.
    static func schedule(hour: Int) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])

        let content = UNMutableNotificationContent()
        content.title = "Keep your streak alive"
        content.body = "A quick Morse session today keeps your practice streak going."
        content.sound = .default

        var when = DateComponents()
        when.hour = hour
        when.minute = 0
        let trigger = UNCalendarNotificationTrigger(dateMatching: when, repeats: true)
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
    }

    /// Remove the daily reminder.
    static func cancel() {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [identifier])
    }
}
