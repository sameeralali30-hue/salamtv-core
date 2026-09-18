package tv.own.owntv.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import java.io.File
import java.io.IOException

/**
 * In-app updates straight from GitHub Releases: checks the repo's latest release, compares its tag
 * with the installed version, downloads the release APK, and hands it to the system installer.
 * No server of our own — the releases CI already publishes `OwnTV-vX.Y.Z.apk` (arm) and
 * `OwnTV-x86_64-vX.Y.Z.apk` per tag; the asset matching this device's ABI is chosen, so updates
 * also work on an x86_64 emulator.
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
) {
    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure

        /** Internal storage cannot hold the APK twice (download + install session). */
        data class NotEnoughSpace(val requiredBytes: Long) : Failure

        /** The file that arrived is truncated or not a readable OwnTV package — never install it. */
        data object DamagedDownload : Failure

        /** The system installer refused the package; [message] is its own wording when it gave one. */
        data class InstallRejected(val message: String?) : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException : IOException()
    private class EmptyDownloadException : IOException()
    private class NotEnoughSpaceException(val requiredBytes: Long) : IOException()
    private class DamagedDownloadException(reason: String) : IOException(reason)

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        is NotEnoughSpaceException -> Failure.NotEnoughSpace(error.requiredBytes)
        is DamagedDownloadException -> Failure.DamagedDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    val currentVersion: String = CoreBuildInfo.versionName

    /** Queries GitHub's latest release; moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                // [SALAMTV] Two published files per release; the panel picks by device form.
                val url = RELEASE_URL.toHttpUrl().newBuilder()
                    .setQueryParameter("form", DeviceForm.detect(context))
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "SalamTV")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                    val body = resp.body.string()
                    if (body.isBlank()) throw InvalidReleaseResponseException()
                    val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
                    val version = o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                        ?: throw InvalidReleaseResponseException()
                    val notes = o.optString("body").take(16_000)
                    val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
                    // The panel already answered for this device's form (see the `form` query above),
                    // so the first .apk it lists is the right one. GitHub's raw feed (forks, dev
                    // builds) lists every asset — prefer the one named for this form, else any.
                    val form = DeviceForm.detect(context)
                    val apkUrl = (0 until assets.length())
                        .asSequence()
                        .mapNotNull { assets.optJSONObject(it) }
                        .mapNotNull { asset ->
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) url else null
                        }
                        .toList()
                        .let { urls -> urls.firstOrNull { it.contains("-$form-", ignoreCase = true) } ?: urls.firstOrNull() }
                        ?: throw NoCompatibleApkException()
                    val info = UpdateInfo(version, notes, apkUrl)
                    if (isNewer(version, currentVersion)) _state.value = State.Available(info)
                    else _state.value = State.UpToDate
                }
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /** Downloads the release APK with progress, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "owntv-update.apk")
                val partial = File(dir, "owntv-update.part")
                try {
                    downloadResumable(info.apkUrl, partial)
                    out.delete()
                    if (!partial.renameTo(out)) throw DamagedDownloadException("rename failed")
                    verifyApk(out)
                    install(out)
                } catch (e: Throwable) {
                    out.delete() // a bad file must not linger and be retried as-is
                    if (e is DamagedDownloadException || e is InstallException) {
                        partial.delete() // bytes proven wrong — a later resume must start clean
                    }
                    throw e
                }
                _state.value = State.Available(info) // dialog stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = info)
            }
        }
    }

    /**
     * ═══ لماذا التنزيل يستأنف ولا يبدأ من الصفر ═══
     * الملفّ عشرات الميغابايتات والشبكة عند كثير من المشتركين 100–200 KB/s وتتقطّع. تنزيلٌ
     * يُحذف عند أوّل انقطاع لا ينتهي أبداً على هذه الشبكات — يبدو للمشترك «التحديث لا يعمل».
     * هنا الجزء المنزَّل يبقى في `.part`؛ كلّ محاولة تطلب ما بعده (`Range`)، وتُعاد المحاولة
     * حتّى [MAX_ATTEMPTS] مرّة قبل الاستسلام. خادمنا وGitHub يجيبان 206؛ خادمٌ يعيد 200 يعني
     * أنّه لا يدعم الاستئناف فنبدأ من الصفر معه. تغيّر الحجم الكلّيّ بين محاولتين = ملفّ آخر
     * على الخادم ⇒ نبدأ من الصفر أيضاً.
     */
    private fun downloadResumable(url: String, partial: File) {
        var attempt = 0
        var lastError: Throwable? = null
        var knownTotal = -1L
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val have = if (partial.exists()) partial.length() else 0L
                val request = Request.Builder().url(url).header("User-Agent", "SalamTV")
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                    val resumed = resp.code == 206 && have > 0
                    val body = resp.body
                    val total = if (resumed) have + body.contentLength() else body.contentLength()
                    if (knownTotal > 0 && total > 0 && total != knownTotal) {
                        partial.delete(); knownTotal = -1
                        throw java.io.IOException("file changed on server") // next attempt restarts clean
                    }
                    knownTotal = total
                    // The APK is written once here and copied again into the install session, so
                    // the download needs room for two of it. Checking up front turns a silent short
                    // write — which reaches the installer as "App not installed" — into a real message.
                    if (total > 0 && partial.parentFile!!.usableSpace < total * 2) throw NotEnoughSpaceException(total * 2)
                    var copied = if (resumed) have else 0L
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(partial, resumed).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) _state.value = State.Downloading((copied * 100 / total).toInt())
                            }
                        }
                    }
                    if (copied == 0L) throw EmptyDownloadException()
                    if (total > 0 && copied != total) {
                        throw java.io.IOException("truncated: got $copied of $total bytes") // resumable
                    }
                }
                return
            } catch (e: java.io.IOException) {
                // ردّ HTTP سيّئ أو قرص ممتلئ أو جسم فارغ لا تصلحه المحاولة؛ الشبكة المتقطّعة وحدها تُعاد
                if (e is DownloadHttpException || e is NotEnoughSpaceException || e is EmptyDownloadException) throw e
                lastError = e
                Log.w(TAG, "download attempt $attempt failed, ${partial.length()} bytes kept: ${e.message}")
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
        throw DamagedDownloadException("gave up after $MAX_ATTEMPTS attempts: ${lastError?.message}")
    }

    /**
     * Refuses anything the system cannot read back as this app's package. A truncated or corrupted
     * APK is otherwise only rejected by the installer itself, which reports nothing to us and shows
     * the user a bare "App not installed" — the failure this whole guard exists to explain.
     */
    private fun verifyApk(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw DamagedDownloadException("unparseable APK (${apk.length()} bytes)")
        if (info.packageName != context.packageName) {
            throw DamagedDownloadException("wrong package ${info.packageName}")
        }
    }

    /**
     * Installs through [PackageInstaller] rather than an `ACTION_VIEW` intent on a FileProvider URI.
     * Two reasons: the session reads the APK from our own storage (no cross-app URI grant to go wrong
     * on older TV installers), and its status broadcast carries the real reason a package was refused
     * — insufficient storage, a signature conflict, a bad file — instead of the system's silent
     * "App not installed".
     */
    private fun install(apk: File) {
        registerInstallReceiver()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallException(e)
        }
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 0, intent, flags).intentSender)
        }
        apk.delete() // the session owns its own copy now
    }

    /** Registered once, for the app's lifetime: the confirmation round trip outlives any one call. */
    private fun registerInstallReceiver() {
        if (installReceiverRegistered) return
        installReceiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            installStatusReceiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private var installReceiverRegistered = false

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                // The user still has to approve the install — this is the system prompt, not a failure.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let { context.startActivity(it) } }
                        .onFailure { _state.value = State.Failed(Failure.Install) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
                // Declining the system prompt is a choice, not an error — leave the dialog as it was.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "install cancelled by user")
                else -> {
                    Log.w(TAG, "install refused status=$status message=$message")
                    _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }))
                }
            }
        }
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"
        private const val MAX_ATTEMPTS = 8          // شبكة متقطّعة: ثماني استئنافات قبل الاستسلام
        private const val RETRY_DELAY_MS = 2_000L   // × رقم المحاولة
        private const val SESSION_ENTRY = "owntv-update"
        private const val INSTALL_STATUS_ACTION = "tv.own.owntv.UPDATE_INSTALL_STATUS"
        const val REPO = "sameeralali30-hue/salamtv"

        /**
         * أين يسأل التطبيق عن التحديث.
         *
         * ⚠ كان يسأل api.github.com مباشرةً، فجاء 403 على هاتف مشترك ولم ينزّل
         *   شيئاً. لـGitHub سببان لقول 403 هنا، وكلاهما خارج سيطرتنا: تقييد
         *   الوصول من شبكاتٍ بعينها، وحدّ ستّين طلباً في الساعة لكلّ عنوان —
         *   ومزوّد إنترنت كامل خلف عنوان واحد يستهلك الحدّ قبل الظهر.
         *
         *   والنتيجة أسوأ ما يمكن: نُصلح عطلاً ثمّ لا يصل الإصلاح إلى من يعاني
         *   منه، ولا يعرف هو لماذا.
         *
         *   فصار الخادم — لا الهاتف — هو من يكلّم GitHub، مرّة كلّ نصف ساعة
         *   لكلّ المشتركين، ويردّ بنفس شكل GitHub مع رابط ملفٍ على نطاقنا.
         *   REPO يبقى للعرض في «حول التطبيق»: المصدر مفتوح ورخصة GPL تُلزمنا
         *   بإظهاره.
         */
        val RELEASE_URL: String = tv.own.owntv.core.CoreBuildInfo.updateUrl
    }
}
