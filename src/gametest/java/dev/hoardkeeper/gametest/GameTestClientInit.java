package dev.hoardkeeper.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;

/**
 * options.txt cannot be pre-seeded because the run directory is deleted before every launch, so
 * the two settings the gametest API does not set itself are set here, in code, every time.
 *
 * <p>Deviation from the brief: {@code Minecraft.getInstance().options} is still null while
 * {@code ClientModInitializer.onInitializeClient()} runs in 26.2 — confirmed by a crash
 * (NullPointerException at this class) on the first {@code runClientGameTest} of Task 1. Options
 * are only readable once the client has finished starting, so the write moves into
 * {@code ClientLifecycleEvents.CLIENT_STARTED}, which fires with the fully-constructed
 * {@code Minecraft} instance.
 */
public final class GameTestClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Options options = client.options;
            options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            options.pauseOnLostFocus = false;
            options.save();
        });
    }
}
