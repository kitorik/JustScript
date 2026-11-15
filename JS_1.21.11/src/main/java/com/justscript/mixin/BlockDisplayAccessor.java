package com.justscript.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.entity.decoration.DisplayEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватному setBlockState у BlockDisplay. */
@Mixin(DisplayEntity.BlockDisplayEntity.class)
public interface BlockDisplayAccessor {
	@Invoker("setBlockState")
	void js$setBlockState(BlockState state);
}
