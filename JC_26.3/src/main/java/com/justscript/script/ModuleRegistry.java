package com.justscript.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justscript.Config;
import com.justscript.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Установленные модули проекта: их можно добавлять по ID, а функции и процессы
 * из них — вызывать из редактора (кнопка «Модули»).
 *
 * <p>Хранится в {@code config/justscript/modules.json}. После каждой сборки
 * текущий модуль автоматически появляется в списке со своими функциями/процессами.
 */
public final class ModuleRegistry {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static ModuleRegistry instance;

	/** Вызываемая функция или процесс модуля. */
	public record Callable(String moduleId, String moduleName, String name, String kind, List<String> params) {
		public String label() {
			String suffix = params.isEmpty() ? "" : "(" + String.join(", ", params) + ")";
			return kind.equals("process") ? "↻ " + name + suffix : "ƒ " + name + suffix;
		}
	}

	public static final class Module {
		public String id = "";
		public String name = "";
		/** manual — добавлен по ID, upload — из сборки, global — из глобального скрипта. */
		public String source = "manual";
		public long added = System.currentTimeMillis();
		public List<Func> funcs = new ArrayList<>();
	}

	public static final class Func {
		public String name = "";
		public String kind = "function";
		public List<String> params = new ArrayList<>();
	}

	private static final class Data {
		List<Module> modules = new ArrayList<>();
	}

	private final List<Module> modules = new ArrayList<>();

	public static ModuleRegistry get() {
		if (instance == null) {
			instance = new ModuleRegistry();
			instance.load();
		}
		return instance;
	}

	private static Path file() {
		return Config.rootDir().resolve("modules.json");
	}

	private void load() {
		Path path = file();
		if (!Files.exists(path)) {
			return;
		}
		try {
			Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
			if (data != null && data.modules != null) {
				modules.addAll(data.modules);
			}
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось прочитать список модулей", e);
		}
	}

	public void save() {
		try {
			Path path = file();
			Files.createDirectories(path.getParent());
			Data data = new Data();
			data.modules = modules;
			Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить список модулей", e);
		}
	}

	public List<Module> all() {
		refreshGlobal();
		return List.copyOf(modules);
	}

	/** Добавить модуль по ID. */
	public boolean add(String rawId) {
		String id = rawId == null ? "" : rawId.trim();
		if (id.isEmpty()) {
			return false;
		}
		for (Module module : modules) {
			if (module.id.equalsIgnoreCase(id)) {
				return false;
			}
		}
		Module module = new Module();
		module.id = id;
		module.name = id;
		module.source = "manual";
		modules.add(module);
		save();
		return true;
	}

	public boolean remove(String id) {
		boolean removed = modules.removeIf(module -> module.id.equalsIgnoreCase(id));
		if (removed) {
			save();
		}
		return removed;
	}

	/** После сборки: свежий модуль попадает в список с функциями и параметрами. */
	public static void registerCompiled(String name, String json) {
		ModuleRegistry registry = get();
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		Module module = registry.find(name);
		if (module == null) {
			module = new Module();
			module.id = name;
			module.name = name;
			registry.modules.add(module);
		}
		module.source = "upload";
		module.funcs = functionsOf(root);
		registry.save();
	}

	private static List<Func> functionsOf(JsonObject root) {
		List<Func> funcs = new ArrayList<>();
		JsonElement handlers = root.get("handlers");
		if (handlers == null || !handlers.isJsonArray()) {
			return funcs;
		}
		for (JsonElement element : handlers.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject handler = element.getAsJsonObject();
			String type = handler.has("type") ? handler.get("type").getAsString() : "";
			if (!type.equals("function") && !type.equals("process")) {
				continue;
			}
			Func func = new Func();
			func.kind = type;
			func.name = handler.has("name") ? handler.get("name").getAsString() : "";
			JsonElement values = handler.get("values");
			if (values != null && values.isJsonArray()) {
				for (JsonElement valueElement : values.getAsJsonArray()) {
					if (!valueElement.isJsonObject()) {
						continue;
					}
					JsonObject value = valueElement.getAsJsonObject();
					if (!value.has("name") || !"parameters".equals(value.get("name").getAsString())) {
						continue;
					}
					JsonObject array = value.getAsJsonObject("value");
					JsonElement params = array == null ? null : array.get("values");
					if (params != null && params.isJsonArray()) {
						for (JsonElement param : params.getAsJsonArray()) {
							if (param.isJsonObject() && param.getAsJsonObject().has("name")) {
								func.params.add(param.getAsJsonObject().get("name").getAsString());
							}
						}
					}
				}
			}
			if (!func.name.isBlank()) {
				funcs.add(func);
			}
		}
		return funcs;
	}

	private Module find(String id) {
		for (Module module : modules) {
			if (module.id.equalsIgnoreCase(id)) {
				return module;
			}
		}
		return null;
	}

	/** Функции из живого глобального скрипта показываем даже без сборки. */
	private void refreshGlobal() {
		for (ScriptUnit unit : ScriptStore.get().all()) {
			if (!unit.isGlobal() || unit.code == null || unit.code.isBlank()) {
				continue;
			}
			String moduleId = "global";
			Module module = find(moduleId);
			if (module == null) {
				module = new Module();
				module.id = moduleId;
				module.name = "Глобальные скрипты";
				module.source = "global";
				modules.add(0, module);
			}
			module.funcs.clear();
			for (ScriptParser.Section section : ScriptParser.parse(unit.code)) {
				if (!section.kind().equals("function") && !section.kind().equals("process")) {
					continue;
				}
				Func func = new Func();
				func.name = section.name();
				func.kind = section.kind();
				for (ScriptParser.Param param : section.params()) {
					func.params.add(param.name());
				}
				module.funcs.add(func);
			}
		}
	}

	/** Все функции и процессы, которые можно вызвать. */
	public List<Callable> callables() {
		List<Callable> list = new ArrayList<>();
		for (Module module : all()) {
			for (Func func : module.funcs) {
				list.add(new Callable(module.id, module.name, func.name, func.kind, List.copyOf(func.params)));
			}
		}
		return list;
	}

	/** Готовая строка вызова для текущего языка. */
	public static String callSnippet(Callable callable, String language) {
		String lang = language == null ? "justmc-json" : language.toLowerCase(Locale.ROOT);
		if (lang.equals("jmcc")) {
			return "function::call(\"" + callable.name() + "\");";
		}
		if (lang.equals("xero")) {
			return "run " + callable.name();
		}
		boolean process = callable.kind().equals("process");
		JsonObject action = new JsonObject();
		action.addProperty("action", process ? "start_process" : "call_function");
		JsonArray values = new JsonArray();
		JsonObject name = new JsonObject();
		name.addProperty("type", "text");
		name.addProperty("text", callable.name());
		name.addProperty("parsing", "plain");
		values.add(argument(process ? "process_name" : "function_name", name));
		if (process) {
			JsonObject target = new JsonObject();
			target.addProperty("type", "enum");
			target.addProperty("enum", "CURRENT_SELECTION");
			values.add(argument("target_mode", target));
			JsonObject locals = new JsonObject();
			locals.addProperty("type", "enum");
			locals.addProperty("enum", "SHARE");
			values.add(argument("local_variables_mode", locals));
		}
		JsonObject args = new JsonObject();
		args.addProperty("type", "map");
		args.add("values", new JsonObject());
		values.add(argument("args", args));
		action.add("values", values);
		return GSON.toJson(action);
	}

	private static JsonObject argument(String name, JsonElement value) {
		JsonObject argument = new JsonObject();
		argument.addProperty("name", name);
		argument.add("value", value);
		return argument;
	}
}
