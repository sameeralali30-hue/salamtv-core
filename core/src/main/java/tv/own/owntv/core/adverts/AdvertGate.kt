package tv.own.owntv.core.adverts

import android.os.SystemClock
import android.util.Log
import tv.own.owntv.core.database.dao.AdvertDao
import tv.own.owntv.core.database.entity.AdvertImpressionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  البوّابة: هل يُعرض إعلانٌ الآن، وأيّه؟
 * ───────────────────────────────────────────────────────────────────────────
 *  ⚠ هذا الكود يقع بين ضغطة المستخدم على قناةٍ وبين ظهورها. **لا يجوز أن
 *    ينتظر الشبكة هنا أبداً** — لا مرّة، ولا في حالةٍ نادرة. كلّ ما يحتاجه
 *    محفوظٌ سلفاً: القواعد من آخر نبضة، والعدّ من قاعدة محلّيّة مفهرسة،
 *    والملفّ منزَّلٌ ومتحقَّقٌ منه.
 *
 *  ═══ ستّة قرارات ═══
 *
 *  ① لا إعلان عند التنقّل بـCH±.
 *     ⚠ هذه القاعدة وحدها هي الفرق بين ميزةٍ محتملة وموجةِ إلغاءات. من يمرّ
 *       على عشرين قناة بحثاً عن مباراة يجب ألّا يصطدم بعشرين إعلاناً — ولا
 *       بواحد. الإعلان لاختيارٍ قاصد، لا لمرورٍ عابر.
 *
 *  ② إعلانٌ بلا ملفٍّ مكتمل لا يُعرض، ولا يُنتظر.
 *     بثّ الإعلان لحظة الفتح يعيد بالضبط التأخير الذي وُجدت هذه البوّابة
 *     لتفاديه. الملفّ حاضرٌ أو لا إعلان.
 *
 *  ③ العدّ بمفتاح تاريخٍ محفوظ لا بدالّة.
 *     `WHERE dayKey = ?` يستعمل الفهرس؛ `WHERE date(shownAt) = ...` يمسح
 *     الجدول. الفرق لا يُلاحَظ بمئة صفّ ويُلاحَظ بعشرة آلاف.
 *
 *  ④ واحدٌ لكلّ فتحة، مهما تعدّدت المؤهّلات.
 *     الأعلى أولويّةً يفوز؛ وعند التساوي قرعةٌ موزونة بالوزن. وفوق ذلك فاصلٌ
 *     عامّ ([AdvertPolicy.minGapSecs]) يمنع إعلانين بسقفٍ يوميّ ١ من التتالي
 *     في فتحتين متلاحقتين.
 *
 *  ⑤ [decide] لا تكتب شيئاً، و[begin] هي التي تكتب.
 *     ⚠ الفصل مقصود: [wouldFire] تُستدعى من مسار المعاينة الذي يجري عشرات
 *       المرّات أثناء التصفّح، وكتابةُ صفٍّ في كلّ مرّة كانت ستستهلك السقف
 *       بمجرّد المرور بالمؤشّر على القناة.
 *
 *  ⑥ الصفّ يُكتب عند **بداية** العرض.
 *     ⚠ لو كُتب عند نهايته لكان الإغلاق القسريّ أثناء الإعلان يترك السقف
 *       غير مستهلَك، فيتكرّر الإعلان — أي أنّنا نُعلّم المستخدم أنّ إغلاق
 *       التطبيق قسراً يُخلّصه منه.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AdvertGate(
    private val repository: AdvertRepository,
    private val media: AdvertMediaCache,
    private val dao: AdvertDao,
    /** false على نكهة المتجر: الميزة تُحذف من البناء بالكامل. */
    private val compiledIn: Boolean,
) {

    /** يتبدّل مع كلّ تشغيلٍ للعمليّة — نافذة `session`. */
    val sessionId: String = UUID.randomUUID().toString()

    /**
     * ⑤ القرار، بلا أثرٍ جانبيّ.
     *
     * @param channelRemoteId معرّف القناة **كما تعرفه اللوحة** لا مفتاح Room.
     * @param categoryRemoteId معرّف فئتها كما تعرفه اللوحة، أو null.
     */
    suspend fun decide(
        profileId: Long,
        channelRemoteId: String,
        categoryRemoteId: String?,
        reason: TuneReason,
        channelName: String = "",
        placement: Placement = Placement.ON_TUNE,
    ): AdvertDecision? {
        if (!compiledIn) return null

        // ① المرور العابر لا يُعلَن عليه، ولا الاستئناف بعد إعلانٍ للتوّ.
        if (reason == TuneReason.ZAP || reason == TuneReason.RESUME) {
            Log.d(TAG, "no advert: $reason")
            return null
        }

        val policy = repository.policy.value
        if (!policy.enabled || policy.spots.isEmpty()) return null

        val now = System.currentTimeMillis()

        // ④ الفاصل العامّ بين إعلانين، أيّاً كانا.
        if (policy.minGapSecs > 0) {
            val last = dao.lastAnyShownAt(profileId) ?: 0L
            if (last > 0 && now - last < policy.minGapSecs * 1000L) {
                Log.d(TAG, "no advert: min gap (${(now - last) / 1000}s < ${policy.minGapSecs}s)")
                return null
            }
        }

        if (policy.maxPerSession > 0 &&
            dao.countSessionAll(profileId, sessionId) >= policy.maxPerSession
        ) {
            Log.d(TAG, "no advert: session cap ${policy.maxPerSession}")
            return null
        }

        val eligible = policy.spots.filter {
            eligible(it, profileId, channelRemoteId, categoryRemoteId, placement, now)
        }
        if (eligible.isEmpty()) return null

        val chosen = pick(eligible) ?: return null
        val path = media.localPath(chosen) ?: return null   // ② تحقّقٌ أخير

        Log.i(TAG, "advert chosen: #${chosen.id} '${chosen.title}' for channel $channelRemoteId")
        return AdvertDecision(
            spot = chosen,
            placement = placement,
            localPath = path,
            channelRemoteId = channelRemoteId,
            channelName = channelName,
            eventUid = newEventUid(),
        )
    }

    /**
     * هل كان إعلانٌ سيُعرض لهذه القناة الآن؟
     *
     * يُستعمل لكبح المعاينة الصامتة: لولاه لرأى المشاهد بثّ القناة المستهدفة
     * بمجرّد أن يستقرّ المؤشّر عليها، فلا يحتاج إلى الضغط أصلاً — ويسقط
     * الإعلان كلّه بالتحويم.
     *
     * ومشروطٌ بالأهليّة الكاملة لا بـ«القناة مستهدفة»: متى استُهلك سقف اليوم
     * عادت المعاينة، فالكلفة متناسبة مع ما يُجنى.
     */
    suspend fun wouldFire(
        profileId: Long,
        channelRemoteId: String,
        categoryRemoteId: String?,
    ): Boolean {
        if (!compiledIn) return false
        val policy = repository.policy.value
        if (!policy.enabled || policy.spots.isEmpty()) return false

        val now = System.currentTimeMillis()
        return policy.spots.any {
            eligible(it, profileId, channelRemoteId, categoryRemoteId, Placement.ON_TUNE, now) &&
                media.localPath(it) != null
        }
    }

    /**
     * ⑥ يُسجّل بدء العرض ويعيد معرّف الحدث.
     *
     * يُستدعى **قبل** أن يبدأ التشغيل لا بعده. `outcome` يبقى null حتّى
     * [finish]، وما بقي null من جلسةٍ سابقة يُختم `aborted` عند الإقلاع.
     */
    suspend fun begin(decision: AdvertDecision, profileId: Long) {
        val now = System.currentTimeMillis()
        dao.insert(
            AdvertImpressionEntity(
                eventUid = decision.eventUid,
                profileId = profileId,
                spotId = decision.spot.id,
                campaignId = decision.spot.campaignId,
                channelRemoteId = decision.channelRemoteId,
                placement = decision.placement.wire,
                shownAt = now,
                elapsedRealtime = SystemClock.elapsedRealtime(),
                dayKey = dayKey(now),
                weekKey = weekKey(now),
                monthKey = monthKey(now),
                sessionId = sessionId,
                grantedMinutes = decision.spot.grantMinutes,
            ),
        )
    }

    /** يختم عرضاً بدأ. */
    suspend fun finish(eventUid: String, outcome: AdvertOutcome, watchedMs: Long) {
        dao.finish(eventUid, outcome.wire, watchedMs)
    }

    /** يُختم ما بقي معلّقاً من تشغيلٍ سابق — يُستدعى مرّةً عند الإقلاع. */
    suspend fun sealAbandoned() = dao.sealAbandoned(sessionId)

    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun eligible(
        spot: AdvertSpot,
        profileId: Long,
        channelRemoteId: String,
        categoryRemoteId: String?,
        placement: Placement,
        now: Long,
    ): Boolean {
        if (placement !in spot.placements) return false
        if (!matchesTarget(spot.target, channelRemoteId, categoryRemoteId)) return false

        if (spot.cap.cooldownSecs > 0) {
            val last = dao.lastShownAt(profileId, spot.id) ?: 0L
            if (last > 0 && now - last < spot.cap.cooldownSecs * 1000L) return false
        }

        return count(spot, profileId, channelRemoteId, now) < spot.cap.max
    }

    /** ③ العدّ: `cap_scope` يختار الدالّة، و`cap_window` يختار المفتاح. */
    private suspend fun count(spot: AdvertSpot, profileId: Long, channel: String, now: Long): Int {
        val perChannel = spot.cap.scope == CapScope.PER_CHANNEL
        return when (spot.cap.window) {
            CapWindow.DAY ->
                if (perChannel) dao.countByDayChannel(profileId, spot.id, channel, dayKey(now))
                else dao.countByDay(profileId, spot.id, dayKey(now))
            CapWindow.WEEK ->
                if (perChannel) dao.countByWeekChannel(profileId, spot.id, channel, weekKey(now))
                else dao.countByWeek(profileId, spot.id, weekKey(now))
            CapWindow.MONTH ->
                if (perChannel) dao.countByMonthChannel(profileId, spot.id, channel, monthKey(now))
                else dao.countByMonth(profileId, spot.id, monthKey(now))
            CapWindow.EVER ->
                if (perChannel) dao.countEverChannel(profileId, spot.id, channel)
                else dao.countEver(profileId, spot.id)
            CapWindow.SESSION ->
                if (perChannel) dao.countBySessionChannel(profileId, spot.id, channel, sessionId)
                else dao.countBySession(profileId, spot.id, sessionId)
        }
    }

    private fun matchesTarget(t: AdvertTarget, channel: String, category: String?): Boolean = when (t.mode) {
        "channels"   -> channel.isNotBlank() && channel in t.channels
        "categories" -> category != null && category in t.categories
        else         -> true
    }

    /** ④ الأعلى أولويّةً، ثمّ قرعةٌ موزونة، ثمّ الأقدم معرّفاً — ترتيبٌ حاسم. */
    private fun pick(candidates: List<AdvertSpot>): AdvertSpot? {
        if (candidates.isEmpty()) return null
        val top = candidates.maxOf { it.priority }
        val tier = candidates.filter { it.priority == top }.sortedBy { it.id }
        if (tier.size == 1) return tier.first()

        val total = tier.sumOf { it.weight.toLong() }
        if (total <= 0L) return tier.first()
        var roll = Random.nextLong(total)
        for (s in tier) {
            roll -= s.weight
            if (roll < 0) return s
        }
        return tier.last()
    }

    private fun newEventUid(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    companion object {
        private const val TAG = "SalamTVAds"

        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

        /* التوقيت المحلّيّ للجهاز عمداً: «مرّة كلّ يوم» يعني يومَ المشاهد لا
           يومَ الخادم. مشاهدٌ في الرياض يبدأ يومه قبل مشاهدٍ في الرباط بساعتين،
           وحسابُ ذلك بـUTC يجعل السقف يتجدّد في منتصف مسائه. */
        fun dayKey(ms: Long): String =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DAY)

        fun monthKey(ms: Long): String =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(MONTH)

        /** أسبوع ISO — الاثنين أوّله، وسنته قد تخالف سنة التاريخ في الحدود. */
        fun weekKey(ms: Long): String {
            val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            val wf = WeekFields.ISO
            return "%d-W%02d".format(d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear()))
        }
    }
}
