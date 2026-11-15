package com.justscript.ui;

import com.justscript.Config;
import com.justscript.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Клиентская кнопка «Скрипты мира» в меню мира JustMC: рисуется поверх
 * пустого слота над компасом и открывает глобальный скрипт.
 * Сервер про эту кнопку ничего не знает.
 */
public final class WorldMenuOverlay {
	private WorldMenuOverlay() {
	}

	/** Координаты кнопки (18x18) или null, если это не меню мира. */
	private static int[] findBox(HandledScreen<?> screen) {
		Config config = Config.get();
		if (screen == null || !config.worldMenuButton) {
			return null;
		}
		String title = screen.getTitle() == null ? "" : screen.getTitle().getString().toLowerCase();
		String wanted = config.worldMenuTitle == null ? "" : config.worldMenuTitle.toLowerCase();
		if (wanted.isBlank() || !title.contains(wanted)) {
			return null;
		}
		HandledScreenAccessor accessor = (HandledScreenAccessor) (Object) screen;
		ScreenHandler handler = screen.getScreenHandler();
		if (handler == null) {
			return null;
		}
		Slot compass = null;
		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			if (stack != null && stack.isOf(Items.COMPASS)) {
				compass = slot;
				break;
			}
		}
		if (compass == null) {
			return null;
		}
		int x = accessor.js$getX() + compass.x - 1;
		int y = accessor.js$getY() + compass.y - 19;
		return new int[]{x, y};
	}

	private static ItemStack icon() {
		String id = Config.get().worldMenuIcon;
		Item item = Items.WRITABLE_BOOK;
		if (id != null && !id.isBlank()) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				Item resolved = Registries.ITEM.get(identifier);
				if (resolved != Items.AIR) {
					item = resolved;
				}
			}
		}
		return new ItemStack(item);
	}

	public static void render(HandledScreen<?> screen, DrawContext context, int mouseX, int mouseY) {
		int[] box = findBox(screen);
		if (box == null) {
			return;
		}
		int x = box[0];
		int y = box[1];
		context.fill(x, y, x + 18, y + 18, 0x66000000);
		context.drawItem(icon(), x + 1, y + 1);
		boolean hovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
		int color = hovered ? 0xFFFFD966 : 0x803AA0FF;
		context.fill(x, y, x + 18, y + 1, color);
		context.fill(x, y + 17, x + 18, y + 18, color);
		context.fill(x, y, x + 1, y + 18, color);
		context.fill(x + 17, y, x + 18, y + 18, color);
		if (hovered) {
			MinecraftClient client = MinecraftClient.getInstance();
			context.drawTooltip(client.textRenderer, List.of(
					Text.literal("JustScript — меню"),
					Text.literal("ЛКМ — открыть меню мода")), mouseX, mouseY);
		}
	}

	public static boolean click(HandledScreen<?> screen, double mouseX, double mouseY) {
		int[] box = findBox(screen);
		if (box == null) {
			return false;
		}
		if (mouseX < box[0] || mouseX >= box[0] + 18 || mouseY < box[1] || mouseY >= box[1] + 18) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return true;
		}
		client.setScreen(new WorldHomeScreen(screen));
		return true;
	}
}
