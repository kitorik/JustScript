package com.justscript.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к заголовку/подвалу списка игроков (там JustMC пишет имя мира). */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
	@Accessor("header")
	Text js$getHeader();

	@Accessor("footer")
	Text js$getFooter();
}