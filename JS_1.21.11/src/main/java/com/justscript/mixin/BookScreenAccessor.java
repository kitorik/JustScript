package com.justscript.mixin;

import net.minecraft.client.gui.screen.ingame.BookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к страницам открытой книжки (для автоподтверждения). */
@Mixin(BookScreen.class)
public interface BookScreenAccessor {
	@Accessor("contents")
	BookScreen.Contents js$getContents();
}
