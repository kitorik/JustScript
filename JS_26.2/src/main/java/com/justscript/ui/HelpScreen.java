package com.justscript.ui;

import com.justscript.input.Keybinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Справка по управлению: клавиши мода (в том числе переназначенные),
 * горячие клавиши редактора, управление меню и основные клавиши игры.
 *
 * <p>Значения клавиш читаются из ванильных биндов при каждом открытии экрана,
 * поэтому после смены в «Управление → Назначение клавиш» здесь сразу видно новое.
 * Ниже — короткие советы по редактору.
 */
public final class HelpScreen extends Screen {
	private static final int PANEL = 0xFF14171D;
	private static final int PANEL_EDGE = 0xFF2A303C;
	private static final int MUTED = 0xFF9AA4B2;
	private static final int HEADER = 0xFF7FB2FF;
	private static final int HEADER_TIPS = 0xFFFFB86C;
	private static final int ITEM = 0xFFD4D4D4;
	private static final int KEY = 0xFF9CDCFE;

	private static final int HEADER_H = 20;
	private static final int ITEM_H = 13;
	private static final int TIP_H = 12;

	private enum Style {
		HEADER, HEADER_TIPS, ITEM, TIP
	}

	private record Row(String left, String right, Style style) {
		int height() {
			return switch (style) {
				case HEADER, HEADER_TIPS -> HEADER_H;
				case TIP -> TIP_H;
				default -> ITEM_H;
			};
		}
	}

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();

	private int panelX;
	private int panelW;
	private int listTop;
	private int listH;
	private int scroll;

	public HelpScreen(Screen parent) {
		super(Component.literal("JustScript — помощь"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.panelW = Math.min(this.width - 40, 560);
		this.panelX = (this.width - this.panelW) / 2;
		this.listTop = 48;

		int buttonY = this.height - 28;
		int keysW = 152;
		int doneW = 100;
		int gap = 8;
		int start = this.width / 2 - (keysW + gap + doneW) / 2;
		addRenderableWidget(Button.builder(Component.literal("Сменить клавиши…"), button -> openKeybinds())
				.bounds(start, buttonY, keysW, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Готово"), button -> onClose())
				.bounds(start + keysW + gap, buttonY, doneW, 20).build());

		this.listH = Math.max(60, buttonY - 8 - this.listTop);
		buildRows();
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
	}

	/** Собирает строки справки; клавиши читаются прямо из текущих биндов. */
	private void buildRows() {
		this.rows.clear();

		rows.add(new Row("Клавиши мода", "", Style.HEADER));
		rows.add(new Row("Меню JustScript", Keybinds.describeMenu(), Style.ITEM));
		rows.add(new Row("Код-меню объекта", Keybinds.describe(), Style.ITEM));
		rows.add(new Row("Кнопка код-меню", Keybinds.describeWrenchButton(), Style.ITEM));
		String modifier = Keybinds.describeModifier();
		rows.add(new Row("Модификатор код-меню", modifier.isEmpty() ? "не задан — не нужен" : modifier, Style.ITEM));
		addTip("Клавиши меняются в «Управление → Назначение клавиш → JustScript». "
				+ "Изменённые значения сразу видны здесь.");

		rows.add(new Row("Редактор кода", "", Style.HEADER));
		rows.add(new Row("Отменить / повторить", "Ctrl + Z / Ctrl + Shift + Z", Style.ITEM));
		rows.add(new Row("Повторить (запасной вариант)", "Ctrl + Y", Style.ITEM));
		rows.add(new Row("Поиск по коду / поиск с заменой", "Ctrl + F / Ctrl + H", Style.ITEM));
		rows.add(new Row("Перейти к строке", "Ctrl + G", Style.ITEM));
		rows.add(new Row("В баре поиска: дальше / назад", "Enter / Shift + Enter", Style.ITEM));
		rows.add(new Row("В баре поиска: сменить поле", "Tab", Style.ITEM));
		rows.add(new Row("Закрыть поиск", "Esc", Style.ITEM));
		rows.add(new Row("Сохранить скрипт", "Ctrl + S", Style.ITEM));
		rows.add(new Row("Собрать JSON в буфер", "Ctrl + Enter", Style.ITEM));
		rows.add(new Row("Копировать / вырезать / вставить", "Ctrl + C / X / V", Style.ITEM));
		rows.add(new Row("Выделить весь код", "Ctrl + A", Style.ITEM));
		rows.add(new Row("Удалить выделение или строку", "Ctrl + Shift + K", Style.ITEM));
		rows.add(new Row("Принять подсказку", "Tab или Enter", Style.ITEM));
		rows.add(new Row("Выбрать подсказку", "↑ / ↓", Style.ITEM));
		rows.add(new Row("Скрыть подсказки", "Esc", Style.ITEM));
		rows.add(new Row("Отступ без подсказки", "Tab", Style.ITEM));
		rows.add(new Row("Подсветить пару скобок {} [] ()", "Shift (удерживать)", Style.ITEM));
		rows.add(new Row("Двигать строки", "ЛКМ по красному кружку", Style.ITEM));
		rows.add(new Row("Выделить текст", "ЛКМ и протянуть", Style.ITEM));
		rows.add(new Row("Прокрутка кода", "колесо мыши", Style.ITEM));
		rows.add(new Row("Листать подсказки", "колесо над попапом", Style.ITEM));

		rows.add(new Row("Меню JustScript", "", Style.HEADER));
		rows.add(new Row("Открыть скрипт", "ЛКМ", Style.ITEM));
		rows.add(new Row("Выделить диапазон", "Shift + ЛКМ", Style.ITEM));
		rows.add(new Row("Снять выделение", "повторный ЛКМ", Style.ITEM));
		rows.add(new Row("Дублировать выделенные", "Ctrl + D", Style.ITEM));
		rows.add(new Row("Меню скрипта: открыть / копия / имя / удалить", "ПКМ по скрипту", Style.ITEM));
		rows.add(new Row("Удалить выделенные", "Delete", Style.ITEM));
		rows.add(new Row("Карточки: скрипты / модули / настройки", "1 / 2 / 3", Style.ITEM));
		rows.add(new Row("Прокрутка дерева", "колесо мыши", Style.ITEM));
		rows.add(new Row("Число копий в «Памяти»", "колесо (Shift — по 10)", Style.ITEM));
		rows.add(new Row("Закрыть меню", "Esc", Style.ITEM));

		rows.add(new Row("Основные клавиши игры", "", Style.HEADER));
		Options options = this.minecraft == null ? Minecraft.getInstance().options : this.minecraft.options;
		if (options == null) {
			rows.add(new Row("Настройки игры недоступны", "", Style.ITEM));
		} else {
			rows.add(new Row("Вперёд / назад", key(options.keyUp) + " / " + key(options.keyDown), Style.ITEM));
			rows.add(new Row("Влево / вправо", key(options.keyLeft) + " / " + key(options.keyRight), Style.ITEM));
			rows.add(new Row("Прыжок", key(options.keyJump), Style.ITEM));
			rows.add(new Row("Присесть", key(options.keyShift), Style.ITEM));
			rows.add(new Row("Бег", key(options.keySprint), Style.ITEM));
			rows.add(new Row("Инвентарь", key(options.keyInventory), Style.ITEM));
			rows.add(new Row("Выбросить предмет", key(options.keyDrop), Style.ITEM));
			rows.add(new Row("Чат", key(options.keyChat), Style.ITEM));
			rows.add(new Row("Команда", key(options.keyCommand), Style.ITEM));
			rows.add(new Row("Список игроков", key(options.keyPlayerList), Style.ITEM));
			rows.add(new Row("Смена перспективы", key(options.keyTogglePerspective), Style.ITEM));
		}

		rows.add(new Row("Советы", "", Style.HEADER_TIPS));
		addTip("Shift подсвечивает парную скобку рядом с курсором — так проще искать ошибки в JSON.");
		addTip("Tab принимает серую подсказку, а если подсказки нет — ставит отступ в два пробела.");
		addTip("Тень-дополнение не появляется, если после курсора уже есть продолжение слова.");
		addTip("Красный кружок слева двигает строку или все выделенные строки.");
		addTip("Ctrl+Z отменяет правку, Ctrl+Shift+Z возвращает; пауза при наборе склеивает шаги.");
		addTip("Ctrl+F ищет по коду, Ctrl+H заменяет, Ctrl+G прыгает на номер строки.");
		addTip("Строки с ошибками отмечены красным в жёлобе — ошибки видны до сборки.");
		addTip("ПКМ по скрипту в дереве: открыть, дублировать, переименовать или удалить.");
		addTip("Дерево листается колесом мыши — внизу видны глобальные скрипты, выше остальные ветки.");
		addTip("Колесо над кнопкой «Память» меняет точное число копий; Shift — сразу на 10.");
		addTip("В мире кода мод не отправляет /dev и /jointo: модуль ставится на месте.");
		addTip("ИИ может попросить разрешение на создание скрипта для другого объекта — "
				+ "ответь в уведомлении справа внизу.");
		addTip("«Разр. всегда» в уведомлении ИИ отключает вопрос для этого типа объектов; "
				+ "сброс — в дополнительных настройках.");
	}

	private static String key(KeyMapping binding) {
		return binding == null ? "не задано" : binding.getTranslatedKeyMessage().getString();
	}

	/** Добавляет совет с переносом строк по ширине панели. */
	private void addTip(String text) {
		int max = this.panelW - 34;
		String pending = "• " + text;
		boolean first = true;
		while (!pending.isEmpty()) {
			String prefix = first ? "" : "     ";
			String line = prefix;
			int lastSpace = -1;
			int index = 0;
			while (index < pending.length()) {
				char c = pending.charAt(index);
				if (c == ' ') {
					lastSpace = index;
				}
				if (this.font.width(line + pending.charAt(index)) > max && index > 0) {
					if (lastSpace > 0) {
						line = prefix + pending.substring(0, lastSpace);
						pending = pending.substring(lastSpace + 1);
					} else {
						line = prefix + pending.substring(0, index);
						pending = pending.substring(index);
					}
					break;
				}
				line += c;
				index++;
				if (index == pending.length()) {
					pending = "";
				}
			}
			rows.add(new Row(line, "", Style.TIP));
			first = false;
			if (pending.isEmpty()) {
				break;
			}
		}
	}

	private void openKeybinds() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(new KeyBindsScreen(this, this.minecraft.options));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Помощь — управление", 12, 10, 0xFFE8E8E8, false);
		context.text(this.font, "Клавиши мода, редактора и игры · изменённые видны сразу",
				12, 24, MUTED, false);

		context.fill(this.panelX - 8, this.listTop - 8, this.panelX + this.panelW + 8,
				this.listTop + this.listH + 8, PANEL_EDGE);
		context.fill(this.panelX - 6, this.listTop - 6, this.panelX + this.panelW + 6,
				this.listTop + this.listH + 6, PANEL);

		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));

		context.enableScissor(this.panelX, this.listTop, this.panelX + this.panelW, this.listTop + this.listH);
		int y = this.listTop - this.scroll;
		for (Row row : rows) {
			int height = row.height();
			if (y + height >= this.listTop && y <= this.listTop + this.listH) {
				drawRow(context, row, y);
			}
			y += height;
		}
		context.disableScissor();

		drawScrollbar(context);
		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	private void drawRow(GuiGraphicsExtractor context, Row row, int y) {
		int x = this.panelX + 8;
		int right = this.panelX + this.panelW - 8;
		switch (row.style()) {
			case HEADER -> {
				context.text(this.font, row.left(), x, y + 7, HEADER, false);
				int lineX = x + this.font.width(row.left()) + 8;
				if (lineX < right) {
					context.fill(lineX, y + 11, right, y + 12, 0x553A4050);
				}
			}
			case HEADER_TIPS -> {
				context.text(this.font, row.left(), x, y + 7, HEADER_TIPS, false);
				int lineX = x + this.font.width(row.left()) + 8;
				if (lineX < right) {
					context.fill(lineX, y + 11, right, y + 12, 0x553A4050);
				}
			}
			case ITEM -> {
				int keyWidth = this.font.width(row.right());
				int keyX = right - keyWidth;
				String left = trimTo(row.left(), Math.max(20, keyX - x - 10));
				context.text(this.font, left, x, y + 2, ITEM, false);
				if (!row.right().isEmpty()) {
					context.text(this.font, row.right(), keyX, y + 2, KEY, false);
				}
			}
			case TIP -> context.text(this.font, row.left(), x, y + 1, MUTED, false);
		}
	}

	private void drawScrollbar(GuiGraphicsExtractor context) {
		int total = contentHeight();
		if (total <= this.listH) {
			return;
		}
		int trackX = this.panelX + this.panelW + 2;
		context.fill(trackX, this.listTop, trackX + 2, this.listTop + this.listH, 0x553A4050);
		int knobH = Math.max(20, (int) ((long) this.listH * this.listH / total));
		int span = this.listH - knobH;
		int knobY = this.listTop + (maxScroll() == 0 ? 0 : span * this.scroll / maxScroll());
		context.fill(trackX - 1, knobY, trackX + 3, knobY + knobH, 0xFF7FB2FF);
	}

	private int contentHeight() {
		int total = 0;
		for (Row row : rows) {
			total += row.height();
		}
		return total;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - this.listH);
	}

	private String trimTo(String value, int maxWidth) {
		if (value == null) {
			return "";
		}
		if (this.font.width(value) <= maxWidth) {
			return value;
		}
		String result = value;
		while (!result.isEmpty() && this.font.width(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX >= this.panelX && mouseX <= this.panelX + this.panelW
				&& mouseY >= this.listTop && mouseY <= this.listTop + this.listH) {
			int step = 22;
			this.scroll = Math.max(0, Math.min(maxScroll(),
					this.scroll - (int) Math.signum(verticalAmount) * step));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
