package dev.hoardkeeper.gametest.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The gametest window must not take keyboard focus from whatever the developer is doing.
 * MC 26.3 creates the window through SDL3, not GLFW: the two activate hints replace the old
 * GLFW_FOCUSED / GLFW_FOCUS_ON_SHOW hints, and SDL positions the window after creation rather
 * than through creation hints, so the parking happens on RETURN. The window lands in the
 * bottom-right corner of the primary display. Test mod only; never in the shipped jar.
 */
@Mixin(Window.class)
abstract class WindowFocusMixin {

    @Inject(method = "createWindow", at = @At("HEAD"))
    private void hoardkeeper$noFocusHints(GpuBackend backend, int width, int height, String title,
                                             CallbackInfoReturnable<Long> cir) {
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
    }

    @Inject(method = "createWindow", at = @At("RETURN"))
    private void hoardkeeper$parkWindow(GpuBackend backend, int width, int height, String title,
                                           CallbackInfoReturnable<Long> cir) {
        long handle = cir.getReturnValueJ();
        if (handle == 0L) {
            return;
        }
        int display = SDLVideo.SDL_GetPrimaryDisplay();
        if (display == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect bounds = SDL_Rect.malloc(stack);
            if (SDLVideo.SDL_GetDisplayBounds(display, bounds)) {
                SDLVideo.SDL_SetWindowPosition(handle,
                        Math.max(0, bounds.x() + bounds.w() - width - 20),
                        Math.max(0, bounds.y() + bounds.h() - height - 60));
            }
        }
    }
}
