package com.justscript.mixin;

import com.justscript.util.BookAutoConfirm;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ловит открытие книжки подтверждения от /module load-url. */
@Mixin(BookViewScreen.class)
public class BookScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void js$autoConfirmOnInit(CallbackInfo ci) {
		BookAutoConfirm.tryConfirm(this);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void js$autoConfirmOnRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		BookAutoConfirm.tryConfirm(this);
	}
}
