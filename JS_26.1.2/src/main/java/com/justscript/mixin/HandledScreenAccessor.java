package com.justscript.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к координатам контейнера, чтобы рисовать кнопку в меню мира. */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
	@Accessor("leftPos")
	int js$getX();

	@Accessor("topPos")
	int js$getY();
}
