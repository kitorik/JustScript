package com.justscript.mixin;

import com.justscript.ui.WorldMenuOverlay;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Рисует кнопку «Скрипты мира» поверх меню мира и ловит клик по ней. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void js$drawWorldMenuButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		WorldMenuOverlay.render((HandledScreen<?>) (Object) this, context, mouseX, mouseY);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void js$clickWorldMenuButton(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (click.button() == 0 && WorldMenuOverlay.click((HandledScreen<?>) (Object) this, click.x(), click.y())) {
			cir.setReturnValue(true);
		}
	}
}
