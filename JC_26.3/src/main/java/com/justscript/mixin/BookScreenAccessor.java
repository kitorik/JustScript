package com.justscript.mixin;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к страницам открытой книжки (для автоподтверждения). */
@Mixin(BookViewScreen.class)
public interface BookScreenAccessor {
	@Accessor("bookAccess")
	BookViewScreen.BookAccess js$getContents();
}
