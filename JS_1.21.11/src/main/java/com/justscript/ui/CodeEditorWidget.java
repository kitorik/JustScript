package com.justscript.ui;

import com.justscript.script.HintCatalog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Многострочное поле для кода: курсор, выделение мышью, копирование/вставка,
 * прокрутка, подсветка, номера строк.
 *
 * <ul>
 *   <li>красный кружок слева перетаскивает только строки (или выделенные строки);</li>
 *   <li>Shift подсвечивает пару скобок {@code {} [] ()};</li>
 *   <li>серая «тень» показывает дополнение, но само не применяется — Tab вставит;</li>
 *   <li>подсказки зависят от языка и места: заголовок — события, тело — действия,
 *       внутри строки — ничего;</li>
 *   <li>в режиме ИИ кружок, подсказки и тень отключаются.</li>
 * </ul>
 */
public final class CodeEditorWidget extends ClickableWidget {
	private static final int LINE_HEIGHT = 10;
	private static final int PAD = 4;
	/** Слева: красный кружок (4 px) + номер строки, затем сам код. */
	private static final int GUTTER = 26;
	private static final int CIRCLE = 4;

	private static final int HINT_PRIMARY_H = 24;
	private static final int HINT_ROW_H = 9;
	private static final int HINT_ROWS = 4;
	private static final int HINT_SLIDER_W = 5;
	/** Нижний горизонтальный ползунок. */
	private static final int HSCROLL_H = 5;
	private static final int HSCROLL_MIN_THUMB = 14;

	private static final int TEXT_COLOR = 0xFFD4D4D4;
	private static final int GHOST_COLOR = 0xFF555C68;
	private static final int BRACKET_COLOR = 0x804ADE80;

	private final TextRenderer textRenderer;
	private final StringBuilder text = new StringBuilder();
	private int caret;
	private int anchor;
	private int scrollLine;
	/** Горизонтальная прокрутка в пикселях: длинные строки не влезают в поле. */
	private int scrollX;
	private boolean draggingHScroll;
	private int cachedContentWidth = -1;
	private int cachedContentLength = -1;
	private boolean highlight = true;
	private String placeholder = "";
	private String language = "justmc-json";
	private boolean assistMode;

	// подсказки
	private List<HintCatalog.Hint> hints = List.of();
	private String hintPrefix = "";
	private int hintIndex;
	private int hintX;
	private int hintY;
	private int hintW;
	private int hintH;
	private boolean draggingSlider;

	// перетаскивание строк за красный кружок
	private boolean draggingLine;
	private int dragFirst = -1;
	private int dragLast = -1;
	private int dragTo = -1;

	// выделение мышью
	private boolean draggingText;

	// отмена/повтор
	private static final int UNDO_LIMIT = 200;
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();
	private final Deque<Snapshot> redoStack = new ArrayDeque<>();
	private String lastEditKind = "";
	private long lastEditAt;

	// поиск и замена
	private boolean findMode;
	private boolean replaceMode;
	private boolean replaceFocus;
	private String findQuery = "";
	private String replaceQuery = "";
	private int barX;
	private int barY;
	private int barW;
	private int barH;
	private final int[] findRect = new int[4];
	private final int[] replaceRect = new int[4];
	private final int[] prevRect = new int[4];
	private final int[] nextRect = new int[4];
	private final int[] closeRect = new int[4];
	private final int[] replaceButtonRect = new int[4];
	private final int[] replaceAllRect = new int[4];

	// переход к строке
	private boolean gotoMode;
	private String gotoBuffer = "";

	// метки ошибок (0-based строки)
	private final Set<Integer> errorLines = new LinkedHashSet<>();

	private record Snapshot(String text, int caret, int anchor) {
	}

	public CodeEditorWidget(int x, int y, int width, int height, TextRenderer textRenderer) {
		super(x, y, width, height, Text.empty());
		this.textRenderer = textRenderer;
	}

	public String getText() {
		return text.toString();
	}

	public void setText(String value) {
		text.setLength(0);
		if (value != null) {
			text.append(value.replace("\r\n", "\n").replace('\r', '\n'));
		}
		caret = 0;
		anchor = 0;
		scrollLine = 0;
		scrollX = 0;
		cachedContentWidth = -1;
		undoStack.clear();
		redoStack.clear();
		lastEditKind = "";
		errorLines.clear();
		hideHints();
	}

	/** Ставит метки ошибок на строки (0-based), чтобы подсветить их в жёлобе. */
	public void setErrorLines(Collection<Integer> lines) {
		errorLines.clear();
		if (lines != null) {
			for (Integer line : lines) {
				if (line != null && line >= 0) {
					errorLines.add(line);
				}
			}
		}
	}

	public boolean hasErrors() {
		return !errorLines.isEmpty();
	}

	/**
	 * Esc сначала закрывает бар поиска/перехода или подсказки, и только потом
	 * экран: Screen сам закрывает экран на Esc до передачи клавиши виджетам.
	 *
	 * @return true, если Esc был занят внутри редактора
	 */
	public boolean handleEscape() {
		if (findMode || gotoMode) {
			closeFind();
			return true;
		}
		if (!hints.isEmpty()) {
			hideHints();
			return true;
		}
		return false;
	}

	public void setHighlight(boolean highlight) {
		this.highlight = highlight;
	}

	public void setPlaceholder(String placeholder) {
		this.placeholder = placeholder == null ? "" : placeholder;
	}

	/** Язык скрипта: от него зависят подсказки. */
	public void setLanguage(String language) {
		this.language = language == null ? "justmc-json" : language;
		refreshHints();
	}

	/** Режим ИИ: кружок-ручка и подсказки отключены. */
	public void setAssistMode(boolean assistMode) {
		this.assistMode = assistMode;
		if (assistMode) {
			hideHints();
			draggingLine = false;
			draggingText = false;
		}
	}

	public boolean assistMode() {
		return assistMode;
	}

	/** При получении фокуса сразу считаем подсказки — иначе они появляются только после ввода. */
	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		if (focused && !findMode && !gotoMode) {
			refreshHints();
		} else {
			hideHints();
		}
	}

	public int getCaretLine() {
		return lineOfIndex(caret);
	}

	public int getCaretColumn() {
		return caret - lineStartIndex(getCaretLine());
	}

	public int getCaret() {
		return caret;
	}

	public void setCaret(int offset) {
		caret = clamp(offset, 0, text.length());
		anchor = caret;
		hideHints();
		ensureVisible();
	}

	public int selectionStart() {
		return Math.min(caret, anchor);
	}

	public int selectionEnd() {
		return Math.max(caret, anchor);
	}

	public void insertAtCaret(String snippet) {
		recordEdit("insert");
		replaceSelection(snippet == null ? "" : snippet);
		hideHints();
		ensureVisible();
	}

	public void goToLine(int line) {
		caret = Math.min(lineStartIndex(clamp(line, 0, lineCount() - 1)), text.length());
		anchor = caret;
		hideHints();
		ensureVisible();
	}

	/** Удаляет выделение (или текущую строку при Ctrl+Shift+K). */
	public void deleteSelectionOrLine() {
		recordEdit("delete-line");
		if (caret != anchor) {
			replaceSelection("");
		} else {
			int line = getCaretLine();
			int start = lineStartIndex(line);
			int end = lineEndIndex(start);
			if (end < text.length()) {
				end++;
			}
			text.delete(start, end);
			caret = start;
			anchor = caret;
		}
		hideHints();
		ensureVisible();
	}

	/** Выделенный текст (пустая строка, если выделения нет). */
	public String selectedText() {
		if (caret == anchor) {
			return "";
		}
		return text.substring(Math.min(caret, anchor), Math.max(caret, anchor));
	}

	// ---------------------------------------------------------------- отмена/повтор

	private Snapshot snapshot() {
		return new Snapshot(text.toString(), caret, anchor);
	}

	/**
	 * Запоминает состояние перед правкой. Непрерывный набор (пауза < 700 мс)
	 * склеивается в один шаг, чтобы Ctrl+Z не отменял по одной букве.
	 */
	private void recordEdit(String kind) {
		long now = System.currentTimeMillis();
		boolean mergeable = "type".equals(kind) || "delete".equals(kind);
		boolean merge = mergeable && kind.equals(lastEditKind) && now - lastEditAt < 700;
		if (!merge) {
			undoStack.push(snapshot());
			while (undoStack.size() > UNDO_LIMIT) {
				undoStack.removeLast();
			}
		}
		redoStack.clear();
		lastEditKind = kind;
		lastEditAt = now;
	}

	public void undo() {
		if (undoStack.isEmpty()) {
			return;
		}
		redoStack.push(snapshot());
		restore(undoStack.pop());
		lastEditKind = "";
	}

	public void redo() {
		if (redoStack.isEmpty()) {
			return;
		}
		undoStack.push(snapshot());
		restore(redoStack.pop());
		lastEditKind = "";
	}

	private void restore(Snapshot state) {
		text.setLength(0);
		text.append(state.text());
		cachedContentWidth = -1;
		caret = clamp(state.caret(), 0, text.length());
		anchor = clamp(state.anchor(), 0, text.length());
		hideHints();
		ensureVisible();
	}

	// ---------------------------------------------------------------- отрисовка

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		context.fill(x, y, x + w, y + h, 0xFF15171C);
		context.fill(x, y, x + w, y + 1, 0xFF3A4050);
		context.fill(x, y + h - 1, x + w, y + h, 0xFF3A4050);
		context.fill(x, y, x + 1, y + h, 0xFF3A4050);
		context.fill(x + w - 1, y, x + w, y + h, 0xFF3A4050);

		// всё содержимое режем по границам редактора, иначе длинные строки
		// вылезают на соседние панели
		context.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		if (text.length() == 0) {
			String[] lines = placeholder.split("\n", -1);
			for (int i = 0; i < Math.min(lines.length, visibleRows()); i++) {
				context.drawText(textRenderer, lines[i], codeX(), y + PAD + i * LINE_HEIGHT, 0xFF5A6070, false);
				if (errorLines.contains(i)) {
					context.drawText(textRenderer, "!", x + CIRCLE + 5, y + PAD + i * LINE_HEIGHT,
							0xFFFF6B6B, false);
				}
			}
			context.disableScissor();
			renderFindBar(context);
			return;
		}

		int rows = visibleRows();
		int lineCount = lineCount();
		scrollLine = clamp(scrollLine, 0, Math.max(0, lineCount - rows));

		int selStart = Math.min(caret, anchor);
		int selEnd = Math.max(caret, anchor);

		// подсветка скобок по Shift
		int[] brackets = shiftHeld() ? matchingBrackets() : null;

		// все совпадения поиска — подсвечиваем фоном, текущее выделено отдельно
		List<int[]> matches = findMode ? searchMatches() : List.of();

		int lineStart = lineStartIndex(scrollLine);
		for (int row = 0; row < rows; row++) {
			int line = scrollLine + row;
			if (line >= lineCount) {
				break;
			}
			int start = lineStart;
			int end = lineEndIndex(start);
			int drawY = y + PAD + row * LINE_HEIGHT;

			// перетаскивание строк
			if (draggingLine && dragTo == line) {
				context.fill(x + 1, drawY - 1, x + w - 1, drawY, 0xFF3A86FF);
			}
			if (draggingLine && line >= dragFirst && line <= dragLast) {
				context.fill(x + 1, drawY - 2, x + w - 1, drawY + LINE_HEIGHT - 1, 0x3344AAFF);
			}

			// номер строки и красный кружок-ручка; строка с ошибкой помечена
			boolean hasError = errorLines.contains(line);
			String number = String.valueOf(line);
			context.drawText(textRenderer, number, x + CIRCLE + 6, drawY,
					hasError ? 0xFFFF6B6B : 0xFF4C5464, false);
			if (hasError) {
				context.fill(x + 1, drawY - 1, x + 2, drawY + LINE_HEIGHT - 1, 0xFFFF4D4D);
				context.fill(codeX(), drawY + LINE_HEIGHT - 2, x + w - 2, drawY + LINE_HEIGHT - 1, 0x44FF4D4D);
			}
			if (!assistMode) {
				boolean handleHovered = mouseX >= x + 1 && mouseX <= x + CIRCLE + 5
						&& mouseY >= drawY && mouseY < drawY + LINE_HEIGHT;
				boolean handleActive = draggingLine && line >= dragFirst && line <= dragLast;
				context.fill(x + 2, drawY + (LINE_HEIGHT - CIRCLE) / 2,
						x + 2 + CIRCLE, drawY + (LINE_HEIGHT - CIRCLE) / 2 + CIRCLE,
						handleHovered || handleActive ? 0xFFFF6B6B : 0xFFC93C3C);
			}

			// фоновые метки всех совпадений поиска
			if (!matches.isEmpty()) {
				for (int[] match : matches) {
					if (match[1] > start && match[0] < end) {
						int from = Math.max(match[0], start);
						int to = Math.min(match[1], end);
						int fromX = codeX() + textRenderer.getWidth(text.substring(start, from));
						int toX = codeX() + textRenderer.getWidth(text.substring(start, to));
						if (toX > fromX) {
							context.fill(fromX, drawY - 1, toX, drawY + LINE_HEIGHT - 1, 0x332F6BFF);
						}
					}
				}
			}

			if (selStart != selEnd && selStart <= end && selEnd >= start) {
				int from = Math.max(selStart, start);
				int to = Math.min(selEnd, end);
				int fromX = textX() + textRenderer.getWidth(text.substring(start, from));
				int toX = textX() + textRenderer.getWidth(text.substring(start, to));
				if (toX > fromX) {
					context.fill(fromX, drawY - 1, toX, drawY + LINE_HEIGHT - 1, 0x553A86FF);
				}
			}

			String lineText = text.substring(start, end);
			drawLine(context, lineText, codeX(), drawY);

			// подсвеченные скобки
			if (brackets != null) {
				drawBracket(context, brackets[0], drawY, line, start, end);
				drawBracket(context, brackets[1], drawY, line, start, end);
			}
			lineStart = end + 1;
		}

		if (isFocused()) {
			int cursorLine = getCaretLine();
			if (cursorLine >= scrollLine && cursorLine < scrollLine + rows) {
				int start = lineStartIndex(cursorLine);
				int cursorX = codeX() + textRenderer.getWidth(text.substring(start, Math.min(caret, text.length())));
				int cursorY = y + PAD + (cursorLine - scrollLine) * LINE_HEIGHT;
				context.fill(cursorX, cursorY - 1, cursorX + 1, cursorY + LINE_HEIGHT - 1, 0xFFF0F0F0);
				drawGhost(context, cursorX, cursorY);
			}
		}
		context.disableScissor();

		renderHScroll(context, mouseX, mouseY);
		renderHints(context);
		renderFindBar(context);
	}

	private int textX() {
		return getX() + GUTTER;
	}

	/** Левый край текста с учётом горизонтальной прокрутки. */
	private int codeX() {
		return textX() - scrollX;
	}

	/** Ширина самой длинной строки в пикселях (с кэшем по длине текста). */
	private int contentWidth() {
		if (cachedContentWidth >= 0 && cachedContentLength == text.length()) {
			return cachedContentWidth;
		}
		int max = 0;
		int start = 0;
		while (start <= text.length()) {
			int end = lineEndIndex(start);
			max = Math.max(max, textRenderer.getWidth(text.substring(start, end)));
			if (end >= text.length()) {
				break;
			}
			start = end + 1;
		}
		cachedContentWidth = max;
		cachedContentLength = text.length();
		return max;
	}

	/** Сколько пикселей можно прокрутить вправо. */
	private int maxScrollX() {
		int visible = getWidth() - GUTTER - PAD - 1;
		return Math.max(0, contentWidth() - Math.max(1, visible));
	}

	private boolean hasHScroll() {
		return maxScrollX() > 0;
	}

	private int hScrollY() {
		return getY() + getHeight() - HSCROLL_H - 1;
	}

	private int hScrollTrackX() {
		return getX() + GUTTER;
	}

	private int hScrollTrackW() {
		return Math.max(1, getWidth() - GUTTER - 2);
	}

	/** {x, ширина} бегунка горизонтального ползунка. */
	private int[] hScrollThumb() {
		int track = hScrollTrackW();
		int content = Math.max(1, contentWidth());
		int visible = Math.max(1, getWidth() - GUTTER - PAD - 1);
		int thumb = Math.max(HSCROLL_MIN_THUMB, (int) ((long) track * visible / content));
		thumb = Math.min(track, thumb);
		int max = maxScrollX();
		int travel = Math.max(0, track - thumb);
		int offset = max <= 0 ? 0 : (int) ((long) travel * scrollX / max);
		return new int[]{hScrollTrackX() + offset, thumb};
	}

	private void renderHScroll(DrawContext context, int mouseX, int mouseY) {
		if (!hasHScroll()) {
			return;
		}
		int trackX = hScrollTrackX();
		int trackW = hScrollTrackW();
		int y = hScrollY();
		context.fill(trackX, y, trackX + trackW, y + HSCROLL_H, 0xFF20242C);
		int[] thumb = hScrollThumb();
		boolean hovered = mouseX >= thumb[0] && mouseX <= thumb[0] + thumb[1]
				&& mouseY >= y && mouseY <= y + HSCROLL_H;
		int color = draggingHScroll ? 0xFF8FB4FF : hovered ? 0xFF6C82AD : 0xFF4E5A72;
		context.fill(thumb[0], y, thumb[0] + thumb[1], y + HSCROLL_H, color);
	}

	/** Перетаскивание бегунка: mouseX → доля прокрутки. */
	private void updateHScroll(double mouseX) {
		int track = hScrollTrackW();
		int[] thumb = hScrollThumb();
		int travel = Math.max(1, track - thumb[1]);
		double ratio = (mouseX - hScrollTrackX() - thumb[1] / 2.0) / travel;
		ratio = Math.max(0.0, Math.min(1.0, ratio));
		scrollX = (int) Math.round(ratio * maxScrollX());
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getNarrationMessage());
	}

	private void drawBracket(DrawContext context, int index, int drawY, int line, int start, int end) {
		if (index < start || index >= end || index >= text.length()) {
			return;
		}
		int bx = codeX() + textRenderer.getWidth(text.substring(start, index));
		int charWidth = Math.max(3, textRenderer.getWidth(String.valueOf(text.charAt(index))));
		context.fill(bx - 1, drawY - 1, bx + charWidth + 1, drawY + LINE_HEIGHT - 1, BRACKET_COLOR);
	}

	/** Серая «тень» дополнения: показывается, но не применяется само. */
	private void drawGhost(DrawContext context, int cursorX, int cursorY) {
		if (assistMode || hints.isEmpty() || caret != anchor) {
			return;
		}
		// если после курсора уже есть продолжение слова — дополнять нечего
		if (caret < text.length() && isWordChar(text.charAt(caret))) {
			return;
		}
		String token = hints.get(hintIndex).token();
		String prefix = hintPrefix;
		if (prefix.isEmpty() || !token.startsWith(prefix) || token.length() == prefix.length()) {
			return;
		}
		int right = getX() + getWidth() - 4;
		String suffix = token.substring(prefix.length());
		while (!suffix.isEmpty() && cursorX + textRenderer.getWidth(suffix) > right) {
			suffix = suffix.substring(0, suffix.length() - 1);
		}
		if (suffix.isEmpty()) {
			return;
		}
		context.drawText(textRenderer, suffix, cursorX, cursorY, GHOST_COLOR, false);
	}

	/** Языковая подсветка одной строки. */
	private void drawLine(DrawContext context, String line, int x, int y) {
		if (!highlight || line.isEmpty()) {
			context.drawText(textRenderer, line, x, y, TEXT_COLOR, false);
			return;
		}
		String trimmed = line.trim();
		boolean jmcc = "jmcc".equalsIgnoreCase(language);
		if (trimmed.startsWith("@") || trimmed.startsWith("when ") || trimmed.startsWith("func ")
				|| trimmed.startsWith("process ") || trimmed.startsWith("function ")
				|| trimmed.startsWith("event<")) {
			context.drawText(textRenderer, line, x, y, 0xFFDCDCAA, false);
			return;
		}
		if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
			context.drawText(textRenderer, line, x, y, 0xFF6A9955, false);
			return;
		}
		int i = 0;
		int cursorX = x;
		while (i < line.length()) {
			int color = TEXT_COLOR;
			int start = i;
			char c = line.charAt(i);
			if (c == '"') {
				color = 0xFFCE9178;
				i++;
				while (i < line.length()) {
					char inside = line.charAt(i);
					i++;
					if (inside == '\\' && i < line.length()) {
						i++;
					} else if (inside == '"') {
						break;
					}
				}
			} else if (Character.isDigit(c) || (c == '-' && i + 1 < line.length() && Character.isDigit(line.charAt(i + 1)))) {
				color = 0xFFB5CEA8;
				i++;
				while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) {
					i++;
				}
			} else if ("{}[]():,".indexOf(c) >= 0) {
				color = 0xFF9CDCFE;
				i++;
			} else if (jmcc && (c == '<' || c == '>')) {
				color = 0xFF9CDCFE;
				i++;
			} else {
				i++;
				while (i < line.length()) {
					char next = line.charAt(i);
					if (next == '"' || Character.isDigit(next) || "{}[]():,<>".indexOf(next) >= 0) {
						break;
					}
					i++;
				}
			}
			String run = line.substring(start, i);
			context.drawText(textRenderer, run, cursorX, y, color, false);
			cursorX += textRenderer.getWidth(run);
		}
	}

	// ---------------------------------------------------------------- скобки

	/** Ищет пару скобок у курсора: {индекс открывающей, индекс закрывающей} или null. */
	private int[] matchingBrackets() {
		if (text.length() == 0) {
			return null;
		}
		int index = -1;
		if (caret > 0 && isBracket(text.charAt(caret - 1))) {
			index = caret - 1;
		} else if (caret < text.length() && isBracket(text.charAt(caret))) {
			index = caret;
		}
		if (index < 0) {
			return null;
		}
		char bracket = text.charAt(index);
		int depth = 0;
		if (isOpening(bracket)) {
			boolean inString = false;
			for (int i = index; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
					inString = !inString;
				}
				if (inString) {
					continue;
				}
				if (c == bracket) {
					depth++;
				} else if (closesFor(bracket, c)) {
					depth--;
					if (depth == 0) {
						return new int[]{index, i};
					}
				}
			}
			return null;
		}
		for (int i = index; i >= 0; i--) {
			char c = text.charAt(i);
			if (c == bracket) {
				depth++;
			} else if (opensFor(bracket, c)) {
				depth--;
				if (depth == 0) {
					return new int[]{i, index};
				}
			}
		}
		return null;
	}

	private static boolean isBracket(char c) {
		return "{}[]()".indexOf(c) >= 0;
	}

	private static boolean isOpening(char c) {
		return "{([".indexOf(c) >= 0;
	}

	private static boolean closesFor(char open, char close) {
		return (open == '{' && close == '}') || (open == '[' && close == ']') || (open == '(' && close == ')');
	}

	private static boolean opensFor(char close, char open) {
		return closesFor(open, close);
	}

	private static boolean shiftHeld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getWindow() == null) {
			return false;
		}
		return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
				|| InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	// ---------------------------------------------------------------- подсказки

	private void renderHints(DrawContext context) {
		if (assistMode || hints.isEmpty() || !isFocused()) {
			return;
		}
		hintW = Math.min(getWidth() - GUTTER - 10, 260);
		hintW = Math.max(150, hintW);
		hintH = HINT_PRIMARY_H + Math.min(HINT_ROWS, Math.max(0, hints.size() - 1)) * HINT_ROW_H + 3;
		int row = getCaretLine() - scrollLine;
		hintX = Math.min(codeX(), getX() + getWidth() - hintW - 4);
		int below = getY() + PAD + (row + 1) * LINE_HEIGHT + 2;
		hintY = below + hintH > getY() + getHeight() - 2
				? Math.max(getY() + 2, getY() + PAD + row * LINE_HEIGHT - hintH - 1)
				: below;

		context.fill(hintX, hintY, hintX + hintW, hintY + hintH, 0xF012161E);
		context.fill(hintX, hintY, hintX + hintW, hintY + 1, 0xFF3A86FF);
		context.fill(hintX, hintY + hintH - 1, hintX + hintW, hintY + hintH, 0xFF3A86FF);
		context.fill(hintX, hintY, hintX + 1, hintY + hintH, 0xFF3A86FF);
		context.fill(hintX + hintW - 1, hintY, hintX + hintW, hintY + hintH, 0xFF3A86FF);

		HintCatalog.Hint primary = hints.get(hintIndex);
		int textLimit = hintX + hintW - HINT_SLIDER_W - 4;
		context.drawText(textRenderer, "▸ " + primary.kind().label(), hintX + 4, hintY + 2, 0xFF7FB2FF, false);
		String token = trimTo(primary.token(), textLimit - (hintX + 4 + textRenderer.getWidth("▸ " + primary.kind().label()) + 6));
		context.drawText(textRenderer, token,
				hintX + 4 + textRenderer.getWidth("▸ " + primary.kind().label()) + 6, hintY + 2, 0xFFFFFFFF, false);
		String description = trimTo(primary.description(), textLimit - hintX - 8);
		context.drawText(textRenderer, description, hintX + 4, hintY + 13, 0xFF8A93A6, false);

		// варианты ниже: по 9 px, «сворачиваем» по кругу от выбранного
		int shown = Math.min(HINT_ROWS, Math.max(0, hints.size() - 1));
		for (int i = 0; i < shown; i++) {
			int index = (hintIndex + 1 + i) % hints.size();
			HintCatalog.Hint hint = hints.get(index);
			int rowY = hintY + HINT_PRIMARY_H + i * HINT_ROW_H;
			if (index == hintIndex) {
				continue;
			}
			String label = hint.token();
			int labelLimit = textLimit - hintX - 8;
			context.drawText(textRenderer, trimTo(label, labelLimit), hintX + 5, rowY, 0xFFB9C2D0, false);
		}

		if (hints.size() > 1) {
			// ползунок справа: тянем — листаем вероятные варианты
			int trackTop = hintY + 3;
			int trackBottom = hintY + hintH - 3;
			int trackX = hintX + hintW - HINT_SLIDER_W;
			context.fill(trackX, trackTop, trackX + 2, trackBottom, 0x553A86FF);
			int knobH = 8;
			int span = Math.max(1, trackBottom - trackTop - knobH);
			int knobY = trackTop + (int) Math.round(span * (double) hintIndex / Math.max(1, hints.size() - 1));
			context.fill(trackX - 1, knobY, trackX + 3, knobY + knobH, 0xFF7FB2FF);
		}
	}

	private String trimTo(String value, int maxWidth) {
		if (value == null) {
			return "";
		}
		if (textRenderer.getWidth(value) <= maxWidth) {
			return value;
		}
		String result = value;
		while (!result.isEmpty() && textRenderer.getWidth(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}

	private boolean overHints(double mouseX, double mouseY) {
		return !hints.isEmpty() && mouseX >= hintX && mouseX <= hintX + hintW
				&& mouseY >= hintY && mouseY <= hintY + hintH;
	}

	private void hideHints() {
		hints = List.of();
		hintPrefix = "";
		hintIndex = 0;
		draggingSlider = false;
	}

	/** Где стоит курсор: в заголовке, в теле или внутри строки. */
	private HintCatalog.Context contextAtCaret() {
		int line = getCaretLine();
		int start = lineStartIndex(line);
		String beforeCaret = text.substring(start, Math.min(caret, text.length()));
		int quotes = 0;
		for (int i = 0; i < beforeCaret.length(); i++) {
			if (beforeCaret.charAt(i) == '"' && (i == 0 || beforeCaret.charAt(i - 1) != '\\')) {
				quotes++;
			}
		}
		if (quotes % 2 == 1) {
			return HintCatalog.Context.STRING;
		}
		String trimmed = beforeCaret.trim().toLowerCase();
		if (trimmed.startsWith("@") || trimmed.startsWith("when") || trimmed.startsWith("func")
				|| trimmed.startsWith("process") || trimmed.startsWith("function")
				|| trimmed.startsWith("event<")) {
			return HintCatalog.Context.HEADER;
		}
		// до первого заголовка секции — тоже заголовок
		boolean anyHeader = false;
		for (String lineText : text.substring(0, start).split("\n")) {
			String value = lineText.trim().toLowerCase();
			if (value.startsWith("@") || value.startsWith("when ") || value.startsWith("func ")
					|| value.startsWith("process ") || value.startsWith("function ") || value.startsWith("event<")) {
				anyHeader = true;
				break;
			}
		}
		if (!anyHeader && trimmed.isEmpty()) {
			return HintCatalog.Context.HEADER;
		}
		return HintCatalog.Context.BODY;
	}

	/** Пересчитывает подсказки по слову перед курсором. */
	private void refreshHints() {
		hints = List.of();
		hintPrefix = "";
		hintIndex = 0;
		if (assistMode) {
			return;
		}
		HintCatalog.Context context = contextAtCaret();
		if (context == HintCatalog.Context.STRING || context == HintCatalog.Context.NONE) {
			return;
		}
		int start = caret;
		while (start > 0 && isWordChar(text.charAt(start - 1))) {
			start--;
		}
		String word = text.substring(start, caret);
		if (word.length() < 1) {
			return;
		}
		List<HintCatalog.Hint> found = HintCatalog.suggest(word, text.toString(), language, context);
		if (found.isEmpty()) {
			return;
		}
		hintPrefix = word;
		hints = found;
		hintIndex = 0;
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == ':';
	}

	private void acceptHint() {
		if (hints.isEmpty() || hintPrefix.isEmpty()) {
			return;
		}
		String token = hints.get(hintIndex).token();
		int start = Math.max(0, caret - hintPrefix.length());
		text.replace(start, caret, token);
		caret = start + token.length();
		anchor = caret;
		hideHints();
		ensureVisible();
	}

	private void moveHint(int delta) {
		if (hints.isEmpty()) {
			return;
		}
		hintIndex = Math.floorMod(hintIndex + delta, hints.size());
	}

	// ---------------------------------------------------------------- поиск, замена, строка

	/** Открывает строку поиска (Ctrl+F) или поиск с заменой (Ctrl+H). */
	private void openFind(boolean withReplace) {
		findMode = true;
		gotoMode = false;
		gotoBuffer = "";
		replaceMode = withReplace;
		replaceFocus = false;
		hideHints();
		if (findQuery.isEmpty()) {
			String selected = selectedText();
			if (!selected.isEmpty() && !selected.contains("\n")) {
				findQuery = selected;
			}
		}
	}

	private void closeFind() {
		findMode = false;
		replaceMode = false;
		replaceFocus = false;
		gotoMode = false;
		super.setFocused(true);
	}

	/** Все совпадения текущего запроса: пары {начало, конец}. */
	private List<int[]> searchMatches() {
		List<int[]> matches = new ArrayList<>();
		if (findQuery.isEmpty()) {
			return matches;
		}
		String source = text.toString().toLowerCase(Locale.ROOT);
		String needle = findQuery.toLowerCase(Locale.ROOT);
		int from = 0;
		while (from <= source.length() - needle.length()) {
			int index = source.indexOf(needle, from);
			if (index < 0) {
				break;
			}
			matches.add(new int[]{index, index + needle.length()});
			from = index + Math.max(1, needle.length());
		}
		return matches;
	}

	private void findNext(boolean forward) {
		List<int[]> matches = searchMatches();
		if (matches.isEmpty()) {
			return;
		}
		int position = forward ? Math.max(caret, anchor) : Math.min(caret, anchor);
		int target = -1;
		if (forward) {
			for (int[] match : matches) {
				if (match[0] >= position) {
					target = match[0];
					break;
				}
			}
			if (target < 0) {
				target = matches.get(0)[0];
			}
		} else {
			for (int i = matches.size() - 1; i >= 0; i--) {
				if (matches.get(i)[1] <= position) {
					target = matches.get(i)[0];
					break;
				}
			}
			if (target < 0) {
				target = matches.get(matches.size() - 1)[0];
			}
		}
		anchor = target;
		caret = Math.min(text.length(), target + findQuery.length());
		ensureVisible();
	}

	/** Заменяет текущее выделение, если оно совпадает с запросом. */
	private void replaceCurrent() {
		if (findQuery.isEmpty()) {
			return;
		}
		int start = selectionStart();
		int end = selectionEnd();
		if (end - start == findQuery.length() && text.substring(start, end).equalsIgnoreCase(findQuery)) {
			recordEdit("replace");
			text.replace(start, end, replaceQuery);
			caret = start + replaceQuery.length();
			anchor = caret;
			findNext(true);
		} else {
			findNext(true);
		}
		ensureVisible();
	}

	/** Заменяет все совпадения; возвращает число замен. */
	private int replaceAll() {
		if (findQuery.isEmpty()) {
			return 0;
		}
		String source = text.toString();
		String lower = source.toLowerCase(Locale.ROOT);
		String needle = findQuery.toLowerCase(Locale.ROOT);
		StringBuilder result = new StringBuilder();
		int from = 0;
		int count = 0;
		while (from <= lower.length() - needle.length()) {
			int index = lower.indexOf(needle, from);
			if (index < 0) {
				break;
			}
			result.append(source, from, index).append(replaceQuery);
			from = index + needle.length();
			count++;
		}
		if (count == 0) {
			return 0;
		}
		result.append(source.substring(from));
		recordEdit("replace-all");
		text.setLength(0);
		text.append(result);
		caret = clamp(caret, 0, text.length());
		anchor = caret;
		ensureVisible();
		return count;
	}

	private void openGoto() {
		gotoMode = true;
		findMode = false;
		replaceMode = false;
		replaceFocus = false;
		gotoBuffer = "";
		hideHints();
	}

	private void applyGoto() {
		if (!gotoBuffer.isEmpty()) {
			try {
				int line = Integer.parseInt(gotoBuffer) - 1;
				goToLine(Math.max(0, Math.min(line, lineCount() - 1)));
			} catch (NumberFormatException ignored) {
				// остаёмся на месте
			}
		}
		gotoMode = false;
		gotoBuffer = "";
	}

	private void searchChanged() {
		hideHints();
		if (!findQuery.isEmpty()) {
			caret = 0;
			anchor = 0;
			findNext(true);
		}
	}

	// ---------------------------------------------------------------- отрисовка поиска

	private void renderFindBar(DrawContext context) {
		if (!findMode && !gotoMode) {
			return;
		}
		barX = getX() + 1;
		barY = getY() + 1;
		barW = getWidth() - 2;

		if (gotoMode) {
			barH = 22;
			findRect[2] = 0;
			replaceRect[2] = 0;
			prevRect[2] = 0;
			nextRect[2] = 0;
			closeRect[2] = 0;
			replaceButtonRect[2] = 0;
			replaceAllRect[2] = 0;
			context.fill(barX, barY, barX + barW, barY + barH, 0xF51B1F27);
			context.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFFFFC94D);
			String hint = "Перейти к строке: " + (gotoBuffer.isEmpty() ? "…" : gotoBuffer) + "_";
			context.drawText(textRenderer, hint, barX + 8, barY + 7, 0xFFFFE08A, false);
			String tail = "Enter — перейти · Esc — отмена";
			int tailX = barX + barW - 8 - textRenderer.getWidth(tail);
			if (tailX > barX + textRenderer.getWidth(hint) + 16) {
				context.drawText(textRenderer, tail, tailX, barY + 7, 0xFF9AA4B2, false);
			}
			return;
		}

		barH = replaceMode ? 46 : 25;
		int right = barX + barW - 3;

		context.fill(barX, barY, barX + barW, barY + barH, 0xF51B1F27);
		context.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF3A86FF);

		// поле «Найти»
		findRect[0] = barX + 4;
		findRect[1] = barY + 3;
		findRect[2] = Math.max(40, right - 72 - findRect[0]);
		findRect[3] = 18;
		drawField(context, findRect, findQuery, "Найти", !replaceFocus);
		int count = searchMatches().size();
		if (count > 0 && findRect[2] > 60) {
			String value = String.valueOf(count);
			context.drawText(textRenderer, value,
					findRect[0] + findRect[2] - 4 - textRenderer.getWidth(value),
					findRect[1] + 5, 0xFF7FB2FF, false);
		}

		prevRect[0] = right - 68;
		prevRect[1] = barY + 2;
		prevRect[2] = 20;
		prevRect[3] = 20;
		nextRect[0] = right - 46;
		nextRect[1] = barY + 2;
		nextRect[2] = 20;
		nextRect[3] = 20;
		closeRect[0] = right - 24;
		closeRect[1] = barY + 2;
		closeRect[2] = 20;
		closeRect[3] = 20;
		drawButton(context, prevRect, "↑");
		drawButton(context, nextRect, "↓");
		drawButton(context, closeRect, "×");

		if (replaceMode) {
			replaceRect[0] = barX + 4;
			replaceRect[1] = barY + 25;
			replaceRect[2] = Math.max(40, right - 112 - replaceRect[0]);
			replaceRect[3] = 18;
			drawField(context, replaceRect, replaceQuery, "Заменить на", replaceFocus);

			replaceButtonRect[0] = right - 108;
			replaceButtonRect[1] = barY + 24;
			replaceButtonRect[2] = 54;
			replaceButtonRect[3] = 20;
			replaceAllRect[0] = right - 50;
			replaceAllRect[1] = barY + 24;
			replaceAllRect[2] = 46;
			replaceAllRect[3] = 20;
			drawButton(context, replaceButtonRect, "Заменить");
			drawButton(context, replaceAllRect, "Все");
		}
	}

	private void drawField(DrawContext context, int[] rect, String value, String placeholder, boolean focused) {
		if (rect[2] <= 0) {
			return;
		}
		context.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xFF12161E);
		context.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + 1,
				focused ? 0xFF3A86FF : 0x553A4050);
		String shown = value.isEmpty() ? placeholder : value;
		int color = value.isEmpty() ? 0xFF6F7683 : 0xFFE8E8E8;
		String cut = trimTo(shown, rect[2] - 8);
		context.drawText(textRenderer, cut, rect[0] + 4, rect[1] + 5, color, false);
		if (focused && rect[2] > 14) {
			int caretX = rect[0] + 4 + textRenderer.getWidth(trimTo(value, rect[2] - 12));
			context.fill(caretX, rect[1] + 4, caretX + 1, rect[1] + 14, 0xFFF0F0F0);
		}
	}

	private void drawButton(DrawContext context, int[] rect, String label) {
		boolean hovered = isMouseOverButton(rect);
		context.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], hovered ? 0xFF2C3444 : 0xFF232A36);
		context.drawText(textRenderer, label,
				rect[0] + (rect[2] - textRenderer.getWidth(label)) / 2,
				rect[1] + 6, hovered ? 0xFFFFFFFF : 0xFFB9C2D0, false);
	}

	private boolean isMouseOverButton(int[] rect) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.mouse == null) {
			return false;
		}
		double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
		double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
		return inside(rect, mouseX, mouseY);
	}

	private static boolean inside(int[] rect, double x, double y) {
		return rect[2] > 0 && x >= rect[0] && x < rect[0] + rect[2]
				&& y >= rect[1] && y < rect[1] + rect[3];
	}

	private void typeInFind(String value) {
		if (replaceMode && replaceFocus) {
			if (replaceQuery.length() < 160) {
				replaceQuery += value;
			}
		} else {
			if (findQuery.length() < 160) {
				findQuery += value;
				searchChanged();
			}
		}
	}

	private static String dropLast(String value) {
		return value.isEmpty() ? value : value.substring(0, value.length() - 1);
	}

	// ---------------------------------------------------------------- мышь

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();

		// строка поиска/замены и переход к строке перехватывают клики
		if (findMode || gotoMode) {
			if (click.button() == 0) {
				setFocused(true);
				if (inside(findRect, mouseX, mouseY)) {
					replaceFocus = false;
					return true;
				}
				if (replaceMode && inside(replaceRect, mouseX, mouseY)) {
					replaceFocus = true;
					return true;
				}
				if (inside(prevRect, mouseX, mouseY)) {
					findNext(false);
					return true;
				}
				if (inside(nextRect, mouseX, mouseY)) {
					findNext(true);
					return true;
				}
				if (inside(closeRect, mouseX, mouseY)) {
					closeFind();
					return true;
				}
				if (replaceMode && inside(replaceButtonRect, mouseX, mouseY)) {
					replaceCurrent();
					return true;
				}
				if (replaceMode && inside(replaceAllRect, mouseX, mouseY)) {
					replaceAll();
					return true;
				}
			}
			// клик по самому бару (вне полей) не должен ставить курсор в код
			if (barH > 0 && mouseX >= barX && mouseX < barX + barW && mouseY >= barY && mouseY < barY + barH) {
				return true;
			}
			// клик по коду возвращает ввод редактору, бар поиска закрывается
			closeFind();
		}

		if (click.button() == 0 && overHints(mouseX, mouseY)) {
			setFocused(true);
			// ползунок
			int trackX = hintX + hintW - HINT_SLIDER_W;
			if (mouseX >= trackX - 3 && mouseX <= trackX + 5) {
				draggingSlider = true;
				updateSlider(mouseY);
				return true;
			}
			if (mouseY < hintY + HINT_PRIMARY_H) {
				acceptHint();
				return true;
			}
			int row = (int) ((mouseY - hintY - HINT_PRIMARY_H) / HINT_ROW_H);
			int shown = Math.min(HINT_ROWS, Math.max(0, hints.size() - 1));
			if (row >= 0 && row < shown) {
				hintIndex = (hintIndex + 1 + row) % hints.size();
				return true;
			}
			return true;
		}

		if (!isMouseOver(mouseX, mouseY)) {
			setFocused(false);
			hideHints();
			return false;
		}
		setFocused(true);

		// нижний горизонтальный ползунок тянется мышью
		if (click.button() == 0 && hasHScroll()
				&& mouseY >= hScrollY() && mouseY <= hScrollY() + HSCROLL_H) {
			draggingHScroll = true;
			updateHScroll(mouseX);
			hideHints();
			return true;
		}

		int line = lineFromMouseY(mouseY);
		// красный кружок слева — берём строку (или выделенные строки) и тащим
		if (!assistMode && click.button() == 0 && mouseX >= getX() + 1 && mouseX <= getX() + CIRCLE + 6) {
			int first = line;
			int last = line;
			if (caret != anchor) {
				int selFirst = lineOfIndex(Math.min(caret, anchor));
				int selLast = lineOfIndex(Math.max(caret, anchor));
				if (line >= selFirst && line <= selLast) {
					first = selFirst;
					last = selLast;
				}
			}
			draggingLine = true;
			dragFirst = first;
			dragLast = last;
			dragTo = last;
			hideHints();
			return true;
		}

		int target = indexFromMouse(mouseX, line);
		caret = target;
		if ((click.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0) {
			anchor = caret;
		}
		draggingText = click.button() == 0;
		if (caret == anchor) {
			refreshHints();
		} else {
			hideHints();
		}
		return true;
	}

	/** Позиция в тексте по координате мыши внутри строки. */
	private int indexFromMouse(double mouseX, int line) {
		int start = lineStartIndex(line);
		int end = lineEndIndex(start);
		int relativeX = (int) (mouseX - codeX());
		if (relativeX <= 0) {
			return start;
		}
		int target = start;
		while (target < end) {
			if (textRenderer.getWidth(text.substring(start, target + 1)) > relativeX) {
				break;
			}
			target++;
		}
		return target;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (draggingHScroll) {
			updateHScroll(click.x());
			return true;
		}
		if (draggingSlider) {
			updateSlider(click.y());
			return true;
		}
		if (draggingLine) {
			dragTo = lineFromMouseY(click.y());
			return true;
		}
		if (draggingText) {
			int visibleRows = visibleRows();
			// автопрокрутка, когда тянем выделение за край
			if (click.y() < getY() + PAD && scrollLine > 0) {
				scrollLine--;
			} else if (click.y() > getY() + getHeight() - PAD) {
				scrollLine = clamp(scrollLine + 1, 0, Math.max(0, lineCount() - visibleRows));
			}
			caret = indexFromMouse(click.x(), lineFromMouseY(click.y()));
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (draggingHScroll) {
			draggingHScroll = false;
			return true;
		}
		if (draggingSlider) {
			draggingSlider = false;
			return true;
		}
		if (draggingText) {
			draggingText = false;
			return true;
		}
		if (draggingLine) {
			draggingLine = false;
			if (dragFirst >= 0 && dragTo >= 0 && dragTo >= dragFirst && dragTo <= dragLast) {
				// бросили строки на их же место — ничего не делаем
			} else if (dragFirst >= 0 && dragTo >= 0) {
				moveLines(dragFirst, dragLast, dragTo);
			}
			dragFirst = -1;
			dragLast = -1;
			dragTo = -1;
			return true;
		}
		return super.mouseReleased(click);
	}

	private void updateSlider(double mouseY) {
		if (hints.size() <= 1) {
			return;
		}
		int trackTop = hintY + 3;
		int trackBottom = hintY + hintH - 3;
		double ratio = (mouseY - trackTop) / Math.max(1.0, trackBottom - trackTop);
		ratio = Math.max(0.0, Math.min(1.0, ratio));
		hintIndex = (int) Math.round(ratio * (hints.size() - 1));
	}

	private int lineFromMouseY(double mouseY) {
		int row = (int) ((mouseY - getY() - PAD) / LINE_HEIGHT);
		return clamp(scrollLine + row, 0, Math.max(0, lineCount() - 1));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (overHints(mouseX, mouseY)) {
			moveHint((int) -Math.signum(verticalAmount));
			return true;
		}
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		hideHints();
		// Shift+колесо или тачпад — прокрутка вбок, иначе вниз
		if (horizontalAmount != 0 || shiftHeld()) {
			double amount = horizontalAmount != 0 ? horizontalAmount : verticalAmount;
			scrollX = clamp(scrollX - (int) Math.signum(amount) * 16, 0, maxScrollX());
			if (horizontalAmount != 0) {
				return true;
			}
		}
		scrollLine = clamp(scrollLine - (int) Math.signum(verticalAmount) * 3, 0,
				Math.max(0, lineCount() - visibleRows()));
		return true;
	}

	// ---------------------------------------------------------------- клавиатура

	@Override
	public boolean keyPressed(KeyInput input) {
		if (!isFocused()) {
			return false;
		}
		int keyCode = input.key();
		boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shift = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;

		// переход к строке: только цифры, Enter — перейти, Esc — отмена
		if (gotoMode) {
			switch (keyCode) {
				case GLFW.GLFW_KEY_ESCAPE -> {
					gotoMode = false;
					gotoBuffer = "";
					return true;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					applyGoto();
					return true;
				}
				case GLFW.GLFW_KEY_BACKSPACE -> {
					gotoBuffer = dropLast(gotoBuffer);
					return true;
				}
				default -> {
					// цифры приходят через charTyped
					return keyCode != GLFW.GLFW_KEY_F && !ctrl;
				}
			}
		}

		// поиск/замена открыты: клавиши управляют баром, а не кодом
		if (findMode) {
			if (ctrl && keyCode == GLFW.GLFW_KEY_H) {
				openFind(true);
				return true;
			}
			if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
				openFind(false);
				return true;
			}
			if (ctrl && keyCode == GLFW.GLFW_KEY_G) {
				findMode = false;
				openGoto();
				return true;
			}
			switch (keyCode) {
				case GLFW.GLFW_KEY_ESCAPE -> {
					closeFind();
					return true;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					findNext(!shift);
					return true;
				}
				case GLFW.GLFW_KEY_TAB -> {
					if (replaceMode) {
						replaceFocus = !replaceFocus;
					}
					return true;
				}
				case GLFW.GLFW_KEY_UP -> {
					findNext(false);
					return true;
				}
				case GLFW.GLFW_KEY_DOWN -> {
					findNext(true);
					return true;
				}
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (replaceMode && replaceFocus) {
						replaceQuery = dropLast(replaceQuery);
					} else {
						findQuery = dropLast(findQuery);
						searchChanged();
					}
					return true;
				}
				default -> {
					// Ctrl+Z/Y и остальные сочетания пусть работают как обычно
					if (!ctrl) {
						return true;
					}
				}
			}
		}

		// глобальные сочетания редактора
		if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
			if (shift) {
				redo();
			} else {
				undo();
			}
			refreshHints();
			return true;
		}
		if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
			redo();
			refreshHints();
			return true;
		}
		if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
			openFind(false);
			return true;
		}
		if (ctrl && keyCode == GLFW.GLFW_KEY_H) {
			openFind(true);
			return true;
		}
		if (ctrl && keyCode == GLFW.GLFW_KEY_G) {
			openGoto();
			return true;
		}

		if (ctrl && shift && keyCode == GLFW.GLFW_KEY_K) {
			deleteSelectionOrLine();
			refreshHints();
			return true;
		}

		if (!hints.isEmpty()) {
			switch (keyCode) {
				case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					acceptHint();
					return true;
				}
				case GLFW.GLFW_KEY_ESCAPE -> {
					hideHints();
					return true;
				}
				case GLFW.GLFW_KEY_UP -> {
					moveHint(-1);
					return true;
				}
				case GLFW.GLFW_KEY_DOWN -> {
					moveHint(1);
					return true;
				}
				default -> {
					if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
						hideHints();
					}
				}
			}
		}

		switch (keyCode) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				recordEdit("newline");
				replaceSelection("\n");
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (hasSelection()) {
					recordEdit("delete");
					replaceSelection("");
				} else if (caret > 0) {
					recordEdit("delete");
					text.deleteCharAt(caret - 1);
					caret--;
					anchor = caret;
				}
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (hasSelection()) {
					recordEdit("delete");
					replaceSelection("");
				} else if (caret < text.length()) {
					recordEdit("delete");
					text.deleteCharAt(caret);
				}
			}
			case GLFW.GLFW_KEY_LEFT -> move(caret > 0 ? caret - 1 : caret, shift);
			case GLFW.GLFW_KEY_RIGHT -> move(caret < text.length() ? caret + 1 : caret, shift);
			case GLFW.GLFW_KEY_UP -> move(vertical(caret, -1), shift);
			case GLFW.GLFW_KEY_DOWN -> move(vertical(caret, 1), shift);
			case GLFW.GLFW_KEY_HOME -> move(lineStartIndex(getCaretLine()), shift);
			case GLFW.GLFW_KEY_END -> move(lineEndIndex(lineStartIndex(getCaretLine())), shift);
			case GLFW.GLFW_KEY_PAGE_UP -> move(vertical(caret, -visibleRows()), shift);
			case GLFW.GLFW_KEY_PAGE_DOWN -> move(vertical(caret, visibleRows()), shift);
			case GLFW.GLFW_KEY_TAB -> {
				recordEdit("insert");
				replaceSelection("  ");
			}
			case GLFW.GLFW_KEY_A -> {
				if (ctrl) {
					anchor = 0;
					caret = text.length();
				} else {
					return false;
				}
			}
			case GLFW.GLFW_KEY_C -> {
				if (ctrl) {
					copySelection();
				} else {
					return false;
				}
			}
			case GLFW.GLFW_KEY_X -> {
				if (ctrl) {
					recordEdit("cut");
					copySelection();
					replaceSelection("");
				} else {
					return false;
				}
			}
			case GLFW.GLFW_KEY_V -> {
				if (ctrl) {
					String clip = MinecraftClient.getInstance().keyboard.getClipboard();
					recordEdit("paste");
					replaceSelection(clip == null ? "" : clip.replace("\r\n", "\n").replace('\r', '\n'));
				} else {
					return false;
				}
			}
			default -> {
				return false;
			}
		}
		ensureVisible();
		refreshHints();
		return true;
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (!isFocused() || !input.isValidChar()) {
			return false;
		}
		String typed = input.asString();

		if (gotoMode) {
			if (typed.length() == 1 && Character.isDigit(typed.charAt(0)) && gotoBuffer.length() < 4) {
				gotoBuffer += typed;
			}
			return true;
		}
		if (findMode) {
			if (!typed.isEmpty() && typed.charAt(0) >= ' ') {
				typeInFind(typed);
			}
			return true;
		}

		recordEdit("type");
		replaceSelection(typed);
		ensureVisible();
		refreshHints();
		return true;
	}

	// ---------------------------------------------------------------- строки

	/** Переносит блок строк {@code first..last} на позицию {@code to}. */
	private void moveLines(int first, int last, int to) {
		String[] lines = text.toString().split("\n", -1);
		if (first < 0 || last >= lines.length || first > last) {
			return;
		}
		recordEdit("move-lines");
		List<String> list = new ArrayList<>();
		for (String line : lines) {
			list.add(line);
		}
		List<String> block = new ArrayList<>(list.subList(first, last + 1));
		for (int i = last; i >= first; i--) {
			list.remove(i);
		}
		int target = to > last ? to - block.size() : to;
		target = clamp(target, 0, list.size());
		list.addAll(target, block);

		StringBuilder rebuilt = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				rebuilt.append('\n');
			}
			rebuilt.append(list.get(i));
		}
		text.setLength(0);
		text.append(rebuilt);

		int startIndex = lineStartIndex(target);
		int endIndex = target + block.size() < list.size()
				? Math.max(startIndex, lineStartIndex(target + block.size()) - 1)
				: text.length();
		anchor = clamp(startIndex, 0, text.length());
		caret = clamp(endIndex, 0, text.length());
		ensureVisible();
	}

	// ---------------------------------------------------------------- служебное

	private void move(int newCaret, boolean shift) {
		caret = clamp(newCaret, 0, text.length());
		if (!shift) {
			anchor = caret;
		}
		hideHints();
	}

	private int vertical(int from, int lines) {
		int line = 0;
		int column = 0;
		for (int i = 0; i < from; i++) {
			if (text.charAt(i) == '\n') {
				line++;
				column = 0;
			} else {
				column++;
			}
		}
		int targetLine = clamp(line + lines, 0, lineCount() - 1);
		int start = lineStartIndex(targetLine);
		int end = lineEndIndex(start);
		return Math.min(start + column, end);
	}

	private boolean hasSelection() {
		return caret != anchor;
	}

	private void copySelection() {
		if (!hasSelection()) {
			int start = lineStartIndex(getCaretLine());
			int end = lineEndIndex(start);
			MinecraftClient.getInstance().keyboard.setClipboard(text.substring(start, end));
			return;
		}
		int start = Math.min(caret, anchor);
		int end = Math.max(caret, anchor);
		MinecraftClient.getInstance().keyboard.setClipboard(text.substring(start, end));
	}

	private void replaceSelection(String replacement) {
		int start = Math.min(caret, anchor);
		int end = Math.max(caret, anchor);
		text.replace(start, end, replacement);
		caret = start + replacement.length();
		anchor = caret;
	}

	private void ensureVisible() {
		int line = getCaretLine();
		int rows = visibleRows();
		if (line < scrollLine) {
			scrollLine = line;
		} else if (line >= scrollLine + rows) {
			scrollLine = line - rows + 1;
		}
		scrollLine = Math.max(0, scrollLine);
		// курсор не должен уезжать за края по горизонтали
		int visible = Math.max(1, getWidth() - GUTTER - PAD - 1);
		int caretX = textRenderer.getWidth(text.substring(lineStartIndex(line), Math.min(caret, text.length())));
		if (caretX < scrollX) {
			scrollX = caretX;
		} else if (caretX > scrollX + visible) {
			scrollX = caretX - visible;
		}
		scrollX = clamp(scrollX, 0, maxScrollX());
	}

	private int lineOfIndex(int index) {
		int line = 0;
		int limit = Math.min(index, text.length());
		for (int i = 0; i < limit; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private int lineCount() {
		int count = 1;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				count++;
			}
		}
		return count;
	}

	private int lineStartIndex(int line) {
		int index = 0;
		int current = 0;
		while (current < line && index < text.length()) {
			if (text.charAt(index) == '\n') {
				current++;
			}
			index++;
		}
		return Math.min(index, text.length());
	}

	private int lineEndIndex(int start) {
		int index = start;
		while (index < text.length() && text.charAt(index) != '\n') {
			index++;
		}
		return index;
	}

	private int visibleRows() {
		int reserved = hasHScroll() ? HSCROLL_H + 2 : 0;
		return Math.max(1, (getHeight() - PAD * 2 - reserved) / LINE_HEIGHT);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
