package com.justscript.ai;

import com.justscript.editor3d.SceneStore;
import com.justscript.editor3d.Visualizer;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ответ ИИ, разобранный на «цель» и код.
 *
 * <p>Если модель хочет создать скрипт не для текущего объекта, она начинает
 * ответ строкой {@code @target …}:
 * <pre>
 * &#64;target block 10 64 -5 minecraft:chest
 * &#64;target entity Steve
 * &#64;target visualizer 3f2a… (или название)
 * &#64;target hitbox 3f2a…
 * &#64;target global
 * </pre>
 * Строка цели вырезается из кода, а мод спрашивает у игрока разрешение
 * на создание скрипта для найденного объекта.
 */
public final class AiProposal {
	public static final String BLOCK = "block";
	public static final String ENTITY = "entity";
	public static final String VISUALIZER = "visualizer";
	public static final String HITBOX = "hitbox";
	public static final String GLOBAL = "global";

	private final String kind;
	private final int x;
	private final int y;
	private final int z;
	private final String blockId;
	private final String ref;
	private final String code;
	private final String error;

	private AiProposal(String kind, int x, int y, int z, String blockId, String ref,
			String code, String error) {
		this.kind = kind;
		this.x = x;
		this.y = y;
		this.z = z;
		this.blockId = blockId == null ? "" : blockId;
		this.ref = ref == null ? "" : ref;
		this.code = code == null ? "" : code;
		this.error = error == null ? "" : error;
	}

	// ---------------------------------------------------------------- разбор

	/** Разбирает ответ модели; при ошибке цели возвращает {@link #invalid()}. */
	public static AiProposal parse(String raw) {
		String text = raw == null ? "" : raw.replace("\r\n", "\n").trim();
		if (text.isEmpty()) {
			return new AiProposal(null, 0, 0, 0, "", "", "", "");
		}

		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		int targetLine = -1;
		for (int i = 0; i < Math.min(4, lines.size()); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty()) {
				continue;
			}
			if (line.toLowerCase(Locale.ROOT).startsWith("@target")) {
				targetLine = i;
			}
			break;
		}

		if (targetLine < 0) {
			return new AiProposal(null, 0, 0, 0, "", "", text, "");
		}

		String line = lines.get(targetLine).trim();
		String rest = line.substring("@target".length()).trim();
		List<String> body = new ArrayList<>(lines);
		body.remove(targetLine);
		String code = String.join("\n", body).trim();

		if (rest.isEmpty()) {
			return new AiProposal(null, 0, 0, 0, "", "", code, "@target без цели");
		}

		String head = rest.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		String tail = rest.length() > head.length() ? rest.substring(head.length()).trim() : "";

		switch (head) {
			case "block", "блок" -> {
				String normalized = tail.replace(',', ' ');
				List<Integer> numbers = new ArrayList<>();
				String id = "";
				for (String token : normalized.split("\\s+")) {
					if (token.isBlank()) {
						continue;
					}
					try {
						numbers.add(Integer.parseInt(token));
					} catch (NumberFormatException ignored) {
						if (id.isEmpty()) {
							id = token;
						}
					}
				}
				if (numbers.size() < 3) {
					return new AiProposal(BLOCK, 0, 0, 0, id, "", code,
							"в @target block нужны координаты x y z");
				}
				return new AiProposal(BLOCK, numbers.get(0), numbers.get(1), numbers.get(2),
						id, "", code, "");
			}
			case "entity", "сущность" -> {
				if (tail.isBlank()) {
					return new AiProposal(ENTITY, 0, 0, 0, "", "", code,
							"в @target entity нужен UUID или ник");
				}
				return new AiProposal(ENTITY, 0, 0, 0, "", tail, code, "");
			}
			case "visualizer", "visualiser", "visual", "визуализатор" -> {
				if (tail.isBlank()) {
					return new AiProposal(VISUALIZER, 0, 0, 0, "", "", code,
							"в @target visualizer нужен id или название");
				}
				return new AiProposal(VISUALIZER, 0, 0, 0, "", tail, code, "");
			}
			case "hitbox", "хитбокс" -> {
				if (tail.isBlank()) {
					return new AiProposal(HITBOX, 0, 0, 0, "", "", code,
							"в @target hitbox нужен id или название визуализатора");
				}
				return new AiProposal(HITBOX, 0, 0, 0, "", tail, code, "");
			}
			case "global", "world", "мир" -> {
				return new AiProposal(GLOBAL, 0, 0, 0, "", "", code, "");
			}
			case "current", "этот", "текущий" -> {
				return new AiProposal(null, 0, 0, 0, "", "", code, "");
			}
			default -> {
				return new AiProposal(null, 0, 0, 0, "", "", code,
						"неизвестная цель @target: " + head);
			}
		}
	}

	// ---------------------------------------------------------------- свойства

	/** Цель есть и её нужно подтвердить у игрока. */
	public boolean hasTarget() {
		return kind != null && !invalid();
	}

	/** Разбор строки @target не удался. */
	public boolean invalid() {
		return !error.isEmpty();
	}

	public String error() {
		return error;
	}

	public String kind() {
		return kind;
	}

	public String code() {
		return code;
	}

	/** Нужно ли спрашивать разрешение: цель отличается от открытого скрипта. */
	public boolean requiresPermission(ScriptUnit current) {
		if (!hasTarget()) {
			return false;
		}
		if (current == null) {
			return true;
		}
		ScriptUnit target = buildTarget();
		if (target == null) {
			return true;
		}
		return !target.id.equals(current.id);
	}

	/** Текст уведомления — ровно то, что видит игрок. */
	public String permissionMessage() {
		return switch (kind == null ? "" : kind) {
			case BLOCK -> "Агент запрашивает разрешение на создание скрипта для блока";
			case ENTITY -> "Агент запрашивает разрешение на создание скрипта для сущности";
			case VISUALIZER, HITBOX -> "Агент запрашивает разрешение на создание скрипта для визуализатора";
			case GLOBAL -> "Агент запрашивает разрешение на создание глобального скрипта";
			default -> "Агент запрашивает разрешение на создание скрипта";
		};
	}

	/** Вторая строка уведомления: что именно за объект. */
	public String targetDetail() {
		return switch (kind == null ? "" : kind) {
			case BLOCK -> "Блок " + (blockId.isBlank() ? "—" : blockId)
					+ " @ " + x + " " + y + " " + z;
			case ENTITY -> "Сущность: " + ref;
			case VISUALIZER -> "Визуализатор: " + ref;
			case HITBOX -> "Хитбокс визуализатора: " + ref;
			case GLOBAL -> "Скрипты мира (" + dimensionShort() + ")";
			default -> "";
		};
	}

	private static String dimensionShort() {
		Minecraft minecraft = Minecraft.getInstance();
		Level level = minecraft == null ? null : minecraft.level;
		String dimension = level == null ? "minecraft:overworld"
				: level.dimension().identifier().toString();
		return dimension.replace("minecraft:", "");
	}

	// ---------------------------------------------------------------- цель

	/**
	 * Собирает (но не сохраняет) скрипт для цели.
	 *
	 * @return юнит или {@code null}, если объект не найден
	 */
	public ScriptUnit buildTarget() {
		Minecraft minecraft = Minecraft.getInstance();
		Level level = minecraft == null ? null : minecraft.level;
		String dimension = level == null ? "minecraft:overworld"
				: level.dimension().identifier().toString();
		return switch (kind == null ? "" : kind) {
			case BLOCK -> blockUnit(dimension, level);
			case ENTITY -> entityUnit(dimension, level);
			case VISUALIZER -> visualizerUnit(dimension, false);
			case HITBOX -> visualizerUnit(dimension, true);
			case GLOBAL -> globalUnit(level, dimension);
			default -> null;
		};
	}

	private ScriptUnit blockUnit(String dimension, Level level) {
		ScriptUnit unit = new ScriptUnit();
		unit.kind = BLOCK;
		unit.dimension = dimension;
		unit.x = x;
		unit.y = y;
		unit.z = z;
		unit.id = ScriptUnit.blockKey(dimension, new BlockPos(x, y, z));
		String id = blockId;
		if (id.isBlank() && level != null) {
			BlockState state = level.getBlockState(new BlockPos(x, y, z));
			id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		}
		unit.blockId = id.isBlank() ? "minecraft:stone" : id;
		unit.label = unit.blockId.replace("minecraft:", "") + " @ " + x + " " + y + " " + z;
		return unit;
	}

	private ScriptUnit entityUnit(String dimension, Level level) {
		Entity found = findEntity(level);
		if (found != null) {
			return ScriptUnit.forEntity(found);
		}
		ScriptUnit unit = new ScriptUnit();
		unit.kind = ENTITY;
		unit.dimension = dimension;
		if (looksLikeUuid(ref)) {
			unit.entityUuid = ref;
			unit.entityMatch = "uuid";
			unit.id = "entity|" + ref;
		} else {
			unit.entityName = ref;
			unit.entityMatch = "name";
			unit.id = "entity|name|" + ref.toLowerCase(Locale.ROOT);
		}
		unit.label = ref + " (ИИ)";
		return unit;
	}

	private ScriptUnit visualizerUnit(String dimension, boolean hitbox) {
		Visualizer visualizer = findVisualizer();
		if (visualizer == null) {
			return null;
		}
		if (hitbox) {
			ScriptUnit unit = new ScriptUnit();
			unit.kind = ENTITY;
			unit.dimension = dimension;
			unit.entityUuid = visualizer.id;
			unit.entityName = visualizer.hitboxTag();
			unit.entityMatch = "name";
			unit.id = "entity|" + visualizer.id;
			unit.label = "Хитбокс: " + visualizer.hitboxTag();
			return unit;
		}
		BlockPos pos = BlockPos.containing(visualizer.x, visualizer.y, visualizer.z);
		ScriptUnit unit = new ScriptUnit();
		unit.kind = BLOCK;
		unit.dimension = dimension;
		unit.x = pos.getX();
		unit.y = pos.getY();
		unit.z = pos.getZ();
		unit.blockId = visualizer.value == null || visualizer.value.isBlank()
				? "minecraft:stone" : visualizer.value;
		unit.id = ScriptUnit.blockKey(dimension, pos);
		unit.label = "3D: " + visualizer.displayName() + " @ "
				+ pos.getX() + " " + pos.getY() + " " + pos.getZ();
		return unit;
	}

	private ScriptUnit globalUnit(Level level, String dimension) {
		if (level != null) {
			return ScriptUnit.forWorld(level);
		}
		ScriptUnit unit = new ScriptUnit();
		unit.kind = GLOBAL;
		unit.dimension = dimension;
		unit.id = "global|" + dimension;
		unit.label = "Скрипты мира (" + dimension.replace("minecraft:", "") + ")";
		return unit;
	}

	/** Привязывает готовый скрипт к визуализатору (как в 3D-редакторе). */
	public void linkVisualizer(ScriptUnit unit) {
		if (unit == null || (!VISUALIZER.equals(kind) && !HITBOX.equals(kind))) {
			return;
		}
		Visualizer visualizer = findVisualizer();
		if (visualizer == null) {
			return;
		}
		if (HITBOX.equals(kind)) {
			visualizer.hitbox = true;
			visualizer.hitboxScriptId = unit.id;
		} else {
			visualizer.blockScriptId = unit.id;
		}
		SceneStore.get().touch();
	}

	// ---------------------------------------------------------------- поиск

	private Entity findEntity(Level level) {
		if (ref.isBlank() || !(level instanceof ClientLevel client)) {
			return null;
		}
		for (Entity entity : client.entitiesForRendering()) {
			if (entity == null) {
				continue;
			}
			if (ref.equalsIgnoreCase(entity.getStringUUID())
					|| ref.equalsIgnoreCase(entity.getName().getString())) {
				return entity;
			}
		}
		return null;
	}

	private Visualizer findVisualizer() {
		String needle = ref.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return null;
		}
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (visualizer.id.equalsIgnoreCase(needle)) {
				return visualizer;
			}
			String name = visualizer.displayName().toLowerCase(Locale.ROOT);
			if (name.equals(needle) || name.contains(needle)) {
				return visualizer;
			}
			String value = visualizer.value == null ? ""
					: visualizer.value.replace("minecraft:", "").toLowerCase(Locale.ROOT);
			if (value.equals(needle)) {
				return visualizer;
			}
		}
		return null;
	}

	private static boolean looksLikeUuid(String value) {
		if (value == null) {
			return false;
		}
		String clean = value.trim();
		return clean.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
				|| clean.matches("(?i)[0-9a-f]{32}");
	}
}
