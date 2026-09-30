package dev.hoardkeeper.gametest.mixin;

import dev.hoardkeeper.gametest.ChatCapture;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every line {@code ChatComponent} is about to display, into {@link ChatCapture}, so a
 * gametest scenario can assert on chat the way {@code PeekRenderer.currentLinesForTest()} lets one
 * assert on the peek card. Test mod only, registered in {@code hoardkeeper-gametest.mixins.json}
 * beside {@link WindowFocusMixin}; never in the shipped jar.
 *
 * <p><b>Why the private four-argument {@code addMessage}, not the public methods that call it.</b>
 * Confirmed against the 26.2 deobfuscated jar with {@code javap -p -c}: {@code
 * addClientSystemMessage(Component)}, {@code addServerSystemMessage(Component)} and {@code
 * addPlayerMessage(Component, MessageSignature, GuiMessageTag)} each immediately delegate into this
 * one private method -- it is the single choke point every line takes on its way into the chat
 * window, chosen over the three public entry points so one inject catches all of them instead of
 * three. {@code ScanChat.info}/{@code linked} (which is what {@code ScanChat.rescanOffer} and
 * {@code resumeOffer} use) reach it by way of {@code LocalPlayer.sendSystemMessage} ->
 * {@code ChatListener.handleSystemMessage(component, true)} -> {@code addServerSystemMessage} ->
 * here -- also confirmed by disassembling {@code LocalPlayer.sendSystemMessage} and
 * {@code ChatListener.handleSystemMessage} rather than assumed.
 *
 * <p>{@code message.getString()} is recorded verbatim, {@code §}-codes and all: this mod's own chat
 * strings ({@code ScanText}) embed them as plain characters in the literal text rather than through
 * a real {@code Style}, so {@code getString()} (which walks content, not style) returns them
 * unchanged. Stripping them back out would need extra work this class has no reason to spend --
 * every assertion so far only needs {@code String.contains}, which sees straight through a handful
 * of {@code §x} pairs sitting in the string.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;"
            + "Lnet/minecraft/network/chat/MessageSignature;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"))
    private void hoardkeeper$capture(Component message, MessageSignature signature,
                                         GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        ChatCapture.record(message.getString());
    }
}
