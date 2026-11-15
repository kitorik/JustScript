package com.justscript.ui;

import com.justscript.script.ModuleRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Добавление модуля по ID. */
public final class AddModuleScreen extends Screen {
	private final ModulesScreen parent;
	private EditBox idField;
	private String status = "";

	public AddModuleScreen(ModulesScreen parent) {
		super(Component.literal("Добавить модуль"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int width = Math.min(this.width - 40, 380);
		int x = (this.width - width) / 2;
		int y = this.height / 2 - 20;

		this.idField = new EditBox(this.font, x, y, width, 20, Component.literal("ID модуля"));
		this.idField.setMaxLength(80);
		this.idField.setHint(Component.literal("ID модуля, например my_module"));
		this.addRenderableWidget(this.idField);
		this.setInitialFocus(this.idField);

		this.addRenderableWidget(Button.builder(Component.literal("Добавить"), button -> add())
				.bounds(x, y + 28, (width - 6) / 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Назад"), button -> onClose())
				.bounds(x + (width - 6) / 2 + 6, y + 28, (width - 6) / 2, 20).build());
	}

	private void add() {
		String id = this.idField.getValue().trim();
		if (id.isEmpty()) {
			status = "Введи ID модуля";
			return;
		}
		if (ModuleRegistry.get().add(id)) {
			onClose();
		} else {
			status = "Такой модуль уже добавлен";
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Добавить модуль по ID", this.width / 2 - 70, this.height / 2 - 46,
				0xFFE8E8E8, false);
		super.extractRenderState(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.text(this.font, status, this.width / 2 - 60, this.height / 2 + 56,
					0xFFFFC94D, false);
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
