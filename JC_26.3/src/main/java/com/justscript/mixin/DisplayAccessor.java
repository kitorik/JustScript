package com.justscript.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватному setTransformation у Display (нужен 3D-редактору). */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setTransformation")
	void js$setTransformation(Transformation transformation);
}
