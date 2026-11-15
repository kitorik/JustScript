package com.justscript.editor3d;

import com.justscript.mixin.BlockDisplayAccessor;
import com.justscript.mixin.DisplayAccessor;
import com.justscript.mixin.InteractionAccessor;
import com.justscript.mixin.ItemDisplayAccessor;
import com.mojang.math.Transformation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Клиентские сущности визуализаторов: BlockDisplay / ItemDisplay и
 * Interaction-хитбокс. Сервер о них не знает — это только картинка.
 * <p>Скрытые объекты не создаются вовсе, связанные копии берут вид у
 * «мастера» группы, а во время проигрывания анимации поза берётся из
 * ключевых кадров.
 */
public final class VisualizerEntities {
	private static final Map<String, Display> DISPLAYS = new HashMap<>();
	private static final Map<String, Interaction> HITBOXES = new HashMap<>();
	private static int nextClientId = -100000;

	/** Поза для отрисовки: результат анимации, связей и собственных полей. */
	private record Pose(double x, double y, double z, double scale,
			float yaw, float pitch, float roll) {
	}

	private VisualizerEntities() {
	}

	/** Синхронизирует сцену с сущностями: создаёт, обновляет и убирает лишние. */
	public static void sync(ClientLevel level) {
		Set<String> seen = new HashSet<>();
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible) {
				continue;
			}
			seen.add(visualizer.id);
			Visualizer master = SceneStore.get().masterOf(visualizer);
			Pose pose = poseOf(visualizer, master);
			boolean wantItem = "item".equals(master.kind);

			Display display = DISPLAYS.get(visualizer.id);
			boolean mismatch = display != null
					&& (display instanceof Display.ItemDisplay) != wantItem;
			if (display != null && (display.level() != level || mismatch)) {
				display.discard();
				DISPLAYS.remove(visualizer.id);
				display = null;
			}
			if (display == null) {
				display = createDisplay(level, wantItem);
				if (display != null) {
					level.addEntity(display);
					DISPLAYS.put(visualizer.id, display);
				}
			}
			if (display != null) {
				apply(display, master, pose);
			}
			if (visualizer.hitbox) {
				Interaction hitbox = HITBOXES.get(visualizer.id);
				if (hitbox == null || hitbox.level() != level) {
					hitbox = createHitbox(level, visualizer);
					level.addEntity(hitbox);
					HITBOXES.put(visualizer.id, hitbox);
				}
				applyHitbox(hitbox, visualizer, pose);
			} else {
				Interaction hitbox = HITBOXES.remove(visualizer.id);
				if (hitbox != null) {
					hitbox.discard();
				}
			}
		}
		for (Map.Entry<String, Display> entry : new HashMap<>(DISPLAYS).entrySet()) {
			if (!seen.contains(entry.getKey())) {
				entry.getValue().discard();
				DISPLAYS.remove(entry.getKey());
			}
		}
		for (Map.Entry<String, Interaction> entry : new HashMap<>(HITBOXES).entrySet()) {
			if (!seen.contains(entry.getKey())) {
				entry.getValue().discard();
				HITBOXES.remove(entry.getKey());
			}
		}
	}

	/** Итоговая поза объекта: анимация → свои поля (связанные копии делят только вид). */
	private static Pose poseOf(Visualizer visualizer, Visualizer master) {
		Visualizer.Key sample = ScenePlayback.get().sample(visualizer);
		if (sample != null) {
			return new Pose(sample.x, sample.y, sample.z, sample.scale,
					sample.yaw, sample.pitch, sample.roll);
		}
		return new Pose(visualizer.x, visualizer.y, visualizer.z, visualizer.scale,
				visualizer.yaw, visualizer.pitch, visualizer.roll);
	}

	private static Display createDisplay(ClientLevel level, boolean item) {
		Display display;
		if (item) {
			display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		} else {
			display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		}
		prepareClientEntity(display);
		return display;
	}

	/** Клиентская сущность должна иметь уникальные id и UUID. */
	private static void prepareClientEntity(Entity entity) {
		entity.setId(nextClientId--);
		entity.setUUID(java.util.UUID.randomUUID());
	}

	private static void apply(Display display, Visualizer master, Pose pose) {
		display.setPos(pose.x + Visualizer.JUSTMC_OFFSET, pose.y + Visualizer.JUSTMC_OFFSET,
				pose.z + Visualizer.JUSTMC_OFFSET);
		if (display instanceof Display.BlockDisplay blockDisplay) {
			Identifier id = Identifier.tryParse(master.value);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
			((BlockDisplayAccessor) blockDisplay).js$setBlockState(
					(block == null ? Blocks.STONE : block).defaultBlockState());
		} else if (display instanceof Display.ItemDisplay itemDisplay) {
			Identifier id = Identifier.tryParse(master.value);
			Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
			((ItemDisplayAccessor) itemDisplay).js$setItemStack(
					new ItemStack(item == null ? Items.DIAMOND : item));
			((ItemDisplayAccessor) itemDisplay).js$setItemTransform(ItemDisplayContext.GROUND);
		}
		Quaternionf rotation = new Quaternionf().rotationYXZ(
				(float) Math.toRadians(pose.yaw),
				(float) Math.toRadians(pose.pitch),
				(float) Math.toRadians(pose.roll));
		float scale = (float) Math.max(0.02, pose.scale);
		((DisplayAccessor) display).js$setTransformation(
				new Transformation(new Vector3f(0, 0, 0), rotation,
						new Vector3f((float) master.sizeX(), (float) master.sizeY(),
								(float) master.sizeZ()), new Quaternionf()));
	}

	private static Interaction createHitbox(ClientLevel level, Visualizer visualizer) {
		Interaction interaction = new Interaction(EntityTypes.INTERACTION, level);
		prepareClientEntity(interaction);
		interaction.setInvisible(true);
		interaction.setNoGravity(true);
		interaction.setSilent(true);
		interaction.addTag(visualizer.hitboxTag());
		interaction.setCustomName(Component.literal(visualizer.hitboxTag()));
		interaction.setCustomNameVisible(false);
		return interaction;
	}

	private static void applyHitbox(Interaction interaction, Visualizer visualizer, Pose pose) {
		interaction.setPos(pose.x + Visualizer.JUSTMC_OFFSET, pose.y + Visualizer.JUSTMC_OFFSET,
				pose.z + Visualizer.JUSTMC_OFFSET);
		InteractionAccessor accessor = (InteractionAccessor) interaction;
		accessor.js$setWidth((float) Math.max(0.1, Math.max(visualizer.sizeX(), visualizer.sizeZ())));
		accessor.js$setHeight((float) Math.max(0.1, visualizer.sizeY()));
		accessor.js$setResponse(false);
	}

	/** Убирает все клиентские сущности редактора из мира. */
	public static void clear() {
		for (Entity display : DISPLAYS.values()) {
			display.discard();
		}
		DISPLAYS.clear();
		for (Entity hitbox : HITBOXES.values()) {
			hitbox.discard();
		}
		HITBOXES.clear();
	}

	/** Ближайший видимый и не заблокированный визуализатор под лучом. */
	public static Visualizer pick(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
		Visualizer best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible || visualizer.locked) {
				continue;
			}
			AABB box = Editor3DState.visualizerBounds(visualizer).inflate(0.15);
			var hit = box.clip(from, to);
			if (hit.isPresent()) {
				double distance = from.distanceToSqr(hit.get());
				if (distance < bestDistance) {
					bestDistance = distance;
					best = visualizer;
				}
			}
		}
		return best;
	}
}
