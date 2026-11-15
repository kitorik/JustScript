package com.justscript.ui;

import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Переименование скрипта: своё имя вместо «Блок: … / Сущность: …».
 * Открывается кликом по названию в редакторе или через ПКМ-меню в дереве.
 */
public final class RenameScreen extends Screen {
	private final Screen parent;
	private final ScriptUnit unit;
	private TextFieldWidget field;

	public RenameScreen(Screen parent, ScriptUnit unit) {
		super(Text.literal("Название скрипта"));
		this.parent = parent;
		this.unit = unit;
	}

	@Override
	protected void init() {
		int width = Math.min(this.width - 40, 380);
		int x = (this.width - width) / 2;
		int y = this.height / 2 - 20;

		this.field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.literal("Название"));
		this.field.setMaxLength(80);
		this.field.setText(unit.label == null ? "" : unit.label);
		this.field.setPlaceholder(Text.literal("Например: Дверь спавна"));
		this.addDrawableChild(this.field);

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), button -> apply())
				.dimensions(x, y + 28, (width - 6) / 2, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> close())
				.dimensions(x + (width - 6) / 2 + 6, y + 28, (width - 6) / 2, 20).build());
	}

	private void apply() {
		String value = this.field.getText().trim();
		if (!value.isEmpty()) {
			unit.label = value;
			ScriptStore.get().touch(unit);
			ScriptStore.get().save();
		}
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.drawText(this.textRenderer, "Как называть этот скрипт?", this.width / 2 - 90, this.height / 2 - 46,
				0xFFE8E8E8, false);
		super.render(context, mouseX, mouseY, delta);
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
