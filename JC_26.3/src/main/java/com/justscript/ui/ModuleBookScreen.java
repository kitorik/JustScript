package com.justscript.ui;

import com.justscript.Config;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * «Книга модуля»: название модуля JustMC.
 *
 * <p>Книжная страница в тёплых тонах, чтобы настройка не терялась среди
 * технических полей. {@code %player%} подставляется ником при сборке.
 */
public final class ModuleBookScreen extends Screen {
	private static final int COVER = 0xFF3A2A1C;
	private static final int COVER_EDGE = 0xFF20160E;
	private static final int PAGE = 0xFFF1E4C6;
	private static final int PAGE_EDGE = 0xFFD8C49A;
	private static final int INK = 0xFF3B2A1A;
	private static final int INK_SOFT = 0xFF7A6448;

	private final Screen parent;
	private EditBox nameField;

	public ModuleBookScreen(Screen parent) {
		super(Component.literal("JustScript — книга модуля"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int pageW = Math.min(this.width - 40, 400);
		int pageH = Math.min(this.height - 60, 220);
		int x = (this.width - pageW) / 2;
		int y = (this.height - pageH) / 2;

		this.nameField = new EditBox(this.font, x + 24, y + 98, pageW - 48, 20,
				Component.literal("Название модуля"));
		this.nameField.setMaxLength(120);
		this.nameField.setValue(Config.get().moduleName == null ? "" : Config.get().moduleName);
		this.nameField.setHint(Component.literal("%player%_script"));
		this.addRenderableWidget(this.nameField);
		this.nameField.setFocused(true);

		int half = (pageW - 60) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("%player%_script"),
				button -> this.nameField.setValue("%player%_script"))
				.bounds(x + 24, y + 124, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("%player%_world"),
				button -> this.nameField.setValue("%player%_world"))
				.bounds(x + 36 + half, y + 124, half, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Сохранить"), button -> saveAndClose())
				.bounds(x + pageW / 2 - 60, y + pageH - 32, 120, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);

		int pageW = Math.min(this.width - 40, 400);
		int pageH = Math.min(this.height - 60, 220);
		int x = (this.width - pageW) / 2;
		int y = (this.height - pageH) / 2;

		// обложка
		context.fill(x - 12, y - 12, x + pageW + 12, y + pageH + 12, COVER_EDGE);
		context.fill(x - 8, y - 8, x + pageW + 8, y + pageH + 8, COVER);
		// страница
		context.fill(x, y, x + pageW, y + pageH, PAGE_EDGE);
		context.fill(x + 2, y + 2, x + pageW - 2, y + pageH - 2, PAGE);
		// корешок
		context.fill(x + 8, y + 2, x + 10, y + pageH - 2, PAGE_EDGE);

		context.text(this.font, "Название модуля", x + 24, y + 26, INK, false);
		context.text(this.font, "%player% — ник игрока", x + 24, y + 44, INK_SOFT, false);
		context.text(this.font, "Например: %player%_script", x + 24, y + 58, INK_SOFT, false);
		context.text(this.font, "Так модуль будет называться в JustMC.",
				x + 24, y + 72, INK_SOFT, false);

		// светлая полоска под полем ввода, чтобы белый текст читался
		context.fill(x + 22, y + 96, x + pageW - 22, y + 120, 0xFF2A2018);

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	private void saveAndClose() {
		applyName();
		Config.save();
		onClose();
	}

	private void applyName() {
		String value = this.nameField == null ? "" : this.nameField.getValue().trim();
		Config.get().moduleName = value.isEmpty() ? "%player%_script" : value;
	}

	@Override
	public void onClose() {
		applyName();
		Config.save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}