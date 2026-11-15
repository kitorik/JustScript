package com.justscript.mixin;

import com.justscript.ui.WorldMenuOverlay;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Рисует кнопку «Скрипты мира» поверх меню мира и ловит клик по ней. */
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void js$drawWorldMenuButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		WorldMenuOverlay.render((AbstractContainerScreen<?>) (Object) this, context, mouseX, mouseY);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void js$clickWorldMenuButton(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (click.button() == 0 && WorldMenuOverlay.click((AbstractContainerScreen<?>) (Object) this, click.x(), click.y())) {
			cir.setReturnValue(true);
		}
	}
}
