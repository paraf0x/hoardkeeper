package dev.hoardkeeper.sound;

/**
 * Decides what pitch the next "container scanned" chime should be played at — pure arithmetic over
 * a step index and a clock, with no Minecraft dependency, so it can be unit-tested directly (the
 * same split {@code ScreenBlockWatcher} and {@code HudTextModel} use).
 *
 * <p><b>Why a rising run rather than one fixed tone.</b> At the default pacing
 * ({@code minTicksBetweenOpens=4}) a scan reads roughly five containers a second and keeps that up
 * for minutes. One tone repeated at 5 Hz for two minutes is not a reward, it is a smoke alarm — the
 * first thing a player does is turn it off, and then they lose the feedback entirely. So the pitch
 * climbs while containers keep coming and drops back to the bottom after a pause: every cluster of
 * chests becomes its own little rising run, and walking to the next room re-arms it. That is the
 * "collecting things" shape, and it is why {@link #resetAfterMillis} exists.
 *
 * <p><b>Why a pentatonic scale and not {@code pitch += 0.05}.</b> Two reasons, and both are bugs
 * avoided rather than decoration:
 * <ul>
 *   <li>Minecraft silently clamps a sound's pitch to {@code [0.5, 2.0]}. A naive linear increment
 *       runs off the top after a handful of containers and every note from there on is identical —
 *       the design would degrade into exactly the fixed tone it was meant to avoid, and nothing
 *       would report that it had. Every value this class returns is a table lookup that is inside
 *       the legal range by construction; see {@link #SEMITONES}.</li>
 *   <li>Pitch in Minecraft is a frequency multiplier, so equal <em>additive</em> steps are unequal
 *       musical intervals — the run would sound like it is slowing down as it rises. Stepping in
 *       semitones (a multiply by {@code 2^(1/12)}) keeps the intervals even.</li>
 * </ul>
 * The degrees chosen are the <em>major pentatonic</em>, which contains no semitone clashes: at
 * 5 Hz the tails of consecutive chimes overlap, and on a chromatic or diatonic run those overlaps
 * beat against each other. Any two pentatonic notes sound consonant together, so the overlap
 * becomes a chord instead of a clash.
 */
public final class ChimePitch {

    /**
     * The run, in semitones above {@link #BASE_PITCH}: major pentatonic (0-2-4-7-9) across two
     * octaves, ending exactly on the second octave.
     *
     * <p>Eleven steps is deliberate. Fewer (a single octave, six steps) loops about every second at
     * the default pace and reads as a stuck jingle; more cannot exist, because 24 semitones above
     * 0.5 is exactly 2.0 — Minecraft's ceiling — and step 11 would be the first value the game
     * would clamp.
     */
    private static final int[] SEMITONES = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};

    /** Minecraft's pitch floor, and the bottom of the run. */
    public static final float BASE_PITCH = 0.5f;

    /** Minecraft's pitch ceiling. {@code BASE_PITCH * 2^(24/12)} lands on it exactly. */
    public static final float MAX_PITCH = 2.0f;

    private final long resetAfterMillis;

    private int step;

    /**
     * Whether {@link #lastMillis} holds a real reading.
     *
     * <p>A flag rather than a sentinel timestamp on purpose. The obvious sentinel is
     * {@code Long.MIN_VALUE}, and {@code now - Long.MIN_VALUE} overflows to a negative number — the
     * exact bug {@code ScanController.NEVER} documents having cost a measurement run. There is no
     * arithmetic to get wrong here.
     */
    private boolean hasLast;
    private long lastMillis;

    /**
     * @param resetAfterMillis a gap longer than this restarts the run at {@link #BASE_PITCH}. Must
     *                         sit comfortably above the normal gap between two containers (~200 ms
     *                         at default pacing) or the run resets constantly and never rises.
     */
    public ChimePitch(long resetAfterMillis) {
        this.resetAfterMillis = resetAfterMillis;
    }

    /**
     * Advances the run and returns the pitch for the container being reported now.
     *
     * @param nowMillis a millisecond reading from a monotonic clock. Only differences are used, so
     *                  the origin is irrelevant — callers pass {@code System.nanoTime() / 1_000_000}
     *                  rather than wall-clock time, so an NTP step cannot rewind the run.
     * @return a pitch within {@code [0.5, 2.0]}, so the caller never hands Minecraft a value it
     *         would clamp.
     */
    public float next(long nowMillis) {
        long gap = hasLast ? nowMillis - lastMillis : Long.MAX_VALUE;
        if (gap < 0 || gap > resetAfterMillis) {
            // Negative is defensive: nanoTime is monotonic, but a caller that ever passes wall-clock
            // time would otherwise let a backwards clock jump look like "no gap at all" and keep
            // climbing. Restarting is the harmless answer.
            step = 0;
        } else {
            // Wraps rather than sticking at the top: a run that reaches 2.0 and stays there is the
            // fixed tone this whole class exists to avoid. The wrap is audible as the start of the
            // next rise, which is the point.
            step = (step + 1) % SEMITONES.length;
        }
        hasLast = true;
        lastMillis = nowMillis;
        return pitchForStep(step);
    }

    /** The pitch at run position {@code step}, for tests and for reasoning about the table. */
    public static float pitchForStep(int step) {
        return (float) (BASE_PITCH * Math.pow(2.0, SEMITONES[Math.floorMod(step, SEMITONES.length)] / 12.0));
    }

    /** How many notes the run has before it wraps back to {@link #BASE_PITCH}. */
    public static int runLength() {
        return SEMITONES.length;
    }

    /** Forgets the run, so the next chime starts at the bottom. Called when a scan starts or stops. */
    public void reset() {
        hasLast = false;
        step = 0;
    }
}
