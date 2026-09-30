package dev.hoardkeeper.deposit;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * What the deposit sounds like — so the player knows without reading that a click was taken, that
 * something went in, that nothing fitted, and that the walk is over.
 *
 * <p>Client-only, like the scan chime: {@code ClientLevel.playLocalSound} plays for this player and
 * sends nothing anywhere. All of it is off with {@code depositSounds}.
 *
 * <ul>
 *   <li><b>Clicked</b> — a UI click at the container: the sneak-right-click was taken, the deposit
 *       will follow.
 *   <li><b>Deposited</b> — a bundle insert at the container, a little higher the more went in.
 *   <li><b>Nothing fits</b> — the bundle's refusal, for a clicked container that took nothing.
 *   <li><b>All put away</b> — a soft level-up at the player, once, when the last lit container is done.
 *   <li><b>Mode on / off</b> — one chime up, one down.
 * </ul>
 */
final class DepositSounds {

    private static final double CENTRE = 0.5;
    private static final boolean NO_DISTANCE_DELAY = false;

    private DepositSounds() {
    }

    static void clicked(Minecraft mc, int[] pos) {
        at(mc, pos, SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
    }

    static void deposited(Minecraft mc, int[] pos, int stacks) {
        at(mc, pos, SoundEvents.BUNDLE_INSERT, 1.0f, 0.9f + 0.05f * Math.min(stacks, 8));
    }

    static void nothingFits(Minecraft mc, int[] pos) {
        at(mc, pos, SoundEvents.BUNDLE_INSERT_FAIL, 1.0f, 1.0f);
    }

    static void allPutAway(Minecraft mc) {
        atPlayer(mc, SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
    }

    static void modeOn(Minecraft mc) {
        atPlayer(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.5f);
    }

    static void modeOff(Minecraft mc) {
        atPlayer(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 0.9f);
    }

    private static void at(Minecraft mc, int[] pos, SoundEvent sound, float loudness, float pitch) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        ClientLevel level = mc.level;
        if (!config.depositSounds || level == null) {
            return;
        }
        level.playLocalSound(pos[0] + CENTRE, pos[1] + CENTRE, pos[2] + CENTRE, sound, SoundSource.BLOCKS,
                config.depositSoundVolume * loudness, pitch, NO_DISTANCE_DELAY);
    }

    private static void atPlayer(Minecraft mc, SoundEvent sound, float loudness, float pitch) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (!config.depositSounds || level == null || player == null) {
            return;
        }
        level.playLocalSound(player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS,
                config.depositSoundVolume * loudness, pitch, NO_DISTANCE_DELAY);
    }
}
