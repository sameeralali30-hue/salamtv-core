package tv.own.owntv.core.adverts

import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  قواعد الإعلانات كما تصل من اللوحة — بيانات محضة، بلا أندرويد ولا شبكة.
 * ───────────────────────────────────────────────────────────────────────────
 *  ⚠ لا تُسمَّ أيّ من هذه الأنواع `preroll`: الكلمة محجوزة في هذا المشروع
 *    وتعني ثواني التخزين المؤقّت قبل البثّ ([tv.own.owntv.player.OwnTVPlayer]
 *    و`LivePreviewEngine`). خلطها بالإعلان يُنتج التباساً دائماً في ملفّاتٍ
 *    يقرؤها الاثنان معاً. الاسم هنا **spot**.
 *
 *  ═══ ثلاثة قرارات ═══
 *
 *  ① الخادم يُرشّح، وهذه البنية تحمل ما بقي بعد ترشيحه.
 *     ما يصل هنا هو ما **قد** يُعرض لهذا المشترك: كلّ ما هو معطّل أو خارج
 *     نافذته أو معفيٌّ بباقته أو خارج جمهوره سقط في اللوحة. فما يبقى للجهاز
 *     هو العدّ وحده — وهو ما يجب أن يقع بلا شبكة وفي صفر ملّي ثانية.
 *
 *  ② التحليل متسامح، لا صارم.
 *     ⚠ حقلٌ ناقص أو قيمةٌ لم يعرفها هذا الإصدار لا يجوز أن تُسقط السياسة
 *       كلّها. أجهزة الميدان لا تُحدَّث بالأمنية، وسيبقى بينها لسنةٍ ما لا
 *       يعرف موضعاً أضفناه اليوم. المجهول يُتجاهل، والباقي يعمل.
 *
 *  ③ المعرّفات نصوص لا أرقام.
 *     معرّفات القنوات والفئات هنا هي **معرّفات اللوحة** (`remoteId`) لا
 *     مفاتيح Room المحلّيّة. الخلط بينهما يُنتج إعلاناً يُستهدَف بقناةٍ
 *     ويُعرض على أخرى — وهو عطلٌ لا يُكتشف إلّا من شكوى.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** كيف يُحسب سقف العرض. */
enum class CapScope { PER_CHANNEL, GLOBAL;
    companion object {
        fun from(s: String?) = if (s == "per_channel") PER_CHANNEL else GLOBAL
    }
}

/** النافذة الزمنيّة التي يُحسب فيها السقف. */
enum class CapWindow { DAY, WEEK, MONTH, EVER, SESSION;
    companion object {
        fun from(s: String?) = when (s) {
            "week" -> WEEK; "month" -> MONTH; "ever" -> EVER; "session" -> SESSION
            else -> DAY
        }
    }
}

/** أين يُعرض الإعلان. */
enum class Placement(val wire: String) {
    ON_TUNE("on_tune"), MID_ROLL("mid_roll"), ON_APP_OPEN("on_app_open");
    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

/** ما آل إليه عرضٌ بدأ فعلاً. */
enum class AdvertOutcome(val wire: String) {
    COMPLETED("completed"), SKIPPED("skipped"), ABORTED("aborted"), ERROR("error");
    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: COMPLETED
    }
}

/**
 * سبب فتح القناة.
 *
 * [ZAP] مرورٌ عابر بـCH±، و[RESUME] استئنافٌ بعد إعلانٍ وسطيّ — وكلاهما لا
 * يُعلَن عليه: الأوّل لأنّه ليس اختياراً، والثاني لأنّ الإعلان عُرض للتوّ،
 * وإعلانٌ يستدعي إعلاناً حلقةٌ لا تنتهي.
 */
enum class TuneReason { DIRECT, ZAP, STARTUP, RESUME }

data class AdvertCap(
    val scope: CapScope,
    val window: CapWindow,
    val max: Int,
    val cooldownSecs: Int,
)

data class AdvertTarget(
    /** all | channels | categories */
    val mode: String,
    /** ③ معرّفات اللوحة نصّاً. */
    val channels: Set<String>,
    val categories: Set<String>,
)

data class AdvertSpot(
    val id: Long,
    val campaignId: Long,
    val title: String,
    val label: String,
    val kind: String,               // video | image
    val url: String,
    val posterUrl: String,
    val bytes: Long,
    /** بصمة الملفّ، أو "" لرابطٍ خارجيّ لا نستضيفه فلا نملك بصمته. */
    val sha1: String,
    val durationSecs: Int,
    /** ‎-1‎ = لا يُتخطّى. */
    val skipAfterSecs: Int,
    val placements: Set<Placement>,
    val grantMinutes: Int,
    val audience: String,           // all | free_only | paid_only
    val cap: AdvertCap,
    val target: AdvertTarget,
    val priority: Int,
    val weight: Int,
) {
    /** مفتاح الملفّ في الكاش: البصمة إن وُجدت، وإلّا تهشيمُ الرابط. */
    val cacheKey: String
        get() = if (sha1.isNotBlank()) sha1 else url.hashCode().toUInt().toString(16).padStart(8, '0')

    val isSkippable: Boolean get() = skipAfterSecs >= 0
}

/**
 * السياسة كاملةً كما وصلت، ووقتُ وصولها.
 *
 * [fetchedAtMs] يخدم [refetchAfterSecs]: سقفُ تقادمٍ يضمن إعادة الجلب دوريّاً
 * حتّى لو تعطّل مسار البصمة لسببٍ ما، فلا يبقى جهازٌ على قواعدَ ميّتة للأبد.
 */
data class AdvertPolicy(
    val rev: String,
    val enabled: Boolean,
    /** free = يدفع بمشاهدة الإعلانات · paid = مشتركٌ عاديّ. */
    val tier: String,
    val grantMinutes: Int,
    val offlineGrantMinutes: Int,
    val dailyMinutesCap: Int,
    val minGapSecs: Int,
    val maxPerSession: Int,
    val refetchAfterSecs: Int,
    val reportMaxBatch: Int,
    val fetchedAtMs: Long,
    val spots: List<AdvertSpot>,
) {
    val isFree: Boolean get() = tier == "free"

    val isStale: Boolean
        get() = refetchAfterSecs > 0 &&
            System.currentTimeMillis() - fetchedAtMs > refetchAfterSecs * 1000L

    companion object {
        /** سياسةٌ لا تفعل شيئاً — ما قبل أوّل جلب، وما بعد إطفاء النظام. */
        val EMPTY = AdvertPolicy(
            rev = "", enabled = false, tier = "paid",
            grantMinutes = 30, offlineGrantMinutes = 60, dailyMinutesCap = 0,
            minGapSecs = 900, maxPerSession = 0, refetchAfterSecs = 21_600,
            reportMaxBatch = 50, fetchedAtMs = 0L, spots = emptyList(),
        )

        /**
         * ② تحليلٌ متسامح: ما لا يُفهم يُتجاهل، وما يُفهم يعمل.
         *
         * يعيد null فقط حين يكون الردّ نفسه غير مفهوم — والمتّصل يفرّق بين
         * «لا إعلانات» و«لا أدري»، فلا يُفهم عطلُ شبكةٍ إفراغاً للقواعد.
         */
        fun parse(json: JSONObject, fetchedAtMs: Long = System.currentTimeMillis()): AdvertPolicy? {
            if (!json.optBoolean("ok")) return null

            val arr = json.optJSONArray("spots")
            val spots = buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr?.optJSONObject(i) ?: continue
                    parseSpot(o)?.let { add(it) }
                }
            }

            return AdvertPolicy(
                rev = json.optString("adv_rev"),
                enabled = json.optBoolean("enabled"),
                tier = json.optString("tier", "paid"),
                grantMinutes = json.optInt("grant_minutes", 30),
                offlineGrantMinutes = json.optInt("offline_grant", 60),
                dailyMinutesCap = json.optInt("daily_minutes_cap", 0),
                minGapSecs = json.optInt("min_gap_secs", 900),
                maxPerSession = json.optInt("max_per_session", 0),
                refetchAfterSecs = json.optInt("refetch_after", 21_600),
                reportMaxBatch = json.optInt("report_max_batch", 50).coerceIn(1, 200),
                fetchedAtMs = fetchedAtMs,
                spots = spots,
            )
        }

        private fun parseSpot(o: JSONObject): AdvertSpot? {
            val id = o.optLong("id")
            val url = o.optString("url")
            // إعلانٌ بلا معرّفٍ أو بلا رابط لا يمكن أن يُعرض ولا أن يُحتسب.
            if (id <= 0L || url.isBlank()) return null

            val capO = o.optJSONObject("cap")
            val tgtO = o.optJSONObject("target")

            val places = buildSet {
                val a = o.optJSONArray("placement")
                for (i in 0 until (a?.length() ?: 0)) {
                    Placement.from(a?.optString(i))?.let { add(it) }   // ② المجهول يُتجاهل
                }
                if (isEmpty()) add(Placement.ON_TUNE)
            }

            fun ids(key: String): Set<String> = buildSet {
                val a = tgtO?.optJSONArray(key)
                for (i in 0 until (a?.length() ?: 0)) {
                    val v = a?.optString(i).orEmpty()
                    if (v.isNotBlank()) add(v)
                }
            }

            return AdvertSpot(
                id = id,
                campaignId = o.optLong("campaign_id"),
                title = o.optString("title"),
                label = o.optString("label"),
                kind = o.optString("kind", "video"),
                url = url,
                posterUrl = o.optString("poster"),
                bytes = o.optLong("bytes"),
                sha1 = o.optString("sha1"),
                durationSecs = o.optInt("duration"),
                skipAfterSecs = o.optInt("skip_after", -1),
                placements = places,
                grantMinutes = o.optInt("grant_minutes"),
                audience = o.optString("audience", "all"),
                cap = AdvertCap(
                    scope = CapScope.from(capO?.optString("scope")),
                    window = CapWindow.from(capO?.optString("window")),
                    max = (capO?.optInt("max") ?: 1).coerceAtLeast(1),
                    cooldownSecs = capO?.optInt("cooldown") ?: 0,
                ),
                target = AdvertTarget(
                    mode = tgtO?.optString("mode", "all") ?: "all",
                    channels = ids("channels"),
                    categories = ids("categories"),
                ),
                priority = o.optInt("priority"),
                weight = o.optInt("weight", 100).coerceAtLeast(1),
            )
        }
    }
}

/** ما قرّرته [AdvertGate]: هذا الإعلان يُعرض الآن، لهذه القناة. */
data class AdvertDecision(
    val spot: AdvertSpot,
    val placement: Placement,
    /** الملفّ المحلّيّ الجاهز — البوّابة لا تقرّر عرضاً بلا ملفّ مكتمل. */
    val localPath: String,
    /** معرّف اللوحة للقناة، أو "" لموضعٍ لا قناة له. */
    val channelRemoteId: String,
    /**
     * اسم القناة التي تُفتح بعده — يُعرض في الطبقة.
     *
     * ⚠ يُحمل هنا ولا يُقرأ من `previewChannel`: البوّابة تعمل **قبل** أن
     *   يُحدَّث ذلك الحقل عمداً، فقراءته أثناء الإعلان تُظهر اسم القناة
     *   السابقة — خطأٌ صامت لا شيء فيه يبدو معطوباً.
     */
    val channelName: String,
    /** يُولَّد عند القرار ويُكتب في السجلّ فوراً — انظر [AdvertImpressionEntity]. */
    val eventUid: String,
)
