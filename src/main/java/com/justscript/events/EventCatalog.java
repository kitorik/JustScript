package com.justscript.events;

import com.google.gson.GsonBuilder;
import com.justscript.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Каталог событий JustScript.
 *
 * <p>События берутся только из ассета {@code assets/justscript/events.txt} —
 * в коде зашитых списков нет. Формат строки:
 * {@code alias|justmc_id|SCOPE|Название|Описание|cancellable}.
 * {@code alias} — то, что игрок пишет после {@code @event}; {@code justmc_id} — реальный
 * ID события JustMC, который уходит в JSON-модуль.
 *
 * <p>Каталог можно выгрузить кнопкой «Экспорт каталога» в настройках мода — получится
 * JSON для «единой базы событий» (её можно положить в GitHub и обновлять модом).
 */
public final class EventCatalog {
	public static final String SCOPE_BLOCK = "BLOCK";
	public static final String SCOPE_ENTITY = "ENTITY";
	public static final String SCOPE_PLAYER = "PLAYER";
	public static final String SCOPE_WORLD = "WORLD";

	private static final String RESOURCE = "/assets/justscript/events.txt";

	private static final Map<String, EventDefinition> BY_ID = new LinkedHashMap<>();
	private static final List<EventDefinition> ALL = new ArrayList<>();

	static {
		load();
	}

	private static void load() {
		try (InputStream stream = EventCatalog.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				Log.LOGGER.warn("Не найден каталог событий {}", RESOURCE);
				return;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String row = line.trim();
					if (row.isEmpty() || row.startsWith("#")) {
						continue;
					}
					String[] parts = row.split("\\|", 6);
					if (parts.length < 6) {
						Log.LOGGER.warn("Пропускаю строку каталога событий: {}", row);
						continue;
					}
					EventDefinition definition = new EventDefinition(parts[0], parts[1], parts[2], parts[3], parts[4],
							Boolean.parseBoolean(parts[5]));
					ALL.add(definition);
					BY_ID.put(definition.id().toLowerCase(Locale.ROOT), definition);
				}
			}
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать каталог событий {}", RESOURCE, e);
		}
	}

	private EventCatalog() {
	}

	public static List<EventDefinition> all() {
		return List.copyOf(ALL);
	}

	/** Поиск по имени JustScript или по ID JustMC. */
	public static EventDefinition find(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		EventDefinition byAlias = BY_ID.get(name.trim().toLowerCase(Locale.ROOT));
		if (byAlias != null) {
			return byAlias;
		}
		for (EventDefinition definition : ALL) {
			if (definition.justmc().equalsIgnoreCase(name.trim())) {
				return definition;
			}
		}
		return null;
	}

	/**
	 * Превращает имя из {@code @event} в ID JustMC.
	 * Если имени нет в каталоге, но оно похоже на ID JustMC ({@code player_join}),
	 * оно принимается как есть. Неизвестное имя вернёт {@code null}.
	 */
	public static String resolveJustMc(String name) {
		EventDefinition definition = find(name);
		if (definition != null) {
			return definition.justmc();
		}
		String trimmed = name.trim();
		if (trimmed.matches("[a-z0-9_]+")) {
			return trimmed;
		}
		return null;
	}

	public static List<EventDefinition> forUnitKind(String kind) {
		return switch (kind) {
			case "block" -> filter(SCOPE_BLOCK);
			case "entity" -> filter(SCOPE_ENTITY);
			default -> {
				// глобальный скрипт: события мира и действия/события игрока
				List<EventDefinition> list = new ArrayList<>(filter(SCOPE_PLAYER));
				list.addAll(filter(SCOPE_WORLD));
				yield list;
			}
		};
	}

	private static List<EventDefinition> filter(String scope) {
		List<EventDefinition> list = new ArrayList<>();
		for (EventDefinition definition : ALL) {
			if (definition.scope().equals(scope)) {
				list.add(definition);
			}
		}
		return list;
	}

	public static String scopeLabel(String scope) {
		return switch (scope) {
			case SCOPE_BLOCK -> "блок";
			case SCOPE_ENTITY -> "сущность";
			case SCOPE_PLAYER -> "игрок";
			case SCOPE_WORLD -> "мир";
			default -> scope;
		};
	}

	public static String toJson() {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(ALL);
	}

	public static void export(Path path) throws IOException {
		Files.createDirectories(path.toAbsolutePath().getParent());
		Files.writeString(path, toJson(), StandardCharsets.UTF_8);
	}
}
