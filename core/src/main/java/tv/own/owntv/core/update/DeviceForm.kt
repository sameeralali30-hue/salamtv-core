package tv.own.owntv.core.update

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * [SALAMTV] Which of the two published APKs this *device* should run: `tv` or `phone`.
 *
 * A device fact, not a build fact. The two flavours share one applicationId and one signing key,
 * so the updater asks the panel for the file that fits the device it is on — a phone that installed
 * the TV flavour (the file the Downloader code hands out, or the universal file of older releases)
 * migrates to the phone flavour with its next ordinary update, and a TV box that somehow got the
 * phone file goes the other way. Nobody uninstalls anything.
 *
 * Television wins on any positive signal: the TV UI mode, the leanback feature, or a device with
 * no touchscreen at all (set-top boxes that forget to declare leanback). Everything else is a phone
 * or tablet.
 */
object DeviceForm {
    const val TV = "tv"
    const val PHONE = "phone"

    fun detect(context: Context): String {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return TV
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return TV
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return TV
        return PHONE
    }
}
