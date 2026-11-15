package com.justscript.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к заголовку/подвалу списка игроков (там JustMC пишет имя мира). */
@Mixin(PlayerTabOverlay.class)
public interface PlayerListHudAccessor {
	@Accessor("header")
	Component js$getHeader();

	@Accessor("footer")
	Component js$getFooter();
}