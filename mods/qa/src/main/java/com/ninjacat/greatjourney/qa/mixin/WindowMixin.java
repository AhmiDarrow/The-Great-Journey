package com.ninjacat.greatjourney.qa.mixin;

import com.mojang.blaze3d.platform.Window;
import com.ninjacat.greatjourney.qa.Qa;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEST ONLY. A playtest client runs while the owner works on the same PC: its window is created hidden and never
 * focused, so it can not take the keyboard or the mouse. (The harness turns NeoForge's early window off, so the game
 * window is created here, with these hints, not by the early loading screen.) Rendering goes to Minecraft's own
 * framebuffer, which is what the harness photographs.
 */
@Mixin(Window.class)
abstract class WindowMixin {
    @Shadow @Final private long window;

    /** Belt and braces: should anything show the window later, it appears far outside every monitor. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void greatjourney_qa$offScreen(CallbackInfo ci) {
        if (!Qa.driving() || !Qa.BACKGROUND) return;
        GLFW.glfwSetWindowPos(window, -10000, -10000);
        GLFW.glfwHideWindow(window);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void greatjourney_qa$background(CallbackInfo ci) {
        if (!Qa.driving() || !Qa.BACKGROUND) return;
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        // the early window (off here) normally gives the game the newest OpenGL the driver has; vanilla asks for 3.2,
        // which some pack mods (Simple Clouds needs 4.3) refuse: ask for 4.6 as the early window would
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
    }
}
