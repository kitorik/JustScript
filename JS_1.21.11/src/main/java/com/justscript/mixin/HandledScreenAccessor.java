package com.justscript.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к координатам контейнера, чтобы рисовать кнопку в меню мира. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int js$getX();

	@Accessor("y")
	int js$getY();
}
