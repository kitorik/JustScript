package com.justscript.ui;

import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Переименование скрипта: своё имя вместо «Блок: … / Сущность: …».
 * Открывается кликом по названию в редакторе или через ПКМ-меню в дереве.
 */
public final class RenameScreen extends Screen {
	private final Screen parent;
	private final ScriptUnit unit;
	private EditBox field;

	public RenameScreen(Screen parent, ScriptUnit unit) {
		super(Component.literal("Название скрипта"));
		this.parent = parent;
		this.unit = unit;
	}

	@Override
	protected void init() {
		int width = Math.min(this.width - 40, 380);
		int x = (this.width - width) / 2;
		int y = this.height / 2 - 20;

		this.field = new EditBox(this.font, x, y, width, 20, Component.literal("Название"));
		this.field.setMaxLength(80);
		this.field.setValue(unit.label == null ? "" : unit.label);
		this.field.setHint(Component.literal("Например: Дверь спавна"));
		this.addRenderableWidget(this.field);

		this.addRenderableWidget(Button.builder(Component.literal("Сохранить"), button -> apply())
				.bounds(x, y + 28, (width - 6) / 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Отмена"), button -> onClose())
				.bounds(x + (width - 6) / 2 + 6, y + 28, (width - 6) / 2, 20).build());
	}

	private void apply() {
		String value = this.field.getValue().trim();
		if (!value.isEmpty()) {
			unit.label = value;
			ScriptStore.get().touch(unit);
			ScriptStore.get().save();
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Как называть этот скрипт?", this.width / 2 - 90, this.height / 2 - 46,
				0xFFE8E8E8, false);
		super.extractRenderState(context, mouseX, mouseY, delta);
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
