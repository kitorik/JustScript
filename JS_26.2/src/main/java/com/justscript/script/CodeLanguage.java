package com.justscript.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.List;

/**
 * Язык кода скрипта: сырой JSON модулей JustMC, Xero или JMCC.
 * Интерфейс превращает тело секции в массив действий JustMC.
 */
public interface CodeLanguage {
	String id();

	String displayName();

	/**
	 * Превращает тело секции в массив действий JustMC.
	 *
	 * @param errors сюда складываются ошибки (пустая строка = ошибок нет)
	 */
	JsonElement toOperations(String body, List<String> errors, String context);

	static List<CodeLanguage> all() {
		return List.of(new JustMcJsonLanguage(), new XeroLanguage(), new JmccLanguage());
	}

	static CodeLanguage byId(String id) {
		for (CodeLanguage language : all()) {
			if (language.id().equalsIgnoreCase(id)) {
				return language;
			}
		}
		return all().get(0);
	}

	/** Сырой JSON JustMC: массив действий либо один объект действия. */
	final class JustMcJsonLanguage implements CodeLanguage {
		@Override
		public String id() {
			return "justmc-json";
		}

		@Override
		public String displayName() {
			return "JustMC JSON";
		}

		@Override
		public JsonElement toOperations(String body, List<String> errors, String context) {
			String text = body == null ? "" : body.trim();
			if (text.isEmpty()) {
				return new JsonArray();
			}
			try {
				JsonElement element = JsonParser.parseString(text);
				if (element.isJsonArray()) {
					return element;
				}
				if (element.isJsonObject()) {
					JsonObject object = element.getAsJsonObject();
					if (object.has("action")) {
						JsonArray single = new JsonArray();
						single.add(object);
						return single;
					}
					errors.add(context + ": объект должен содержать поле \"action\" (или быть массивом действий)");
					return new JsonArray();
				}
				errors.add(context + ": ожидается JSON-массив действий или объект действия");
			} catch (JsonSyntaxException e) {
				errors.add(context + ": ошибка JSON — " + e.getMessage());
			}
			return new JsonArray();
		}
	}
}
