package com.justscript.ai;

import com.justscript.editor3d.SceneStore;
import com.justscript.editor3d.Visualizer;
import com.justscript.events.EventCatalog;
import com.justscript.events.EventDefinition;
import com.justscript.script.ScriptUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Промпты для ИИ. Модель должна вернуть только код JustScript, поэтому формат,
 * правила и список событий продиктованы жёстко — иначе она начнёт придумывать
 * «действия» и оборачивать ответ в markdown.
 */
public final class AiPrompts {
	/** Ограничение на текущий код в промпте, чтобы не улететь в лимит токенов. */
	private static final int MAX_CONTEXT_CHARS = 12_000;

	private AiPrompts() {
	}

	public static String system(ScriptUnit unit) {
		String kind = unit == null ? "global" : unit.kind;
		StringBuilder text = new StringBuilder();

		text.append("Ты — помощник по скриптам JustMC (Creative+) внутри мода JustScript.\n")
				.append("Твоя задача — писать рабочий код JustScript. Отвечай ТОЛЬКО кодом: ")
				.append("без пояснений, без markdown, без ```.\n\n");

		text.append("ФОРМАТ КОДА\n")
				.append("Код состоит из секций. Каждая секция начинается с заголовка на отдельной строке:\n")
				.append("@event <алиас события>\n")
				.append("<тело>\n\n")
				.append("@function <имя>\n")
				.append("<тело>\n\n")
				.append("@process <имя>(<тики>)\n")
				.append("<тело>\n\n")
				.append("Тело — сырой JSON действия или массив действий JustMC, например:\n")
				.append("[\n")
				.append("  {\n")
				.append("    \"action\": \"player_send_message\",\n")
				.append("    \"values\": [\n")
				.append("      { \"name\": \"messages\", \"value\": { \"type\": \"array\", \"values\": [\n")
				.append("        { \"type\": \"text\", \"text\": \"Привет!\", \"parsing\": \"plain\" }\n")
				.append("      ] } },\n")
				.append("      { \"name\": \"merging\", \"value\": { \"type\": \"enum\", \"enum\": \"SPACES\" } }\n")
				.append("    ]\n")
				.append("  }\n")
				.append("]\n\n");

		text.append("ТИПЫ ЗНАЧЕНИЙ\n")
				.append("- текст: {\"type\":\"text\",\"text\":\"...\",\"parsing\":\"plain\"}\n")
				.append("- число: {\"type\":\"number\",\"number\":1}\n")
				.append("- список: {\"type\":\"array\",\"values\":[...]}\n")
				.append("- перечисление: {\"type\":\"enum\",\"enum\":\"SPACES\"}\n")
				.append("- игровое значение: {\"type\":\"game_value\",\"game_value\":\"event_player\",\"selection\":\"null\"}\n")
				.append("- локация: {\"type\":\"location\",\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0}\n")
				.append("- предмет: {\"type\":\"item\",\"item\":\"{Count:1b,id:\\\"minecraft:diamond\\\"}\"}\n")
				.append("- переменная: {\"type\":\"variable\",\"variable\":\"имя\",\"scope\":\"GLOBAL\"}\n\n");

		text.append("ПРАВИЛА\n")
				.append("1. В секции @event используй только события из списка ниже (алиасы) ")
				.append("или их ID JustMC.\n")
				.append("2. Имена функций и процессов — латиница в snake_case, без пробелов.\n")
				.append("3. Не добавляй проверки координат блока, UUID сущности или владельца мира: ")
				.append("мод добавляет их сам при сборке.\n")
				.append("4. Для периодических действий используй @process с числом тиков, а не вечный цикл вручную.\n")
				.append("5. Держись лимитов JustMC: не больше ~33 условий и ~67 действий в одной линии.\n")
				.append("6. Тексты для игрока пиши по-русски, если задача на русском.\n")
				.append("7. Не выдумывай ID действий: используй стандартные действия JustMC ")
				.append("(player_send_message, control_if, control_if_else, control_wait, repeat_forever, ")
				.append("select_reset, set_variable, if_variable_equals, game_action, player_teleport, ")
				.append("entity_teleport, world_play_sound, player_play_sound и т.п.).\n\n");

		text.append("СКРИПТ ДЛЯ ДРУГОГО ОБЪЕКТА\n")
				.append("Если по задаче скрипт нужен не текущему объекту, первой строкой ответа укажи цель:\n")
				.append("@target block <x> <y> <z> [id блока]\n")
				.append("@target entity <UUID или ник>\n")
				.append("@target visualizer <id или название визуализатора>\n")
				.append("@target hitbox <id или название визуализатора>\n")
				.append("@target global\n")
				.append("После строки @target сразу пиши код. Мод сам спросит у игрока разрешение ")
				.append("на создание скрипта для этого объекта.\n")
				.append("Если скрипт нужен текущему объекту, строку @target НЕ добавляй.\n\n");

		text.append("КОНТЕКСТ\n");
		if ("block".equals(kind)) {
			text.append("Это скрипт блока ").append(unit.blockId).append(" на координатах ")
					.append(unit.x).append(' ').append(unit.y).append(' ').append(unit.z)
					.append(". Событие должно происходить с этим блоком.\n\n");
		} else if ("entity".equals(kind)) {
			text.append("Это скрипт сущности «").append(unit.label)
					.append("». Событие должно происходить с этой сущностью.\n\n");
		} else {
			text.append("Это глобальный скрипт мира (не привязан к объекту).\n\n");
		}

		List<EventDefinition> events = EventCatalog.forUnitKind(kind);
		String aliases = events.stream()
				.map(EventDefinition::id)
				.collect(Collectors.joining(", "));
		text.append("СОБЫТИЯ (").append(events.size()).append(")\n").append(aliases).append("\n");

		appendVisualizers(text);
		return text.toString();
	}

	/** Список визуализаторов сцены — чтобы ИИ мог указать цель по id или названию. */
	private static void appendVisualizers(StringBuilder text) {
		List<Visualizer> visualizers = new ArrayList<>(SceneStore.get().all());
		if (visualizers.isEmpty()) {
			return;
		}
		text.append("\nВИЗУАЛИЗАТОРЫ СЦЕНЫ (для @target visualizer / hitbox)\n");
		for (Visualizer visualizer : visualizers) {
			text.append("- ").append(visualizer.id).append(" — ").append(visualizer.displayName())
					.append(" @ ").append(String.format(Locale.ROOT, "%.1f %.1f %.1f",
							visualizer.x, visualizer.y, visualizer.z))
					.append('\n');
		}
	}

	/** Задача игрока + текущий код как контекст. */
	public static String user(String request, String currentCode) {
		StringBuilder text = new StringBuilder();
		if (request != null && !request.isBlank()) {
			text.append("Задача: ").append(request.trim()).append("\n\n");
		}
		if (currentCode != null && !currentCode.isBlank()) {
			String code = currentCode.replace("\r\n", "\n").trim();
			if (code.length() > MAX_CONTEXT_CHARS) {
				code = code.substring(0, MAX_CONTEXT_CHARS) + "\n… (код обрезан)";
			}
			text.append("Текущий код скрипта:\n").append(code).append("\n\n");
			text.append("Верни полный итоговый код скрипта, а не фрагмент.");
		} else {
			text.append("Напиши скрипт с нуля.");
		}
		return text.toString();
	}
}