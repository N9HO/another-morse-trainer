package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncStateCodec
import app.anothermorsetrainer.morsekit.SyncStreak
import org.json.JSONObject
import java.time.LocalDate

/**
 * [SyncLocal] over the app's own stores: session history, lifetime counters
 * and the ledger in [Stats]; the five synced state keys in [JourneyStore],
 * [EngineStore], [FirstFourStore], [OperatingProcedureStore] and the story
 * bookmarks in [Settings]. Each `applySynced` writes without stamping, so a
 * value adopted from the server is not sent straight back.
 */
object AppSyncLocal : SyncLocal {
    override fun historyRecords(): List<SessionRecord> = Stats.history

    override fun ledgerDays(): Map<LocalDate, Int> = Stats.activity.days

    override fun mergeSessions(pulled: List<SessionRecord>): Boolean = Stats.mergeSyncedSessions(pulled)

    override fun adoptAggregates(aggregates: SyncMerge.Aggregates) = Stats.adoptServerTotals(aggregates)

    override fun adoptDays(server: Map<LocalDate, Int>) = Stats.adoptServerDays(server)

    override fun adoptStreak(server: SyncStreak.Server) = Stats.adoptServerStreak(server)

    override fun stateValue(key: String): JSONObject? = when (key) {
        SyncStateCodec.JOURNEY -> JourneyStore.syncValue()
        SyncStateCodec.CHARACTERS -> EngineStore.syncValue()
        SyncStateCodec.FIRST_FOUR -> FirstFourStore.syncValue()
        SyncStateCodec.OPERATING_PROCEDURE -> OperatingProcedureStore.syncValue()
        SyncStateCodec.STORY_BOOKMARKS -> Settings.storyBookmarksSyncValue()
        else -> null
    }

    override fun applyState(key: String, value: JSONObject) {
        when (key) {
            SyncStateCodec.JOURNEY -> JourneyStore.applySynced(value)
            SyncStateCodec.CHARACTERS -> EngineStore.applySynced(value)
            SyncStateCodec.FIRST_FOUR -> FirstFourStore.applySynced(value)
            SyncStateCodec.OPERATING_PROCEDURE -> OperatingProcedureStore.applySynced(value)
            SyncStateCodec.STORY_BOOKMARKS -> Settings.applySyncedStoryBookmarks(value)
        }
    }
}
