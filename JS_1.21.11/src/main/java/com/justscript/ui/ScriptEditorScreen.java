package com.justscript.ui;

import com.justscript.Config;
import com.justscript.publish.Publisher;
import com.justscript.script.CodeLanguage;
import com.justscript.script.ScriptHistory;
import com.justscript.script.ScriptParser;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.script.ScriptValidator;
import com.justscript.util.Chat;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

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
		super(Text.literal(unit.displayTitle()));
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
		this.editor = new CodeEditorWidget(this.editorX, this.editorY, this.editorW, this.editorH, this.textRenderer);
		this.editor.setText(unit.code == null || unit.code.isBlank() ? defaultTemplate() : unit.code);
		// при повторной инициализации (возврат из переименования/списка, resize)
		// заново разбираем секции и проверяем текст
		this.parsedFrom = "";
		this.editor.setHighlight(Config.get().syntaxHighlight);
		this.editor.setLanguage(unit.language);
		this.editor.setAssistMode(aiActive);
		this.editor.setPlaceholder(placeholder());
		this.addDrawableChild(this.editor);
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

		this.addDrawableChild(ButtonWidget.builder(
						Text.literal(aiActive ? "✨ Выйти из ИИ" : "✨ ИИ-помощник"), button -> toggleAi())
				.dimensions(this.listX, buttonsY, this.listW, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("+ Событие"), button -> openPicker("event"))
				.dimensions(this.listX, buttonsY + 22, half, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Удалить"), button -> deleteSelected())
				.dimensions(right, buttonsY + 22, half, 20).build());

		if (global) {
			this.addDrawableChild(ButtonWidget.builder(Text.literal("+ Функция"), button -> insertTemplate("function"))
					.dimensions(this.listX, buttonsY + 44, half, 20).build());
			this.addDrawableChild(ButtonWidget.builder(Text.literal("+ Процесс"), button -> insertTemplate("process"))
					.dimensions(right, buttonsY + 44, half, 20).build());
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Порядок…"), button -> openOrder())
					.dimensions(this.listX, buttonsY + 66, half, 20).build());
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Значения {}"), button -> openValues())
					.dimensions(right, buttonsY + 66, half, 20).build());
		} else if ("entity".equals(unit.kind)) {
			this.addDrawableChild(ButtonWidget.builder(entityMatchLabel(), button -> {
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
			}).dimensions(this.listX, buttonsY + 44, this.listW, 20).build());
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Значения {}"), button -> openValues())
					.dimensions(this.listX, buttonsY + 66, this.listW, 20).build());
		} else {
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Значения {}"), button -> openValues())
					.dimensions(this.listX, buttonsY + 44, this.listW, 20).build());
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
					if (this.client != null) {
						this.client.setScreen(null);
					}
				}
		};
		for (int i = 0; i < count; i++) {
			Runnable action = actions[i];
			this.addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]), button -> action.run())
					.dimensions(this.editorX + (footerW + 4) * i, footerY, footerW, 20).build());
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
		if (this.client == null) {
			return;
		}
		save();
		this.client.setScreen(new EventPickerScreen(this, unit.kind, unit.language, template -> {
			this.editor.insertAtCaret(template);
			this.pendingCaret = this.editor.getCaret();
			this.parsedFrom = "";
			save();
		}));
	}

	/** Справочник значений: текст, число, список, локация, предмет и т.д. */
	private void openValues() {
		if (this.client == null) {
			return;
		}
		save();
		this.client.setScreen(new ValuePickerScreen(this, unit.language, template -> {
			this.editor.insertAtCaret(template);
			this.pendingCaret = this.editor.getCaret();
			this.parsedFrom = "";
			save();
		}));
	}

	private Text entityMatchLabel() {
		return Text.literal("Проверка: " + (unit.matchByName() ? "ник" : "UUID"));
	}

	/**
	 * Дозаполняет имя сущности, если скрипт был создан старой версией мода
	 * (там хранился только UUID). Нужно для режима «проверка по нику».
	 */
	private void ensureEntityName() {
		if (!"entity".equals(unit.kind) || unit.entityName != null && !unit.entityName.isBlank()) {
			return;
		}
		if (this.client == null || !(this.client.world instanceof net.minecraft.client.world.ClientWorld world)) {
			return;
		}
		for (net.minecraft.entity.Entity entity : world.getEntities()) {
			if (entity.getUuidAsString().equals(unit.entityUuid)) {
				unit.entityName = entity.getName().getString();
				save();
				return;
			}
		}
	}

	/** Меню порядка обычных скриптов (глобальный всегда первый). */
	private void openOrder() {
		if (this.client == null) {
			return;
		}
		save();
		this.client.setScreen(new ScriptOrderScreen(this));
	}

	/** История правок: откат к любой сохранённой копии. */
	private void openHistory() {
		if (this.client == null) {
			return;
		}
		save();
		this.client.setScreen(new HistoryScreen(this, unit));
	}

	/** Клик по названию слева сверху — переименование скрипта. */
	private void openRename() {
		if (this.client == null) {
			return;
		}
		save();
		this.client.setScreen(new RenameScreen(this, unit));
	}

	/** Вставляет заготовку функции/процесса с уникальным именем. */
	private void insertTemplate(String kind) {
		String base = kind.equals("function") ? "my_function" : "my_process";
		String name = base;
		int counter = 2;
		String code = this.editor.getText();
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
		if (this.client == null) {
			return;
		}
		save();
		if (this.aiActive) {
			// выходим из режима ИИ: возвращаем ручку строк и подсказки
			this.aiActive = false;
			this.client.setScreen(this);
			return;
		}
		this.aiActive = true;
		this.client.setScreen(new AiScreen(this, "", false));
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
		String current = this.editor.getText();
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
		this.editor.setText(snippet + "\n");
		this.parsedFrom = "";
		save();
	}

	/** Откат из истории. */
	public void restoreFromHistory(String code) {
		if (this.editor == null) {
			return;
		}
		this.editor.setText(code);
		this.parsedFrom = "";
		save();
	}

	private void save() {
		unit.code = this.editor.getText();
		unit.language = Config.get().language;
		ScriptStore.get().touch(unit);
		ScriptStore.get().save();
		ScriptHistory.snapshot(unit, unit.code, "сохранение");
		status = "Сохранено";
		statusColor = 0xFF4ADE80;
	}

	private void saveAndClose() {
		save();
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	@Override
	public void close() {
		this.aiActive = false;
		saveAndClose();
	}

	@Override
	public boolean shouldPause() {
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
		String[] lines = this.editor.getText().split("\n", -1);
		StringBuilder rebuilt = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i >= startLine && i < endLine) {
				continue;
			}
			rebuilt.append(lines[i]).append('\n');
		}
		this.editor.setText(rebuilt.toString());
		this.editor.goToLine(Math.max(0, startLine));
		this.parsedFrom = "";
		selectedSection = -1;
		save();
		status = "Секция удалена";
		statusColor = 0xFF4ADE80;
	}

	// ---------------------------------------------------------------- отрисовка

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		refreshSections();

		// шапка: название слева сверху можно переименовать кликом
		boolean titleHovered = mouseX >= 10 && mouseX <= 300 && mouseY >= 8 && mouseY <= 22;
		String title = unit.displayTitle();
		context.drawText(this.textRenderer, title, 12, 14, titleHovered ? 0xFFFFFFFF : 0xFFE8E8E8, false);
		if (titleHovered) {
			context.fill(12, 24, 12 + Math.min(280, this.textRenderer.getWidth(title)), 25, 0x66FFFFFF);
		}
		String language = CodeLanguage.byId(unit.language).displayName();
		context.drawText(this.textRenderer, language, this.width - 12 - this.textRenderer.getWidth(language), 14,
				0xFF7A8085, false);

		// левая панель
		context.fill(this.listX - 4, this.listY - 4, this.listX + this.listW + 4, this.listY + this.listH, 0xFF14171D);
		context.drawText(this.textRenderer, "Секции", this.listX, this.listY, 0xFFB8C0CC, false);

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
			context.drawText(this.textRenderer, label, this.listX, rowY, color, false);
			rowY += 13;
			if (rowY > this.listY + this.listH - this.sectionRows * 22 - 6) {
				break;
			}
		}

		super.render(context, mouseX, mouseY, delta);

		// строка состояния
		String cursor = "стр. " + (this.editor.getCaretLine() + 1) + ", стлб. " + (this.editor.getCaretColumn() + 1);
		context.drawText(this.textRenderer, cursor, this.editorX, this.editorY + this.editorH + 6, 0xFF7A8085, false);
		String shownStatus = validationIssues.isEmpty() ? status : validationStatus();
		if (!shownStatus.isEmpty()) {
			int color = validationIssues.isEmpty() ? statusColor : 0xFFFF6B6B;
			String fitted = this.textRenderer.trimToWidth(shownStatus, Math.max(60, this.editorW - 130));
			context.drawText(this.textRenderer, fitted,
					this.editorX + this.editorW - this.textRenderer.getWidth(fitted),
					this.editorY + this.editorH + 6, color, false);
		}
	}

	private void refreshSections() {
		if (this.editor == null) {
			return;
		}
		String current = this.editor.getText();
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
public boolean charTyped(CharInput input) {
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
	public boolean mouseClicked(Click click, boolean doubled) {
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
	public boolean keyPressed(KeyInput input) {
		boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		// клавиатура всегда работает с кодом, даже если фокус остался на кнопке
		if (input.isEscape() && this.editor != null && this.editor.handleEscape()) {
			return true;
		}
		if (!input.isEscape() && this.editor != null && !this.editor.isFocused()) {
			this.setFocused(this.editor);
		}
		if (ctrl && input.key() == GLFW.GLFW_KEY_S) {
			save();
			Chat.info("Скрипт сохранён");
			return true;
		}
		if (ctrl && input.key() == GLFW.GLFW_KEY_ENTER) {
			save();
			Publisher.compileOnly(true);
			return true;
		}
		return super.keyPressed(input);
	}
}
