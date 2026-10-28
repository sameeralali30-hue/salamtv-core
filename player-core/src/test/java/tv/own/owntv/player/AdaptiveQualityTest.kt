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
    fun `starts on the provider's own rendition, not a rung we re-encoded`() {
        val s = AdaptiveQuality.State()
        val first = AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        // The complaint this fixes: every Auto viewer watched an x264 rung while the provider was
        // handing the panel clean H.264 it would have copied untouched. A line that cannot carry it
        // drops on the first real sign of trouble - see the stall tests below.
        assertEquals(1080, first)
        assertEquals(AdaptiveQuality.startRung(ladder.sorted()), first)
    }

    @Test
    fun `the start is the top of what the panel allows, whatever that is`() {
        // Not "1080 because we like 1080": the subscriber's cap is the panel's word, and Auto starts
        // at the top of it. A free account capped at 480 starts at 480, not at a rung above its plan.
        assertEquals(1080, AdaptiveQuality.startRung(listOf(240, 360, 480, 720, 1080)))
        assertEquals(480, AdaptiveQuality.startRung(listOf(240, 480)))
        assertEquals(360, AdaptiveQuality.startRung(listOf(360)))
    }

    /**
     * A state already settled on [h], as if the line had carried Auto there.
     *
     * Since Auto now starts at the top, every test about *climbing* has to put the state somewhere
     * it can climb from — otherwise it asserts nothing: a ladder's top rung has nowhere to rise to,
     * and the test would pass whatever the policy did.
     */
    private fun settledAt(h: Int) = AdaptiveQuality.State().also { it.current = h }

    /** Just past the refill grace, still well inside the anti-oscillation cooldown. */
    private val afterGrace = AdaptiveQuality.STARTUP_GRACE_MS + 1_000L

    @Test
    fun `a stall drops one rung immediately, ignoring cooldown`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)       // settles on 1080, the top
        // Still inside COOLDOWN_MS: a stall must override it, or the viewer sits frozen.
        val dropped = AdaptiveQuality.decide(s, stalling(), ladder, afterGrace)
        assertEquals(720, dropped)
    }

    @Test
    fun `the first seconds of a channel are not read as a failing line`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)        // settles on 1080, the top
        // With no pre-buffer the picture starts on an empty cache, so these are the NORMAL signals
        // of a channel opening - not a line that cannot cope. Dropping here would walk every
        // subscriber to the floor on every channel change.
        var t = 1_000L
        while (t < AdaptiveQuality.STARTUP_GRACE_MS) {
            assertNull(AdaptiveQuality.decide(s, stalling(), ladder, t)); t += 1_000L
        }
        assertEquals(1080, s.current)
    }

    @Test
    fun `at the lowest rung a stall changes nothing`() {
        val s = settledAt(240)                                      // the floor of this ladder
        // Nothing left to give: the policy must return null rather than thrash the item on a reload
        // that cannot improve anything.
        assertNull(AdaptiveQuality.decide(s, stalling(), ladder, afterGrace))
        assertEquals(240, s.current)
    }

    @Test
    fun `climbing needs a long healthy streak, not one good sample`() {
        val s = settledAt(240)                                  // a line that was walked down earlier
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK - 1) {
            assertNull(AdaptiveQuality.decide(s, healthy(), ladder, t)); t += 1_000L
        }
        assertEquals(360, AdaptiveQuality.decide(s, healthy(), ladder, t))
    }

    @Test
    fun `one bad sample resets the streak`() {
        val s = settledAt(240)
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK - 1) { AdaptiveQuality.decide(s, healthy(), ladder, t); t += 1_000L }
        // A dip mid-climb: the line is not as comfortable as the streak suggested.
        AdaptiveQuality.decide(s, AdaptiveQuality.Sample(5.0, stalled = false), ladder, t); t += 1_000L
        assertNull(AdaptiveQuality.decide(s, healthy(), ladder, t))
    }

    @Test
    fun `cooldown blocks a climb so the picture does not flicker`() {
        val s = settledAt(240)                                   // last switch at t=0
        var t = AdaptiveQuality.STARTUP_GRACE_MS + 100L
        repeat(AdaptiveQuality.RISE_STREAK + 5) { AdaptiveQuality.decide(s, healthy(), ladder, t); t += 100L }
        // Streak is long enough but we are still inside the cooldown window.
        assertEquals(240, s.current)
    }

    @Test
    fun `an engine that cannot report its cache is not read as healthy`() {
        val s = settledAt(240)                                   // somewhere a climb is possible
        var t = AdaptiveQuality.COOLDOWN_MS + 1_000L
        repeat(AdaptiveQuality.RISE_STREAK * 2) {
            AdaptiveQuality.decide(s, AdaptiveQuality.Sample(-1.0, stalled = false), ladder, t); t += 1_000L
        }
        // Unknown must never accumulate toward a climb.
        assertEquals(240, s.current)
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
    fun `a capped subscriber starts at the top of their own cap`() {
        val s = AdaptiveQuality.State()
        // Panel caps this subscriber at 480/240 only: 480 is their provider-quality equivalent.
        assertEquals(480, AdaptiveQuality.decide(s, healthy(), listOf(480, 240), 0L))
    }

    @Test
    fun `reset forgets the previous channel`() {
        val s = AdaptiveQuality.State()
        AdaptiveQuality.decide(s, healthy(), ladder, 0L)
        AdaptiveQuality.decide(s, stalling(), ladder, 1_000L)
        s.reset()
        assertEquals(0, s.current)
        assertEquals(1080, AdaptiveQuality.decide(s, healthy(), ladder, 5_000L))
    }
}
