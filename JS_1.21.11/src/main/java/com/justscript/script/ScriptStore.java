package com.justscript.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.justscript.Config;
import com.justscript.Log;
import com.justscript.util.WorldName;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Хранилище скриптов: один JSON-файл на сервер (проект).
 * <p>Ключ проекта — адрес сервера, поэтому миры одного JustMC-аккаунта не перемешиваются
 * при переходе на другие серверы. Всё лежит на клиенте, сервер не нагружается.
 */
public final class ScriptStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static ScriptStore instance;

	private final Map<String, ScriptUnit> units = new LinkedHashMap<>();
	private String project = "";
	private Path file;
	private boolean dirty;
	private String worldName = "";
	private int worldIdentity;
	private int worldProbe;

	private static final class Data {
		String project = "";
		ArrayList<ScriptUnit> units = new ArrayList<>();
	}

	public static ScriptStore get() {
		if (instance == null) {
			instance = new ScriptStore();
		}
		return instance;
	}

	public static String projectKey(MinecraftClient client) {
		return projectKey(client, WorldName.detect(client));
	}

	/** Адрес сервера + имя мира (суффикс редактора срезан). */
	public static String projectKey(MinecraftClient client, String world) {
		ServerInfo info = client == null ? null : client.getCurrentServerEntry();
		String raw = info == null ? "local" : info.address;
		String server = raw.replaceAll("[^A-Za-z0-9._-]", "_");
		String clean = WorldName.sanitize(world);
		return clean.isEmpty() ? server : server + "__" + clean;
	}

	/** Вызывает менеджер при смене мира: сохраняет старый проект и читает новый. */
	public void updateProject(MinecraftClient client) {
		int identity = client == null || client.world == null ? 0 : System.identityHashCode(client.world);
		if (identity != this.worldIdentity) {
			this.worldIdentity = identity;
			this.worldName = WorldName.detect(client);
			this.worldProbe = 0;
		} else if (this.worldName.isEmpty() && ++this.worldProbe >= 40) {
			this.worldProbe = 0;
			this.worldName = WorldName.detect(client);
		}
		String key = projectKey(client, this.worldName);
		if (key.equals(this.project)) {
			return;
		}
		save();
		this.project = key;
		this.file = Config.rootDir().resolve("projects").resolve(key).resolve("scripts.json");
		this.units.clear();
		load();
	}

	/** Имя мира, определённое модом (может быть пустым). */
	public String worldName() {
		return worldName;
	}

	public String projectName() {
		return project.isEmpty() ? "local" : project;
	}

	public Path file() {
		return file;
	}

	/**
	 * Старые отдельные скрипты игрока переводим в глобальные:
	 * в дереве больше нет ветки «Игрок», а действия с игроком живут
	 * в глобальном скрипте. Если глобальный уже занят, бывший скрипт
	 * игрока сохраняется отдельной записью в ветке глобальных.
	 */
	private void migratePlayerScripts() {
		boolean migrated = false;
		for (ScriptUnit unit : new ArrayList<>(units.values())) {
			if (!"player".equals(unit.kind)) {
				continue;
			}
			units.remove(unit.id);
			String dimension = unit.dimension == null || unit.dimension.isBlank()
					? "minecraft:overworld" : unit.dimension;
			String globalId = "global|" + dimension;
			unit.kind = "global";
			if (!units.containsKey(globalId)) {
				unit.id = globalId;
				unit.label = "Скрипты мира (" + dimension.replace("minecraft:", "") + ")";
			} else {
				String legacyId = globalId + "|player";
				int number = 2;
				while (units.containsKey(legacyId)) {
					legacyId = globalId + "|player" + number++;
				}
				String old = unit.label == null || unit.label.isBlank() ? "Игрок" : unit.label;
				unit.id = legacyId;
				unit.label = old + " (в глобальных)";
			}
			units.put(unit.id, unit);
			migrated = true;
		}
		if (migrated) {
			markDirty();
			Log.LOGGER.info("Скрипты игрока перенесены в глобальные: {}", units.size());
		}
	}

	private void load() {
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			if (data != null && data.units != null) {
				for (ScriptUnit unit : data.units) {
					if (unit != null && unit.id != null && !unit.id.isEmpty()) {
						units.put(unit.id, unit);
					}
				}
			}
			migratePlayerScripts();
			Log.LOGGER.info("Загружено {} скриптов из {}", units.size(), file);
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать {}", file, e);
		}
	}

	public void save() {
		if (!dirty || file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Data data = new Data();
			data.project = project;
			data.units = new ArrayList<>(units.values());
			Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить {}", file, e);
		}
	}

	public void saveIfDirty() {
		if (dirty) {
			save();
		}
	}

	public void markDirty() {
		dirty = true;
	}

	public ScriptUnit get(String id) {
		return units.get(id);
	}

	public ScriptUnit getOrCreate(Supplier<ScriptUnit> factory) {
		ScriptUnit probe = factory.get();
		ScriptUnit existing = units.get(probe.id);
		if (existing != null) {
			return existing;
		}
		units.put(probe.id, probe);
		markDirty();
		return probe;
	}

	public boolean remove(String id) {
		boolean removed = units.remove(id) != null;
		if (removed) {
			markDirty();
		}
		return removed;
	}

	public Collection<ScriptUnit> all() {
		return units.values();
	}

	/**
	 * Копия скрипта: новый id, подпись «… (копия)» и позиция в конце своего вида.
	 * Нужна для глобальных скриптов — у блочных/сущностных копия осталась бы
	 * привязана к тому же объекту и срабатывала бы дважды.
	 *
	 * @return копия или {@code null}, если исходника нет
	 */
	public ScriptUnit duplicate(String id) {
		ScriptUnit source = units.get(id);
		if (source == null) {
			return null;
		}
		ScriptUnit copy = GSON.fromJson(GSON.toJson(source), ScriptUnit.class);
		if (copy == null) {
			return null;
		}
		String candidate = id + "|copy";
		int n = 2;
		while (units.containsKey(candidate)) {
			candidate = id + "|copy" + n++;
		}
		copy.id = candidate;
		String baseLabel = source.label == null || source.label.isBlank()
				? source.displayTitle()
				: source.label;
		baseLabel = baseLabel.replaceAll("\\s*\\(копия(?:\\s+\\d+)?\\)$", "");
		String copyLabel = baseLabel + " (копия)";
		int number = 2;
		while (labelExists(copyLabel)) {
			copyLabel = baseLabel + " (копия " + number++ + ")";
		}
		copy.label = copyLabel;
		copy.position = nextPosition(source.kind);
		copy.modified = System.currentTimeMillis();
		units.put(copy.id, copy);
		markDirty();
		return copy;
	}

	/** Есть ли уже скрипт с такой подписью (копии не должны сливаться в дереве). */
	private boolean labelExists(String label) {
		for (ScriptUnit unit : units.values()) {
			if (label.equals(unit.label)) {
				return true;
			}
		}
		return false;
	}

	/** Следующая свободная позиция среди скриптов этого вида. */
	private int nextPosition(String kind) {
		int max = -1;
		for (ScriptUnit unit : units.values()) {
			if (Objects.equals(unit.kind, kind)) {
				max = Math.max(max, unit.position);
			}
		}
		return max + 1;
	}

	public boolean isEmpty() {
		return units.values().stream().allMatch(unit -> unit.code == null || unit.code.isBlank());
	}

	/** Переименовывает владельца юнита, если блок сменился (полезно для подсказок). */
	public void touch(ScriptUnit unit) {
		unit.modified = System.currentTimeMillis();
		markDirty();
	}
}
