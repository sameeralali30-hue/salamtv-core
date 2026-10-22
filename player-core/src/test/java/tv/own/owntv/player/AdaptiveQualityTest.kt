package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SALAMTV] The Auto policy, proven rather than asserted.
 *
 * Every case here is a complaint a subscriber could make: "it starts at the wrong quality",
 * "it keeps freezing and never drops", "it stays blurry on my fibre", "the picture keeps
 * flickering between qualities". Each test is the fix for one of them.
 */
class AdaptiveQualityTest {

    private val ladder = listOf(1080, 720, 480, 360, 240)

    private fun healthy(cache: Double = 12.0) = AdaptiveQuality.Sample(cache, stalled = false)
    private fun stalling() = AdaptiveQuality.Sample(0.5, stalled = true)

    @Test
    fun `starts at the safe default, not the highest`() {
        val s = AdaptiveQuality.State()
        val first = AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        // A viewer on an unknown line must not be handed 1080p and left to stall.
        assertEquals(StreamQuality.AUTO_HEIGHT, first)
    }

    /** Just past the refill grace, still well inside the anti-oscillation cooldown. */
    private val afterGrace = AdaptiveQuality.STARTUP_GRACE_MS + 1_000L

    @Test
    fun `a stall drops one rung immediately, ignoring cooldown`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)       // settles on 360
        // Still inside COOLDOWN_MS: a stall must override it, or the viewer sits frozen.
        val dropped = AdaptiveQuality.decide(s, stalling(), ladder, afterGrace)
        assertEquals(240, dropped)
    }

    @Test
    fun `the first seconds of a channel are not read as a failing line`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)        // settles on 360
        // With no pre-buffer the picture starts on an empty cache, so these are the NORMAL signals
        // of a channel opening - not a line that cannot cope. Dropping here would walk every
        // subscriber to the floor on every channel change.
        var t = 1_000L
        while (t < AdaptiveQuality.STARTUP_GRACE_MS) {
            assertNull(AdaptiveQuality.decide(s, stalling(), ladder, t)); t += 1_000L
        }
        assertEquals(StreamQuality.AUTO_HEIGHT, s.current)
    }

    @Test
    fun `at the lowest rung a stall changes nothing`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        AdaptiveQuality.decide(s, stalling(), ladder, afterGrace)   // → 240, the floor
        // A second grace runs from that switch: the rung change reloaded the item.
        assertNull(AdaptiveQuality.decide(s, stalling(), ladder, afterGrace * 2 + 1_000L))
        assertEquals(240, s.current)
    }

    @Test
    fun `climbing needs a long healthy streak, not one good sample`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)        // 360
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK - 1) {
            assertNull(AdaptiveQuality.decide(s, healthy(), ladder, t)); t += 1_000L
        }
        assertEquals(480, AdaptiveQuality.decide(s, healthy(), ladder, t))
    }

    @Test
    fun `one bad sample resets the streak`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK - 1) { AdaptiveQuality.decide(s, healthy(), ladder, t); t += 1_000L }
        // A dip mid-climb: the line is not as comfortable as the streak suggested.
        AdaptiveQuality.decide(s, AdaptiveQuality.Sample(5.0, stalled = false), ladder, t); t += 1_000L
        assertNull(AdaptiveQuality.decide(s, healthy(), ladder, t))
    }

    @Test
    fun `cooldown blocks a climb so the picture does not flicker`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)         // switch at t=0
        var t = 1_000L
        repeat(AdaptiveQuality.RISE_STREAK + 5) { AdaptiveQuality.decide(s, healthy(), ladder, t); t += 100L }
        // Streak is long enough but we are still inside the cooldown window.
        assertEquals(StreamQuality.AUTO_HEIGHT, s.current)
    }

    @Test
    fun `an engine that cannot report its cache is not read as healthy`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK * 2) {
            AdaptiveQuality.decide(s, AdaptiveQuality.Sample(-1.0, stalled = false), ladder, t); t += 1_000L
        }
        // Unknown must never accumulate toward a climb.
        assertEquals(StreamQuality.AUTO_HEIGHT, s.current)
    }

    @Test
    fun `a weak line walks down to the floor and stays`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        // Each drop reloads the item, so each step waits out its own refill grace.
        var t = 1_000L
        repeat(60) { AdaptiveQuality.decide(s, stalling(), ladder, t); t += 1_000L }
        assertEquals(240, s.current)
    }

    @Test
    fun `an empty ladder disables Auto entirely`() {
        val s = AdaptiveQuality.State()
        assertNull(AdaptiveQuality.decide(s, healthy(), emptyList(), 0L))
    }

    @Test
    fun `a ladder without the safe default starts at the closest below it`() {
        val s = AdaptiveQuality.State()
        // Panel caps this subscriber at 480/240 only.
        assertEquals(240, AdaptiveQuality.decide(s, healthy(), listOf(480, 240), 0L))
    }

    @Test
    fun `reset forgets the previous channel`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        AdaptiveQuality.decide(s, stalling(), ladder, 1_000L)
        s.reset()
        assertEquals(0, s.current)
        assertEquals(StreamQuality.AUTO_HEIGHT, AdaptiveQuality.decide(s, healthy(), ladder, 5_000L))
    }
}
