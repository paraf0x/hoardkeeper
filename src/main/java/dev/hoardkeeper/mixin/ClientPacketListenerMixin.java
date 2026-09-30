package dev.hoardkeeper.mixin;

import dev.hoardkeeper.MixinTargets;
import dev.hoardkeeper.deposit.DepositService;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.scan.PhantomMenu;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.search.SearchService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the two packets that carry a container's contents, so the scanner can read a chest
 * without a GUI ever appearing.
 *
 * <p><b>Why not {@code @At("HEAD")}.</b> In MC 26.2 the first instruction of both target methods
 * is {@code PacketUtils.ensureRunningOnSameThread(...)}. That method reschedules the packet onto
 * the client thread and then throws {@code RunningOnDifferentThreadException} to unwind the netty
 * I/O thread. A HEAD injection therefore fires <em>twice</em> — once on the netty thread, before
 * the reschedule — and cancelling there would suppress the reschedule entirely, consuming the
 * packet off-thread with all scanner state mutated from the wrong thread.
 *
 * <p>Injecting {@code INVOKE} + {@code Shift.AFTER} on that call is unreachable on the netty
 * thread (the call throws there) and runs exactly once on the client thread. It is still ahead of
 * {@code MenuScreens.create(...)}, which is what builds the menu and calls
 * {@code minecraft.gui.setScreen(...)} — so cancelling suppresses the screen entirely.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    /** See the class javadoc for the injection-point reasoning. */
    @Inject(
            method = "handleOpenScreen",
            at = @At(value = "INVOKE", target = MixinTargets.ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void hoardkeeper$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        // The server has moved on to this menu, so it sends nothing more under the last one's id.
        PhantomMenu.forget();
        if (ScanController.get().onOpenScreen(packet.getContainerId(), packet.getType(), packet.getTitle())) {
            ci.cancel();
            return;
        }
        // The singleplayer deposit's own open: no screen, but a menu it can click. The scanner asks
        // first; the two never have a request in flight together.
        if (DepositService.get().onOpenScreen(packet.getContainerId(), packet.getType(), packet.getTitle())) {
            ci.cancel();
            return;
        }
        // Not swallowed, so this menu is the player's own -- the other half of "the player opened
        // that chest", the half that carries no position. SearchService pairs it with the block it
        // remembered them right-clicking; see its class javadoc for why neither signal is enough
        // alone.
        SearchService.get().onContainerScreenOpened();
        // The passive observer needs the identical fact, and one more thing off this packet: the
        // menu type id, which is the only place the type is stated before the screen exists. It
        // keeps its own pairing rather than sharing SearchService's -- two consumers, two
        // instances, each fed by its own wiring.
        PassiveObserver.get().onContainerScreenOpened(packet.getContainerId(), packet.getType());
    }

    /** See the class javadoc for the injection-point reasoning. */
    @Inject(
            method = "handleContainerContent",
            at = @At(value = "INVOKE", target = MixinTargets.ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void hoardkeeper$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (ScanController.get().onContainerContent(packet.containerId(), packet.items())) {
            ci.cancel();
            return;
        }
        // Never cancelled: the game has to fill the deposit's menu. This only notes that it will.
        DepositService.get().onContainerContent(packet.containerId());
    }

    /**
     * A slot update for a menu this mod opened without a screen — see {@link PhantomMenu}. Vanilla
     * drops it, because the id is not the client's own menu; a player slot among them is applied to
     * the inventory instead, or the item is lost on the client for good.
     */
    @Inject(
            method = "handleContainerSetSlot",
            at = @At(value = "INVOKE", target = MixinTargets.ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void hoardkeeper$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu.containerId == packet.getContainerId()) {
            return;
        }
        int slot = PhantomMenu.inventorySlot(packet.getContainerId(), packet.getSlot());
        if (slot >= 0) {
            player.getInventory().setItem(slot, packet.getItem().copy());
            ci.cancel();
        }
    }

    /**
     * Where the realm key comes from — see {@link RealmKey}. {@code RETURN} rather than the
     * {@code INVOKE} point the two container hooks use: nothing here needs to run before vanilla
     * does, and the netty-thread pass never reaches a return anyway, because
     * {@code ensureRunningOnSameThread} throws out of the method's first instruction.
     *
     * <p>This is also the hook that catches a proxy backend switch. The socket does not move and no
     * join or disconnect fires, so a fresh login packet is the only signal that the realm changed.
     */
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void hoardkeeper$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        RealmKey.get().onSpawnInfo(packet.commonPlayerSpawnInfo().seed());
    }

    /** The other packet carrying {@code CommonPlayerSpawnInfo}; some proxies hand over a world this way. */
    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void hoardkeeper$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        RealmKey.get().onSpawnInfo(packet.commonPlayerSpawnInfo().seed());
    }
}
