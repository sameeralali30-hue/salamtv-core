package tv.own.owntv.player

/**
 * [SALAMTV] The subscriber's quality choice, applied at the URL.
 *
 * The panel builds a quality ladder for every channel and film — either the provider's variants or
 * rungs it transcodes itself — and serves one rung when the stream URL carries `?q=<height>`. Doing
 * the choice at the URL is what makes it engine-agnostic: mpv hands HLS to FFmpeg, which always
 * takes the highest variant of a master playlist (`hls-bitrate=max`), so a master alone gives a
 * viewer on a weak connection the worst possible default. A rung URL gives both engines exactly
 * the rendition the viewer asked for.
 *
 * `AUTO` used to map to a fixed 360p rung rather than to the master, on the reasoning that the
 * owner's subscribers are on slow networks and 360p that starts beats 1080p that stalls. That made
 * every Auto viewer watch a rung the panel had re-encoded with x264, even when the provider was
 * already handing us clean H.264 the panel would otherwise have copied untouched.
 *
 * Now `AUTO` maps to the master, so a tune starts on the stream **as the provider delivers it**, at
 * the provider's own resolution. The weak-line case did not disappear — it moved to where it belongs:
 * [AdaptiveQuality] watches buffer health and steps down the moment the line cannot carry it, which
 * is the honest signal the fixed rung was only guessing at.
 *
 * Setting [AUTO_HEIGHT] back to a positive height restores the old behaviour exactly; it is the one
 * number that decides this.
 */
object StreamQuality {
    const val AUTO = 0
    /**
     * What "Auto" means on the wire.
     *
     * `0` = the master playlist: the provider's own stream at its own resolution, which the panel
     * copies without re-encoding (`-c:v copy`) whenever the source is H.264 8-bit up to 1080p.
     * A positive height pins Auto to that rung instead — the pre-1.0.47 behaviour.
     */
    const val AUTO_HEIGHT = 0
    /** `-1` in the quality menu = Auto (matches [TrackOption.mpvId] semantics for "no override"). */
    const val MENU_AUTO_ID = -1

    private val LADDER_URL = Regex("""^(https?://[^?#]+/(?:live|movie|series)/[^/?#]+/[^/?#]+/\d+\.m3u8)(?:\?([^#]*))?$""")

    /** An Xtream-style HLS URL on our panel — the only URLs that understand `q=`. */
    fun isLadderUrl(url: String): Boolean = LADDER_URL.matches(url)

    /** The master playlist (no `q`) — used to list the rungs the panel allows this subscriber. */
    fun masterUrl(url: String): String = withHeight(url, AUTO, forceMaster = true)

    /**
     * [url] with `q=<h>` replacing any earlier `q`. `h == AUTO` writes [AUTO_HEIGHT], or drops `q`
     * entirely when that is `0` (the master — the provider's own rendition); [forceMaster] always
     * drops it. Non-ladder URLs are returned untouched.
     */
    fun withHeight(url: String, h: Int, forceMaster: Boolean = false): String {
        val m = LADDER_URL.matchEntire(url) ?: return url
        val base = m.groupValues[1]
        val rest = m.groupValues[2].split('&').filter { it.isNotBlank() && !it.startsWith("q=") }
        val q = when {
            forceMaster -> null
            h > 0 -> h
            AUTO_HEIGHT > 0 -> AUTO_HEIGHT
            else -> null                 // Auto = the master, untouched by us
        }
        val params = if (q == null) rest else rest + "q=$q"
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }

    /** Rung heights advertised in a master playlist, highest first; empty when it is not a master. */
    fun parseMasterHeights(master: String): List<Int> =
        Regex("""RESOLUTION=\d+x(\d+)""").findAll(master).map { it.groupValues[1].toInt() }.toSet().sortedDescending()
}
