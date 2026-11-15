package com.justscript.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xero — компактный синтаксис для JustMC-модулей (экспериментально).
 *
 * <p>Это не полная реализация языка Xero (github.com/garfystyle/xero),
 * а удобное подмножество, которое превращается в те же JSON-действия JustMC:
 *
 * <pre>
 * when player_join {
 *   send "Привет, %player%!"
 *   set counter = 0
 *   add 1 to counter
 *   if counter == 1 {
 *     send "Первый раз"
 *   } else {
 *     send "Уже был здесь"
 *   }
 *   repeat 3 times {
 *     wait 1s
 *   }
 *   run my_function
 * }
 * </pre>
 *
 * Поддерживаются: {@code when}, {@code func}, {@code process},
 * {@code send}, {@code set/add/subtract}, {@code if/else}, {@code repeat N times},
 * {@code forever}, {@code wait}, {@code run}, {@code stop}, {@code break}, {@code raw { … }}.
 * Значения: строки, числа, формулы, {@code true/false}, {@code loc(…)}, {@code vec(…)},
 * {@code item(…)}, списки, словари и переменные (префиксы {@code save.}, {@code game.},
 * {@code line.}, {@code local.}).
 */
public final class XeroLanguage implements CodeLanguage {
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
	private static final Pattern SUBSTITUTION = Pattern.compile("\\$\\{([^}]+)}");
	private static final Pattern MATH = Pattern.compile("[0-9+\\-*/%(). ,]+");

	@Override
	public String id() {
		return "xero";
	}

	@Override
	public String displayName() {
		return "Xero (экспериментально)";
	}

	@Override
	public JsonElement toOperations(String body, List<String> errors, String context) {
		List<String> lines = prepare(body);
		Cursor cursor = new Cursor();
		JsonArray operations = parseBlock(lines, cursor, errors, context);
		if (cursor.index < lines.size()) {
			errors.add(context + ": лишний «}» в строке " + (cursor.index + 1));
		}
		return operations;
	}

	// ---------------------------------------------------------------- строки

	/** Нормализует переводы строк и убирает комментарии вне строк. */
	private static List<String> prepare(String body) {
		List<String> lines = new ArrayList<>();
		if (body == null) {
			return lines;
		}
		String text = body.replace("\r\n", "\n").replace('\r', '\n');
		if (text.startsWith("\uFEFF")) {
			text = text.substring(1);
		}
		for (String raw : text.split("\n", -1)) {
			lines.add(stripComment(raw));
		}
		return lines;
	}

	private static String stripComment(String line) {
		boolean inString = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && inString) {
				i++;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (!inString) {
				if (c == '#' || (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')) {
					return line.substring(0, i);
				}
			}
		}
		return line;
	}

	// ---------------------------------------------------------------- блоки

	private static final class Cursor {
		int index;
		boolean pendingElse;
		String pendingElseText = "";
	}

	private static JsonArray parseBlock(List<String> lines, Cursor cursor, List<String> errors, String context) {
		JsonArray operations = new JsonArray();
		while (cursor.index < lines.size()) {
			String line = lines.get(cursor.index).trim();
			if (line.isEmpty()) {
				cursor.index++;
				continue;
			}
			if (line.startsWith("}")) {
				String rest = line.substring(1).trim();
				cursor.index++;
				if (rest.startsWith("else")) {
					cursor.pendingElse = true;
					cursor.pendingElseText = rest;
				} else if (!rest.isEmpty() && !rest.equals(";")) {
					errors.add(context + ": после «}» неожиданный текст «" + rest + "»");
				}
				return operations;
			}
			if (line.equals("{")) {
				cursor.index++;
				continue;
			}
			int lineNo = cursor.index + 1;
			try {
				JsonElement statement = parseStatement(lines, cursor, errors, context, line);
				if (statement == null) {
					continue;
				}
				if (statement.isJsonArray()) {
					statement.getAsJsonArray().forEach(operations::add);
				} else {
					operations.add(statement);
				}
			} catch (RuntimeException e) {
				errors.add(context + ": строка " + lineNo + ": " + e.getMessage());
				cursor.index++;
			}
		}
		return operations;
	}

	private static JsonElement parseStatement(List<String> lines, Cursor cursor, List<String> errors,
			String context, String line) {
		String lower = line.toLowerCase(Locale.ROOT);

		if (lower.startsWith("send ")) {
			cursor.index++;
			return send(line.substring(5).trim());
		}
		if (lower.startsWith("set ") && line.contains("=")) {
			cursor.index++;
			int equals = topLevelIndexOf(line, '=');
			if (equals < 0) {
				throw new IllegalArgumentException("в «set» нужен знак =");
			}
			String name = line.substring(4, equals).trim();
			String value = line.substring(equals + 1).trim();
			if (name.isEmpty()) {
				throw new IllegalArgumentException("не указано имя переменной");
			}
			return action("set_variable_value",
					argument("variable", variable(name)),
					argument("value", value(value)));
		}
		if (lower.startsWith("add ") && lower.contains(" to ")) {
			cursor.index++;
			int to = lower.lastIndexOf(" to ");
			String value = line.substring(4, to).trim();
			String name = line.substring(to + 4).trim();
			return action("set_variable_add",
					argument("variable", variable(name)),
					argument("value", value(value)));
		}
		if (lower.startsWith("subtract ") && lower.contains(" from ")) {
			cursor.index++;
			int from = lower.lastIndexOf(" from ");
			String value = line.substring(9, from).trim();
			String name = line.substring(from + 6).trim();
			return action("set_variable_subtract",
					argument("variable", variable(name)),
					argument("value", value(value)));
		}
		if (lower.startsWith("wait ")) {
			cursor.index++;
			return waitStatement(line.substring(5).trim());
		}
		if (lower.startsWith("run ")) {
			cursor.index++;
			String name = line.substring(4).trim();
			JsonObject args = new JsonObject();
			args.addProperty("type", "map");
			args.add("values", new JsonObject());
			return action("call_function",
					argument("function_name", text(name)),
					argument("args", args));
		}
		if (lower.equals("stop") || lower.equals("return")) {
			cursor.index++;
			return action("control_end_thread");
		}
		if (lower.equals("break")) {
			cursor.index++;
			return action("control_stop_repeat");
		}
		if (lower.startsWith("if ")) {
			return parseIf(lines, cursor, errors, context, line);
		}
		if (lower.startsWith("repeat ") && lower.contains(" time")) {
			return parseRepeat(lines, cursor, errors, context, line);
		}
		if (lower.equals("forever {") || lower.equals("forever")) {
			return parseForever(lines, cursor, errors, context);
		}
		if (lower.equals("raw {") || lower.startsWith("raw ")) {
			return parseRaw(lines, cursor, context, line);
		}
		// Строка, похожая на сырой JSON, — вставляем как есть (удобный мостик к JSON).
		if (line.startsWith("{") || line.startsWith("[")) {
			cursor.index++;
			try {
				return JsonParser.parseString(line);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("неполный JSON — " + e.getMessage()
						+ " (допиши строку или убери фигурную скобку)");
			}
		}
		throw new IllegalArgumentException("не знаю команду «" + line + "» (см. подсказку по Tab)");
	}

	private static JsonElement parseIf(List<String> lines, Cursor cursor, List<String> errors, String context,
			String line) {
		String conditionText = line.substring(2).trim();
		conditionText = stripOpenBrace(conditionText);
		cursor.index++;
		JsonObject condition = condition(conditionText);
		condition.add("operations", parseBlock(lines, cursor, errors, context));
		JsonArray result = new JsonArray();
		result.add(condition);

		if (!cursor.pendingElse && cursor.index < lines.size()
				&& lines.get(cursor.index).trim().toLowerCase(Locale.ROOT).startsWith("else")) {
			String elseLine = lines.get(cursor.index).trim();
			cursor.index++;
			if (!elseLine.toLowerCase(Locale.ROOT).startsWith("else if")) {
				JsonObject elseAction = new JsonObject();
				elseAction.addProperty("action", "else");
				elseAction.add("values", new JsonArray());
				elseAction.add("operations", parseBlock(lines, cursor, errors, context));
				result.add(elseAction);
				return result;
			}
			// else if … { — вкладываем ещё одно условие
			JsonObject elseAction = new JsonObject();
			elseAction.addProperty("action", "else");
			elseAction.add("values", new JsonArray());
			JsonArray nested = new JsonArray();
			nested.add(parseIf(lines, cursor, errors, context, "if " + elseLine.substring(7).trim()));
			elseAction.add("operations", nested);
			result.add(elseAction);
			return result;
		}
		if (cursor.pendingElse) {
			String elseText = cursor.pendingElseText == null ? "" : cursor.pendingElseText;
			cursor.pendingElse = false;
			cursor.pendingElseText = "";
			JsonObject elseAction = new JsonObject();
			elseAction.addProperty("action", "else");
			elseAction.add("values", new JsonArray());
			if (elseText.toLowerCase(Locale.ROOT).startsWith("else if")) {
				// «} else if условие {» — внутри else ещё одно условие
				JsonArray nested = new JsonArray();
				nested.add(parseIf(lines, cursor, errors, context, "if " + elseText.substring(7).trim()));
				elseAction.add("operations", nested);
			} else {
				elseAction.add("operations", parseBlock(lines, cursor, errors, context));
			}
			result.add(elseAction);
		}
		return result;
	}

	private static JsonElement parseRepeat(List<String> lines, Cursor cursor, List<String> errors, String context,
			String line) {
		String rest = line.substring(6).trim();
		String lower = rest.toLowerCase(Locale.ROOT);
		int timesIndex = lower.indexOf(" time");
		if (timesIndex < 0) {
			throw new IllegalArgumentException("пиши «repeat 3 times {»");
		}
		String amount = rest.substring(0, timesIndex).trim();
		stripOpenBrace(rest.substring(timesIndex + 5).trim());
		cursor.index++;
		JsonObject loop = action("repeat_multi_times",
				argument("variable", variable("_i")),
				argument("amount", value(amount)));
		loop.add("operations", parseBlock(lines, cursor, errors, context));
		return loop;
	}

	private static JsonElement parseForever(List<String> lines, Cursor cursor, List<String> errors, String context) {
		cursor.index++;
		JsonObject loop = action("repeat_forever");
		loop.add("operations", parseBlock(lines, cursor, errors, context));
		return loop;
	}

	private static JsonElement parseRaw(List<String> lines, Cursor cursor, String context, String line) {
		String trimmed = line.trim();
		String inner = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
		if (!inner.isEmpty() && !inner.equals("{")) {
			// raw { … } на одной строке
			cursor.index++;
			try {
				return JsonParser.parseString(stripOpenBrace(inner));
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("raw: не разобрать JSON — " + e.getMessage());
			}
		}
		cursor.index++;
		StringBuilder json = new StringBuilder();
		int depth = 0;
		while (cursor.index < lines.size()) {
			String current = lines.get(cursor.index);
			cursor.index++;
			for (char c : current.toCharArray()) {
				if (c == '{' || c == '[') {
					depth++;
				} else if (c == '}' || c == ']') {
					depth--;
				}
			}
			json.append(current).append('\n');
			if (depth <= 0) {
				break;
			}
		}
		try {
			return JsonParser.parseString(json.toString());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("raw: не разобрать JSON — " + e.getMessage()
					+ " (подсказка из " + context + ")");
		}
	}

	private static String stripOpenBrace(String value) {
		String result = value.trim();
		if (result.endsWith("{")) {
			result = result.substring(0, result.length() - 1).trim();
		}
		return result;
	}

	// ---------------------------------------------------------------- действия

	private static JsonObject send(String expression) {
		JsonArray messages = new JsonArray();
		messages.add(value(expression));
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		array.add("values", messages);

		JsonObject merging = new JsonObject();
		merging.addProperty("type", "enum");
		merging.addProperty("enum", "SPACES");
		return action("player_send_message",
				argument("messages", array),
				argument("merging", merging));
	}

	private static JsonObject waitStatement(String expression) {
		String text = expression.trim();
		String unit = "SECONDS";
		String number = text;
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.endsWith("ticks")) {
			number = text.substring(0, text.length() - 5).trim();
			unit = "TICKS";
		} else if (lower.endsWith("seconds")) {
			number = text.substring(0, text.length() - 7).trim();
			unit = "SECONDS";
		} else if (lower.endsWith("minutes")) {
			number = text.substring(0, text.length() - 7).trim();
			unit = "MINUTES";
		} else if (lower.endsWith("t")) {
			number = text.substring(0, text.length() - 1).trim();
			unit = "TICKS";
		} else if (lower.endsWith("s")) {
			number = text.substring(0, text.length() - 1).trim();
			unit = "SECONDS";
		} else if (lower.endsWith("m")) {
			number = text.substring(0, text.length() - 1).trim();
			unit = "MINUTES";
		}
		JsonObject unitValue = new JsonObject();
		unitValue.addProperty("type", "enum");
		unitValue.addProperty("enum", unit);
		return action("control_wait",
				argument("duration", value(number)),
				argument("time_unit", unitValue));
	}

	// ---------------------------------------------------------------- условия

	private static JsonObject condition(String expression) {
		String[] operators = {">=", "<=", "==", "!=", ">", "<", "="};
		for (String operator : operators) {
			int index = topLevelIndexOf(expression, operator);
			if (index < 0) {
				continue;
			}
			String left = expression.substring(0, index).trim();
			String right = expression.substring(index + operator.length()).trim();
			JsonObject result = switch (operator) {
				case "==", "=" -> action("if_variable_equals",
						argument("value", value(left)),
						argument("compare", arrayOf(value(right))),
						argument("compare_type", enumValue("ANY")));
				case "!=" -> {
					JsonObject inverted = action("if_variable_equals",
							argument("value", value(left)),
							argument("compare", arrayOf(value(right))),
							argument("compare_type", enumValue("ANY")));
					inverted.addProperty("is_inverted", true);
					yield inverted;
				}
				case ">" -> comparison("if_variable_greater", left, right);
				case "<" -> comparison("if_variable_less", left, right);
				case ">=" -> comparison("if_variable_greater_or_equals", left, right);
				default -> comparison("if_variable_less_or_equals", left, right);
			};
			return result;
		}
		// Условие без оператора — просто «значение истинно».
		return action("if_variable_equals",
				argument("value", value(expression)),
				argument("compare", arrayOf(enumValue("TRUE"))),
				argument("compare_type", enumValue("ANY")));
	}

	private static JsonObject comparison(String actionId, String left, String right) {
		return action(actionId,
				argument("value", value(left)),
				argument("compare", value(right)));
	}

	// ---------------------------------------------------------------- значения

	private static JsonElement value(String raw) {
		String expression = raw == null ? "" : raw.trim();
		if (expression.isEmpty()) {
			return new JsonObject();
		}
		String lower = expression.toLowerCase(Locale.ROOT);
		if (lower.equals("true") || lower.equals("false")) {
			return enumValue(lower.toUpperCase(Locale.ROOT));
		}
		if (NUMBER.matcher(expression).matches()) {
			JsonObject number = new JsonObject();
			number.addProperty("type", "number");
			number.addProperty("number", Double.parseDouble(expression));
			return number;
		}
		if (expression.startsWith("mm\"") && expression.endsWith("\"")) {
			return text(expression.substring(3, expression.length() - 1));
		}
		if (expression.startsWith("\"") && expression.endsWith("\"") && expression.length() >= 2) {
			return text(unescape(expression.substring(1, expression.length() - 1)));
		}
		if (lower.startsWith("loc(") || lower.startsWith("location(")) {
			return location(expression);
		}
		if (lower.startsWith("vec(") || lower.startsWith("vector(")) {
			return vector(expression);
		}
		if (lower.startsWith("item(")) {
			return item(expression);
		}
		if (expression.startsWith("[")) {
			return list(expression);
		}
		if (expression.startsWith("{")) {
			return map(expression);
		}
		if (expression.matches("[0-9+\\-*/%(). ,]+") && expression.matches(".*[+\\-*/].*")) {
			JsonObject math = new JsonObject();
			math.addProperty("type", "number");
			math.addProperty("number", "%math(" + expression + ")");
			return math;
		}
		if (expression.startsWith("$")) {
			return variable(expression.substring(1));
		}
		if (IDENTIFIER.matcher(expression).matches()) {
			return variable(expression);
		}
		// Всё остальное — текст с подстановками ${переменная}.
		return text(expression);
	}

	private static JsonObject text(String raw) {
		String text = SUBSTITUTION.matcher(raw).replaceAll(match -> Matcher.quoteReplacement(toPlaceholder(match.group(1))));
		JsonObject value = new JsonObject();
		value.addProperty("type", "text");
		value.addProperty("text", escapePlaceholders(text));
		value.addProperty("parsing", text.indexOf('&') >= 0 || text.indexOf('\u00a7') >= 0 ? "legacy" : "plain");
		return value;
	}

	private static String toPlaceholder(String name) {
		String clean = name.trim();
		if (clean.startsWith("save.")) {
			return "%var_save(" + clean.substring(5) + ")";
		}
		if (clean.startsWith("game.")) {
			return "%var(" + clean.substring(5) + ")";
		}
		if (clean.startsWith("line.")) {
			return "%var_line(" + clean.substring(5) + ")";
		}
		if (clean.startsWith("local.")) {
			return "%var_local(" + clean.substring(6) + ")";
		}
		// системные значения JustMC пишутся с закрывающим %
		if (clean.equals("player") || clean.equals("random_uuid")) {
			return "%" + clean + "%";
		}
		return "%var_local(" + clean + ")";
	}

	private static String escapePlaceholders(String text) {
		return text.replace("\\n", "\n");
	}

	private static String unescape(String text) {
		return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
	}

	private static JsonObject location(String expression) {
		List<String> parts = arguments(expression);
		JsonObject value = new JsonObject();
		value.addProperty("type", "location");
		value.addProperty("x", number(parts, 0, 0));
		value.addProperty("y", number(parts, 1, 0));
		value.addProperty("z", number(parts, 2, 0));
		value.addProperty("yaw", number(parts, 3, 0));
		value.addProperty("pitch", number(parts, 4, 0));
		return value;
	}

	private static JsonObject vector(String expression) {
		List<String> parts = arguments(expression);
		JsonObject value = new JsonObject();
		value.addProperty("type", "vector");
		value.addProperty("x", number(parts, 0, 0));
		value.addProperty("y", number(parts, 1, 0));
		value.addProperty("z", number(parts, 2, 0));
		return value;
	}

	private static JsonObject item(String expression) {
		List<String> parts = arguments(expression);
		String id = parts.isEmpty() ? "minecraft:stone" : parts.get(0).trim().replace("\"", "");
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		String count = parts.size() > 1 ? parts.get(1).trim() : "1";
		JsonObject value = new JsonObject();
		value.addProperty("type", "item");
		value.addProperty("item", "{Count:" + count + "b,id:\"" + id + "\"}");
		return value;
	}

	private static JsonObject list(String expression) {
		JsonArray values = new JsonArray();
		String inner = expression.substring(1, expression.endsWith("]") ? expression.length() - 1 : expression.length());
		for (String part : splitTopLevel(inner, ',')) {
			if (!part.trim().isEmpty()) {
				values.add(value(part));
			}
		}
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		array.add("values", values);
		return array;
	}

	private static JsonObject map(String expression) {
		JsonObject values = new JsonObject();
		String inner = expression.substring(1, expression.endsWith("}") ? expression.length() - 1 : expression.length());
		for (String part : splitTopLevel(inner, ',')) {
			int colon = topLevelIndexOf(part, ':');
			if (colon < 0) {
				continue;
			}
			String key = part.substring(0, colon).trim().replaceAll("^\"|\"$", "");
			values.add(text(key).toString(), value(part.substring(colon + 1)));
		}
		JsonObject map = new JsonObject();
		map.addProperty("type", "map");
		map.add("values", values);
		return map;
	}

	private static JsonObject variable(String rawName) {
		String name = rawName.trim();
		String scope = "local";
		if (name.startsWith("save.")) {
			scope = "save";
			name = name.substring(5);
		} else if (name.startsWith("game.")) {
			scope = "game";
			name = name.substring(5);
		} else if (name.startsWith("line.")) {
			scope = "line";
			name = name.substring(4);
		} else if (name.startsWith("local.")) {
			name = name.substring(6);
		}
		JsonObject value = new JsonObject();
		value.addProperty("type", "variable");
		value.addProperty("variable", name);
		value.addProperty("scope", scope);
		return value;
	}

	private static JsonObject enumValue(String name) {
		JsonObject value = new JsonObject();
		value.addProperty("type", "enum");
		value.addProperty("enum", name);
		return value;
	}

	// ---------------------------------------------------------------- утилиты

	private static JsonObject action(String id, JsonObject... arguments) {
		JsonObject action = new JsonObject();
		action.addProperty("action", id);
		JsonArray values = new JsonArray();
		for (JsonObject argument : arguments) {
			values.add(argument);
		}
		action.add("values", values);
		return action;
	}

	private static JsonObject argument(String name, JsonElement value) {
		JsonObject argument = new JsonObject();
		argument.addProperty("name", name);
		argument.add("value", value);
		return argument;
	}

	private static JsonObject arrayOf(JsonElement element) {
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		JsonArray values = new JsonArray();
		values.add(element);
		array.add("values", values);
		return array;
	}

	private static List<String> arguments(String expression) {
		int open = expression.indexOf('(');
		int close = expression.lastIndexOf(')');
		if (open < 0) {
			return List.of();
		}
		String inner = close > open ? expression.substring(open + 1, close) : expression.substring(open + 1);
		return splitTopLevel(inner, ',');
	}

	private static double number(List<String> parts, int index, double fallback) {
		if (index >= parts.size()) {
			return fallback;
		}
		try {
			return Double.parseDouble(parts.get(index).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int topLevelIndexOf(String text, String needle) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && inString) {
				i++;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (depth == 0 && text.startsWith(needle, i)) {
				return i;
			}
		}
		return -1;
	}

	private static int topLevelIndexOf(String text, char needle) {
		return topLevelIndexOf(text, String.valueOf(needle));
	}

	private static List<String> splitTopLevel(String text, char separator) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		boolean inString = false;
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && inString) {
				i++;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (c == separator && depth == 0) {
				parts.add(text.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}
}
