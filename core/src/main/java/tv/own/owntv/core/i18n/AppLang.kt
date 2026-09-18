package tv.own.owntv.core.i18n

import java.util.Locale

/**
 * [SALAMTV] رمز لغة الواجهة الفعليّة كما تراه اللوحة — `ar`، `en`، `tr`…
 *
 * اللوحة تخدم نصوصاً بلغة المشترك (أسماء الأقسام، اسم الخطّة)، فتحتاج أن تعرف بأيّ لغة يعمل
 * التطبيق *الآن*: ما اختاره المشترك في الإعدادات، وإلّا لغة جهازه. مكان واحد يحسبها، وكلّ طلب
 * إلى اللوحة (player_api، app_login، app_account) يحملها بالمفتاح `lang`.
 */
object AppLang {
    fun code(store: LocaleStore): String {
        val tag = store.currentTag.value
        val locale = if (tag == AppLocale.SYSTEM_DEFAULT_TAG) Locale.getDefault() else Locale.forLanguageTag(tag)
        return locale.language.lowercase(Locale.ROOT).take(2).ifBlank { "en" }
    }
}
