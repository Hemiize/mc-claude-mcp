package dev.leon.claudemcp.mixin;

import dev.leon.claudemcp.control.BotInput;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces keyboard movement with Claude's simulated input while a movement task runs. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void claudemcp$overrideInput(CallbackInfo ci) {
		if (BotInput.active()) {
			this.keyPresses = BotInput.toInput();
			this.moveVector = BotInput.moveVector();
		}
	}
}
