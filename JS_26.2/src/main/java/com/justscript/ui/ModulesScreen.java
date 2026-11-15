package com.justscript.ui;

import com.justscript.Config;
import com.justscript.script.ModuleRegistry;
import com.justscript.util.Chat;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка «Модули»: установленные модули, их функции и процессы.
 * ЛКМ по функции — скопировать готовый вызов, ПКМ по модулю — удалить.
 * «+» справа снизу добавляет модуль по ID.
 */
public final class ModulesScreen extends Screen {
	private static final int ROW_H = 18;

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private int scroll;
	private int listX;
	private int listW;
	private int listTop;
	private int listBottom;
	private String status = "";

	private record Row(ModuleRegistry.Module module, ModuleRegistry.Callable callable) {
	}

	public ModulesScreen(Screen parent) {
		super(Component.literal("Модули"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		refresh();
		this.listW = Math.min(this.width - 40, 560);
		this.listX = (this.width - this.listW) / 2;
		this.listTop = 56;
		this.listBottom = this.height - 46;

		this.addRenderableWidget(Button.builder(Component.literal("+ Модуль по ID"), button -> openAdd())
				.bounds(this.listX + this.listW - 130, this.height - 30, 130, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Назад"), button -> onClose())
				.bounds(this.listX, this.height - 30, 100, 20).build());
	}

	private void refresh() {
		rows.clear();
		for (ModuleRegistry.Module module : ModuleRegistry.get().all()) {
			rows.add(new Row(module, null));
			for (ModuleRegistry.Func func : module.funcs) {
				List<String> params = List.copyOf(func.params);
				rows.add(new Row(module, new ModuleRegistry.Callable(
						module.id, module.name, func.name, func.kind, params)));
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Модули", 12, 12, 0xFFE8E8E8, false);
		context.text(this.font, "ЛКМ по функции — скопировать вызов • ПКМ по модулю — удалить",
				12, 26, 0xFF8A93A6, false);

		context.fill(this.listX - 6, this.listTop - 6, this.listX + this.listW + 6,
				this.listBottom + 6, 0xFF14171D);

		int visible = Math.max(1, (this.listBottom - this.listTop) / ROW_H);
		int y = this.listTop;
		for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
			Row row = rows.get(index);
			boolean hovered = mouseX >= this.listX && mouseX <= this.listX + this.listW
					&& mouseY >= y && mouseY < y + ROW_H - 2;
			if (hovered) {
				context.fill(this.listX - 4, y - 2, this.listX + this.listW + 4, y + ROW_H - 4, 0x332F6FDB);
			}
			if (row.callable() == null) {
				context.text(this.font, "▸ " + row.module().name, this.listX, y, 0xFFFFD966, false);
				context.text(this.font, "ID: " + row.module().id,
						this.listX + this.listW - this.font.width("ID: " + row.module().id),
						y, 0xFF6F7683, false);
			} else {
				context.text(this.font, "   " + row.callable().label(), this.listX, y, 0xFFB9C2D0, false);
			}
			y += ROW_H;
		}
		if (rows.isEmpty()) {
			context.text(this.font, "Модулей нет — добавь по ID кнопкой справа снизу",
					this.listX, this.listTop, 0xFF7A8085, false);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.text(this.font, status, this.listX, this.height - 44, 0xFF4ADE80, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.x() >= this.listX - 4 && click.x() <= this.listX + this.listW + 4
				&& click.y() >= this.listTop && click.y() < this.listBottom) {
			int index = scroll + (int) ((click.y() - this.listTop) / ROW_H);
			if (index >= 0 && index < rows.size()) {
				Row row = rows.get(index);
				if (click.button() == 1 && row.callable() == null) {
					ModuleRegistry.get().remove(row.module().id);
					status = "Модуль удалён: " + row.module().name;
					refresh();
					return true;
				}
				if (click.button() == 0 && row.callable() != null) {
					String snippet = ModuleRegistry.callSnippet(row.callable(), Config.get().language);
					Chat.copy(snippet);
					status = "Вызов скопирован: " + row.callable().name();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int visible = Math.max(1, (this.listBottom - this.listTop) / ROW_H);
		int max = Math.max(0, rows.size() - visible);
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));
		if (next == scroll) {
			return false;
		}
		scroll = next;
		return true;
	}

	private void openAdd() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(new AddModuleScreen(this));
		}
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
