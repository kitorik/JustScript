package com.justscript.editor3d;

import com.justscript.Config;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Подсветка в 3D-редакторе: сетка полотна и голубые рамки у объекта
 * под прицелом. Цвет и толщина берутся из настроек.
 */
public final class Editor3DRenderer {
	private Editor3DRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(Editor3DRenderer::afterEntities);
	}

	private static void afterEntities(WorldRenderContext context) {
		Editor3DState state = Editor3DState.get();
		if (!state.isActive()) {
			return;
		}
		MatrixStack matrices = context.matrices();
		if (matrices == null) {
			return;
		}
		Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
		VertexConsumer buffer = context.consumers().getBuffer(RenderLayers.linesTranslucent());
		int color = parseColor(Config.get().editor3dHighlight);
		float width = Math.max(1.0f, Config.get().editor3dOutlineWidth);

		if (Config.get().editor3dGrid) {
			draw(matrices, buffer, camera, state.areaBounds(), withAlpha(color, 0x55), 1.0f);
		}
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible) {
				continue;
			}
			boolean selected = state.isSelected(visualizer.id);
			boolean hovered = visualizer.id.equals(state.hoveredVisualizer());
			if (selected || hovered) {
				Visualizer master = SceneStore.get().masterOf(visualizer);
				double halfX = Math.max(0.05, master.sizeX() / 2.0);
				double halfY = Math.max(0.05, master.sizeY() / 2.0);
				double halfZ = Math.max(0.05, master.sizeZ() / 2.0);
				double offset = Visualizer.JUSTMC_OFFSET;
				Box bounds = new Box(visualizer.x + offset - halfX, visualizer.y + offset - halfY,
						visualizer.z + offset - halfZ, visualizer.x + offset + halfX,
						visualizer.y + offset + halfY, visualizer.z + offset + halfZ);
				draw(matrices, buffer, camera, bounds.expand(0.02),
						withAlpha(color, selected ? 0xFF : 0xCC), selected ? width + 0.5f : width);
			}
		}
		if (state.hoveredBlock() != null) {
			draw(matrices, buffer, camera, new Box(state.hoveredBlock()).expand(0.002),
					withAlpha(color, 0xFF), width);
		}
		String entityId = state.hoveredEntity();
		if (!entityId.isEmpty()) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.world != null) {
				for (var entity : client.world.getEntities()) {
					if (entity.getUuidAsString().equals(entityId)) {
						draw(matrices, buffer, camera, entity.getBoundingBox().expand(0.03),
								withAlpha(color, 0xFF), width);
						break;
					}
				}
			}
		}
	}

	/** Рисует рамку вокруг box; координаты переводим в камерные. */
	private static void draw(MatrixStack matrices, VertexConsumer buffer, Vec3d camera, Box box,
			int color, float width) {
		VoxelShape shape = VoxelShapes.cuboid(box.offset(-camera.x, -camera.y, -camera.z));
		VertexRendering.drawOutline(matrices, buffer, shape, 0, 0, 0, color, width);
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
