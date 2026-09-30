package dev.hoardkeeper.sound;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.scan.ContainerCandidate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Plays the short chime that marks a container having been read successfully.
 *
 * <p>Thin on purpose: every decision about <em>what</em> it should sound like lives in
 * {@link ChimePitch}, which has no Minecraft types and is unit-tested; this class only owns the one
 * call into the game.
 *
 * <p><b>Client-only, by construction.</b> {@code ClientLevel.playLocalSound} plays the sound
 * straight into this client's sound engine and sends nothing to the server, so nobody else on the
 * server hears anything. That matters for a mod whose entire premise is that a scan is invisible to
 * everyone but the person running it — a sound that leaked as a packet would undo that in the most
 * literal way possible.
 *
 * <p>Sound is a courtesy, never a correctness concern: nothing here can change what the scan does,
 * and a missing level simply means no chime.
 */
public final class ScanChime {

    /**
     * Sounds are positional and centred on the block, so the chime comes from the chest the scanner
     * just read rather than from inside the player's head. With the HUD's direction arrow that makes
     * the audio actually informative — you can hear the scanner working its way down a wall.
     */
    private static final double BLOCK_CENTRE = 0.5;

    /**
     * {@code false} = do not delay playback by distance. Distance delay is the "thunder rolls in
     * late" effect; over the handful of blocks a scan covers it would only smear the run's timing,
     * which is the one thing the rising pitch depends on being crisp.
     */
    private static final boolean NO_DISTANCE_DELAY = false;

    private final HoardkeeperConfig config;
    private final ChimePitch pitch;

    public ScanChime(HoardkeeperConfig config) {
        this.config = config;
        this.pitch = new ChimePitch(config.scanSoundRunResetMillis);
    }

    /**
     * One container was read successfully. Called from {@code ScanController.onContainerContent} on
     * the client thread — the same thread the packet callbacks already arrive on, so this needs no
     * {@code mc.execute} hop.
     *
     * <p>Deliberately not called for {@code unread} or {@code gone} containers: a chime is a reward
     * for a container whose contents are now on disk, and ringing it for a failure would make the
     * two indistinguishable by ear during a scan that is quietly failing.
     */
    public void onScanned(ClientLevel level, ContainerCandidate candidate) {
        if (!config.scanSoundEnabled || level == null || candidate == null) {
            return;
        }

        // nanoTime, not currentTimeMillis: only the gap between two containers matters, and a
        // wall-clock adjustment mid-scan must not be able to look like a pause (or like no pause).
        float notePitch = pitch.next(System.nanoTime() / 1_000_000L);

        // AMETHYST_BLOCK_CHIME is a plain SoundEvent in 26.2, unlike NOTE_BLOCK_* which are
        // Holder.Reference<SoundEvent> and would need .value() here.
        level.playLocalSound(
                candidate.pos[0] + BLOCK_CENTRE,
                candidate.pos[1] + BLOCK_CENTRE,
                candidate.pos[2] + BLOCK_CENTRE,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS,
                config.scanSoundVolume,
                notePitch,
                NO_DISTANCE_DELAY);
    }

    /** Drops the run, so the next scan's first container starts at the bottom of the scale. */
    public void reset() {
        pitch.reset();
    }
}
