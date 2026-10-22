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
 * ① No pre-buffer at all.
 *    This started at three seconds, on the reasoning that a head start absorbs the ordinary jitter
 *    of a mobile connection. What it absorbed instead was the owner's patience: the gate holds the
 *    picture on every channel change AND after every underrun, so a channel on a shaky line spends
 *    its time dark, fills three seconds, plays three seconds, and goes dark again — which reads as
 *    "it records a clip and replays it", not as smoothing.
 *
 *    Zero hands that judgement to the engine, which starts as soon as it can decode and keeps
 *    buffering while it plays. The protection lost is real — a line that cannot hold the bitrate
 *    now stutters instead of waiting — and that is what the quality ladder is for: dropping a rung
 *    keeps playback running, where a head start only delayed the same stall. A subscriber who
 *    prefers the old behaviour still has Settings → Player → Pre-buffer.
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

    /** Seconds filled before a live channel starts, and refilled after a rebuffer. 0 = no gate. */
    const val PREROLL_SECS: Int = 0

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
