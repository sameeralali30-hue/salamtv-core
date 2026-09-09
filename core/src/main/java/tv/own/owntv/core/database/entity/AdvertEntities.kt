package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  سجلّ عرض الإعلانات — عدّادُ السقوف وصندوقُ البريد الصادر في جدولٍ واحد.
 * ───────────────────────────────────────────────────────────────────────────
 *  ═══ أربعة قرارات ═══
 *
 *  ① Room لا DataStore.
 *     ⚠ تعليق [tv.own.owntv.core.settings.SettingsRepository] نفسه يحذّر أنّ
 *       كلّ كتابةٍ في DataStore تبثّ كامل `Preferences` إلى **كلّ** متلقٍّ.
 *       وهذا الصفّ يُكتب عند كلّ فتحِ قناةٍ مستهدفة — وشاشات الإعدادات تتلقّى.
 *       وفوقه: سؤال السقف هو `COUNT ... WHERE`، وهو في DataStore مفتاحٌ لكلّ
 *       ثلاثيّة (إعلان، قناة، يوم) تُنظَّف يدويّاً إلى الأبد.
 *
 *  ② الصفّ يُكتب عند **بداية** العرض لا عند نهايته.
 *     ⚠ لو كُتب عند النهاية لكان إغلاق التطبيق قسراً أثناء الإعلان يترك
 *       السقف غير مستهلَك — فيتكرّر الإعلان عند الفتح التالي، ثمّ التالي.
 *       أي أنّنا نُعلّم المستخدم أنّ الإغلاق القسريّ يُخلّصه من الإعلان.
 *       [outcome] يبقى null حتّى النهاية، وما بقي null عند الإقلاع التالي
 *       يُختم `aborted`.
 *
 *  ③ ثلاثة مفاتيح تاريخ محفوظة، لا واحدٌ يُحسب.
 *     [dayKey] و[weekKey] و[monthKey] تُحسب مرّةً عند الكتابة. السقوف عندئذٍ
 *     `WHERE dayKey = ?` — فهرسٌ يُستعمل، لا دالّة تُطبَّق على كلّ صفّ.
 *
 *  ④ [elapsedRealtime] بجانب [shownAt].
 *     ⚠ السقوف بالتاريخ المحلّيّ، فإرجاع ساعة الجهاز يمنح حصّةً جديدة. لا
 *       يُزال هذا كلّيّاً بلا سؤال الخادم عند كلّ فتح — وهو بالضبط ما اشترى
 *       «صفر تأخير ويعمل بلا شبكة». الساعة الرتيبة تكشف القفزة فتُقاس بها
 *       الفواصل، ويُحدّ الضرر ولا يُدّعى أنّه أُزيل.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Entity(
    tableName = "advert_impressions",
    primaryKeys = ["eventUid"],
    indices = [
        Index("profileId", "spotId", "dayKey"),
        Index("spotId", "channelRemoteId", "dayKey"),
        Index("reported"),
        Index("shownAt"),
    ],
)
data class AdvertImpressionEntity(
    /** ٣٢ حرفاً سداسيّاً، يُولَّد على الجهاز — ومفتاحُ التفرّد لدى الخادم أيضاً. */
    val eventUid: String,
    val profileId: Long,
    val spotId: Long,
    val campaignId: Long = 0,
    /** ③ معرّف اللوحة نصّاً، لا مفتاح Room — نفس ما يستهدف به الإعلان. */
    val channelRemoteId: String = "",
    val placement: String = "on_tune",
    val shownAt: Long,
    /** ④ ساعةٌ رتيبة لا تُرجَع بتغيير إعدادات الجهاز. */
    val elapsedRealtime: Long = 0,
    val dayKey: String,
    val weekKey: String,
    val monthKey: String,
    /** يتبدّل مع كلّ تشغيلٍ للعمليّة — نافذة `session`. */
    val sessionId: String,
    val watchedMs: Long = 0,
    val grantedMinutes: Int = 0,
    /** ② null = ما زال جارياً؛ يُختم `aborted` عند الإقلاع التالي. */
    val outcome: String? = null,
    val reported: Boolean = false,
)

/**
 * دفتر الاستحقاق اليوميّ — للمشترك المجّانيّ وحده.
 *
 * الجهاز هو المرجع اللحظيّ (بلا شبكة وبلا تأخير)، والخادم يصحّحه في كلّ تقرير،
 * فالانحراف محدودٌ بدورة نبضٍ واحدة لا مفتوح.
 */
@Entity(
    tableName = "advert_ledger",
    primaryKeys = ["profileId", "dayKey"],
)
data class AdvertLedgerEntity(
    val profileId: Long,
    val dayKey: String,
    val grantedMinutes: Int = 0,
    val consumedMinutes: Int = 0,
    /** آخر رصيدٍ أقرّه الخادم، أو ‎-1‎ إن لم يُقَرّ بعد. */
    val serverBalance: Int = -1,
    val updatedAt: Long = 0,
)
