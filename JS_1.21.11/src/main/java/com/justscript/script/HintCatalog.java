package com.justscript.script;

import com.justscript.events.EventCatalog;
import com.justscript.events.EventDefinition;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Каталог подсказок для редактора: функции/процессы проекта (в том числе
 * из глобального скрипта), события JustMC и константы действий/условий.
 *
 * <p>Подсказки зависят от языка и от места курсора:
 * в заголовке — события, в теле — действия, внутри строки-аргумента — ничего.
 *
 * <p>Список действий/условий лежит в {@code assets/justscript/hints/actions.txt}
 * (сгенерирован из справочника JustMC) — его можно пополнять, не трогая код.
 */
public final class HintCatalog {
	public enum Kind {
		FUNCTION("ƒ функция"),
		PROCESS("↻ процесс"),
		EVENT("◆ событие"),
		ACTION("⚡ действие"),
		CONDITION("? условие"),
		VALUE("{} значение"),
		KEYWORD("⌨ команда");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** Где находится курсор — от этого зависит, что предлагать. */
	public enum Context {
		/** Заголовок секции: события. */
		HEADER,
		/** Тело секции: действия и вызовы. */
		BODY,
		/** Внутри строкового аргумента — подсказок нет. */
		STRING,
		NONE
	}

	public record Hint(String token, String description, Kind kind, int score) {
	}

	private static final Pattern FUNCTION_JSON = Pattern.compile("(?im)^\\s*@function\\s+([^\\s{(]+)");
	private static final Pattern PROCESS_JSON = Pattern.compile("(?im)^\\s*@process\\s+([^\\s{(]+)");
	private static final Pattern FUNCTION_XERO = Pattern.compile("(?im)^\\s*func\\s+([^\\s{(]+)");
	private static final Pattern PROCESS_XERO = Pattern.compile("(?im)^\\s*process\\s+([^\\s{(]+)");
	private static final Pattern FUNCTION_JMCC = Pattern.compile("(?im)^\\s*function\\s+([^\\s{(]+)");
	private static final Pattern PROCESS_JMCC = Pattern.compile("(?im)^\\s*process\\s+([^\\s{(]+)");

	private static final List<String[]> CONSTANTS = loadConstants();
	private static final List<Hint> XERO_KEYWORDS = loadHints("hints/xero.txt", Kind.KEYWORD);
	private static final List<Hint> JMCC_KEYWORDS = loadHints("hints/jmcc.txt", Kind.KEYWORD);
	private static final List<Hint> VALUE_HINTS = loadHints("hints/values.txt", Kind.VALUE);
	private static List<Hint> constantHints;

	private HintCatalog() {
	}

	private static List<String[]> loadConstants() {
		List<String[]> list = new ArrayList<>();
		try (InputStream stream = HintCatalog.class.getResourceAsStream("/assets/justscript/hints/actions.txt")) {
			if (stream == null) {
				return list;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String[] parts = line.split("\\|", 3);
					if (parts.length >= 2 && !parts[0].isBlank()) {
						list.add(parts);
					}
				}
			}
		} catch (Exception ignored) {
			// без каталога подсказки всё равно работают (функции и события)
		}
		return list;
	}

	private static List<Hint> constants() {
		if (constantHints != null) {
			return constantHints;
		}
		List<Hint> hints = new ArrayList<>();
		for (String[] row : CONSTANTS) {
			String id = row[0];
			String category = row[1];
			String name = row.length > 2 ? row[2] : "";
			Kind kind = id.startsWith("if_") ? Kind.CONDITION : Kind.ACTION;
			String description = name.isBlank() ? category : name + (category.isBlank() ? "" : " · " + category);
			hints.add(new Hint(id, description, kind, 0));
		}
		hints.addAll(VALUE_HINTS);
		hints.sort(Comparator.comparing(Hint::token));
		constantHints = List.copyOf(hints);
		return constantHints;
	}

	/** Строки вида {@code token|описание} из ассета; в коде списков нет. */
	private static List<Hint> loadHints(String resource, Kind kind) {
		List<Hint> list = new ArrayList<>();
		try (InputStream stream = HintCatalog.class.getResourceAsStream("/assets/justscript/" + resource)) {
			if (stream == null) {
				return list;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String row = line.trim();
					if (row.isEmpty() || row.startsWith("#")) {
						continue;
					}
					String[] parts = row.split("\\|", 2);
					if (!parts[0].isBlank()) {
						list.add(new Hint(parts[0], parts.length > 1 ? parts[1] : "", kind, 0));
					}
				}
			}
		} catch (Exception ignored) {
			// без ассета подсказки этого языка просто не показываются
		}
		return List.copyOf(list);
	}

	private static List<Hint> eventHints() {
		List<Hint> hints = new ArrayList<>();
		for (EventDefinition definition : EventCatalog.all()) {
			hints.add(new Hint(definition.id(), definition.name() + " · " + definition.justmc(), Kind.EVENT, 0));
			hints.add(new Hint(definition.justmc(), definition.name() + " · " + definition.id(), Kind.EVENT, 0));
		}
		return hints;
	}

	/**
	 * Подсказки по префиксу. Лучшие — сверху.
	 *
	 * @param prefix   то, что уже набрано (минимум 1 символ)
	 * @param code     текст текущего скрипта (чтобы видеть его функции)
	 * @param language justmc-json, xero или jmcc
	 * @param context  где стоит курсор
	 */
	public static List<Hint> suggest(String prefix, String code, String language, Context context) {
		if (context == null || context == Context.STRING || context == Context.NONE) {
			return List.of();
		}
		String needle = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		String lang = language == null ? "justmc-json" : language.toLowerCase(Locale.ROOT);
		Map<String, Hint> byToken = new LinkedHashMap<>();

		if (context == Context.BODY) {
			// свои и глобальные функции/процессы — в любом языке
			for (String function : functions(code)) {
				add(byToken, function, "функция текущего скрипта", Kind.FUNCTION, needle);
			}
			for (String process : processes(code)) {
				add(byToken, process, "процесс текущего скрипта", Kind.PROCESS, needle);
			}
			ScriptStore store = ScriptStore.get();
			for (ScriptUnit unit : store.all()) {
				if (!unit.isGlobal() || unit.code == null || unit.code.isBlank()) {
					continue;
				}
				for (String function : functions(unit.code)) {
					add(byToken, function, "глобальная функция", Kind.FUNCTION, needle);
				}
				for (String process : processes(unit.code)) {
					add(byToken, process, "глобальный процесс", Kind.PROCESS, needle);
				}
			}
		}

		List<Hint> pool = new ArrayList<>();
		switch (lang) {
			case "xero" -> {
				pool.addAll(XERO_KEYWORDS);
				if (context == Context.HEADER) {
					pool.addAll(eventHints());
				}
			}
			case "jmcc" -> {
				pool.addAll(JMCC_KEYWORDS);
				if (context == Context.HEADER) {
					pool.addAll(eventHints());
				}
			}
			default -> {
				if (context == Context.HEADER) {
					pool.addAll(eventHints());
				} else {
					pool.addAll(constants());
				}
			}
		}

		for (Hint hint : pool) {
			int score = score(hint.token(), needle);
			if (score > 0) {
				// действия и условия полезнее типов значений — поднимаем их выше
				int priority = switch (hint.kind()) {
					case ACTION, CONDITION -> 40;
					case EVENT -> 20;
					default -> 0;
				};
				byToken.putIfAbsent(hint.token(),
						new Hint(hint.token(), hint.description(), hint.kind(), score + priority));
			}
		}

		List<Hint> result = new ArrayList<>(byToken.values());
		result.sort(Comparator.comparingInt(Hint::score).reversed().thenComparing(Hint::token));
		return result.size() > 30 ? result.subList(0, 30) : result;
	}

	/** Совместимость со старым вызовом: JSON, тело секции. */
	public static List<Hint> suggest(String prefix, String currentCode) {
		return suggest(prefix, currentCode, "justmc-json", Context.BODY);
	}

	private static void add(Map<String, Hint> target, String token, String description, Kind kind, String needle) {
		int score = score(token, needle);
		if (score <= 0) {
			return;
		}
		// функции важнее констант — их и ищет игрок в первую очередь
		score += 2000;
		Hint existing = target.get(token);
		if (existing == null || existing.score() < score) {
			target.put(token, new Hint(token, description, kind, score));
		}
	}

	private static int score(String token, String needle) {
		String value = token.toLowerCase(Locale.ROOT);
		if (value.equals(needle)) {
			return 1000;
		}
		if (value.startsWith(needle)) {
			return 850 - Math.min(200, value.length() - needle.length());
		}
		int index = value.indexOf(needle);
		if (index >= 0) {
			return 550 - Math.min(200, index);
		}
		// подпоследовательность: bkr → block_break
		int position = 0;
		for (int i = 0; i < value.length() && position < needle.length(); i++) {
			if (value.charAt(i) == needle.charAt(position)) {
				position++;
			}
		}
		if (position == needle.length()) {
			return 300 - Math.min(200, value.length() - needle.length());
		}
		return 0;
	}

	public static List<String> functions(String code) {
		return collect(code, FUNCTION_JSON, FUNCTION_XERO, FUNCTION_JMCC);
	}

	public static List<String> processes(String code) {
		return collect(code, PROCESS_JSON, PROCESS_XERO, PROCESS_JMCC);
	}

	private static List<String> collect(String code, Pattern... patterns) {
		List<String> names = new ArrayList<>();
		if (code == null || code.isBlank()) {
			return names;
		}
		for (Pattern pattern : patterns) {
			Matcher matcher = pattern.matcher(code);
			while (matcher.find()) {
				String name = matcher.group(1).trim();
				if (!name.isEmpty() && !names.contains(name)) {
					names.add(name);
				}
			}
		}
		return names;
	}
}
