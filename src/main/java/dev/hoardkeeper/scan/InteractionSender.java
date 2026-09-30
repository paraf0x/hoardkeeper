package dev.hoardkeeper.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The two raw packet interactions the scanner needs: "open this container" and "close it again".
 *
 * <p>Kept apart from {@link ScanController} so the state machine reads as a state machine, and so
 * the two pieces of hard-won protocol knowledge below sit in one small, obvious place.
 */
public final class InteractionSender {

    /**
     * True only while {@link #open} is inside {@code gameMode.useItemOn}.
     *
     * <p>{@code useItemOn} is also where Fabric fires {@code UseBlockCallback}, so a listener on
     * that event hears the scanner's silent opens exactly as it hears the player's own clicks —
     * and nothing in the event itself says which is which. The item search subscribes to it to
     * notice the player opening a highlighted chest, and must not have its highlight quietly
     * cleared by the scanner opening the same chest behind the player's back.
     *
     * <p>Client thread only, hence a plain field: {@code open} is called from
     * {@code ScanController.trySend} on the client thread, the callback fires synchronously inside
     * the call it wraps, and the {@code finally} clears it before anything else can run.
     */
    private static boolean sendingOwnInteraction;

    private InteractionSender() {
    }

    /** Whether the interaction being processed right now is the scanner's, not the player's. */
    public static boolean isSendingOwnInteraction() {
        return sendingOwnInteraction;
    }

    /**
     * Sends a normal block interaction at {@code pos}, aimed at the centre of the block face
     * nearest the player.
     *
     * <p>{@code ServerGamePacketListenerImpl.handleUseItemOn} validates BOTH
     * {@code player.isWithinBlockInteractionRange(pos, 1.0)} AND that the hit vector is less than
     * {@code 1.0000001} from the block centre on every axis. Aiming at the centre of the face
     * nearest the player satisfies both with room to spare — the offset is exactly {@code 0.5} on
     * one axis and {@code 0} on the other two. The scanner deliberately shrinks its <em>own</em>
     * reach by {@code config.reachMargin} rather than relying on the server's {@code +1.0} slack.
     *
     * <p>{@code swing} exists because some anticheat plugins expect the arm-swing packet alongside
     * an interaction; vanilla and Paper do not need it, so it defaults to off in config.
     */
    public static void open(Minecraft mc, LocalPlayer player, BlockPos pos, boolean swing) {
        Vec3 eye = player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(eye.subtract(centre));
        Vec3 hit = centre.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        if (swing) {
            // MC 26.3: swing takes the stack's interact animation; false = do not self-update.
            player.swing(InteractionHand.MAIN_HAND,
                    player.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation(), false);
        }
        sendingOwnInteraction = true;
        try {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        } finally {
            sendingOwnInteraction = false;
        }
    }

    /**
     * Tells the server we are done with the menu {@code containerId}.
     *
     * <p><b>Never {@code player.closeContainer()}.</b> Because {@code handleOpenScreen} was
     * cancelled by the mixin, {@code player.containerMenu} was never replaced — it is still the
     * player's own inventory menu, so {@code closeContainer()} would close the wrong thing (and
     * would send container id {@code 0}, which the server reads as "the player closed their
     * inventory"). Only the raw packet names the phantom menu we actually opened.
     */
    public static void close(LocalPlayer player, int containerId) {
        player.connection.send(new ServerboundContainerClosePacket(containerId));
    }

    // =========================================================================
    // Test seams
    // =========================================================================

    /**
     * Forces {@link #sendingOwnInteraction} back to {@code false}, for the chained client gametest
     * runner ({@code dev.hoardkeeper.gametest.GameTestReset}). Never called from the shipped
     * mod.
     *
     * <p>In practice this flag is never left {@code true}: the {@code finally} in {@link #open}
     * clears it before that method can return, on the same thread, whether or not
     * {@code useItemOn} throws. This exists anyway so the chained runner's reset does not have to
     * reason about that guarantee holding across every scenario forever — a single stray
     * {@code true} here would make every real right-click in the next scenario look like the
     * scanner's own, and {@code SearchService}/{@code PassiveObserver} would silently stop hearing
     * the player at all.
     */
    public static void resetForGameTest() {
        sendingOwnInteraction = false;
    }
}
