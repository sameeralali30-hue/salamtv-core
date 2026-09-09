package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import tv.own.owntv.core.database.entity.AdvertImpressionEntity
import tv.own.owntv.core.database.entity.AdvertLedgerEntity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  استعلامات السقوف والدفتر.
 * ───────────────────────────────────────────────────────────────────────────
 *  دوالّ العدّ هنا هي **دلالة السقوف حرفيّاً**: `cap_scope` يقرّر أيّ الدالّتين
 *  تُستدعى، و`cap_window` يقرّر أيّ عمودِ تاريخٍ يُقارَن. لا منطق في مكانٍ آخر.
 *
 *  ⚠ الصفوف التي `outcome = 'error'` تُحتسب في السقف عمداً: عطلٌ عندنا لا
 *    يُعاقَب به المستخدم بإعادة المحاولة عليه في كلّ قناة يفتحها. ولا تُحتسب
 *    في تقرير المعلن — ذلك ترشيحٌ يقع على الخادم.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Dao
interface AdvertDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: AdvertImpressionEntity)

    // ── السقوف: مفتاح تاريخٍ واحد لكلّ نافذة، فيُستعمل الفهرس ولا تُطبَّق دالّة ──

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND dayKey = :key")
    suspend fun countByDay(profileId: Long, spotId: Long, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND weekKey = :key")
    suspend fun countByWeek(profileId: Long, spotId: Long, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND monthKey = :key")
    suspend fun countByMonth(profileId: Long, spotId: Long, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId")
    suspend fun countEver(profileId: Long, spotId: Long): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND sessionId = :sessionId")
    suspend fun countBySession(profileId: Long, spotId: Long, sessionId: String): Int

    // ── نفس الخمس، مقيّدةً بقناةٍ واحدة (cap_scope = per_channel) ──

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND channelRemoteId = :channel AND dayKey = :key")
    suspend fun countByDayChannel(profileId: Long, spotId: Long, channel: String, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND channelRemoteId = :channel AND weekKey = :key")
    suspend fun countByWeekChannel(profileId: Long, spotId: Long, channel: String, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND channelRemoteId = :channel AND monthKey = :key")
    suspend fun countByMonthChannel(profileId: Long, spotId: Long, channel: String, key: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND channelRemoteId = :channel")
    suspend fun countEverChannel(profileId: Long, spotId: Long, channel: String): Int

    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId AND channelRemoteId = :channel AND sessionId = :sessionId")
    suspend fun countBySessionChannel(profileId: Long, spotId: Long, channel: String, sessionId: String): Int

    // ── الفواصل ──

    /** آخر مرّةٍ عُرض فيها هذا الإعلان — لفحص `cooldown_secs`. */
    @Query("SELECT MAX(shownAt) FROM advert_impressions WHERE profileId = :profileId AND spotId = :spotId")
    suspend fun lastShownAt(profileId: Long, spotId: Long): Long?

    /** آخر مرّةٍ عُرض فيها **أيّ** إعلان — لفحص `adv_min_gap_secs`. */
    @Query("SELECT MAX(shownAt) FROM advert_impressions WHERE profileId = :profileId")
    suspend fun lastAnyShownAt(profileId: Long): Long?

    /** كم إعلاناً عُرض في هذه الجلسة — لفحص `adv_max_per_session`. */
    @Query("SELECT COUNT(*) FROM advert_impressions WHERE profileId = :profileId AND sessionId = :sessionId")
    suspend fun countSessionAll(profileId: Long, sessionId: String): Int

    // ── الختم والتقارير ──

    @Query("UPDATE advert_impressions SET outcome = :outcome, watchedMs = :watchedMs WHERE eventUid = :uid")
    suspend fun finish(uid: String, outcome: String, watchedMs: Long)

    /**
     * ② ما بقي بلا نتيجة من تشغيلٍ سابق يُختم `aborted`.
     *
     * يُستدعى مرّةً عند الإقلاع. القيد على [sessionId] لا على `outcome` وحده:
     * صفٌّ جارٍ في **هذه** الجلسة ليس مهجوراً بل قيد العرض الآن.
     */
    @Query("UPDATE advert_impressions SET outcome = 'aborted' WHERE outcome IS NULL AND sessionId != :sessionId")
    suspend fun sealAbandoned(sessionId: String)

    @Query("SELECT * FROM advert_impressions WHERE reported = 0 AND outcome IS NOT NULL ORDER BY shownAt LIMIT :limit")
    suspend fun unreported(limit: Int): List<AdvertImpressionEntity>

    @Query("UPDATE advert_impressions SET reported = 1 WHERE eventUid IN (:uids)")
    suspend fun markReported(uids: List<String>)

    /** صندوقٌ محدود: ما لم يُرسَل خلال أسبوعين يُهمل بدل أن ينمو بلا سقف. */
    @Query("DELETE FROM advert_impressions WHERE shownAt < :before")
    suspend fun prune(before: Long)

    // ── الدفتر ──

    @Upsert
    suspend fun upsertLedger(row: AdvertLedgerEntity)

    @Query("SELECT * FROM advert_ledger WHERE profileId = :profileId AND dayKey = :dayKey LIMIT 1")
    suspend fun ledger(profileId: Long, dayKey: String): AdvertLedgerEntity?

    @Query("DELETE FROM advert_ledger WHERE dayKey < :before")
    suspend fun pruneLedger(before: String)
}
