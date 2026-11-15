package com.justscript.mixin;

import com.justscript.editor3d.Editor3DState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Отделяет камеру 3D-редактора от тела игрока: после обычного расчёта
 * подменяем позицию и поворот камеры на «свободные» значения редактора.
 */
@Mixin(Camera.class)
public abstract class Editor3DCameraMixin {
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "update", at = @At("TAIL"))
	private void js$applyEditorCamera(DeltaTracker delta, CallbackInfo ci) {
		Editor3DState state = Editor3DState.get();
		if (!state.isActive()) {
			return;
		}
		setPosition(state.camX(), state.camY(), state.camZ());
		setRotation(state.yaw(), state.pitch());
	}
}
