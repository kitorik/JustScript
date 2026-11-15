package com.justscript.editor3d;

import com.justscript.mixin.BlockDisplayAccessor;
import com.justscript.mixin.DisplayAccessor;
import com.justscript.mixin.InteractionAccessor;
import com.justscript.mixin.ItemDisplayAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
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
	private static final Map<String, DisplayEntity> DISPLAYS = new HashMap<>();
	private static final Map<String, InteractionEntity> HITBOXES = new HashMap<>();
	private static int nextClientId = -100000;

	/** Поза для отрисовки: результат анимации и собственных полей. */
	private record Pose(double x, double y, double z, double scale,
			float yaw, float pitch, float roll) {
	}

	private VisualizerEntities() {
	}

	/** Синхронизирует сцену с сущностями: создаёт, обновляет и убирает лишние. */
	public static void sync(ClientWorld world) {
		Set<String> seen = new HashSet<>();
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible) {
				continue;
			}
			seen.add(visualizer.id);
			Visualizer master = SceneStore.get().masterOf(visualizer);
			Pose pose = poseOf(visualizer);
			boolean wantItem = "item".equals(master.kind);

			DisplayEntity display = DISPLAYS.get(visualizer.id);
			boolean mismatch = display != null
					&& (display instanceof DisplayEntity.ItemDisplayEntity) != wantItem;
			if (display != null && (display.getEntityWorld() != world || mismatch)) {
				display.discard();
				DISPLAYS.remove(visualizer.id);
				display = null;
			}
			if (display == null) {
				display = createDisplay(world, wantItem);
				if (display != null) {
					world.addEntity(display);
					DISPLAYS.put(visualizer.id, display);
				}
			}
			if (display != null) {
				apply(display, master, pose);
			}
			if (visualizer.hitbox) {
				InteractionEntity hitbox = HITBOXES.get(visualizer.id);
				if (hitbox == null || hitbox.getEntityWorld() != world) {
					hitbox = createHitbox(world, visualizer);
					world.addEntity(hitbox);
					HITBOXES.put(visualizer.id, hitbox);
				}
				applyHitbox(hitbox, visualizer, pose);
			} else {
				InteractionEntity hitbox = HITBOXES.remove(visualizer.id);
				if (hitbox != null) {
					hitbox.discard();
				}
			}
		}
		for (Map.Entry<String, DisplayEntity> entry : new HashMap<>(DISPLAYS).entrySet()) {
			if (!seen.contains(entry.getKey())) {
				entry.getValue().discard();
				DISPLAYS.remove(entry.getKey());
			}
		}
		for (Map.Entry<String, InteractionEntity> entry : new HashMap<>(HITBOXES).entrySet()) {
			if (!seen.contains(entry.getKey())) {
				entry.getValue().discard();
				HITBOXES.remove(entry.getKey());
			}
		}
	}

	/** Итоговая поза объекта: анимация → свои поля. */
	private static Pose poseOf(Visualizer visualizer) {
		Visualizer.Key sample = ScenePlayback.get().sample(visualizer);
		if (sample != null) {
			return new Pose(sample.x, sample.y, sample.z, sample.scale,
					sample.yaw, sample.pitch, sample.roll);
		}
		return new Pose(visualizer.x, visualizer.y, visualizer.z, visualizer.scale,
				visualizer.yaw, visualizer.pitch, visualizer.roll);
	}

	private static DisplayEntity createDisplay(ClientWorld world, boolean item) {
		DisplayEntity display;
		if (item) {
			display = new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, world);
		} else {
			display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
		}
		prepareClientEntity(display);
		return display;
	}

	/** Клиентская сущность должна иметь уникальные id и UUID. */
	private static void prepareClientEntity(Entity entity) {
		entity.setId(nextClientId--);
		entity.setUuid(java.util.UUID.randomUUID());
	}

	private static void apply(DisplayEntity display, Visualizer master, Pose pose) {
		display.setPosition(pose.x + Visualizer.JUSTMC_OFFSET, pose.y + Visualizer.JUSTMC_OFFSET,
				pose.z + Visualizer.JUSTMC_OFFSET);
		if (display instanceof DisplayEntity.BlockDisplayEntity blockDisplay) {
			Identifier id = Identifier.tryParse(master.value);
			Block block = id == null ? null : Registries.BLOCK.get(id);
			((BlockDisplayAccessor) blockDisplay).js$setBlockState(
					(block == null ? Blocks.STONE : block).getDefaultState());
		} else if (display instanceof DisplayEntity.ItemDisplayEntity itemDisplay) {
			Identifier id = Identifier.tryParse(master.value);
			Item item = id == null ? null : Registries.ITEM.get(id);
			((ItemDisplayAccessor) itemDisplay).js$setItemStack(
					new ItemStack(item == null ? Items.DIAMOND : item));
			((ItemDisplayAccessor) itemDisplay).js$setItemDisplayContext(ItemDisplayContext.GROUND);
		}
		Quaternionf rotation = new Quaternionf().rotationYXZ(
				(float) Math.toRadians(pose.yaw),
				(float) Math.toRadians(pose.pitch),
				(float) Math.toRadians(pose.roll));
		float scale = (float) Math.max(0.02, pose.scale);
		((DisplayAccessor) display).js$setTransformation(
				new AffineTransformation(new Vector3f(0, 0, 0), rotation,
						new Vector3f((float) master.sizeX(), (float) master.sizeY(),
								(float) master.sizeZ()), new Quaternionf()));
	}

	private static InteractionEntity createHitbox(ClientWorld world, Visualizer visualizer) {
		InteractionEntity interaction = new InteractionEntity(EntityType.INTERACTION, world);
		prepareClientEntity(interaction);
		interaction.setInvisible(true);
		interaction.setNoGravity(true);
		interaction.setSilent(true);
		interaction.addCommandTag(visualizer.hitboxTag());
		interaction.setCustomName(Text.literal(visualizer.hitboxTag()));
		interaction.setCustomNameVisible(false);
		return interaction;
	}

	private static void applyHitbox(InteractionEntity interaction, Visualizer visualizer, Pose pose) {
		interaction.setPosition(pose.x + Visualizer.JUSTMC_OFFSET, pose.y + Visualizer.JUSTMC_OFFSET,
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
	public static Visualizer pick(Vec3d from, Vec3d to) {
		Visualizer best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.visible || visualizer.locked) {
				continue;
			}
			Box box = Editor3DState.visualizerBounds(visualizer).expand(0.15);
			var hit = box.raycast(from, to);
			if (hit.isPresent()) {
				double distance = from.squaredDistanceTo(hit.get());
				if (distance < bestDistance) {
					bestDistance = distance;
					best = visualizer;
				}
			}
		}
		return best;
	}
}
