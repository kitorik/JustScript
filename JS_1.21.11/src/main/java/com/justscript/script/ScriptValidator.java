package com.justscript.script;

import com.justscript.events.EventCatalog;
import com.justscript.events.EventDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Быстрая проверка скрипта прямо в редакторе — без полной сборки проекта.
 *
 * <p>Для каждой секции: разбирает тело выбранным языком (JSON/Xero/JMCC),
 * проверяет известность события и его область (блок / сущность / игрок / мир),
 * а также что функции и процессы объявлены в глобальном скрипте.
 * Возвращает строки (0-based) для меток в жёлобе редактора.
 */
public final class ScriptValidator {
	/** Ошибка с номером строки (0-based). */
	public record Issue(int line, String message) {
	}

	/** «line 3 column 5» из Gson и «строка 3» из Xero. */
	private static final Pattern LINE_IN_TEXT = Pattern.compile(
			"(?i)\\bline\\s+(\\d+)|строк[ае]\\s+(\\d+)");

	private ScriptValidator() {
	}

	/**
	 * @param code     весь текст скрипта
	 * @param language id языка ({@code justmc-json}, {@code xero}, {@code jmcc})
	 * @param unitKind block / entity / player / global
	 */
	public static List<Issue> validate(String code, String language, String unitKind) {
		List<Issue> issues = new ArrayList<>();
		if (code == null || code.isBlank()) {
			return issues;
		}
		List<ScriptParser.Section> sections = ScriptParser.parse(code);
		String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

		int searchFrom = 0;
		for (ScriptParser.Section section : sections) {
			if (section.kind().equals("preamble")) {
				continue;
			}
			int headerLine = findHeader(lines, section.header(), searchFrom);
			searchFrom = headerLine + 1;

			String context = "@" + section.kind() + " " + section.name();
			List<String> errors = new ArrayList<>();
			try {
				CodeLanguage.byId(language).toOperations(section.body(), errors, context);
			} catch (RuntimeException e) {
				errors.add(context + ": не удалось разобрать скрипт — " + e.getMessage());
			}

			if (!errors.isEmpty()) {
				for (String message : errors) {
					int line = extractLine(message);
					int issueLine;
					if (line >= 0) {
						issueLine = Math.min(headerLine + line, lines.length - 1);
					} else {
						int located = locateMention(message, lines, headerLine + 1);
						issueLine = located >= 0 ? located : headerLine;
					}
					issues.add(new Issue(issueLine, clean(message)));
				}
				continue;
			}

			if (section.kind().equals("event")) {
				String justmc = EventCatalog.resolveJustMc(section.name());
				if (justmc == null) {
					issues.add(new Issue(headerLine, "Неизвестное событие «" + section.name()
							+ "». Выбери событие из списка слева."));
					continue;
				}
				EventDefinition definition = EventCatalog.find(section.name());
				if (definition != null && !Compiler.scopeAllowed(unitKind, definition.scope())) {
					issues.add(new Issue(headerLine, "Событие «" + definition.id() + "» — "
							+ EventCatalog.scopeLabel(definition.scope()) + ", а скрипт "
							+ Compiler.kindLabel(unitKind) + "."));
				}
			} else if (section.kind().equals("function") || section.kind().equals("process")) {
				if (!"global".equals(unitKind)) {
					issues.add(new Issue(headerLine,
							"Функции и процессы работают только в глобальном скрипте."));
				} else if (section.name().isBlank()) {
					issues.add(new Issue(headerLine, "У функции/процесса должно быть имя."));
				}
			}
		}

		issues.sort(Comparator.comparingInt(Issue::line));
		return issues;
	}

	/** Строка заголовка секции (0-based); если потерялась — начало поиска. */
	private static int findHeader(String[] lines, String header, int from) {
		for (int i = Math.max(0, from); i < lines.length; i++) {
			if (lines[i].trim().equals(header)) {
				return i;
			}
		}
		return Math.max(0, Math.min(from, lines.length - 1));
	}

	/** Номер строки из текста ошибки (1-based) или -1. */
	private static int extractLine(String message) {
		if (message == null) {
			return -1;
		}
		Matcher matcher = LINE_IN_TEXT.matcher(message);
		if (!matcher.find()) {
			return -1;
		}
		String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
		try {
			return Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Ищет в коде упомянутый в ошибке фрагмент («send», `nonsense::do`). */
	private static int locateMention(String message, String[] lines, int from) {
		if (message == null || from >= lines.length) {
			return -1;
		}
		Matcher matcher = Pattern.compile("[«\"`]([^»\"`]{2,48})[»\"`]").matcher(message);
		while (matcher.find()) {
			String token = matcher.group(1).trim();
			if (token.isEmpty()) {
				continue;
			}
			for (int i = Math.max(0, from); i < lines.length; i++) {
				if (lines[i].contains(token)) {
					return i;
				}
			}
		}
		return -1;
	}

	/** Убирает длинный служебный префикс Gson, оставляя суть. */
	private static String clean(String message) {
		String value = message == null ? "Ошибка" : message.trim();
		int colon = value.indexOf(": ");
		if (value.startsWith("com.google.gson") && colon > 0) {
			value = value.substring(colon + 2);
		}
		return value.length() > 160 ? value.substring(0, 157) + "…" : value;
	}
}