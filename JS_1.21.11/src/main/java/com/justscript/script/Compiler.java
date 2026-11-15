package com.justscript.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.justscript.events.EventCatalog;
import com.justscript.events.EventDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Собирает все скрипты проекта в один JSON-модуль JustMC
 * ({"name": ..., "handlers": [...]}).
 *
 * <p>События блоков оборачиваются в условие «блок события = этот блок»
 * (if_game_event_block_equals), события сущностей — в проверку UUID/ника.
 * Это всегда включено: иначе код блока срабатывал бы на любое такое событие.
 *
 * <p>Слишком длинные обработчики (> {@value #MAX_OPERATIONS} действий) автоматически
 * выносятся в функцию: JustMC ограничивает длину одной линии, а функция — нет.
 */
public final class Compiler {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Сколько действий в линии ещё безопасно; больше — выносим в функцию. */
	private static final int MAX_OPERATIONS = 60;

	public record Result(String json, List<String> errors, int handlers) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	private Compiler() {
	}

	public static Result compile(String moduleName, Collection<ScriptUnit> units) {
		JsonArray handlers = new JsonArray();
		List<String> errors = new ArrayList<>();
		java.util.Set<String> functionNames = new java.util.HashSet<>();
		for (ScriptUnit unit : units) {
			if (unit.code != null) {
				functionNames.addAll(HintCatalog.functions(unit.code));
			}
		}
		double position = 0;

		// Сначала глобальный скрипт (функции/процессы/события мира), потом обычные —
		// в порядке, который игрок выставил в меню «Порядок скриптов».
		List<ScriptUnit> ordered = new ArrayList<>(units);
		ordered.sort(Comparator
				.comparingInt((ScriptUnit unit) -> unit.isGlobal() ? 0 : 1)
				.thenComparingInt(unit -> unit.position));

		for (ScriptUnit unit : ordered) {
			if (unit.code == null || unit.code.isBlank()) {
				continue;
			}
			CodeLanguage language = CodeLanguage.byId(unit.language);
			for (ScriptParser.Section section : ScriptParser.parse(unit.code)) {
				if (section.kind().equals("preamble")) {
					continue;
				}
				String context = "«" + unit.displayTitle() + "» › @" + section.kind() + " " + section.name();
				List<String> sectionErrors = new ArrayList<>();
				JsonElement operations = language.toOperations(section.body(), sectionErrors, context);
				if (!sectionErrors.isEmpty()) {
					errors.addAll(sectionErrors);
					continue;
				}

				JsonObject handler = new JsonObject();
				handler.addProperty("type", section.kind());
				handler.addProperty("position", position++);

				if (section.kind().equals("event")) {
					String justmc = EventCatalog.resolveJustMc(section.name());
					if (justmc == null) {
						errors.add(context + ": неизвестное событие «" + section.name() + "»");
						continue;
					}
					EventDefinition definition = EventCatalog.find(section.name());
					if (definition != null && !scopeAllowed(unit.kind, definition.scope())) {
						errors.add(context + ": событие «" + definition.id() + "» относится к "
								+ EventCatalog.scopeLabel(definition.scope())
								+ ", а скрипт — " + kindLabel(unit.kind));
						continue;
					}
					handler.addProperty("event", justmc);

					JsonElement body = operations;
					if (countOperations(body) > MAX_OPERATIONS) {
						String functionName = autoFunctionName(section, functionNames);
						functionNames.add(functionName);
						JsonObject function = new JsonObject();
						function.addProperty("type", "function");
						function.addProperty("position", position++);
						function.addProperty("name", functionName);
						function.add("values", new JsonArray());
						function.add("operations", body);
						handlers.add(function);
						body = callFunctionOperations(functionName);
					}

					JsonElement wrapped = guard(unit, body);
					JsonObject interaction = interactionCondition(section.name());
					handler.add("operations", interaction == null ? wrapped : withCondition(interaction, wrapped));
					handlers.add(handler);
				} else if (section.kind().equals("function") || section.kind().equals("process")) {
					if (!"global".equals(unit.kind)) {
						errors.add(context + ": функции и процессы живут только в глобальном скрипте");
						continue;
					}
					if (section.name().isBlank()) {
						errors.add(context + ": у функции/процесса должно быть имя");
						continue;
					}
					handler.addProperty("name", section.name());
					handler.add("values", parameterValues(section));
					if (section.kind().equals("process") && section.ticks() > 0) {
						handler.add("operations", loopOperations(operations, section.ticks()));
					} else {
						handler.add("operations", operations);
					}
					handlers.add(handler);
				} else {
					errors.add(context + ": неизвестный вид секции «" + section.kind() + "»");
				}
			}
		}

		JsonObject root = new JsonObject();
		root.addProperty("name", moduleName);
		root.add("handlers", handlers);
		return new Result(GSON.toJson(root), errors, handlers.size());
	}

	/** Событие подходит скрипту этого вида. */
	static boolean scopeAllowed(String kind, String scope) {
		return switch (kind == null ? "global" : kind) {
			case "block" -> EventCatalog.SCOPE_BLOCK.equals(scope);
			case "entity" -> EventCatalog.SCOPE_ENTITY.equals(scope);
			default -> !EventCatalog.SCOPE_BLOCK.equals(scope) && !EventCatalog.SCOPE_ENTITY.equals(scope);
		};
	}

	static String kindLabel(String kind) {
		return switch (kind == null ? "global" : kind) {
			case "block" -> "блочный";
			case "entity" -> "сущности";
			default -> "глобальный";
		};
	}

	/** «Повторять всегда» + пауза, чтобы процесс шёл раз в N тиков. */
	private static JsonElement loopOperations(JsonElement operations, int ticks) {
		JsonObject wait = new JsonObject();
		wait.addProperty("action", "control_wait");
		JsonArray waitValues = new JsonArray();
		JsonObject duration = new JsonObject();
		duration.addProperty("type", "number");
		duration.addProperty("number", ticks);
		waitValues.add(argument("duration", duration));
		JsonObject unit = new JsonObject();
		unit.addProperty("type", "enum");
		unit.addProperty("enum", "TICKS");
		waitValues.add(argument("time_unit", unit));
		wait.add("values", waitValues);

		JsonArray body = new JsonArray();
		body.add(wait);
		if (operations.isJsonArray()) {
			for (JsonElement element : operations.getAsJsonArray()) {
				body.add(element);
			}
		} else if (operations != null) {
			body.add(operations);
		}

		JsonObject loop = new JsonObject();
		loop.addProperty("action", "repeat_forever");
		loop.add("values", new JsonArray());
		loop.add("operations", body);

		JsonArray wrapped = new JsonArray();
		wrapped.add(loop);
		return wrapped;
	}

	/** Условие «событие произошло именно на нашем блоке / с нашей сущностью». */
	private static JsonElement guard(ScriptUnit unit, JsonElement operations) {
		if ("block".equals(unit.kind)) {
			return withCondition(blockCondition(unit), operations);
		}
		if ("entity".equals(unit.kind) && !unit.entityMatchValue().isBlank()) {
			return withCondition(entityCondition(unit), operations);
		}
		return operations;
	}

	// ---------------------------------------------------------------- параметры и длинные линии

	/** Значения функции: объявленные параметры (формат JustMC «parameter»). */
	private static JsonArray parameterValues(ScriptParser.Section section) {
		JsonArray values = new JsonArray();
		if (section.params() == null || section.params().isEmpty()) {
			return values;
		}
		JsonArray parameters = new JsonArray();
		int slot = 0;
		for (ScriptParser.Param param : section.params()) {
			JsonObject value = new JsonObject();
			value.addProperty("type", "parameter");
			value.addProperty("type_key", "singular");
			value.addProperty("name", param.name());
			value.addProperty("value_type", param.type());
			value.addProperty("is_required", param.required() ? "true" : "false");
			value.addProperty("default_value", param.defaultValue() == null ? "{}" : param.defaultValue());
			value.addProperty("slot", slot);
			value.addProperty("description_slot", 9 + slot);
			value.addProperty("description", description(param.name()));
			parameters.add(value);
			slot++;
		}
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		array.add("values", parameters);
		values.add(argument("parameters", array));
		return values;
	}

	/** Подпись параметра для визуального редактора JustMC. */
	private static String description(String name) {
		JsonObject fallback = new JsonObject();
		fallback.addProperty("rawText", name);
		fallback.addProperty("parsingType", "LEGACY");
		JsonObject translations = new JsonObject();
		JsonObject root = new JsonObject();
		root.add("translations", translations);
		root.add("fallback", fallback);
		return root.toString();
	}

	/** Сколько действий и условий в линии (рекурсивно). */
	private static int countOperations(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return 0;
		}
		if (element.isJsonArray()) {
			int count = 0;
			for (JsonElement child : element.getAsJsonArray()) {
				count += countOperations(child);
			}
			return count;
		}
		if (!element.isJsonObject()) {
			return 0;
		}
		JsonObject object = element.getAsJsonObject();
		int count = object.has("action") ? 1 : 0;
		if (object.has("operations")) {
			count += countOperations(object.get("operations"));
		}
		return count;
	}

	/** Уникальное имя для авто-функции из длинного события. */
	private static String autoFunctionName(ScriptParser.Section section, java.util.Set<String> used) {
		String base = section.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
		if (base.isBlank()) {
			base = "event";
		}
		String name = "js_" + base;
		int counter = 2;
		while (used.contains(name)) {
			name = "js_" + base + "_" + counter++;
		}
		return name;
	}

	/** Действие «вызвать функцию» без аргументов. */
	private static JsonArray callFunctionOperations(String functionName) {
		JsonObject action = new JsonObject();
		action.addProperty("action", "call_function");
		JsonArray values = new JsonArray();
		JsonObject name = new JsonObject();
		name.addProperty("type", "text");
		name.addProperty("text", functionName);
		name.addProperty("parsing", "plain");
		values.add(argument("function_name", name));
		JsonObject args = new JsonObject();
		args.addProperty("type", "map");
		args.add("values", new JsonObject());
		values.add(argument("args", args));
		action.add("values", values);
		JsonArray wrapped = new JsonArray();
		wrapped.add(action);
		return wrapped;
	}

	private static JsonElement withCondition(JsonObject condition, JsonElement operations) {
		condition.addProperty("is_inverted", false);
		condition.add("operations", operations);
		JsonArray wrapped = new JsonArray();
		wrapped.add(condition);
		return wrapped;
	}

	private static JsonObject blockCondition(ScriptUnit unit) {
		JsonObject condition = new JsonObject();
		condition.addProperty("action", "if_game_event_block_equals");

		JsonArray values = new JsonArray();
		// Тип блока не ставим: JustMC проверяет блоки отдельным условием,
		// здесь достаточно местоположения.
		JsonObject locationValue = new JsonObject();
		locationValue.addProperty("type", "location");
		locationValue.addProperty("x", unit.x);
		locationValue.addProperty("y", unit.y);
		locationValue.addProperty("z", unit.z);
		locationValue.addProperty("yaw", 0);
		locationValue.addProperty("pitch", 0);
		values.add(argument("locations", locationValue));
		condition.add("values", values);
		return condition;
	}

	/**
	 * Сравниваем событие с сущностью скрипта.
	 * <p>Два режима (переключаются в скрипте сущности):
	 * <ul>
	 *   <li>UUID — {@code uuid} цели события против сохранённого UUID;</li>
	 *   <li>ник — {@code name} цели события против сохранённого имени.</li>
	 * </ul>
	 * Значение цели события берём через game_value с целью {@code default}
	 * (у события есть «своя» сущность), поэтому условие работает в любом
	 * событии, где такая цель есть.
	 */
	private static JsonObject entityCondition(ScriptUnit unit) {
		JsonObject condition = new JsonObject();
		condition.addProperty("action", "if_variable_equals");

		JsonArray values = new JsonArray();
		JsonObject actual = new JsonObject();
		actual.addProperty("type", "game_value");
		actual.addProperty("game_value", unit.matchByName() ? "name" : "uuid");
		actual.addProperty("selection", "{\"type\":\"default\"}");
		values.add(argument("value", actual));

		JsonObject expected = new JsonObject();
		expected.addProperty("type", "text");
		expected.addProperty("text", unit.entityMatchValue());
		expected.addProperty("parsing", "plain");
		values.add(argument("compare", arrayOf(expected)));

		JsonObject any = new JsonObject();
		any.addProperty("type", "enum");
		any.addProperty("enum", "ANY");
		values.add(argument("compare_type", any));

		condition.add("values", values);
		return condition;
	}

	private static JsonObject arrayOf(JsonElement... elements) {
		JsonObject array = new JsonObject();
		array.addProperty("type", "array");
		JsonArray values = new JsonArray();
		for (JsonElement element : elements) {
			values.add(element);
		}
		array.add("values", values);
		return array;
	}

	/**
	 * Уточнение для «кликов по блоку»: player_interact срабатывает и на ЛКМ, и на ПКМ,
	 * поэтому добавляем проверку типа взаимодействия.
	 */
	private static JsonObject interactionCondition(String alias) {
		String expected = switch (alias == null ? "" : alias) {
			case "block.click.right", "block.click.shift_right", "block.click.item" -> "right_click_block";
			case "block.click.left" -> "left_click_block";
			default -> null;
		};
		if (expected == null) {
			return null;
		}
		JsonObject condition = new JsonObject();
		condition.addProperty("action", "if_variable_equals");

		JsonArray values = new JsonArray();
		JsonObject eventValue = new JsonObject();
		eventValue.addProperty("type", "game_value");
		eventValue.addProperty("game_value", "event_interaction");
		eventValue.addProperty("selection", "null");
		values.add(argument("value", eventValue));

		JsonObject expectedValue = new JsonObject();
		expectedValue.addProperty("type", "text");
		expectedValue.addProperty("text", expected);
		expectedValue.addProperty("parsing", "plain");
		values.add(argument("compare", arrayOf(expectedValue)));
		condition.add("values", values);
		return condition;
	}

	private static JsonObject argument(String name, JsonElement value) {
		JsonObject argument = new JsonObject();
		argument.addProperty("name", name);
		argument.add("value", value);
		return argument;
	}
}
