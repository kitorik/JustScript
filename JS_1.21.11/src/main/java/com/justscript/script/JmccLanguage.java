package com.justscript.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JMCC — «Minecraft C», компактный синтаксис для JustMC
 * (github.com/Deolure/JMCC). Это практичное подмножество:
 *
 * <pre>
 * event&lt;player_join&gt; {
 *   player::message("Привет, %player%!");
 *   variable::set("counter", 0);
 *   variable::add("counter", 1);
 *   code::wait(20);
 * }
 *
 * function welcome(name: text) {
 *   player::message("Привет, " + name);
 * }
 * </pre>
 *
 * Поддерживаются: {@code player::message}, {@code code::wait},
 * {@code variable::set/add/subtract}, {@code function::call}, {@code code::stop}.
 * Каждая строка превращается в действие JustMC.
 */
public final class JmccLanguage implements CodeLanguage {
	private static final Pattern CALL = Pattern.compile("^([A-Za-z_][\\w]*)\\s*::\\s*([A-Za-z_][\\w|]*)\\s*\\((.*)\\)\\s*;?$");

	@Override
	public String id() {
		return "jmcc";
	}

	@Override
	public String displayName() {
		return "JMCC";
	}

	@Override
	public JsonElement toOperations(String body, List<String> errors, String context) {
		JsonArray operations = new JsonArray();
		if (body == null || body.isBlank()) {
			return operations;
		}
		for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
			String statement = line.trim();
			if (statement.isEmpty() || statement.equals("{") || statement.equals("}")) {
				continue;
			}
			if (statement.startsWith("//") || statement.startsWith("#")) {
				continue;
			}
			if (!statement.endsWith(";") && !statement.endsWith("}")) {
				// JMCC допускает строки без «;» — просто добавляем
				statement = statement + ";";
			}
			JsonObject action = parseStatement(statement, errors, context);
			if (action != null) {
				operations.add(action);
			}
		}
		return operations;
	}

	private JsonObject parseStatement(String statement, List<String> errors, String context) {
		Matcher matcher = CALL.matcher(statement);
		if (!matcher.matches()) {
			errors.add(context + ": JMCC не понял строку «" + statement + "». "
					+ "Поддерживается: player::message(...), code::wait(...), variable::set/add/subtract(...)");
			return null;
		}
		String object = matcher.group(1).toLowerCase(Locale.ROOT);
		String method = matcher.group(2).toLowerCase(Locale.ROOT);
		List<String> args = splitArgs(matcher.group(3));
		return switch (object + "::" + method) {
			case "player::message", "player::send_message" -> message(args, errors, context);
			case "code::wait" -> wait(args, errors, context);
			case "variable::set", "var::set" -> variable("set_variable_value", args, errors, context);
			case "variable::add", "var::add" -> variable("set_variable_add", args, errors, context);
			case "variable::subtract", "var::subtract" -> variable("set_variable_subtract", args, errors, context);
			case "function::call", "call" -> callFunction(args, errors, context);
			case "code::stop", "control::stop" -> {
				JsonObject action = new JsonObject();
				action.addProperty("action", "control_end_thread");
				action.add("values", new JsonArray());
				yield action;
			}
			default -> {
				errors.add(context + ": в JMCC пока нет «" + object + "::" + method
						+ "». Есть player::message, code::wait, variable::set/add/subtract, function::call");
				yield null;
			}
		};
	}

	private JsonObject message(List<String> args, List<String> errors, String context) {
		if (args.isEmpty()) {
			errors.add(context + ": player::message требует текст");
			return null;
		}
		JsonObject action = new JsonObject();
		action.addProperty("action", "player_send_message");
		JsonArray values = new JsonArray();
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		JsonArray texts = new JsonArray();
		for (String arg : args) {
			JsonElement value = valueOf(arg);
			if (value.isJsonObject() && "text".equals(value.getAsJsonObject().get("type").getAsString())) {
				texts.add(value);
			} else if (value.isJsonObject() && "variable".equals(value.getAsJsonObject().get("type").getAsString())) {
				texts.add(value);
			} else {
				texts.add(text(unquote(arg)));
			}
		}
		array.add("values", texts);
		values.add(argument("messages", array));
		JsonObject merging = new JsonObject();
		merging.addProperty("type", "enum");
		merging.addProperty("enum", "SPACES");
		values.add(argument("merging", merging));
		action.add("values", values);
		return action;
	}

	private JsonObject wait(List<String> args, List<String> errors, String context) {
		if (args.isEmpty()) {
			errors.add(context + ": code::wait требует число тиков");
			return null;
		}
		JsonObject action = new JsonObject();
		action.addProperty("action", "control_wait");
		JsonArray values = new JsonArray();
		JsonObject duration = new JsonObject();
		duration.addProperty("type", "number");
		duration.addProperty("number", parseNumber(args.get(0), 1));
		values.add(argument("duration", duration));
		JsonObject unit = new JsonObject();
		unit.addProperty("type", "enum");
		unit.addProperty("enum", "TICKS");
		values.add(argument("time_unit", unit));
		action.add("values", values);
		return action;
	}

	private JsonObject variable(String actionId, List<String> args, List<String> errors, String context) {
		if (args.size() < 2) {
			errors.add(context + ": нужно имя переменной и значение: " + actionId);
			return null;
		}
		JsonObject action = new JsonObject();
		action.addProperty("action", actionId);
		JsonArray values = new JsonArray();
		JsonObject variable = new JsonObject();
		variable.addProperty("type", "variable");
		variable.addProperty("variable", unquote(args.get(0)));
		variable.addProperty("scope", "game");
		values.add(argument("variable", variable));
		values.add(argument("value", valueOf(args.get(1))));
		action.add("values", values);
		return action;
	}

	private JsonObject callFunction(List<String> args, List<String> errors, String context) {
		if (args.isEmpty()) {
			errors.add(context + ": function::call требует имя функции");
			return null;
		}
		JsonObject action = new JsonObject();
		action.addProperty("action", "call_function");
		JsonArray values = new JsonArray();
		values.add(argument("function_name", text(unquote(args.get(0)))));
		JsonObject map = new JsonObject();
		map.addProperty("type", "map");
		map.add("values", new JsonObject());
		values.add(argument("args", map));
		action.add("values", values);
		return action;
	}

	// ---------------------------------------------------------------- значения

	private JsonElement valueOf(String raw) {
		String value = raw.trim();
		if (value.isEmpty()) {
			return text("");
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return text(unquote(value));
		}
		if (value.startsWith("$")) {
			JsonObject variable = new JsonObject();
			variable.addProperty("type", "variable");
			variable.addProperty("variable", value.substring(1).trim());
			variable.addProperty("scope", "game");
			return variable;
		}
		if (value.matches("-?\\d+(\\.\\d+)?")) {
			JsonObject number = new JsonObject();
			number.addProperty("type", "number");
			number.addProperty("number", parseNumber(value, 0));
			return number;
		}
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			JsonObject bool = new JsonObject();
			bool.addProperty("type", "enum");
			bool.addProperty("enum", value.toUpperCase(Locale.ROOT));
			return bool;
		}
		return text(value);
	}

	private JsonObject text(String value) {
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", value);
		text.addProperty("parsing", "plain");
		return text;
	}

	private JsonObject argument(String name, JsonElement value) {
		JsonObject argument = new JsonObject();
		argument.addProperty("name", name);
		argument.add("value", value);
		return argument;
	}

	private static double parseNumber(String value, double fallback) {
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String unquote(String value) {
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	/** Аргументы вызова: по запятым вне строк и скобок. */
	private static List<String> splitArgs(String value) {
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
				if (c == '(' || c == '[' || c == '{') {
					depth++;
				} else if (c == ')' || c == ']' || c == '}') {
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
}
