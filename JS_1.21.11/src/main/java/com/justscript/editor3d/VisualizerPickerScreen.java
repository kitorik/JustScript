package com.justscript.editor3d;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Инвентарь 3D-редактора: все ванильные блоки или предметы в виде сетки
 * с поиском. Выбор сразу ставит визуализатор перед камерой и возвращает
 * в редактор.
 */
public final class VisualizerPickerScreen extends Screen {
	private static final int SLOT = 22;
	private static final int MARGIN = 20;
	private static final int HEADER = 80;

	private static final int PANEL = 0xF00B0D10;
	private static final int SLOT_BG = 0xFF14181E;
	private static final int SLOT_HOVER = 0xFF232C37;
	private static final int EDGE = 0x33FFFFFF;
	private static final int TEXT = 0xFFE6EDF5;
	private static final int MUTED = 0xFF93A0B0;
	private static final int ACCENT = 0xFF63D2FF;

	private final Editor3DScreen parent;
	private final boolean blocksOnly;
	private final List<ItemStack> all = new ArrayList<>();
	private final List<ItemStack> shown = new ArrayList<>();
	private String query = "";
	private int scroll;

	public VisualizerPickerScreen(Editor3DScreen parent, boolean blocksOnly) {
		super(Text.literal(blocksOnly ? "JustScript — блоки" : "JustScript — предметы"));
		this.parent = parent;
		this.blocksOnly = blocksOnly;
	}

	@Override
	protected void init() {
		if (this.all.isEmpty()) {
			for (Item item : Registries.ITEM) {
				if (this.blocksOnly && !(item instanceof BlockItem)) {
					continue;
				}
				this.all.add(new ItemStack(item));
			}
			this.all.sort(Comparator.comparing(stack -> Registries.ITEM.getId(stack.getItem()).toString()));
		}
		applyFilter();
		this.scroll = 0;

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> back())
				.dimensions(this.width - MARGIN - 60, 7, 60, 20).build());
	}

	private void back() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.setScreen(this.parent);
		}
	}

	private void applyFilter() {
		this.shown.clear();
		String needle = this.query.trim().toLowerCase(Locale.ROOT);
		for (ItemStack stack : this.all) {
			if (needle.isEmpty()) {
				this.shown.add(stack);
				continue;
			}
			String name = stack.getName().getString().toLowerCase(Locale.ROOT);
			String id = Registries.ITEM.getId(stack.getItem()).toString().toLowerCase(Locale.ROOT);
			if (name.contains(needle) || id.contains(needle)) {
				this.shown.add(stack);
			}
		}
		this.scroll = 0;
	}

	private int columns() {
		return Math.max(1, (this.width - MARGIN * 2) / SLOT);
	}

	private int rowsVisible() {
		return Math.max(1, (this.height - HEADER - 36) / SLOT);
	}

	private int maxScroll() {
		int rows = (this.shown.size() + columns() - 1) / columns();
		return Math.max(0, rows - rowsVisible());
	}

	private int indexAt(double mouseX, double mouseY) {
		if (mouseX < MARGIN || mouseY < HEADER || mouseY >= this.height - 36) {
			return -1;
		}
		int column = (int) ((mouseX - MARGIN) / SLOT);
		int row = (int) ((mouseY - HEADER) / SLOT) + this.scroll;
		if (column < 0 || column >= columns() || row < 0) {
			return -1;
		}
		int index = row * columns() + column;
		return index < this.shown.size() ? index : -1;
	}

	private void choose(ItemStack stack) {
		Identifier id = Registries.ITEM.getId(stack.getItem());
		this.parent.placeFromPicker(this.blocksOnly ? "block" : "item", id.toString());
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.setScreen(this.parent);
		}
	}

	// ------------------------------------------------------------- ввод

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		if (click.button() == 0) {
			int index = indexAt(click.x(), click.y());
			if (index >= 0) {
				choose(this.shown.get(index));
			}
			return true;
		}
		if (click.button() == 1) {
			back();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int next = this.scroll - (int) Math.signum(vertical) * 2;
		this.scroll = Math.max(0, Math.min(maxScroll(), next));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			back();
			return true;
		}
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
			if (!this.query.isEmpty()) {
				this.query = this.query.substring(0, this.query.length() - 1);
				applyFilter();
			}
			return true;
		}
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			if (!this.shown.isEmpty()) {
				choose(this.shown.get(0));
			}
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (input.isValidChar()) {
			this.query += input.asString();
			applyFilter();
			return true;
		}
		return super.charTyped(input);
	}

	// ------------------------------------------------------------- отрисовка

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xC00B0D11);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		context.fill(0, 0, this.width, 34, PANEL);
		context.fill(0, 33, this.width, 34, EDGE);
		context.drawText(this.textRenderer, this.blocksOnly ? "Блоки" : "Предметы", MARGIN, 12, TEXT, false);
		context.drawText(this.textRenderer, "ЛКМ — поставить · ПКМ — назад · колесо — прокрутка",
				MARGIN + 70, 12, MUTED, false);

		int searchY = 44;
		int searchW = this.width - MARGIN * 2;
		context.fill(MARGIN, searchY, MARGIN + searchW, searchY + 20, 0xFF14181E);
		context.fill(MARGIN, searchY + 19, MARGIN + searchW, searchY + 20,
				this.query.isEmpty() ? EDGE : ACCENT);
		String text = this.query.isEmpty() ? "Поиск: название или id…" : this.query;
		context.drawText(this.textRenderer, text, MARGIN + 6, searchY + 6,
				this.query.isEmpty() ? 0xFF6B7480 : TEXT, false);
		if (!this.query.isEmpty()) {
			int caret = MARGIN + 7 + this.textRenderer.getWidth(this.query);
			context.fill(caret, searchY + 4, caret + 1, searchY + 16, ACCENT);
		}

		context.enableScissor(MARGIN, HEADER, this.width - MARGIN, this.height - 36);
		int columns = columns();
		int start = this.scroll * columns;
		int end = Math.min(this.shown.size(), start + columns * rowsVisible() + columns);
		for (int index = start; index < end; index++) {
			int cell = index - start;
			int column = cell % columns;
			int row = cell / columns;
			int x = MARGIN + column * SLOT;
			int y = HEADER + row * SLOT;
			boolean hover = mouseX >= x && mouseX < x + SLOT - 2
					&& mouseY >= y && mouseY < y + SLOT - 2;
			context.fill(x, y, x + SLOT - 2, y + SLOT - 2, hover ? SLOT_HOVER : SLOT_BG);
			context.drawItem(this.shown.get(index), x + 3, y + 3);
			if (hover) {
				context.fill(x, y, x + SLOT - 2, y + 1, ACCENT);
				context.fill(x, y + SLOT - 3, x + SLOT - 2, y + SLOT - 2, ACCENT);
				context.fill(x, y, x + 1, y + SLOT - 2, ACCENT);
				context.fill(x + SLOT - 3, y, x + SLOT - 2, y + SLOT - 2, ACCENT);
			}
		}
		context.disableScissor();

		// ползунок
		int trackTop = HEADER;
		int trackBottom = this.height - 36;
		if (maxScroll() > 0) {
			int trackX = this.width - MARGIN + 6;
			context.fill(trackX, trackTop, trackX + 3, trackBottom, 0x33FFFFFF);
			double ratio = (double) this.scroll / maxScroll();
			int knobY = trackTop + (int) ((trackBottom - trackTop - 24) * ratio);
			context.fill(trackX - 1, knobY, trackX + 4, knobY + 24, ACCENT);
		}

		String footer = this.shown.size() + " из " + this.all.size()
				+ (this.query.isEmpty() ? "" : " · фильтр: " + this.query);
		context.drawText(this.textRenderer, footer, MARGIN, this.height - 26, MUTED, false);
		context.drawText(this.textRenderer, "Esc — назад в редактор", this.width - MARGIN
				- this.textRenderer.getWidth("Esc — назад в редактор"), this.height - 26, 0xFF7D8794, false);

		int index = indexAt(mouseX, mouseY);
		if (index >= 0) {
			String name = this.shown.get(index).getName().getString();
			String id = Registries.ITEM.getId(this.shown.get(index).getItem()).toString();
			int tipW = Math.max(this.textRenderer.getWidth(name), this.textRenderer.getWidth(id)) + 10;
			int tipX = Math.min(this.width - tipW - 4, mouseX + 12);
			int tipY = Math.max(4, mouseY - 26);
			context.fill(tipX, tipY, tipX + tipW, tipY + 26, 0xF0101317);
			context.drawText(this.textRenderer, name, tipX + 5, tipY + 4, TEXT, false);
			context.drawText(this.textRenderer, id, tipX + 5, tipY + 14, MUTED, false);
		}
	}

	@Override
	public void close() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.setScreen(this.parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
