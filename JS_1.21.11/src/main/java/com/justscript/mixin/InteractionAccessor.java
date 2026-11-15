package com.justscript.mixin;

import net.minecraft.entity.decoration.InteractionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватным размерам и отклику Interaction (хитбоксы). */
@Mixin(InteractionEntity.class)
public interface InteractionAccessor {
	@Invoker("setInteractionWidth")
	void js$setWidth(float width);

	@Invoker("setInteractionHeight")
	void js$setHeight(float height);

	@Invoker("setResponse")
	void js$setResponse(boolean response);
}
