package com.justscript.editor3d;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.justscript.Config;
import com.justscript.Log;
import com.justscript.script.ScriptStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Сцена 3D-редактора: один JSON на проект (сервер + мир).
 * <p>Визуализаторы живут только на клиенте и не отправляются на сервер,
 * поэтому их можно свободно ставить и удалять. История изменений хранится
 * снимками JSON: {@link #beginEdit()} перед действием, {@link #undo()} — откат.
 */
public final class SceneStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int UNDO_LIMIT = 80;
	private static SceneStore instance;

	private final Map<String, Visualizer> items = new LinkedHashMap<>();
	private final Deque<String> undoStack = new ArrayDeque<>();
	private final Deque<String> redoStack = new ArrayDeque<>();
	private String project = "";
	private Path file;
	private boolean dirty;

	private static final class Data {
		String project = "";
		ArrayList<Visualizer> visualizers = new ArrayList<>();
	}

	public static SceneStore get() {
		if (instance == null) {
			instance = new SceneStore();
		}
		return instance;
	}

	/** Следит за сменой проекта и подхватывает сцену нужного мира. */
	public void updateProject() {
		String key = ScriptStore.get().projectName();
		if (key.equals(project)) {
			return;
		}
		save();
		project = key;
		file = Config.rootDir().resolve("scenes").resolve(safeName(key) + ".json");
		items.clear();
		undoStack.clear();
		redoStack.clear();
		load();
	}

	private static String safeName(String value) {
		String safe = value == null ? "local" : value.replaceAll("[^A-Za-z0-9._-]", "_");
		return safe.isBlank() ? "local" : safe;
	}

	private void load() {
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			if (data != null && data.visualizers != null) {
				for (Visualizer visualizer : data.visualizers) {
					if (visualizer != null && visualizer.id != null && !visualizer.id.isBlank()) {
						items.put(visualizer.id, visualizer);
					}
				}
			}
			Log.LOGGER.info("Сцена 3D: загружено {} визуализаторов", items.size());
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать сцену {}", file, e);
		}
	}

	public void save() {
		if (!dirty || file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, serialize(), StandardCharsets.UTF_8);
			dirty = false;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить сцену {}", file, e);
		}
	}

	private String serialize() {
		Data data = new Data();
		data.project = project;
		data.visualizers = new ArrayList<>(items.values());
		return GSON.toJson(data);
	}

	// ------------------------------------------------------------- история

	/** Запомнить состояние перед изменением — потом можно откатить. */
	public void beginEdit() {
		undoStack.push(serialize());
		while (undoStack.size() > UNDO_LIMIT) {
			undoStack.removeLast();
		}
		redoStack.clear();
	}

	public boolean canUndo() {
		return !undoStack.isEmpty();
	}

	public boolean canRedo() {
		return !redoStack.isEmpty();
	}

	public boolean undo() {
		if (undoStack.isEmpty()) {
			return false;
		}
		redoStack.push(serialize());
		restore(undoStack.pop());
		return true;
	}

	public boolean redo() {
		if (redoStack.isEmpty()) {
			return false;
		}
		undoStack.push(serialize());
		restore(redoStack.pop());
		return true;
	}

	private void restore(String json) {
		try {
			Data data = GSON.fromJson(json, Data.class);
			items.clear();
			if (data != null && data.visualizers != null) {
				for (Visualizer visualizer : data.visualizers) {
					if (visualizer != null && visualizer.id != null && !visualizer.id.isBlank()) {
						items.put(visualizer.id, visualizer);
					}
				}
			}
			dirty = true;
			save();
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось откатить сцену", e);
		}
	}

	// ------------------------------------------------------------- данные

	public Collection<Visualizer> all() {
		return items.values();
	}

	public List<Visualizer> list() {
		return new ArrayList<>(items.values());
	}

	public Collection<Visualizer> visible() {
		List<Visualizer> result = new ArrayList<>();
		for (Visualizer visualizer : items.values()) {
			if (visualizer.visible) {
				result.add(visualizer);
			}
		}
		return result;
	}

	public Visualizer get(String id) {
		return id == null ? null : items.get(id);
	}

	public void add(Visualizer visualizer) {
		items.put(visualizer.id, visualizer);
		dirty = true;
		save();
	}

	public boolean remove(String id) {
		if (items.remove(id) == null) {
			return false;
		}
		for (Visualizer visualizer : items.values()) {
			if (id.equals(visualizer.parentId)) {
				visualizer.parentId = "";
			}
		}
		dirty = true;
		save();
		return true;
	}

	public void touch() {
		dirty = true;
		save();
	}

	/** Слои в порядке появления. */
	public List<String> layers() {
		Set<String> layers = new LinkedHashSet<>();
		for (Visualizer visualizer : items.values()) {
			layers.add(layerOf(visualizer));
		}
		return new ArrayList<>(layers);
	}

	public static String layerOf(Visualizer visualizer) {
		return visualizer.layer == null || visualizer.layer.isBlank() ? "Общее" : visualizer.layer;
	}

	/** «Мастер» связанной группы: с него берут вид остальные копии. */
	public Visualizer masterOf(Visualizer visualizer) {
		if (visualizer.linkId == null || visualizer.linkId.isBlank()) {
			return visualizer;
		}
		for (Visualizer candidate : items.values()) {
			if (visualizer.linkId.equals(candidate.linkId)) {
				return candidate;
			}
		}
		return visualizer;
	}

	/** Дети объекта (по parentId), рекурсивно не обходим — для одного шага. */
	public List<Visualizer> childrenOf(String parentId) {
		if (parentId == null || parentId.isBlank()) {
			return Collections.emptyList();
		}
		List<Visualizer> children = new ArrayList<>();
		for (Visualizer visualizer : items.values()) {
			if (parentId.equals(visualizer.parentId)) {
				children.add(visualizer);
			}
		}
		return children;
	}

	// ------------------------------------------------------------- импорт/экспорт

	/** JSON выбранных объектов (или всей сцены) — для буфера обмена. */
	public String exportJson(Collection<Visualizer> subset) {
		Data data = new Data();
		data.project = project;
		data.visualizers = subset == null || subset.isEmpty()
				? new ArrayList<>(items.values())
				: new ArrayList<>(subset);
		return GSON.toJson(data);
	}

	/** Вставляет объекты из JSON. Возвращает сколько добавилось. */
	public int importJson(String json) {
		if (json == null || json.isBlank()) {
			return 0;
		}
		try {
			Data data = GSON.fromJson(json, Data.class);
			if (data == null || data.visualizers == null || data.visualizers.isEmpty()) {
				return 0;
			}
			beginEdit();
			int added = 0;
			Map<String, String> remap = new LinkedHashMap<>();
			for (Visualizer visualizer : data.visualizers) {
				if (visualizer == null) {
					continue;
				}
				String oldId = visualizer.id == null ? "" : visualizer.id;
				visualizer.id = UUID.randomUUID().toString();
				remap.put(oldId, visualizer.id);
				if (visualizer.layer == null || visualizer.layer.isBlank()) {
					visualizer.layer = "Общее";
				}
				visualizer.blockScriptId = "";
				visualizer.hitboxScriptId = "";
				items.put(visualizer.id, visualizer);
				added++;
			}
			for (Visualizer visualizer : items.values()) {
				if (visualizer.parentId != null && remap.containsKey(visualizer.parentId)) {
					visualizer.parentId = remap.get(visualizer.parentId);
				}
			}
			dirty = true;
			save();
			return added;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось импортировать сцену", e);
			return 0;
		}
	}

	// ------------------------------------------------------------- команды в мир

	/**
	 * Команды для переноса сцены в настоящий мир: каждый визуализатор —
	 * сущность block_display/item_display, хитбокс — interaction. Командные
	 * блоки не нужны: строки можно вставить в чат или в .mcfunction.
	 */
	public String commands() {
		StringBuilder out = new StringBuilder();
		out.append("# JustScript: сцена «").append(project).append("» — ")
				.append(items.size()).append(" объект(ов)\n");
		for (Visualizer visualizer : items.values()) {
			if (!visualizer.visible) {
				continue;
			}
			double x = visualizer.x + Visualizer.JUSTMC_OFFSET;
			double y = visualizer.y + Visualizer.JUSTMC_OFFSET;
			double z = visualizer.z + Visualizer.JUSTMC_OFFSET;
			String value = visualizer.value == null || visualizer.value.isBlank()
					? "minecraft:stone" : visualizer.value;
			String transformation = String.format(Locale.ROOT,
					"transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
							+ "translation:[0f,0f,0f],scale:[%.3ff,%.3ff,%.3ff]}",
					visualizer.sizeX(), visualizer.sizeY(), visualizer.sizeZ());
			if ("item".equals(visualizer.kind)) {
				out.append(String.format(Locale.ROOT,
						"summon item_display %.3f %.3f %.3f {item:{id:\"%s\",count:1},%s}%n",
						x, y, z, value, transformation));
			} else {
				out.append(String.format(Locale.ROOT,
						"summon block_display %.3f %.3f %.3f {block_state:{Name:\"%s\"},%s}%n",
						x, y, z, value, transformation));
			}
			if (visualizer.hitbox) {
				out.append(String.format(Locale.ROOT,
						"summon interaction %.3f %.3f %.3f {width:%.3ff,height:%.3ff,response:0b,"
								+ "Tags:[\"%s\"]}%n",
						x, y, z, Math.max(visualizer.sizeX(), visualizer.sizeZ()),
						visualizer.sizeY(), visualizer.hitboxTag()));
			}
		}
		return out.toString();
	}

	/** Пишет команды рядом со сценой ({@code <проект>-commands.txt}). */
	public Path writeCommands() {
		if (file == null) {
			return null;
		}
		try {
			Files.createDirectories(file.getParent());
			Path target = file.getParent().resolve(safeName(project) + "-commands.txt");
			Files.writeString(target, commands(), StandardCharsets.UTF_8);
			return target;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить команды сцены", e);
			return null;
		}
	}
}
