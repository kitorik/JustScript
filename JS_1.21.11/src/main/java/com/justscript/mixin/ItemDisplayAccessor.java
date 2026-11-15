package com.justscript.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватным сеттерам ItemDisplay. */
@Mixin(DisplayEntity.ItemDisplayEntity.class)
public interface ItemDisplayAccessor {
	@Invoker("setItemStack")
	void js$setItemStack(ItemStack stack);

	@Invoker("setItemDisplayContext")
	void js$setItemDisplayContext(ItemDisplayContext context);
}
