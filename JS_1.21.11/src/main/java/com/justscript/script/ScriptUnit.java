package com.justscript.script;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Один скрипт: привязан к блоку, сущности или ко всему миру.
 * Хранится в {@code config/justscript/projects/<сервер>__<мир>/scripts.json}.
 * <p>Отдельного скрипта игрока больше нет: действия с игроком живут
 * в глобальном скрипте вместе с событиями мира.
 */
public final class ScriptUnit {
	public String id = "";
	/** block, entity или global. */
	public String kind = "block";
	public String dimension = "";
	public int x;
	public int y;
	public int z;
	public String entityUuid = "";
	/** Имя сущности на момент привязки (для проверки по нику). */
	public String entityName = "";
	/**
	 * Как опознавать сущность в событиях: {@code uuid} (надёжно, но падает при
	 * пересоздании сущности) или {@code name} (по нику — переживает переселение,
	 * но срабатывает на всех одноимённых).
	 */
	public String entityMatch = "uuid";
	public String blockId = "";
	public String label = "";
	/** Порядок скрипта внутри проекта: меньше — раньше. Глобальный скрипт всегда первым. */
	public int position;
	public String language = "justmc-json";
	public String code = "";
	public long modified = System.currentTimeMillis();

	public static ScriptUnit forBlock(World world, BlockPos pos) {
		ScriptUnit unit = new ScriptUnit();
		unit.kind = "block";
		unit.dimension = world.getRegistryKey().getValue().toString();
		unit.x = pos.getX();
		unit.y = pos.getY();
		unit.z = pos.getZ();
		BlockState state = world.getBlockState(pos);
		unit.blockId = Registries.BLOCK.getId(state.getBlock()).toString();
		unit.id = blockKey(unit.dimension, pos);
		unit.label = unit.blockId.replace("minecraft:", "") + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		return unit;
	}

	public static String blockKey(String dimension, BlockPos pos) {
		return "block|" + dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
	}

	public static ScriptUnit forEntity(Entity entity) {
		ScriptUnit unit = new ScriptUnit();
		unit.kind = "entity";
		unit.dimension = entity.getEntityWorld().getRegistryKey().getValue().toString();
		unit.entityUuid = entity.getUuidAsString();
		unit.entityName = entity.getName().getString();
		unit.id = "entity|" + unit.entityUuid;
		String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString().replace("minecraft:", "");
		unit.label = unit.entityName + " (" + type + ")";
		return unit;
	}

	public static ScriptUnit forWorld(World world) {
		ScriptUnit unit = new ScriptUnit();
		unit.kind = "global";
		unit.dimension = world.getRegistryKey().getValue().toString();
		unit.id = "global|" + unit.dimension;
		unit.label = "Скрипты мира (" + unit.dimension.replace("minecraft:", "") + ")";
		return unit;
	}

	/** Скрипт игрока больше не отдельный: действия с игроком — в глобальном. */
	public String displayTitle() {
		return switch (kind) {
			case "entity" -> "Сущность: " + label;
			case "global" -> label;
			default -> "Блок: " + label;
		};
	}

	/** Короткая подпись для списков (без «Блок:»). */
	public String shortLabel() {
		return label == null || label.isBlank() ? id : label;
	}

	public boolean isGlobal() {
		return "global".equals(kind);
	}

	/** Как фильтровать событие: проверка по UUID или по нику. */
	public boolean matchByName() {
		return "name".equalsIgnoreCase(entityMatch);
	}

	/** Текст, с которым сравниваем событие. */
	public String entityMatchValue() {
		if (matchByName()) {
			return entityName == null ? "" : entityName;
		}
		return entityUuid == null ? "" : entityUuid;
	}
}
