package com.justscript.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор «скриптового» текста JustScript.
 *
 * <pre>
 * # преамбула (комментарии) — игнорируется компилятором
 *
 * &#64;event block.click.right
 * {
 *   { "action": "player_send_message", "values": [] }
 * }
 *
 * &#64;function open_door(loc: location, sound: text = "block.chest.open")
 * { ... }
 *
 * &#64;process tick(20)
 * { ... }
 * </pre>
 *
 * Поддерживаются заголовки JustScript ({@code @event}, {@code @function},
 * {@code @process}), Xero ({@code when}, {@code func}, {@code process}) и JMCC
 * ({@code event&lt;player_join&gt;}, {@code function}, {@code process}).
 * У функций и процессов можно объявлять параметры: {@code (a: number, b: text = "x")}.
 */
public final class ScriptParser {
	private ScriptParser() {
	}

	/** Параметр функции или процесса. */
	public record Param(String name, String type, boolean required, String defaultValue) {
	}

	public record Section(String kind, String name, String header, String body, int ticks, List<Param> params) {
		public Section(String kind, String name, String header, String body, int ticks) {
			this(kind, name, header, body, ticks, List.of());
		}
	}

	public static List<Section> parse(String code) {
		List<Section> sections = new ArrayList<>();
		if (code == null || code.isBlank()) {
			return sections;
		}
		String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		StringBuilder preamble = new StringBuilder();
		String kind = null;
		String name = "";
		String header = "";
		int ticks = 0;
		List<Param> params = List.of();
		StringBuilder body = new StringBuilder();

		for (String line : lines) {
			String trimmed = line.trim();
			String lower = trimmed.toLowerCase(Locale.ROOT);
			String detected = detectKind(lower);
			if (detected != null) {
				if (kind != null) {
					sections.add(new Section(kind, name, header, body.toString().trim(), ticks, params));
				}
				kind = detected;
				header = trimmed;
				String rest = headerRest(trimmed, detected);
				name = extractName(detected, rest);
				ticks = detected.equals("process") ? extractTicks(rest) : 0;
				params = extractParams(rest, ticks);
				body.setLength(0);
			} else if (kind == null) {
				preamble.append(line).append('\n');
			} else {
				body.append(line).append('\n');
			}
		}
		if (kind != null) {
			sections.add(new Section(kind, name, header, body.toString().trim(), ticks, params));
		}
		if (!preamble.toString().isBlank()) {
			sections.add(0, new Section("preamble", "", "", preamble.toString().trim(), 0));
		}
		return sections;
	}

	/** Определяет вид секции по строке-заголовку (или null, если это не заголовок). */
	private static String detectKind(String lower) {
		if (lower.startsWith("@event ") || lower.equals("@event")) {
			return "event";
		}
		if (lower.startsWith("@function ") || lower.equals("@function")) {
			return "function";
		}
		if (lower.startsWith("@process ") || lower.equals("@process")) {
			return "process";
		}
		// JMCC: event<player_join> {
		if (lower.startsWith("event<") || lower.startsWith("event <")) {
			return "event";
		}
		if (lower.startsWith("function ") || lower.equals("function")) {
			return "function";
		}
		// Xero
		if (lower.equals("when") || lower.startsWith("when ")) {
			return "event";
		}
		if (lower.equals("func") || lower.startsWith("func ")) {
			return "function";
		}
		if (lower.equals("process") || lower.startsWith("process ")) {
			return "process";
		}
		return null;
	}

	/** Часть заголовка после ключевого слова. */
	private static String headerRest(String header, String kind) {
		if (header.startsWith("@")) {
			return header.substring(1 + kind.length()).trim();
		}
		if (header.toLowerCase(Locale.ROOT).startsWith("event<")) {
			return header.substring(header.indexOf('<')).trim();
		}
		if (header.toLowerCase(Locale.ROOT).startsWith("event <")) {
			return header.substring(header.indexOf('<')).trim();
		}
		int space = header.indexOf(' ');
		return space < 0 ? "" : header.substring(space + 1).trim();
	}

	/** Период процесса в тиках: {@code @process name(20)} → 20; 0 = не указан. */
	private static int extractTicks(String rest) {
		int open = rest.indexOf('(');
		int close = rest.indexOf(')', open + 1);
		if (open < 0 || close < 0) {
			return 0;
		}
		String inside = rest.substring(open + 1, close).trim();
		// У процесса в скобках может быть и период, и параметры: (20, amount: number)
		for (String token : splitTopLevel(inside)) {
			String value = token.trim().replaceAll("(?i)^every\\s+", "").trim();
			try {
				return Math.max(0, (int) Double.parseDouble(value));
			} catch (NumberFormatException ignored) {
				// это параметр, а не период
			}
		}
		return 0;
	}

	private static String extractName(String kind, String rest) {
		String value = rest.trim();
		if (kind.equals("event") && value.startsWith("<")) {
			int close = value.indexOf('>');
			return close > 0 ? value.substring(1, close).trim() : "";
		}
		if (value.endsWith("{")) {
			value = value.substring(0, value.length() - 1).trim();
		}
		int paren = value.indexOf('(');
		if (paren > 0) {
			value = value.substring(0, paren).trim();
		}
		if (kind.equals("event")) {
			// событие может быть записано как block.click.right — берём всё до пробела
			int space = value.indexOf(' ');
			if (space > 0) {
				value = value.substring(0, space).trim();
			}
		}
		return value;
	}

	/** Параметры из скобок заголовка функции/процесса. */
	private static List<Param> extractParams(String rest, int ticks) {
		int open = rest.indexOf('(');
		int close = rest.lastIndexOf(')');
		if (open < 0 || close <= open) {
			return List.of();
		}
		String inside = rest.substring(open + 1, close).trim();
		if (inside.isEmpty()) {
			return List.of();
		}
		List<Param> params = new ArrayList<>();
		for (String token : splitTopLevel(inside)) {
			// чистое число в скобках процесса — это период, а не параметр
			if (token.trim().matches("(?i)(every\\s+)?\\d+(\\.\\d+)?")) {
				continue;
			}
			Param param = parseParam(token);
			if (param != null) {
				params.add(param);
			}
		}
		return params;
	}

	private static List<String> splitTopLevel(String value) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			}
			if (!quoted) {
				if (c == '[' || c == '{' || c == '(') {
					depth++;
				} else if (c == ']' || c == '}' || c == ')') {
					depth--;
				} else if (c == ',' && depth == 0) {
					parts.add(current.toString().trim());
					current.setLength(0);
					continue;
				}
			}
			current.append(c);
		}
		if (!current.toString().isBlank()) {
			parts.add(current.toString().trim());
		}
		return parts;
	}

	private static Param parseParam(String token) {
		String value = token.trim();
		if (value.isEmpty()) {
			return null;
		}
		String defaultValue = null;
		int equals = value.indexOf('=');
		if (equals >= 0) {
			defaultValue = value.substring(equals + 1).trim();
			if (defaultValue.length() >= 2 && defaultValue.startsWith("\"") && defaultValue.endsWith("\"")) {
				defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
			}
			value = value.substring(0, equals).trim();
		}
		String type = "any";
		int colon = value.indexOf(':');
		if (colon >= 0) {
			type = value.substring(colon + 1).trim();
			value = value.substring(0, colon).trim();
		}
		boolean required = defaultValue == null;
		if (value.endsWith("?")) {
			value = value.substring(0, value.length() - 1).trim();
			required = false;
		}
		if (value.isEmpty()) {
			return null;
		}
		return new Param(value, normalizeType(type), required, defaultValue);
	}

	/** Приводит тип параметра к типу значения JustMC. */
	public static String normalizeType(String type) {
		String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "text", "string", "str" -> "text";
			case "number", "num", "int", "float" -> "number";
			case "location", "loc", "position" -> "location";
			case "item", "block" -> "item";
			case "vector", "vec" -> "vector";
			case "array", "list" -> "array";
			case "map", "dict", "dictionary" -> "map";
			case "sound" -> "sound";
			case "particle" -> "particle";
			case "potion" -> "potion";
			default -> "any";
		};
	}

	/** Собирает текст обратно (заголовок + тело). */
	public static String render(List<Section> sections) {
		StringBuilder out = new StringBuilder();
		for (Section section : sections) {
			if (section.kind().equals("preamble")) {
				out.append(section.body()).append("\n\n");
				continue;
			}
			out.append(section.header()).append('\n');
			out.append(section.body()).append("\n\n");
		}
		return out.toString().stripTrailing() + "\n";
	}

	public static String template(String kind, String name) {
		return template(kind, name, List.of());
	}

	/** Заготовка секции с параметрами (для функций и процессов). */
	public static String template(String kind, String name, List<Param> params) {
		String action = switch (kind) {
			case "function" -> "[\n  {\n    \"action\": \"player_send_message\",\n    \"values\": [\n      { \"name\": \"messages\", \"value\": { \"type\": \"text\", \"text\": \"Привет из JustScript!\", \"parsing\": \"plain\" } },\n      { \"name\": \"merging\", \"value\": { \"type\": \"enum\", \"enum\": \"SPACES\" } }\n    ]\n  }\n]";
			case "process" -> "[\n  {\n    \"action\": \"player_send_message\",\n    \"values\": [\n      { \"name\": \"messages\", \"value\": { \"type\": \"text\", \"text\": \"Тик процесса\", \"parsing\": \"plain\" } },\n      { \"name\": \"merging\", \"value\": { \"type\": \"enum\", \"enum\": \"SPACES\" } }\n    ]\n  }\n]";
			default -> "[\n  {\n    \"action\": \"player_send_message\",\n    \"values\": [\n      { \"name\": \"messages\", \"value\": { \"type\": \"text\", \"text\": \"Привет из JustScript!\", \"parsing\": \"plain\" } },\n      { \"name\": \"merging\", \"value\": { \"type\": \"enum\", \"enum\": \"SPACES\" } }\n    ]\n  }\n]";
		};
		String signature = params.isEmpty() ? "" : renderParams(params);
		String header = switch (kind) {
			case "function" -> "@function " + name + signature;
			case "process" -> "@process " + name + "(" + (params.isEmpty() ? "20"
					: "20, " + renderParams(params).substring(1, renderParams(params).length() - 1)) + ")";
			default -> "@event " + name;
		};
		return header + "\n" + action + "\n\n";
	}

	/** Список параметров в виде {@code (a: number, b: text = "x")}. */
	public static String renderParams(List<Param> params) {
		StringBuilder out = new StringBuilder("(");
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			Param param = params.get(i);
			out.append(param.name()).append(": ").append(param.type());
			if (param.defaultValue() != null) {
				String value = param.defaultValue();
				boolean plain = value.matches("-?\\d+(\\.\\d+)?")
						|| value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
				out.append(" = ");
				if (plain) {
					out.append(value);
				} else {
					out.append('"').append(value).append('"');
				}
			}
		}
		return out.append(')').toString();
	}
}
