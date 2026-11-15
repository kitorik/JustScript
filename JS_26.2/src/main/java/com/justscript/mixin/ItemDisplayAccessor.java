package com.justscript.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватным сеттерам ItemDisplay. */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
	@Invoker("setItemStack")
	void js$setItemStack(ItemStack stack);

	@Invoker("setItemTransform")
	void js$setItemTransform(ItemDisplayContext context);
}
