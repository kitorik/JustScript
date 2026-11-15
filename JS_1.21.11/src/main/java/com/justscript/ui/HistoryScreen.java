package com.justscript.ui;

import com.justscript.Config;
import com.justscript.script.ScriptHistory;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * История скрипта: список копий кода и откат к выбранному моменту.
 * Сколько копий хранится, задаётся в настройках («Память»).
 */
public final class HistoryScreen extends Screen {
	private static final int ROW_H = 20;

	private final ScriptEditorScreen parent;
	private final ScriptUnit unit;
	private final List<ScriptHistory.Snapshot> snapshots;
	private int scroll;
	private int selected;
	private int listX;
	private int listW;
	private int listTop;
	private int listBottom;
	private String status = "";

	public HistoryScreen(ScriptEditorScreen parent, ScriptUnit unit) {
		super(Text.literal("История скрипта"));
		this.parent = parent;
		this.unit = unit;
		this.snapshots = ScriptHistory.list(unit);
	}

	@Override
	protected void init() {
		this.listW = Math.min(this.width - 40, 280);
		this.listX = 16;
		this.listTop = 46;
		this.listBottom = this.height - 40;

		int buttonsY = this.height - 28;
		int half = (this.width - 48) / 2;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Откатить к копии"), button -> restore())
				.dimensions(16, buttonsY, half, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Удалить копию"), button -> delete())
				.dimensions(32 + half, buttonsY, half, 20).build());

		if (snapshots.isEmpty()) {
			this.status = "Копий пока нет. Ctrl+Shift+K удаляет выделенное или строку.";
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.drawText(this.textRenderer, "История: " + unit.displayTitle(), 16, 12, 0xFFE8E8E8, false);
		context.drawText(this.textRenderer, "Хранится копий: " + Config.get().historyLimit, 16, 26, 0xFF9AA4B2, false);

		context.fill(this.listX - 4, this.listTop - 4, this.listX + this.listW + 4, this.listBottom, 0xFF14171D);
		int rows = Math.max(1, (this.listBottom - this.listTop) / ROW_H);
		for (int row = 0; row < rows; row++) {
			int index = scroll + row;
			if (index >= snapshots.size()) {
				break;
			}
			ScriptHistory.Snapshot snapshot = snapshots.get(index);
			int y = this.listTop + row * ROW_H;
			boolean hovered = mouseX >= this.listX && mouseX <= this.listX + this.listW
					&& mouseY >= y && mouseY < y + ROW_H - 2;
			if (index == selected || hovered) {
				context.fill(this.listX - 4, y - 2, this.listX + this.listW + 4, y + ROW_H - 4,
						index == selected ? 0x553A86FF : 0x332F6FDB);
			}
			context.drawText(this.textRenderer, snapshot.timeLabel(), this.listX, y, 0xFFBFE0FF, false);
			String reason = snapshot.reason();
			context.drawText(this.textRenderer, reason, this.listX, y + 8, 0xFF6F7683, false);
		}

		context.drawText(this.textRenderer, "Просмотр", this.listX + this.listW + 24, 12, 0xFFE8E8E8, false);
		int previewX = this.listX + this.listW + 24;
		if (selected >= 0 && selected < snapshots.size()) {
			String[] lines = snapshots.get(selected).code().split("\n", -1);
			for (int i = 0; i < Math.min(lines.length, 18); i++) {
				context.drawText(this.textRenderer, trim(lines[i], this.width - previewX - 12),
						previewX, 26 + i * 10, 0xFFB9C2D0, false);
			}
		}

		super.render(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.drawText(this.textRenderer, status, 16, this.height - 42, 0xFFFFC94D, false);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0 && click.x() >= this.listX - 4 && click.x() <= this.listX + this.listW + 4
				&& click.y() >= this.listTop && click.y() < this.listBottom) {
			int row = (int) ((click.y() - this.listTop) / ROW_H);
			int index = scroll + row;
			if (index >= 0 && index < snapshots.size()) {
				selected = index;
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int rows = Math.max(1, (this.listBottom - this.listTop) / ROW_H);
		int max = Math.max(0, snapshots.size() - rows);
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));
		if (next == scroll) {
			return false;
		}
		scroll = next;
		return true;
	}

	private void restore() {
		if (selected < 0 || selected >= snapshots.size()) {
			status = "Сначала выбери копию слева";
			return;
		}
		ScriptHistory.Snapshot snapshot = snapshots.get(selected);
		parent.restoreFromHistory(snapshot.code());
		status = "Скрипт откатан к " + snapshot.timeLabel();
	}

	private void delete() {
		if (selected < 0 || selected >= snapshots.size()) {
			status = "Сначала выбери копию слева";
			return;
		}
		ScriptHistory.delete(snapshots.get(selected));
		snapshots.remove(selected);
		selected = Math.min(selected, snapshots.size() - 1);
		status = "Копия удалена";
	}

	private String trim(String value, int maxWidth) {
		if (value == null) {
			return "";
		}
		if (this.textRenderer.getWidth(value) <= maxWidth) {
			return value;
		}
		String result = value;
		while (!result.isEmpty() && this.textRenderer.getWidth(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
