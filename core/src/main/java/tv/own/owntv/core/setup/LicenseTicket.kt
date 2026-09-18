package tv.own.owntv.core.setup

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  تذكرة الرخصة — ما يجعل رخصة اللوحة شرطاً في جيب المشترك لا في PHP
 * ───────────────────────────────────────────────────────────────────────────
 *  كلّ فحص داخل لوحة العميل يُحذف بسطر. هذا لا: مع كلّ ردّ دخول/حساب تصل تذكرة
 *  يوقّعها مركز الرخص بمفتاحه الخاصّ، والتطبيق يحمل المفتاح العامّ ويرفض الخدمة
 *  بلا تذكرة صالحة. لوحة بلا رخصة سارية لا تملك تذكرة، ومن حذف الفحص من PHP لا
 *  يصنع توقيعاً. الصيغة: `base64url(json) . base64url(RSA-SHA256)`؛ الحمولة:
 *  v, lic, hosts, iat, exp — انظر modules/license/ticket.php في اللوحة.
 *
 *  ═══ ثلاثة قرارات ═══
 *  ① RSA-2048/SHA-256 من java.security: متاح في كلّ Android بلا مكتبة.
 *  ② المضيف الذي يُفحص هو مضيف الدخول (SALAMTV_LOGIN_URL) لا مضيف البثّ: البثّ قد
 *     يأتي من عقدة مزوّد إنترنت بنطاقها هي، أمّا الدخول فإلى لوحة المشغّل دائماً.
 *     تذكرة منسوخة إلى نطاق آخر لا تعمل.
 *  ③ المفتاح العامّ ثابت في الشيفرة: تبديله إصدارٌ جديد عمداً — لا إعداد ولا تنزيل.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object LicenseTicket {

    /** المفتاح العامّ لمركز الرخص — بصمة 84d79fb385c41406 (license_cli.php ticket-pubkey). */
    private const val PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsHQKzomlEmjW6O8AQZVo
AF2G3LhUU1qpmb90C/Mf3HkXk1PiMbt/FsQQsIhDvWBDcHM4FbFSBYKYSIiHLD9D
/gBOn3xT4wfcZVDSPzBISqaZMxrSwTlvbuwKhzJSQ3hZOQI7El+JGNSY4ZxEmu3L
J85f6FZQWrWcgNclZzs17kGPiiIeYVAvLYtdLjAfiJLjThqv+wzjgKlq5JZdaoCs
9CT8/f7XZ6Kzqg5MZMKhkZ3azUWK55YJ/9eRo3foOdnjXQ35ZicOcsS1HtZeBHh8
2k0NpgrANFac2PW8vU+Lz7jeJIFE45lbO0+Z+lFmnfaBXMY5Aw8sT1pyfzrWSnLR
+QIDAQAB
-----END PUBLIC KEY-----"""

    private const val VERSION = 1

    /** حمولة تذكرة صالحة. [exp] بالثواني منذ الحقبة. */
    data class Payload(val hosts: List<String>, val issuedAt: Long, val exp: Long)

    private val publicKey: PublicKey by lazy {
        val body = PUBLIC_KEY_PEM.lines().filter { !it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(body, Base64.DEFAULT)))
    }

    /**
     * يتحقّق من التوقيع والعمر والمضيف. null = مرفوضة (تالفة، منتهية، موقَّعة بغير مفتاحنا،
     * أو لمضيفٍ آخر). [host] فارغ = لا فحص مضيف (للاختبار). قائمة مضيفين فارغة = لا قيد.
     */
    fun verify(ticket: String, host: String, nowSec: Long = System.currentTimeMillis() / 1000): Payload? {
        val dot = ticket.indexOf('.')
        if (dot <= 0 || dot == ticket.length - 1) return null
        val payloadBytes = decode(ticket.substring(0, dot)) ?: return null
        val signature = decode(ticket.substring(dot + 1)) ?: return null
        val ok = runCatching {
            Signature.getInstance("SHA256withRSA").run { initVerify(publicKey); update(payloadBytes); verify(signature) }
        }.getOrDefault(false)
        if (!ok) return null
        val json = runCatching { JSONObject(String(payloadBytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (json.optInt("v") != VERSION) return null
        val exp = json.optLong("exp")
        if (exp <= nowSec) return null
        val hosts = json.optJSONArray("hosts")?.let { arr -> List(arr.length()) { arr.optString(it).lowercase() } }
            ?.filter { it.isNotBlank() }.orEmpty()
        if (host.isNotBlank() && !hostAllowed(host, hosts)) return null
        return Payload(hosts, json.optLong("iat"), exp)
    }

    /** المضيف يساوي أحد النطاقات أو نطاق فرعيّ منه. */
    fun hostAllowed(host: String, hosts: List<String>): Boolean {
        if (hosts.isEmpty()) return true
        val h = host.trim().lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    private fun decode(s: String): ByteArray? =
        runCatching { Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
}
