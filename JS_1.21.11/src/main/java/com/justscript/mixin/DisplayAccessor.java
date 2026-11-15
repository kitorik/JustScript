package com.justscript.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватному setTransformation у Display (нужно 3D-редактору). */
@Mixin(DisplayEntity.class)
public interface DisplayAccessor {
	@Invoker("setTransformation")
	void js$setTransformation(AffineTransformation transformation);
}
