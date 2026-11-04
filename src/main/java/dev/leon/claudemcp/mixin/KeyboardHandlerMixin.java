package dev.leon.claudemcp.mixin;

import dev.leon.claudemcp.ClaudeMcp;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Detects real keyboard input: user override of Claude's tasks and "key" hooks. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void claudemcp$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle()) return;
		if (action == 1 && mc.gui.screen() == null) {
			Options o = mc.options;
			if (o.keyUp.matches(event) || o.keyDown.matches(event) || o.keyLeft.matches(event) || o.keyRight.matches(event)
					|| o.keyJump.matches(event) || o.keyShift.matches(event)) {
				ClaudeMcp.onUserInput("key " + event.key());
			}
		}
		if (ClaudeMcp.hooks() != null && ClaudeMcp.hooks().has("key")) {
			ClaudeMcp.hooks().fire("key", Map.of("key", event.key(), "modifiers", event.modifiers(),
					"action", action, "screen", mc.gui.screen() != null));
		}
	}
}
