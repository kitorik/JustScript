package com.justscript.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватным размерам Interaction (хитбокс визуализатора). */
@Mixin(Interaction.class)
public interface InteractionAccessor {
	@Invoker("setWidth")
	void js$setWidth(float width);

	@Invoker("setHeight")
	void js$setHeight(float height);

	@Invoker("setResponse")
	void js$setResponse(boolean response);
}
