package com.justscript.mixin;

import com.justscript.util.BookAutoConfirm;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ловит открытие книжки подтверждения от /module load-url. */
@Mixin(BookScreen.class)
public class BookScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void js$autoConfirmOnInit(CallbackInfo ci) {
		BookAutoConfirm.tryConfirm(this);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void js$autoConfirmOnRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		BookAutoConfirm.tryConfirm(this);
	}
}
