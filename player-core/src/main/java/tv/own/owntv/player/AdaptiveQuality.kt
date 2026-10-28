package tv.own.owntv.player

/**
 * [SALAMTV] Automatic rung selection — what "Auto" should mean.
 *
 * Until now `AUTO` meant a fixed [StreamQuality.AUTO_HEIGHT]: safe for a weak line, wasteful for a
 * good one, and never reacting to a line that changes mid-match. This picks the rung from how
 * playback is actually going and moves with it, the way a viewer expects.
 *
 * Since [StreamQuality.AUTO_HEIGHT] became `0`, Auto also *starts* at the provider's own rendition
 * ([startRung]) instead of the bottom, so the common case — a line that can carry the channel — is
 * no longer charged a re-encoded picture for the first minute and a half of every tune.
 *
 * ═══ Why buffer health, not a bandwidth number ═══
 *
 * The obvious input is measured throughput, but on this player it is the wrong one. Throughput is
 * only observable for ExoPlayer ([ThroughputTracker] is a Media3 `TransferListener`), and mpv — the
 * primary engine — exposes none. What both engines do expose is the demuxer cache: seconds of video
 * already downloaded and waiting. That is the honest signal, because it already folds in bitrate,
 * latency, packet loss and a provider that throttles mid-stream. A line "fast enough" on paper that
 * stalls every twenty seconds is a line the viewer calls broken, and only the buffer sees that.
 *
 * ═══ Why asymmetric ═══
 *
 * Dropping is cheap and stepping up is expensive: each switch reloads the item on a new rung URL
 * (see `OwnTVPlayer.selectVideo`), so the picture freezes for a moment. A wrong drop costs sharpness
 * for a few seconds; a wrong raise costs a stall, then a drop, then another stall. So we fall on the
 * first real sign of trouble and climb only after the cache has been comfortable for a long,
 * uninterrupted stretch.
 *
 * ═══ Why a floor on switching ═══
 *
 * A line sitting exactly at the edge of two rungs will oscillate forever if nothing stops it, and
 * constant reloads are worse than either rung. [COOLDOWN_MS] after any switch, plus a climb that
 * needs [RISE_STREAK] consecutive healthy samples, makes oscillation cost time rather than loop.
 */
object AdaptiveQuality {

    /** Cache at or under this means the line is not keeping up — drop now. */
    const val LOW_CACHE_S = 2.5

    /** Cache at or over this is headroom; enough of it in a row earns a step up. */
    const val HIGH_CACHE_S = 8.0

    /** Healthy samples in a row before climbing. At one sample/second this is a settled minute. */
    const val RISE_STREAK = 45

    /** No switch may follow another sooner than this — the anti-oscillation floor. */
    const val COOLDOWN_MS = 20_000L

    /** A stall is unambiguous: drop immediately regardless of streaks. */
    const val STALL_DROP = true

    /**
     * How long after a switch a stall is read as "still filling" rather than "the line cannot cope".
     *
     * With the pre-buffer gate off (`SalamTvDefaults.PREROLL_SECS`) playback begins before the cache
     * has anything in it, so the first seconds of every channel report `paused-for-cache` and a cache
     * near zero — the same signals a genuinely failing line gives. Without this grace the policy would
     * read the normal start of every channel as four failures in four seconds and walk the subscriber
     * down to the bottom rung before the picture had settled, on a line that was never in trouble.
     *
     * It is deliberately shorter than [COOLDOWN_MS]: a line that is still stalling after it has had
     * this long to fill really is struggling, and must be allowed to drop without waiting out the
     * full cooldown.
     */
    const val STARTUP_GRACE_MS = 8_000L

    /**
     * One observation of how playback is going.
     *
     * @param cacheSeconds video already downloaded and waiting (mpv `demuxer-cache-duration`,
     *   ExoPlayer buffered-ahead). Negative when the engine cannot say — treated as "no opinion".
     * @param stalled playback halted waiting for data (mpv `paused-for-cache`, Exo `STATE_BUFFERING`
     *   while playWhenReady).
     */
    data class Sample(val cacheSeconds: Double, val stalled: Boolean)

    /**
     * Mutable decision state for one playing item. Reset it whenever the item changes: streaks from
     * the previous channel say nothing about this one.
     */
    class State {
        internal var healthyStreak = 0
        internal var lastSwitchMs = 0L
        /** The rung Auto settled on, or 0 before the first decision. */
        var current: Int = 0
            internal set

        fun reset() {
            healthyStreak = 0
            lastSwitchMs = 0L
            current = 0
        }
    }

    /**
     * The rung to be on now, or `null` to stay put.
     *
     * @param ladder heights the panel allows this subscriber, any order; empty disables Auto.
     * @param nowMs monotonic clock (`SystemClock.elapsedRealtime`), passed in so the policy stays
     *   testable without Android.
     */
    /**
     * Where Auto begins, from [rungs] sorted ascending.
     *
     * [StreamQuality.AUTO_HEIGHT] of `0` means the top rung — the provider's own rendition, which the
     * panel copies rather than re-encodes. Starting there and falling on the first real sign of
     * trouble costs a weak line a few seconds of stalling once; starting at the bottom cost every
     * good line a re-encoded picture for the ~90 s [RISE_STREAK] needs to climb two rungs, on every
     * single tune. A positive [StreamQuality.AUTO_HEIGHT] restores the old pick exactly.
     */
    fun startRung(rungs: List<Int>): Int =
        if (StreamQuality.AUTO_HEIGHT <= 0) rungs.last()
        else rungs.lastOrNull { it <= StreamQuality.AUTO_HEIGHT } ?: rungs.first()

    fun decide(state: State, sample: Sample, ladder: List<Int>, nowMs: Long): Int? {
        if (ladder.isEmpty()) return null
        val rungs = ladder.distinct().sorted()

        // First decision: start where [startRung] says, then let real playback correct it. One second
        // of actual buffer tells us more than any prior assumption about the line.
        if (state.current == 0) {
            val start = startRung(rungs)
            state.current = start
            state.lastSwitchMs = nowMs
            return start
        }

        val idx = rungs.indexOf(state.current).let { if (it < 0) nearestIndex(rungs, state.current) else it }
        val sinceSwitch = nowMs - state.lastSwitchMs
        val cooling = sinceSwitch < COOLDOWN_MS
        // Still filling after a load or a rung change — see [STARTUP_GRACE_MS].
        if (sinceSwitch < STARTUP_GRACE_MS) return null

        val struggling = (STALL_DROP && sample.stalled) ||
            (sample.cacheSeconds in 0.0..LOW_CACHE_S)

        if (struggling) {
            state.healthyStreak = 0
            if (idx == 0) return null                 // already lowest — nothing left to give
            if (cooling && !sample.stalled) return null
            val next = rungs[idx - 1]
            state.current = next
            state.lastSwitchMs = nowMs
            return next
        }

        // "No opinion" samples neither build nor break a streak: an engine that cannot report its
        // cache must not be read as either healthy or struggling.
        if (sample.cacheSeconds < 0) return null

        if (sample.cacheSeconds >= HIGH_CACHE_S) state.healthyStreak++ else state.healthyStreak = 0

        if (state.healthyStreak >= RISE_STREAK && !cooling && idx < rungs.lastIndex) {
            val next = rungs[idx + 1]
            state.healthyStreak = 0
            state.current = next
            state.lastSwitchMs = nowMs
            return next
        }
        return null
    }

    /** Closest rung at or below [h], else the lowest — for when the ladder changed under us. */
    private fun nearestIndex(sortedRungs: List<Int>, h: Int): Int {
        val i = sortedRungs.indexOfLast { it <= h }
        return if (i >= 0) i else 0
    }
}
