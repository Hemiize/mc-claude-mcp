package dev.leon.claudemcp.mixin;

import dev.leon.claudemcp.ClaudeMcp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects real mouse clicks in-game (attack/use) for the user override. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void claudemcp$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle()) return;
		if (action == 1 && mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed()) {
			ClaudeMcp.onUserInput("mouse " + info.button());
		}
	}
}
