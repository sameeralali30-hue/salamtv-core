package tv.own.owntv.core.adverts

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ملفّات الإعلانات على الجهاز.
 * ───────────────────────────────────────────────────────────────────────────
 *  ⚠ القاعدة التي تحكم هذا الملفّ كلّه: **إعلانٌ لم يكتمل تنزيله والتحقّق منه
 *    لا يُعرض**. لا بثّ عند الفتح، ولا انتظار، ولا مؤشّر تحميل. بثّ الإعلان
 *    لحظة فتح القناة يعيد بالضبط التأخير الذي وُجدت هذه الميزة لتفاديه،
 *    ودوّامةُ تحميلٍ قبل كلّ قناة هي أقصر طريقٍ إلى حذف التطبيق.
 *
 *  ═══ خمسة قرارات ═══
 *
 *  ① اسم الملفّ هو بصمته.
 *     فإعلانٌ أُعيد رفعه ملفٌّ جديد باسمٍ جديد، والقديم يُجمع تلقائيّاً في
 *     أوّل تنظيف. ولا حاجة إلى «هل تغيّر الملفّ؟» أبداً.
 *
 *  ② البصمة تُتحقّق قبل القبول.
 *     تنزيلٌ مبتور أو تالف يُعطي ملفّاً يُفتح ويُخفق — والإخفاق يقع أمام
 *     المشاهد لا عندنا. الفحص هنا يُحوّله إلى إعادة تنزيلٍ صامتة.
 *     والرابط الخارجيّ بلا بصمة (لا نستضيفه فلا نملكها): يُقبل بحجمه إن
 *     أُعلن، وإلّا بوجوده.
 *
 *  ③ `.part` ثمّ إعادة تسميةٍ ذرّيّة.
 *     ⚠ الكتابة مباشرةً على الاسم النهائيّ تعني أنّ انقطاعاً في المنتصف يترك
 *       ملفّاً **بالاسم الصحيح وبمحتوى ناقص** — وستقبله البوّابة وتعرضه.
 *
 *  ④ الشبكة المحسوبة تُحترم على الهواتف.
 *     صندوق التلفاز غير محسوب فلا أثر لهذا عليه. أمّا الهاتف فتنزيل ٢٠ م.ب
 *     من باقة بياناته بلا إذنٍ ولا سبب ظاهر شكوى مستحقّة.
 *
 *  ⑤ الفشل يتراجع ولا يعاند.
 *     رابطٌ ميّت لا يجوز أن يُطلب مئة مرّة في الساعة. والتراجع في الذاكرة
 *     لا على القرص: إعادة التشغيل تُصفّره، وهو سلوكٌ مقبول ومقصود.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AdvertMediaCache(
    private val context: Context,
    private val client: OkHttpClient,
) {
    /**
     * ⚠ مجلّدٌ خاصّ بالوسائط، لا `adverts/` نفسه.
     *
     *   أوّل نسخة وضعت الملفّات في `adverts/` مباشرةً — حيث يعيش `rules.json`
     *   أيضاً. و[prune] يحذف كلّ ما ليس في السياسة الحاليّة… فحذف السياسة
     *   نفسها في أوّل تشغيل. ظهر في السجلّ سطرٌ واحد: `pruning rules.json`،
     *   وكان أثره أنّ القواعد لا تُستعاد بعد إعادة التشغيل — عطلٌ لا يظهر
     *   إلّا بعد إقلاعٍ ثانٍ وبلا شبكة، أي في أسوأ لحظة لاكتشافه.
     *
     *   الفصل يُلغي الصنف كلّه بدل أن يستثني اسماً يسهل نسيانه.
     */
    private val dir: File get() = File(File(context.filesDir, "adverts"), "media").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    /** ⑤ متى يجوز إعادة محاولة هذا المفتاح، وكم مرّةً أخفق. */
    private val backoff = mutableMapOf<String, Pair<Long, Int>>()

    /** الملفّ المحلّيّ الجاهز، أو null. لا شبكة هنا ولا انتظار. */
    fun localPath(spot: AdvertSpot): String? {
        val f = fileFor(spot)
        return if (f.isFile && f.length() > 0L) f.absolutePath else null
    }

    /**
     * ينزّل ما ينقص من وسائط هذه السياسة، ويحذف ما لم يعد مشاراً إليه.
     *
     * آمنٌ للاستدعاء المتكرّر: ما اكتمل يُتخطّى بلا كلفة.
     */
    fun ensureAll(policy: AdvertPolicy) {
        scope.launch {
            gate.withLock {
                prune(policy)
                for (spot in policy.spots) {
                    if (localPath(spot) != null) continue
                    if (!mayRetry(spot.cacheKey)) continue
                    if (!allowedOnThisNetwork(spot)) {
                        Log.d(TAG, "deferring ${spot.cacheKey}: metered network")
                        continue
                    }
                    runCatching { download(spot) }
                        .onSuccess { noteSuccess(spot.cacheKey) }
                        .onFailure {
                            noteFailure(spot.cacheKey)
                            Log.w(TAG, "download failed for spot #${spot.id}: ${it.javaClass.simpleName} ${it.message}")
                        }
                }
            }
        }
    }

    /** ما لم يعد في السياسة يُحذف — ① الاسم هو البصمة فالتعرّف مجّانيّ. */
    private fun prune(policy: AdvertPolicy) {
        val keep = policy.spots.map { fileFor(it).name }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(PART)) {
                // بقايا تنزيلٍ منقطع: لا قيمة لها ولن تُستأنف.
                f.delete()
            } else if (f.name !in keep) {
                Log.d(TAG, "pruning ${f.name}")
                f.delete()
            }
        }
    }

    private suspend fun download(spot: AdvertSpot) = withContext(Dispatchers.IO) {
        val target = fileFor(spot)
        val part = File(dir, target.name + PART)
        part.delete()

        val request = Request.Builder().url(spot.url).header("User-Agent", "SalamTV").build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("http ${resp.code}")
                val body = resp.body
                val total = body.contentLength()

                if (total > 0 && dir.usableSpace < total * 2) error("not enough space for $total bytes")

                var copied = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                        }
                    }
                }

                if (copied == 0L) error("empty download")
                if (total > 0 && copied != total) error("truncated: $copied of $total")
                if (spot.bytes > 0 && copied != spot.bytes) error("size mismatch: $copied vs ${spot.bytes}")

                // ② البصمة قبل القبول — إن كانت لنا بصمة أصلاً.
                if (spot.sha1.isNotBlank()) {
                    val got = sha1Of(part)
                    if (!got.equals(spot.sha1, ignoreCase = true)) {
                        error("sha1 mismatch: $got vs ${spot.sha1}")
                    }
                }
            }

            // ③ لا يصير الملفّ مرئيّاً للبوّابة إلّا بعد اكتماله وتحقّقه.
            if (!part.renameTo(target)) error("rename failed")
            Log.i(TAG, "cached advert #${spot.id} (${target.length()} bytes)")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /** ④ الهاتف على باقة بيانات: الصغير يمرّ، والكبير ينتظر شبكةً غير محسوبة. */
    private fun allowedOnThisNetwork(spot: AdvertSpot): Boolean {
        val metered = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered ?: false
        }.getOrDefault(false)
        if (!metered) return true
        return spot.bytes in 1..METERED_MAX_BYTES
    }

    private fun fileFor(spot: AdvertSpot): File {
        val ext = if (spot.kind == "image") "img" else "mp4"
        return File(dir, "${spot.cacheKey}.$ext")
    }

    private fun sha1Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ═══ ⑤ التراجع ═══

    private fun mayRetry(key: String): Boolean {
        val (at, _) = backoff[key] ?: return true
        return System.currentTimeMillis() >= at
    }

    private fun noteFailure(key: String) {
        val fails = (backoff[key]?.second ?: 0) + 1
        val wait = BACKOFF_MS.getOrElse(fails - 1) { BACKOFF_MS.last() }
        backoff[key] = (System.currentTimeMillis() + wait) to fails
    }

    private fun noteSuccess(key: String) { backoff.remove(key) }

    companion object {
        private const val TAG = "SalamTVAds"
        private const val PART = ".part"
        private const val METERED_MAX_BYTES = 5L * 1024 * 1024
        private val BACKOFF_MS = longArrayOf(60_000, 300_000, 1_800_000, 21_600_000)
    }
}
