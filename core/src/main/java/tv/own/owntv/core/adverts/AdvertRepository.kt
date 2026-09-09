package tv.own.owntv.core.adverts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  السياسة الحاليّة: تُحفظ على القرص وتُقرأ عند الإقلاع.
 * ───────────────────────────────────────────────────────────────────────────
 *  ═══ أربعة قرارات ═══
 *
 *  ① ملفٌّ في `filesDir` لا DataStore.
 *     ⚠ تعليق [tv.own.owntv.core.settings.SettingsRepository] يحذّر أنّ كلّ
 *       كتابةٍ في DataStore تبثّ كامل `Preferences` إلى كلّ متلقٍّ. وهذه
 *       حمولةٌ نصّيّة تُكتب ككتلةٍ واحدة وتُقرأ مرّةً عند الإقلاع — ملفٌّ عاديّ
 *       هو الأداة الصحيحة، ولا شيء آخر في التطبيق يهمّه محتواه.
 *
 *  ② القرص يُقرأ قبل الشبكة.
 *     ⚠ لو انتُظرت أوّل نبضة لبقي التطبيق بلا قواعد بعد كلّ إقلاع لمدّة تصل
 *       إلى دقيقتين — ولو فُتحت قناةٌ في تلك النافذة لسقط الإعلان. القرص
 *       يجعل السياسة حاضرةً قبل أوّل إطار.
 *
 *  ③ الفشل «لا أدري»، لا «لا إعلانات» ولا «إعلانٌ على كلّ شيء».
 *     ⚠ نفس قاعدة ② في [tv.own.owntv.features.setup.SubscriptionWatcher]:
 *       ردٌّ لم يُفهم أو شبكةٌ انقطعت لا يُغيّران شيئاً. تُحفظ آخر سياسةٍ
 *       سليمة ويُترك الأمر للمحاولة التالية. أمّا إفراغُ القواعد عند كلّ
 *       تعثّرٍ فيعني أنّ ضعف الشبكة يُلغي الإعلانات — وهو ما لا يُكتشف أبداً
 *       من اللوحة.
 *
 *  ④ إفراغُ القواعد يقع بردٍّ مفهوم وحده.
 *     `enabled=false` أو `spots=[]` من الخادم أمرٌ صريح، فيُنفَّذ ويُحفظ.
 *     وهو مفتاح القتل عن بُعد: يصل كلّ جهازٍ في نبضةٍ واحدة.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AdvertRepository(
    context: Context,
    private val media: AdvertMediaCache,
) {
    private val file = File(File(context.filesDir, "adverts").apply { mkdirs() }, "rules.json")

    private val _policy = MutableStateFlow(AdvertPolicy.EMPTY)

    /** ما يقرأه [AdvertGate] عند كلّ فتح قناة — قيمةٌ في الذاكرة، بلا I/O. */
    val policy: StateFlow<AdvertPolicy> = _policy.asStateFlow()

    /** آخر بصمةٍ استُقبلت، لتقرير الحاجة إلى إعادة الجلب. */
    val rev: String get() = _policy.value.rev

    init {
        // ② القرص أوّلاً: السياسة حاضرةٌ قبل أن تصل أوّل نبضة.
        runCatching {
            if (file.isFile) {
                val json = JSONObject(file.readText())
                AdvertPolicy.parse(json, fetchedAtMs = file.lastModified())?.let {
                    _policy.value = it
                    Log.i(TAG, "restored ${it.spots.size} advert(s) from disk, rev=${it.rev}")
                }
            }
        }.onFailure {
            Log.w(TAG, "could not restore advert rules: ${it.javaClass.simpleName}")
            runCatching { file.delete() }
        }
    }

    /**
     * ③④ يقبل ردّاً مفهوماً ويحفظه؛ ويترك كلّ شيء على حاله لغيره.
     *
     * @return true إن تغيّرت السياسة فعلاً.
     */
    fun accept(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false

        val parsed = runCatching { AdvertPolicy.parse(JSONObject(raw)) }.getOrNull()
        if (parsed == null) {
            // ③ ردٌّ غير مفهوم = «لا أدري». لا يُمسّ المحفوظ.
            Log.w(TAG, "advert rules unparseable; keeping the last good policy")
            return false
        }

        val before = _policy.value
        _policy.value = parsed
        runCatching { file.writeText(raw) }
            .onFailure { Log.w(TAG, "could not persist advert rules: ${it.javaClass.simpleName}") }

        media.ensureAll(parsed)

        val changed = before.rev != parsed.rev || before.spots.size != parsed.spots.size
        if (changed) {
            Log.i(TAG, "advert rules updated: rev=${parsed.rev} enabled=${parsed.enabled} " +
                "tier=${parsed.tier} spots=${parsed.spots.size}")
        }
        return changed
    }

    /** هل يجب إعادة الجلب؟ بصمةٌ مختلفة، أو تقادمٌ تجاوز سقف الخادم. */
    fun needsRefresh(serverRev: String?): Boolean {
        val p = _policy.value
        if (p.fetchedAtMs == 0L) return true
        if (!serverRev.isNullOrBlank() && serverRev != p.rev) return true
        return p.isStale
    }

    /** يُنسي كلّ شيء — تسجيل خروج. */
    fun forget() {
        _policy.value = AdvertPolicy.EMPTY
        runCatching { file.delete() }
    }

    companion object {
        private const val TAG = "SalamTVAds"
    }
}
