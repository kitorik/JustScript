package com.justscript.editor3d;

import com.justscript.Config;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Подсветка в 3D-редакторе: сетка полотна и голубые рамки у объекта
 * под прицелом. Цвет и толщина берутся из настроек.
 */
public final class Editor3DRenderer {
	private Editor3DRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_MAIN.register(Editor3DRenderer::endMain);
	}

	private static void endMain(LevelRenderContext context) {
		Editor3DState state = Editor3DState.get();
		if (!state.isActive()) {
			return;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		int color = parseColor(Config.get().editor3dHighlight);
		float width = Math.max(1.0f, Config.get().editor3dOutlineWidth);

		if (Config.get().editor3dGrid) {
			draw(context, camera, state.areaBounds(), withAlpha(color, 0x55), 1.0f);
		}
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible) {
				continue;
			}
			boolean selected = state.isSelected(visualizer.id);
			boolean hovered = visualizer.id.equals(state.hoveredVisualizer());
			if (selected || hovered) {
				AABB bounds = Editor3DState.visualizerBounds(visualizer);
				draw(context, camera, bounds.inflate(0.02),
						withAlpha(color, selected ? 0xFF : 0xCC), selected ? width + 0.5f : width);
			}
		}
		if (state.hoveredBlock() != null) {
			draw(context, camera, new AABB(state.hoveredBlock()).inflate(0.002), withAlpha(color, 0xFF), width);
		}
		String entityId = state.hoveredEntity();
		if (!entityId.isEmpty()) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null) {
				for (var entity : minecraft.level.entitiesForRendering()) {
					if (entity.getStringUUID().equals(entityId)) {
						draw(context, camera, entity.getBoundingBox().inflate(0.03), withAlpha(color, 0xFF), width);
						break;
					}
				}
			}
		}
	}

	private static void draw(LevelRenderContext context, Vec3 camera, AABB box, int color, float width) {
		VoxelShape shape = Shapes.create(box.move(-camera.x, -camera.y, -camera.z));
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
				RenderTypes.linesTranslucent(), (pose, buffer) -> {
					// В 26.1 нет готового submitShapeOutline: рисуем сами,
					// подставив текущую позу в отдельный PoseStack.
					com.mojang.blaze3d.vertex.PoseStack stack = new com.mojang.blaze3d.vertex.PoseStack();
					stack.last().set(pose);
					net.minecraft.client.renderer.ShapeRenderer.renderShape(stack, buffer, shape,
							0.0, 0.0, 0.0, color, width);
				});
	}

	private static int withAlpha(int color, int alpha) {
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	/** "#RRGGBB" → 0xFFRRGGBB, с запасным голубым. */
	public static int parseColor(String value) {
		try {
			String hex = value == null ? "" : value.trim().replace("#", "");
			if (hex.length() == 6) {
				return 0xFF000000 | Integer.parseInt(hex, 16);
			}
		} catch (NumberFormatException ignored) {
			// ниже вернём цвет по умолчанию
		}
		return 0xFF4FC3F7;
	}
}
