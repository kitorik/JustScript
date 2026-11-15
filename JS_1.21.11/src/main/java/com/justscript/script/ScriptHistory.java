package com.justscript.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.justscript.Config;
import com.justscript.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * История скрипта: копии кода до правок. Сколько копий хранить, задаётся
 * в настройках («Память»). Из истории можно откатить скрипт к любому моменту.
 */
public final class ScriptHistory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public record Snapshot(long time, String reason, String code, Path path) {
		public String timeLabel() {
			return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("ru")).format(new Date(time));
		}
	}

	private static final class Data {
		long time;
		String reason = "";
		String code = "";
	}

	private ScriptHistory() {
	}

	/** Папка истории проекта; null, если проект ещё не привязан к файлу. */
	private static Path projectHistoryDir() {
		Path file = ScriptStore.get().file();
		if (file == null || file.getParent() == null) {
			return null;
		}
		return file.getParent().resolve("history");
	}

	private static Path unitDir(ScriptUnit unit) {
		Path root = projectHistoryDir();
		if (root == null) {
			return null;
		}
		String safe = unit.id == null ? "unit" : unit.id.replaceAll("[^A-Za-z0-9._-]", "_");
		if (safe.length() > 80) {
			safe = safe.substring(0, 80);
		}
		return root.resolve(safe);
	}

	/** Сохраняет копию кода. Повтор без изменений не пишется. */
	public static void snapshot(ScriptUnit unit, String code, String reason) {
		int limit = Math.max(0, Config.get().historyLimit);
		if (limit == 0 || unit == null || code == null) {
			return;
		}
		Path dir = unitDir(unit);
		if (dir == null) {
			return;
		}
		try {
			Files.createDirectories(dir);
			List<Snapshot> existing = list(unit);
			if (!existing.isEmpty() && existing.get(0).code().equals(code)) {
				return;
			}
			Data data = new Data();
			data.time = System.currentTimeMillis();
			data.reason = reason == null ? "" : reason;
			data.code = code;
			Path file = dir.resolve(data.time + ".json");
			Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
			prune(unit, limit);
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить копию скрипта", e);
		}
	}

	/** Свежие копии первыми. */
	public static List<Snapshot> list(ScriptUnit unit) {
		List<Snapshot> snapshots = new ArrayList<>();
		Path dir = unitDir(unit);
		if (dir == null || !Files.isDirectory(dir)) {
			return snapshots;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
				try {
					Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
					if (data != null && data.code != null) {
						snapshots.add(new Snapshot(data.time, data.reason == null ? "" : data.reason, data.code, path));
					}
				} catch (Exception e) {
					Log.LOGGER.warn("Пропускаю испорченную копию {}", path, e);
				}
			});
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать историю {}", dir, e);
		}
		snapshots.sort(Comparator.comparingLong(Snapshot::time).reversed());
		return snapshots;
	}

	public static void prune(ScriptUnit unit, int limit) {
		List<Snapshot> snapshots = list(unit);
		for (int i = limit; i < snapshots.size(); i++) {
			try {
				Files.deleteIfExists(snapshots.get(i).path());
			} catch (Exception e) {
				Log.LOGGER.warn("Не удалось удалить старую копию", e);
			}
		}
	}

	public static void pruneToLimit(ScriptUnit unit) {
		prune(unit, Math.max(0, Config.get().historyLimit));
	}

	public static void delete(Snapshot snapshot) {
		try {
			Files.deleteIfExists(snapshot.path());
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось удалить копию", e);
		}
	}
}
