package com.justscript.ui;

import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Порядок обычных скриптов в собранном модуле.
 *
 * <p>Глобальный скрипт всегда идёт первым (в нём функции, процессы и события
 * мира — они должны быть объявлены раньше вызовов). Обычные скрипты
 * (блоки и сущности) можно расставить стрелками ↑/↓: их поле
 * {@link ScriptUnit#position} и определяет порядок в JSON-модуле.
 */
public final class ScriptOrderScreen extends Screen {
	private static final int ROW_H = 22;

	private final Screen parent;
	private final List<ScriptUnit> rows = new ArrayList<>();
	private int scroll;
	private int listTop;
	private int listBottom;
	private int listX;
	private int listW;

	public ScriptOrderScreen(Screen parent) {
		super(Component.literal("JustScript — порядок скриптов"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.listW = Math.min(this.width - 40, 520);
		this.listX = (this.width - this.listW) / 2;
		this.listTop = 62;
		this.listBottom = this.height - 40;
		refresh();
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), button -> onClose())
				.bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
	}

	/** Перечитывает список: глобальные сверху, дальше — по позиции. */
	private void refresh() {
		rows.clear();
		List<ScriptUnit> global = new ArrayList<>();
		List<ScriptUnit> ordinary = new ArrayList<>();
		for (ScriptUnit unit : ScriptStore.get().all()) {
			(unit.isGlobal() ? global : ordinary).add(unit);
		}
		global.sort(Comparator.comparing(unit -> unit.dimension));
		ordinary.sort(Comparator.comparingInt((ScriptUnit unit) -> unit.position)
				.thenComparing(ScriptUnit::shortLabel, String.CASE_INSENSITIVE_ORDER));
		rows.addAll(global);
		rows.addAll(ordinary);
		// нормализуем позиции обычных скриптов: 0, 1, 2, …
		int position = 0;
		boolean changed = false;
		for (ScriptUnit unit : ordinary) {
			if (unit.position != position) {
				changed = true;
			}
			unit.position = position++;
		}
		if (changed) {
			ScriptStore.get().markDirty();
		}
	}

	private int rowsHeight() {
		return Math.max(1, (this.listBottom - this.listTop) / ROW_H);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Порядок скриптов", 12, 12, 0xFFE8E8E8, false);
		context.text(this.font,
				"Глобальный скрипт собирается первым, обычные — в этом порядке",
				12, 26, 0xFF8A93A6, false);

		context.fill(this.listX - 6, this.listTop - 6, this.listX + this.listW + 6, this.listBottom + 6, 0xFF14171D);

		int visible = rowsHeight();
		int end = Math.min(rows.size(), scroll + visible);
		int y = this.listTop;
		for (int index = scroll; index < end; index++) {
			ScriptUnit unit = rows.get(index);
			boolean global = unit.isGlobal();
			boolean hovered = mouseX >= this.listX && mouseX <= this.listX + this.listW
					&& mouseY >= y && mouseY < y + ROW_H - 2;
			if (hovered) {
				context.fill(this.listX - 4, y - 2, this.listX + this.listW + 4, y + ROW_H - 4, 0x332F6FDB);
			}

			String kind = switch (unit.kind) {
				case "entity" -> "◆ сущность";
				case "global" -> "★ глобальный";
				default -> "■ блок";
			};
			String label = trim(unit.shortLabel(), this.listW - 190);
			context.text(this.font, kind, this.listX, y, global ? 0xFFDCDCAA : 0xFF7FB2FF, false);
			context.text(this.font, label, this.listX + 82, y, 0xFFD4D4D4, false);

			if (global) {
				context.text(this.font, "всегда первый", this.listX + this.listW - 92,
						y, 0xFF8A93A6, false);
			} else {
				String pos = "позиция " + unit.position;
				context.text(this.font, pos, this.listX + this.listW - 150, y, 0xFF8A93A6, false);
				drawArrow(context, this.listX + this.listW - 56, y - 4, true, isEnabled(index, -1), mouseX, mouseY);
				drawArrow(context, this.listX + this.listW - 28, y - 4, false, isEnabled(index, 1), mouseX, mouseY);
			}
			y += ROW_H;
		}

		if (rows.isEmpty()) {
			context.text(this.font, "Скриптов пока нет", this.listX, this.listTop, 0xFF7A8085, false);
		}
		if (rows.size() > visible) {
			context.text(this.font, "колесо мыши — прокрутка",
					this.listX, this.listBottom + 8, 0xFF6A7380, false);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	private static final int ARROW = 20;

	private void drawArrow(GuiGraphicsExtractor context, int x, int y, boolean up, boolean enabled, int mouseX, int mouseY) {
		boolean hovered = enabled && mouseX >= x && mouseX <= x + ARROW && mouseY >= y && mouseY <= y + ARROW;
		context.fill(x, y, x + ARROW, y + ARROW, hovered ? 0xFF2F6FDB : 0xFF232934);
		context.fill(x, y, x + ARROW, y + 1, 0xFF3A4050);
		int centerX = x + ARROW / 2;
		int centerY = y + ARROW / 2;
		int color = enabled ? 0xFFE8E8E8 : 0xFF5A6070;
		for (int i = 0; i < 5; i++) {
			// вверх: узкая вершина сверху и широкое основание снизу; вниз — наоборот
			int width = up ? 1 + i * 2 : 9 - i * 2;
			int row = centerY - 2 + i;
			context.fill(centerX - width / 2, row, centerX - width / 2 + width, row + 1, color);
		}
	}

	/** Можно ли двигать строку в заданную сторону (глобальные не двигаются). */
	private boolean isEnabled(int index, int direction) {
		if (index < 0 || index >= rows.size() || rows.get(index).isGlobal()) {
			return false;
		}
		int target = index + direction;
		if (target < 0 || target >= rows.size()) {
			return false;
		}
		return !rows.get(target).isGlobal();
	}

	/** Меняет местами соседние обычные скрипты и перенумеровывает позиции. */
	private void move(int index, int direction) {
		if (!isEnabled(index, direction)) {
			return;
		}
		ScriptUnit unit = rows.get(index);
		ScriptUnit other = rows.get(index + direction);
		int swap = unit.position;
		unit.position = other.position;
		other.position = swap;
		ScriptStore.get().touch(unit);
		ScriptStore.get().touch(other);
		ScriptStore.get().markDirty();
		ScriptStore.get().save();
		refresh();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		double mouseX = click.x();
		double mouseY = click.y();
		int visible = rowsHeight();
		int end = Math.min(rows.size(), scroll + visible);
		int y = this.listTop;
		for (int index = scroll; index < end; index++) {
			ScriptUnit unit = rows.get(index);
			if (!unit.isGlobal()) {
				int upX = this.listX + this.listW - 56;
				int downX = this.listX + this.listW - 28;
				if (mouseX >= upX && mouseX <= upX + ARROW && mouseY >= y - 4 && mouseY <= y - 4 + ARROW) {
					move(index, -1);
					return true;
				}
				if (mouseX >= downX && mouseX <= downX + ARROW && mouseY >= y - 4 && mouseY <= y - 4 + ARROW) {
					move(index, 1);
					return true;
				}
			}
			y += ROW_H;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int maxScroll = Math.max(0, rows.size() - rowsHeight());
		int next = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount)));
		if (next == scroll) {
			return false;
		}
		scroll = next;
		return true;
	}

	@Override
	public void onClose() {
		ScriptStore.get().save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private String trim(String value, int maxWidth) {
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
}