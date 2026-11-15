package com.justscript.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.justscript.Config;
import com.justscript.publish.Publisher;
import com.justscript.script.CodeLanguage;
import com.justscript.script.ScriptHistory;
import com.justscript.script.ScriptParser;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.script.ScriptValidator;
import com.justscript.util.Chat;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Код-меню блока / сущности / всего мира.
 * Слева — секции (события, функции, процессы), справа — редактор кода.
 *
 * <p>Клик по названию слева сверху переименовывает скрипт. История правок
 * открывается кнопкой «История»; в режиме ИИ ручка строк и подсказки отключены.
 */
public final class ScriptEditorScreen extends Screen {
	private final ScriptUnit unit;
	private final Screen parent;

	private CodeEditorWidget editor;
	private final List<ScriptParser.Section> sections = new ArrayList<>();
	private final List<Integer> sectionLines = new ArrayList<>();
	private int selectedSection = -1;
	private String parsedFrom = "";

	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private int editorX;
	private int editorY;
	private int editorW;
	private int editorH;
	private String status = "";
	private int statusColor = 0xFF9AA4B2;
	private int sectionRows = 3;
	/** Результат проверки текущего текста — показывается в статусе поверх обычного. */
	private List<ScriptValidator.Issue> validationIssues = List.of();
	/** Куда вернуть курсор после возврата из списка событий/значений. */
	private int pendingCaret = -1;
	/** Открыт ИИ-помощник: ручка и подсказки в редакторе отключены. */
	private boolean aiActive;

	public ScriptEditorScreen(ScriptUnit unit, Screen parent) {
		super(Component.literal(unit.displayTitle()));
		this.unit = unit;
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelX = 8;
		int panelY = 8;
		int panelW = this.width - 16;
		int panelH = this.height - 16;
		int headerH = 26;
		int footerH = 24;

		this.listW = Math.max(150, Math.min(220, panelW / 3));
		this.listX = panelX + 4;
		this.listY = panelY + headerH;
		this.listH = panelH - headerH - footerH - 20;

		this.editorX = panelX + this.listW + 12;
		this.editorY = panelY + headerH;
		this.editorW = panelW - this.listW - 16;
		this.editorH = panelH - headerH - footerH - 20;

		int previousCaret = this.editor == null ? -1 : this.editor.getCaret();
		this.editor = new CodeEditorWidget(this.editorX, this.editorY, this.editorW, this.editorH, this.font);
		this.editor.setValue(unit.code == null || unit.code.isBlank() ? defaultTemplate() : unit.code);
		// при повторной инициализации (возврат из переименования/списка, resize)
		// заново разбираем секции и проверяем текст
		this.parsedFrom = "";
		this.editor.setHighlight(Config.get().syntaxHighlight);
		this.editor.setLanguage(unit.language);
		this.editor.setAssistMode(aiActive);
		this.editor.setHint(placeholder());
		this.addRenderableWidget(this.editor);
		this.setFocused(this.editor);
		if (this.pendingCaret >= 0) {
			this.editor.setCaret(this.pendingCaret);
			this.pendingCaret = -1;
		} else if (previousCaret >= 0) {
			this.editor.setCaret(previousCaret);
		}
		ensureEntityName();

		boolean global = unit.isGlobal();
		boolean entity = "entity".equals(unit.kind);
		int rows = global || entity ? 4 : 3;
		this.sectionRows = rows;
		int buttonsY = this.listY + this.listH - rows * 22;
		int half = (this.listW - 4) / 2;
		int right = this.listX + half + 4;

		this.addRenderableWidget(Button.builder(
						Component.literal(aiActive ? "✨ Выйти из ИИ" : "✨ ИИ-помощник"), button -> toggleAi())
				.bounds(this.listX, buttonsY, this.listW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("+ Событие"), button -> openPicker("event"))
				.bounds(this.listX, buttonsY + 22, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Удалить"), button -> deleteSelected())
				.bounds(right, buttonsY + 22, half, 20).build());

		if (global) {
			this.addRenderableWidget(Button.builder(Component.literal("+ Функция"), button -> insertTemplate("function"))
					.bounds(this.listX, buttonsY + 44, half, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("+ Процесс"), button -> insertTemplate("process"))
					.bounds(right, buttonsY + 44, half, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Порядок…"), button -> openOrder())
					.bounds(this.listX, buttonsY + 66, half, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Значения {}"), button -> openValues())
					.bounds(right, buttonsY + 66, half, 20).build());
		} else if ("entity".equals(unit.kind)) {
			this.addRenderableWidget(Button.builder(entityMatchLabel(), button -> {
				unit.entityMatch = unit.matchByName() ? "uuid" : "name";
				if (unit.matchByName() && (unit.entityName == null || unit.entityName.isBlank())) {
					unit.entityMatch = "uuid";
					save();
					status = "Не знаю ник этой сущности — останется проверка по UUID";
					statusColor = 0xFFFFC94D;
				} else {
					save();
					status = unit.matchByName()
							? "Проверка по нику: сработает на всех «" + unit.entityName + "»"
							: "Проверка по UUID: только эта сущность";
					statusColor = 0xFF4ADE80;
				}
				button.setMessage(entityMatchLabel());
			}).bounds(this.listX, buttonsY + 44, this.listW, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Значения {}"), button -> openValues())
					.bounds(this.listX, buttonsY + 66, this.listW, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(Component.literal("Значения {}"), button -> openValues())
					.bounds(this.listX, buttonsY + 44, this.listW, 20).build());
		}

		int footerY = panelY + panelH - 22;
		int count = 4;
		int footerW = Math.min(120, (this.editorW - (count - 1) * 4) / count);
		String[] labels = {"Сохранить", "История…", "Собрать", "Загрузить"};
		Runnable[] actions = {
				this::saveAndClose,
				this::openHistory,
				() -> {
					save();
					Publisher.compileOnly(true);
				},
				() -> {
					save();
					Publisher.publish();
					if (this.minecraft != null) {
						this.minecraft.setScreenAndShow(null);
					}
				}
		};
		for (int i = 0; i < count; i++) {
			Runnable action = actions[i];
			this.addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> action.run())
					.bounds(this.editorX + (footerW + 4) * i, footerY, footerW, 20).build());
		}
	}

	private String placeholder() {
		if ("jmcc".equalsIgnoreCase(unit.language)) {
			return "event<player_join> {\n  player::message(\"Привет!\");\n}";
		}
		if ("xero".equalsIgnoreCase(unit.language)) {
			return "when player_join {\n  send \"Привет!\"\n}";
		}
		return "@event player_join\n[\n\n]\n";
	}

	private String defaultTemplate() {
		return switch (unit.language == null ? "justmc-json" : unit.language.toLowerCase()) {
			case "jmcc" -> "event<player_join> {\n  player::message(\"Привет!\");\n}\n";
			case "xero" -> "when player_join {\n  send \"Привет!\"\n}\n";
			default -> {
				String alias = switch (unit.kind) {
					case "entity" -> "entity.click.right";
					case "global" -> "player.join";
					default -> "block.click.right";
				};
				yield "@event " + alias + "\n[\n\n]\n";
			}
		};
	}

	private void openPicker(String kind) {
		if (this.minecraft == null) {
			return;
		}
		save();
		this.minecraft.setScreenAndShow(new EventPickerScreen(this, unit.kind, unit.language, template -> {
			this.editor.insertAtCaret(template);
			this.pendingCaret = this.editor.getCaret();
			this.parsedFrom = "";
			save();
		}));
	}

	/** Справочник значений: текст, число, список, локация, предмет и т.д. */
	private void openValues() {
		if (this.minecraft == null) {
			return;
		}
		save();
		this.minecraft.setScreenAndShow(new ValuePickerScreen(this, unit.language, template -> {
			this.editor.insertAtCaret(template);
			this.pendingCaret = this.editor.getCaret();
			this.parsedFrom = "";
			save();
		}));
	}

	private Component entityMatchLabel() {
		return Component.literal("Проверка: " + (unit.matchByName() ? "ник" : "UUID"));
	}

	/**
	 * Дозаполняет имя сущности, если скрипт был создан старой версией мода
	 * (там хранился только UUID). Нужно для режима «проверка по нику».
	 */
	private void ensureEntityName() {
		if (!"entity".equals(unit.kind) || unit.entityName != null && !unit.entityName.isBlank()) {
			return;
		}
		if (this.minecraft == null || !(this.minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel world)) {
			return;
		}
		for (net.minecraft.world.entity.Entity entity : world.entitiesForRendering()) {
			if (entity.getStringUUID().equals(unit.entityUuid)) {
				unit.entityName = entity.getName().getString();
				save();
				return;
			}
		}
	}

	/** Меню порядка обычных скриптов (глобальный всегда первый). */
	private void openOrder() {
		if (this.minecraft == null) {
			return;
		}
		save();
		this.minecraft.setScreenAndShow(new ScriptOrderScreen(this));
	}

	/** История правок: откат к любой сохранённой копии. */
	private void openHistory() {
		if (this.minecraft == null) {
			return;
		}
		save();
		this.minecraft.setScreenAndShow(new HistoryScreen(this, unit));
	}

	/** Клик по названию слева сверху — переименование скрипта. */
	private void openRename() {
		if (this.minecraft == null) {
			return;
		}
		save();
		this.minecraft.setScreenAndShow(new RenameScreen(this, unit));
	}

	/** Вставляет заготовку функции/процесса с уникальным именем. */
	private void insertTemplate(String kind) {
		String base = kind.equals("function") ? "my_function" : "my_process";
		String name = base;
		int counter = 2;
		String code = this.editor.getValue();
		while (code.contains("@" + kind + " " + name) || code.contains("function " + name)
				|| code.contains("func " + name)) {
			name = base + "_" + counter++;
		}
		this.editor.insertAtCaret(ScriptParser.template(kind, name));
		this.pendingCaret = this.editor.getCaret();
		this.parsedFrom = "";
		save();
	}

	/** ИИ-помощник поверх редактора: запрос + вставка готового кода. */
	private void toggleAi() {
		if (this.minecraft == null) {
			return;
		}
		save();
		if (this.aiActive) {
			// выходим из режима ИИ: возвращаем ручку строк и подсказки
			this.aiActive = false;
			this.minecraft.setScreenAndShow(this);
			return;
		}
		this.aiActive = true;
		this.minecraft.setScreenAndShow(new AiScreen(this, "", false));
	}

	public ScriptUnit unit() {
		return unit;
	}

	/** Вставляет код от ИИ в позицию курсора (и сохраняет скрипт). */
	public void insertAiCode(String generated) {
		if (this.editor == null) {
			return;
		}
		String snippet = generated == null ? "" : generated.replace("\r\n", "\n").trim();
		if (snippet.isEmpty()) {
			return;
		}
		String current = this.editor.getValue();
		String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
		this.editor.insertAtCaret(prefix + snippet + "\n");
		this.parsedFrom = "";
		save();
	}

	/** Заменяет весь код скрипта кодом от ИИ. */
	public void replaceAiCode(String generated) {
		if (this.editor == null) {
			return;
		}
		String snippet = generated == null ? "" : generated.replace("\r\n", "\n").trim();
		this.editor.setValue(snippet + "\n");
		this.parsedFrom = "";
		save();
	}

	/** Откат из истории. */
	public void restoreFromHistory(String code) {
		if (this.editor == null) {
			return;
		}
		this.editor.setValue(code);
		this.parsedFrom = "";
		save();
	}

	private void save() {
		unit.code = this.editor.getValue();
		unit.language = Config.get().language;
		ScriptStore.get().touch(unit);
		ScriptStore.get().save();
		ScriptHistory.snapshot(unit, unit.code, "сохранение");
		status = "Сохранено";
		statusColor = 0xFF4ADE80;
	}

	private void saveAndClose() {
		save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}

	@Override
	public void onClose() {
		this.aiActive = false;
		saveAndClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void deleteSelected() {
		refreshSections();
		if (selectedSection < 0 || selectedSection >= sections.size()) {
			status = "Сначала выбери секцию слева";
			statusColor = 0xFFFFC94D;
			return;
		}
		if (sections.get(selectedSection).kind().equals("preamble")) {
			status = "Комментарии удалить нельзя";
			statusColor = 0xFFFFC94D;
			return;
		}
		int startLine = sectionLines.get(selectedSection);
		int endLine = Integer.MAX_VALUE;
		for (int i = selectedSection + 1; i < sectionLines.size(); i++) {
			if (!sections.get(i).kind().equals("preamble")) {
				endLine = sectionLines.get(i);
				break;
			}
		}
		String[] lines = this.editor.getValue().split("\n", -1);
		StringBuilder rebuilt = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i >= startLine && i < endLine) {
				continue;
			}
			rebuilt.append(lines[i]).append('\n');
		}
		this.editor.setValue(rebuilt.toString());
		this.editor.goToLine(Math.max(0, startLine));
		this.parsedFrom = "";
		selectedSection = -1;
		save();
		status = "Секция удалена";
		statusColor = 0xFF4ADE80;
	}

	// ---------------------------------------------------------------- отрисовка

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		refreshSections();

		// шапка: название слева сверху можно переименовать кликом
		boolean titleHovered = mouseX >= 10 && mouseX <= 300 && mouseY >= 8 && mouseY <= 22;
		String title = unit.displayTitle();
		context.text(this.font, title, 12, 14, titleHovered ? 0xFFFFFFFF : 0xFFE8E8E8, false);
		if (titleHovered) {
			context.fill(12, 24, 12 + Math.min(280, this.font.width(title)), 25, 0x66FFFFFF);
		}
		String language = CodeLanguage.byId(unit.language).displayName();
		context.text(this.font, language, this.width - 12 - this.font.width(language), 14,
				0xFF7A8085, false);

		// левая панель
		context.fill(this.listX - 4, this.listY - 4, this.listX + this.listW + 4, this.listY + this.listH, 0xFF14171D);
		context.text(this.font, "Секции", this.listX, this.listY, 0xFFB8C0CC, false);

		int rowY = this.listY + 16;
		for (int i = 0; i < sections.size(); i++) {
			ScriptParser.Section section = sections.get(i);
			String label = switch (section.kind()) {
				case "event" -> "◆ " + section.name();
				case "function" -> "ƒ " + section.name();
				case "process" -> "↻ " + section.name();
				default -> "# комментарии";
			};
			int color = section.kind().equals("event") ? 0xFF8AB4F8
					: section.kind().equals("function") ? 0xFFCFA6F8
					: section.kind().equals("process") ? 0xFFF8C77A : 0xFF6A9955;
			if (i == selectedSection) {
				context.fill(this.listX - 4, rowY - 2, this.listX + this.listW + 4, rowY + 10, 0x553A86FF);
			}
			context.text(this.font, label, this.listX, rowY, color, false);
			rowY += 13;
			if (rowY > this.listY + this.listH - this.sectionRows * 22 - 6) {
				break;
			}
		}

		super.extractRenderState(context, mouseX, mouseY, delta);

		// строка состояния
		String cursor = "стр. " + (this.editor.getCaretLine() + 1) + ", стлб. " + (this.editor.getCaretColumn() + 1);
		context.text(this.font, cursor, this.editorX, this.editorY + this.editorH + 6, 0xFF7A8085, false);
		String shownStatus = validationIssues.isEmpty() ? status : validationStatus();
		if (!shownStatus.isEmpty()) {
			int color = validationIssues.isEmpty() ? statusColor : 0xFFFF6B6B;
			String fitted = this.font.plainSubstrByWidth(shownStatus, Math.max(60, this.editorW - 130));
			context.text(this.font, fitted,
					this.editorX + this.editorW - this.font.width(fitted),
					this.editorY + this.editorH + 6, color, false);
		}
	}

	private void refreshSections() {
		if (this.editor == null) {
			return;
		}
		String current = this.editor.getValue();
		if (current.equals(parsedFrom)) {
			return;
		}
		parsedFrom = current;
		sections.clear();
		sectionLines.clear();
		sections.addAll(ScriptParser.parse(current));

		String[] lines = current.split("\n", -1);
		int searchFrom = 0;
		for (ScriptParser.Section section : sections) {
			if (section.kind().equals("preamble")) {
				sectionLines.add(0);
				continue;
			}
			int found = -1;
			for (int i = searchFrom; i < lines.length; i++) {
				if (lines[i].trim().equals(section.header())) {
					found = i;
					break;
				}
			}
			if (found < 0) {
				found = Math.min(searchFrom, Math.max(0, lines.length - 1));
			}
			sectionLines.add(found);
			searchFrom = found + 1;
		}

		// проверка прямо в редакторе: метки на строках и первая ошибка в статусе
		try {
			validationIssues = ScriptValidator.validate(current, unit.language, unit.kind);
		} catch (RuntimeException e) {
			validationIssues = List.of(new ScriptValidator.Issue(0,
					"Не удалось проверить скрипт: " + e.getMessage()));
		}
		List<Integer> errorLines = new ArrayList<>();
		for (ScriptValidator.Issue issue : validationIssues) {
			errorLines.add(issue.line());
		}
		this.editor.setErrorLines(errorLines);
	}

	/** Строка ошибки для статуса: счётчик + первая ошибка. */
	private String validationStatus() {
		ScriptValidator.Issue first = validationIssues.get(0);
		String message = first.message();
		if (Config.get().shortErrors && message.length() > 70) {
			message = message.substring(0, 67) + "…";
		}
		return "⚠ " + issueCount(validationIssues.size()) + " · стр. " + (first.line() + 1) + ": " + message;
	}

	/** «1 ошибка», «3 ошибки», «7 ошибок». */
	private static String issueCount(int count) {
		int tail = count % 100;
		int last = count % 10;
		if (tail >= 11 && tail <= 14) {
			return count + " ошибок";
		}
		if (last == 1) {
			return count + " ошибка";
		}
		if (last >= 2 && last <= 4) {
			return count + " ошибки";
		}
		return count + " ошибок";
	}

	@Override
public boolean charTyped(CharacterEvent input) {
		// символы всегда идут в код, даже если фокус остался на кнопке
		if (this.editor == null) {
			return false;
		}
		if (!this.editor.isFocused()) {
			this.setFocused(this.editor);
		}
		return this.editor.charTyped(input);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		if (click.button() == 0 && mouseX >= 10 && mouseX <= 300 && mouseY >= 8 && mouseY <= 24) {
			openRename();
			return true;
		}
		int rowsTop = this.listY + 16;
		int sectionsBottom = this.listY + this.listH - this.sectionRows * 22 - 6;
		if (click.button() == 0 && mouseX >= this.listX - 4 && mouseX <= this.listX + this.listW + 4
				&& mouseY >= rowsTop && mouseY < sectionsBottom) {
			int row = (int) ((mouseY - rowsTop) / 13);
			if (row >= 0 && row < sections.size()) {
				selectedSection = row;
				this.setFocused(this.editor);
				if (!sections.get(row).kind().equals("preamble") && row < sectionLines.size()) {
					this.editor.goToLine(sectionLines.get(row));
				}
				return true;
			}
		}
		// клик по строке ошибки внизу — переход к первой проблеме
		if (click.button() == 0 && !validationIssues.isEmpty()
				&& mouseX >= this.editorX && mouseX <= this.editorX + this.editorW
				&& mouseY >= this.editorY + this.editorH + 2 && mouseY <= this.editorY + this.editorH + 18) {
			this.editor.goToLine(validationIssues.get(0).line());
			this.setFocused(this.editor);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		boolean ctrl = (input.modifiers() & InputConstants.MOD_CONTROL) != 0;
		// клавиатура всегда работает с кодом, даже если фокус остался на кнопке
		if (input.isEscape() && this.editor != null && this.editor.handleEscape()) {
			return true;
		}
		if (!input.isEscape() && this.editor != null && !this.editor.isFocused()) {
			this.setFocused(this.editor);
		}
		if (ctrl && input.key() == InputConstants.KEY_S) {
			save();
			Chat.info("Скрипт сохранён");
			return true;
		}
		if (ctrl && input.key() == InputConstants.KEY_RETURN) {
			save();
			Publisher.compileOnly(true);
			return true;
		}
		return super.keyPressed(input);
	}
}
