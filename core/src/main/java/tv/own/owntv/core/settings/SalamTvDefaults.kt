package tv.own.owntv.core.settings

/**
 * Playback defaults for the SalamTV build.
 *
 * Upstream ships a TV player for a set-top box on a home LAN, where a stream that never stutters
 * needs no head start: pre-buffering is off and the live buffer is whatever the engine picks.
 * A subscription service is the opposite case — public HLS origins of uneven quality, watched on
 * mobile data — so the same defaults produce the one complaint that makes people cancel.
 *
 * ═══ Two decisions ═══
 *
 * ① Three seconds of pre-buffer, not five.
 *    The cost of a head start is paid on every channel change, and channel changing is the thing
 *    subscribers do most. Three seconds absorbs the ordinary jitter of a mobile connection while
 *    still letting a channel appear promptly; five was noticeably sluggish to zap through.
 *
 * ② Stable rather than Balanced.
 *    This raises the buffer the player tries to hold, not the delay before playback starts, so it
 *    buys resilience without adding to the head start above. The cost is being further behind the
 *    live edge — irrelevant for general channels, and the subscriber can pick Low latency in
 *    Settings if a live match makes it matter.
 *
 * Both remain user-editable in Settings → Player. These are starting points, not a policy.
 */
object SalamTvDefaults {

    /** Seconds filled before a live channel starts, and refilled after a rebuffer. */
    const val PREROLL_SECS: Int = 3

    /** Live buffer depth the engine aims to hold. */
    val LATENCY: LiveLatency = LiveLatency.STABLE

    /**
     * Start downloading a found update without waiting for the subscriber to press anything.
     *
     * Distributing outside Play means nobody updates unless we make it happen: a subscriber who
     * ignores the card keeps an old build, and old builds are what turn a server-side change into
     * a support call. So the startup check downloads on its own and the card shows progress.
     *
     * What this cannot do is finish the job silently. Android hands the APK to [PackageInstaller],
     * which always asks the user to confirm — only a device-owner or system-signed app may install
     * without a prompt, and we are neither. So "automatic" here means: check, download, and put the
     * system's install prompt in front of the subscriber. One tap instead of four.
     */
    const val AUTO_INSTALL_UPDATES: Boolean = true
}
