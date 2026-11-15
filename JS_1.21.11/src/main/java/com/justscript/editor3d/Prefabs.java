package com.justscript.editor3d;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.justscript.Config;
import com.justscript.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Префабы 3D-редактора: готовые фигуры (стол, стул, фонарь, арка) и
 * сохранённые пользователем группы объектов. Хранятся в
 * {@code config/justscript/prefabs/<имя>.json}.
 */
public final class Prefabs {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final List<String> BUILTIN = List.of("Стол", "Стул", "Фонарь", "Арка");

	private static final class Data {
		String name = "";
		ArrayList<Visualizer> objects = new ArrayList<>();
	}

	private Prefabs() {
	}

	public static List<String> builtinNames() {
		return BUILTIN;
	}

	private static Path dir() {
		return Config.rootDir().resolve("prefabs");
	}

	private static String safeName(String value) {
		String safe = value == null ? "prefab" : value.trim().replaceAll("[^A-Za-z0-9А-Яа-я._ -]", "_");
		return safe.isBlank() ? "prefab" : safe;
	}

	/** Все имена: сначала встроенные, потом сохранённые. */
	public static List<String> names() {
		List<String> names = new ArrayList<>(BUILTIN);
		try {
			Path dir = dir();
			if (Files.isDirectory(dir)) {
				try (var stream = Files.list(dir)) {
					stream.filter(path -> path.getFileName().toString().endsWith(".json"))
							.map(path -> path.getFileName().toString().replace(".json", ""))
							.sorted()
							.forEach(name -> {
								if (!names.contains(name)) {
									names.add(name);
								}
							});
				}
			}
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать список префабов", e);
		}
		return names;
	}

	public static boolean save(String name, List<Visualizer> objects) {
		if (objects == null || objects.isEmpty()) {
			return false;
		}
		try {
			Path dir = dir();
			Files.createDirectories(dir);
			double baseX = objects.get(0).x;
			double baseY = objects.get(0).y;
			double baseZ = objects.get(0).z;
			Data data = new Data();
			data.name = name;
			for (Visualizer visualizer : objects) {
				Visualizer copy = visualizer.copy();
				copy.id = java.util.UUID.randomUUID().toString();
				copy.x -= baseX;
				copy.y -= baseY;
				copy.z -= baseZ;
				copy.parentId = "";
				copy.linkId = "";
				copy.keys = new ArrayList<>();
				data.objects.add(copy);
			}
			Files.writeString(dir.resolve(safeName(name) + ".json"), GSON.toJson(data),
					StandardCharsets.UTF_8);
			return true;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить префаб {}", name, e);
			return false;
		}
	}

	/** Готовые объекты префаба со свежими id, смещённые в точку base. */
	public static List<Visualizer> build(String name, double baseX, double baseY, double baseZ) {
		List<Visualizer> source = builtin(name);
		if (source == null) {
			source = fromFile(name);
		}
		if (source == null) {
			return List.of();
		}
		List<Visualizer> result = new ArrayList<>();
		for (Visualizer visualizer : source) {
			Visualizer copy = visualizer.copy();
			copy.id = java.util.UUID.randomUUID().toString();
			copy.x += baseX;
			copy.y += baseY;
			copy.z += baseZ;
			copy.blockScriptId = "";
			copy.hitboxScriptId = "";
			result.add(copy);
		}
		return result;
	}

	private static List<Visualizer> fromFile(String name) {
		try {
			Path file = dir().resolve(safeName(name) + ".json");
			if (!Files.exists(file)) {
				return null;
			}
			Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			if (data == null || data.objects == null || data.objects.isEmpty()) {
				return null;
			}
			return data.objects;
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать префаб {}", name, e);
			return null;
		}
	}

	private static List<Visualizer> builtin(String name) {
		if (name == null) {
			return null;
		}
		List<Visualizer> list = new ArrayList<>();
		switch (name.toLowerCase(Locale.ROOT)) {
			case "стол" -> {
				block(list, "minecraft:oak_planks", 0, 0.85, 0, 1.0);
				block(list, "minecraft:oak_fence", -0.38, 0.2, -0.38, 0.16);
				block(list, "minecraft:oak_fence", 0.38, 0.2, -0.38, 0.16);
				block(list, "minecraft:oak_fence", -0.38, 0.2, 0.38, 0.16);
				block(list, "minecraft:oak_fence", 0.38, 0.2, 0.38, 0.16);
			}
			case "стул" -> {
				block(list, "minecraft:oak_slab", 0, 0.5, 0, 0.85);
				block(list, "minecraft:oak_fence", -0.32, 0.2, -0.32, 0.14);
				block(list, "minecraft:oak_fence", 0.32, 0.2, -0.32, 0.14);
				block(list, "minecraft:oak_fence", -0.32, 0.2, 0.32, 0.14);
				block(list, "minecraft:oak_fence", 0.32, 0.2, 0.32, 0.14);
				block(list, "minecraft:oak_planks", 0, 0.95, 0.32, 0.7);
			}
			case "фонарь" -> {
				block(list, "minecraft:oak_fence", 0, 1.0, 0, 0.25);
				item(list, "minecraft:lantern", 0, 2.2, 0, 0.8);
			}
			case "арка" -> {
				for (int i = 0; i < 3; i++) {
					block(list, "minecraft:stone_bricks", -1.5, i + 0.5, 0, 1.0);
					block(list, "minecraft:stone_bricks", 1.5, i + 0.5, 0, 1.0);
				}
				for (int i = -1; i <= 1; i++) {
					block(list, "minecraft:stone_bricks", i, 3.5, 0, 1.0);
				}
			}
			default -> {
				return null;
			}
		}
		return list;
	}

	private static void block(List<Visualizer> list, String value,
			double x, double y, double z, double scale) {
		add(list, "block", value, x, y, z, scale);
	}

	private static void item(List<Visualizer> list, String value,
			double x, double y, double z, double scale) {
		add(list, "item", value, x, y, z, scale);
	}

	private static void add(List<Visualizer> list, String kind, String value,
			double x, double y, double z, double scale) {
		Visualizer visualizer = new Visualizer();
		visualizer.kind = kind;
		visualizer.value = value;
		visualizer.x = x;
		visualizer.y = y;
		visualizer.z = z;
		visualizer.scale = scale;
		list.add(visualizer);
	}
}
