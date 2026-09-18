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
 * `AUTO` maps to [AUTO_HEIGHT] rather than to the master: the owner's subscribers are on slow
 * networks, and 360p that starts is worth more than 1080p that stalls. Anyone who wants more picks it
 * once in the player; the choice persists (Settings → Video player → Stream quality).
 */
object StreamQuality {
    const val AUTO = 0
    /** What "Auto" means on the wire — the same default the panel uses for its own ladder. */
    const val AUTO_HEIGHT = 360
    /** `-1` in the quality menu = Auto (matches [TrackOption.mpvId] semantics for "no override"). */
    const val MENU_AUTO_ID = -1

    private val LADDER_URL = Regex("""^(https?://[^?#]+/(?:live|movie|series)/[^/?#]+/[^/?#]+/\d+\.m3u8)(?:\?([^#]*))?$""")

    /** An Xtream-style HLS URL on our panel — the only URLs that understand `q=`. */
    fun isLadderUrl(url: String): Boolean = LADDER_URL.matches(url)

    /** The master playlist (no `q`) — used to list the rungs the panel allows this subscriber. */
    fun masterUrl(url: String): String = withHeight(url, AUTO, forceMaster = true)

    /**
     * [url] with `q=<h>` replacing any earlier `q`. `h == AUTO` writes [AUTO_HEIGHT]; [forceMaster]
     * drops `q` entirely. Non-ladder URLs are returned untouched.
     */
    fun withHeight(url: String, h: Int, forceMaster: Boolean = false): String {
        val m = LADDER_URL.matchEntire(url) ?: return url
        val base = m.groupValues[1]
        val rest = m.groupValues[2].split('&').filter { it.isNotBlank() && !it.startsWith("q=") }
        val q = when {
            forceMaster -> null
            h > 0 -> h
            else -> AUTO_HEIGHT
        }
        val params = if (q == null) rest else rest + "q=$q"
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }

    /** Rung heights advertised in a master playlist, highest first; empty when it is not a master. */
    fun parseMasterHeights(master: String): List<Int> =
        Regex("""RESOLUTION=\d+x(\d+)""").findAll(master).map { it.groupValues[1].toInt() }.toSet().sortedDescending()
}
