package com.justscript.ui;

import com.justscript.script.ModuleRegistry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Добавление модуля по ID. */
public final class AddModuleScreen extends Screen {
	private final ModulesScreen parent;
	private TextFieldWidget idField;
	private String status = "";

	public AddModuleScreen(ModulesScreen parent) {
		super(Text.literal("Добавить модуль"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int width = Math.min(this.width - 40, 380);
		int x = (this.width - width) / 2;
		int y = this.height / 2 - 20;

		this.idField = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.literal("ID модуля"));
		this.idField.setMaxLength(80);
		this.idField.setPlaceholder(Text.literal("ID модуля, например my_module"));
		this.addDrawableChild(this.idField);
		this.setInitialFocus(this.idField);

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Добавить"), button -> add())
				.dimensions(x, y + 28, (width - 6) / 2, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> close())
				.dimensions(x + (width - 6) / 2 + 6, y + 28, (width - 6) / 2, 20).build());
	}

	private void add() {
		String id = this.idField.getText().trim();
		if (id.isEmpty()) {
			status = "Введи ID модуля";
			return;
		}
		if (ModuleRegistry.get().add(id)) {
			close();
		} else {
			status = "Такой модуль уже добавлен";
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.drawText(this.textRenderer, "Добавить модуль по ID", this.width / 2 - 70, this.height / 2 - 46,
				0xFFE8E8E8, false);
		super.render(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.drawText(this.textRenderer, status, this.width / 2 - 60, this.height / 2 + 56,
					0xFFFFC94D, false);
		}
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
