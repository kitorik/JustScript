package com.justscript.mixin;

import com.justscript.editor3d.Editor3DState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Камера 3D-редактора: после обычного расчёта камеры подменяем позицию
 * и поворот на свободные значения. Тело игрока остается на месте.
 */
@Mixin(Camera.class)
public abstract class Editor3DCameraMixin {
	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void js$applyEditorCamera(World world, Entity entity, boolean thirdPerson,
			boolean inverseView, float tickDelta, CallbackInfo ci) {
		Editor3DState state = Editor3DState.get();
		if (!state.isActive()) {
			return;
		}
		setPos(state.camX(), state.camY(), state.camZ());
		setRotation(state.yaw(), state.pitch());
	}
}
