package tv.own.owntv.core.adverts

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.AdvertDao
import tv.own.owntv.core.database.entity.AdvertLedgerEntity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  دفتر الاستحقاق: ما يشتريه الإعلان من وقت المشاهدة.
 * ───────────────────────────────────────────────────────────────────────────
 *  للمشترك المجّانيّ وحده. المدفوع لا رصيد له ولا معنى لحسابه.
 *
 *  المعادلة كلّها سطر:
 *
 *      المتاح = (الرصيد اليوميّ + ما منحته الإعلانات) − ما استُهلك
 *
 *  ═══ ستّة قرارات ═══
 *
 *  ① رصيدٌ يوميّ يسبق أوّل إعلان.
 *     ⚠ لو بدأ المستخدم من صفر لكان أوّل ما يراه بعد تثبيت التطبيق إعلاناً
 *       قبل أن يرى قناةً واحدة. وهو أسوأ انطباعٍ أوّل ممكن، ويُقاس أثره في
 *       عمليّات الحذف لا في الشكاوى.
 *
 *       وهو أيضاً ما يجعل انقطاع الشبكة غير معاقَب: من لا يستطيع الوصول
 *       إلينا لا يستطيع مشاهدة إعلانٍ أصلاً، فمعاقبته على ذلك عطلٌ نُلقيه
 *       على من لا ذنب له. صفرٌ هنا يجعل النظام صارماً، والقرار للمشغّل.
 *
 *  ② السقف اليوميّ للباقة يعلو الرصيد.
 *     من استهلك سقفه لا يشتري المزيد بإعلانٍ آخر. وإلّا صار السقف اقتراحاً،
 *     وصار المستخدم الأثقل هو الأغلى علينا — وهو عكس المقصود.
 *
 *  ③ الاستهلاك يُحسب بالدقيقة المشاهَدة لا بفتح القناة.
 *     من يفتح عشر قنوات في دقيقة يستهلك دقيقة، لا عشراً.
 *
 *  ④ اليوم يوم الجهاز.
 *     «تسعون دقيقة يوميّاً» تعني يوم المشاهد. حسابُه بـUTC يجعل الرصيد
 *     يتجدّد في منتصف مساء من يسكن شرق غرينتش.
 *
 *  ⑤ الخادم يصحّح ولا يحكم لحظيّاً.
 *     الجهاز هو المرجع في اللحظة — بلا شبكة وبلا تأخير — والتقرير يعيد
 *     الرصيد المعتمَد فيُصحَّح. فالانحراف محدودٌ بدورة نبضٍ واحدة لا مفتوح.
 *
 *  ⑥ قفلٌ واحد.
 *     المؤقّت يكتب كلّ دقيقة، والإعلان يمنح، والتقرير يصحّح — ثلاثة كتّاب
 *     على صفٍّ واحد. بلا قفل تضيع منحةٌ بين قراءةٍ وكتابة.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AdvertLedger(
    private val dao: AdvertDao,
    private val repository: AdvertRepository,
) {
    private val gate = Mutex()   // ⑥

    private val _balance = MutableStateFlow(UNKNOWN)

    /** الدقائق المتاحة الآن، أو [UNKNOWN] قبل أوّل قراءة. */
    val balance: StateFlow<Int> = _balance.asStateFlow()

    /** هل هذا المشترك مجّانيّ أصلاً؟ المدفوع لا يمرّ بشيء من هذا. */
    /* ═══ الدفتر يتوقّف مع المفتاح العامّ ═══

       كان الشرط `isFree` وحده، والنتيجة عكسُ ما وُضع مفتاحُ القتل لأجله:
       تُطفئ النظام، فيستمرّ عدّاد المجّانيّ في الاستهلاك، وعند الصفر يُطلب
       إعلانٌ وسطيّ فترفضه البوّابة (النظام مطفأ) — فتتوقّف المشاهدة عند
       «نفد وقتك» **بلا مخرج**، لأنّ الإعلان الذي يشتري الوقت معطَّل.

       ⚠ أي أنّ مفتاح الطوارئ كان يحبس المستخدم بدل أن يحرّره. والقاعدة
         المعلنة في الخطة عكسه: «عطلٌ عندنا لا يُعاقَب به المستخدم».

       فمع الإطفاء لا دفتر ولا عدّاد ولا سقف: وصولٌ غير محدود حتى يعود. */
    val applies: Boolean get() = repository.policy.value.let { it.isFree && it.enabled }

    /** يقرأ الرصيد ويُحدّث [balance]. */
    suspend fun refresh(profileId: Long): Int = gate.withLock { compute(profileId) }

    /** ③ يسجّل دقائق مشاهدة، ويعيد الرصيد بعدها. */
    suspend fun consume(profileId: Long, minutes: Int): Int = gate.withLock {
        if (minutes <= 0) return@withLock compute(profileId)
        val day = AdvertGate.dayKey(System.currentTimeMillis())
        val row = dao.ledger(profileId, day) ?: AdvertLedgerEntity(profileId, day)
        dao.upsertLedger(
            row.copy(consumedMinutes = row.consumedMinutes + minutes, updatedAt = System.currentTimeMillis()),
        )
        compute(profileId)
    }

    /** يمنح ما اشتراه إعلانٌ اكتمل، ويعيد الرصيد بعده. */
    suspend fun grant(profileId: Long, minutes: Int): Int = gate.withLock {
        if (minutes <= 0) return@withLock compute(profileId)
        val day = AdvertGate.dayKey(System.currentTimeMillis())
        val row = dao.ledger(profileId, day) ?: AdvertLedgerEntity(profileId, day)
        dao.upsertLedger(
            row.copy(grantedMinutes = row.grantedMinutes + minutes, updatedAt = System.currentTimeMillis()),
        )
        Log.i(TAG, "granted $minutes minute(s)")
        compute(profileId)
    }

    /**
     * ⑤ يخزّن الرصيد الذي أقرّه الخادم.
     *
     * لا يُكتب فوق العدّادات المحلّيّة: هي سجلّ ما وقع على هذا الجهاز، وردّ
     * الخادم مجموعُ كلّ الأجهزة. يُحفظ للعرض والتصحيح، والأدنى منهما هو ما
     * يُعتمد — فجهازان يشاهدان معاً لا يضاعفان الرصيد.
     */
    suspend fun applyServerBalance(profileId: Long, serverBalance: Int) = gate.withLock {
        if (serverBalance < 0) return@withLock
        val day = AdvertGate.dayKey(System.currentTimeMillis())
        val row = dao.ledger(profileId, day) ?: AdvertLedgerEntity(profileId, day)
        dao.upsertLedger(row.copy(serverBalance = serverBalance, updatedAt = System.currentTimeMillis()))
        compute(profileId)
    }

    /** دقائق استُهلكت اليوم — تُرسل مع التقرير. */
    suspend fun consumedToday(profileId: Long): Int =
        dao.ledger(profileId, AdvertGate.dayKey(System.currentTimeMillis()))?.consumedMinutes ?: 0

    /** ينظّف دفاتر الأيّام الماضية — تُستدعى عند الإقلاع. */
    suspend fun prune() {
        val cutoff = AdvertGate.dayKey(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L)
        runCatching { dao.pruneLedger(cutoff) }
    }

    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun compute(profileId: Long): Int {
        val policy = repository.policy.value
        // النظام مطفأ = لا أحد «مجّانيّ» محاسَبيّاً — انظر التعليق عند [applies].
        if (!policy.isFree || !policy.enabled) {
            _balance.value = UNLIMITED
            return UNLIMITED
        }

        val day = AdvertGate.dayKey(System.currentTimeMillis())   // ④
        val row = dao.ledger(profileId, day)
        val granted = row?.grantedMinutes ?: 0
        val consumed = row?.consumedMinutes ?: 0

        // ① الرصيد اليوميّ يسبق أوّل إعلان، ويغطّي انقطاع الشبكة.
        var available = policy.offlineGrantMinutes + granted - consumed

        // ⑤ الخادم يرى كلّ الأجهزة؛ الأدنى هو الصادق.
        val server = row?.serverBalance ?: -1
        if (server >= 0) available = minOf(available, server)

        // ② سقف الباقة اليوميّ فوق كلّ ذلك.
        val cap = policy.dailyMinutesCap
        if (cap > 0) available = minOf(available, cap - consumed)

        val out = available.coerceAtLeast(0)
        _balance.value = out
        return out
    }

    companion object {
        private const val TAG = "SalamTVAds"

        /** لا يعرف بعد. */
        const val UNKNOWN = -2

        /** مشتركٌ مدفوع: لا حدّ ولا دفتر. */
        const val UNLIMITED = -1

        private const val KEEP_DAYS = 7L
    }
}
