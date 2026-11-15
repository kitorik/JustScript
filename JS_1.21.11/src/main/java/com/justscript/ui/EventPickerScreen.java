package com.justscript.ui;

import com.justscript.events.EventCatalog;
import com.justscript.events.EventDefinition;
import com.justscript.script.ScriptParser;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Список событий с поиском: выбираешь событие — в код вставляется заготовка
 * секции для текущего языка (JustMC JSON, Xero или JMCC).
 *
 * <p>Работает и мышью, и клавиатурой: ↑/↓ — выбор, Enter — вставить.
 */
public final class EventPickerScreen extends Screen {
	private static final int ROW_H = 22;

	private final Screen parent;
	private final String scope;
	private final String language;
	private final Consumer<String> onPick;

	private final List<EventDefinition> entries = new ArrayList<>();
	private List<EventDefinition> filtered;
	private TextFieldWidget search;
	private int scroll;
	private int selected;
	private int lastMouseX = -1;
	private int lastMouseY = -1;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public EventPickerScreen(Screen parent, String scope, String language, Consumer<String> onPick) {
		super(Text.literal("События JustScript"));
		this.parent = parent;
		this.scope = scope;
		this.language = language;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		entries.clear();
		entries.addAll(EventCatalog.forUnitKind(scope));
		filtered = null;
		scroll = 0;
		selected = 0;

		this.listW = Math.min(this.width - 40, 460);
		this.listX = (this.width - this.listW) / 2;
		this.listY = 58;
		this.listH = this.height - this.listY - 40;

		this.search = new TextFieldWidget(this.textRenderer, this.listX, 30, this.listW - 90, 20, Text.literal("Поиск"));
		this.search.setPlaceholder(Text.literal("Поиск: клик, урон, join…"));
		this.search.setChangedListener(value -> {
			this.filtered = null;
			this.scroll = 0;
			this.selected = 0;
		});
		this.addDrawableChild(this.search);
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> close())
				.dimensions(this.listX + this.listW - 84, 30, 84, 20).build());
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

	private List<EventDefinition> filtered() {
		if (this.filtered != null) {
			return this.filtered;
		}
		String query = this.search == null ? "" : this.search.getText().trim().toLowerCase(Locale.ROOT);
		List<EventDefinition> result = new ArrayList<>();
		for (EventDefinition definition : entries) {
			if (query.isEmpty()
					|| definition.id().toLowerCase(Locale.ROOT).contains(query)
					|| definition.justmc().toLowerCase(Locale.ROOT).contains(query)
					|| definition.name().toLowerCase(Locale.ROOT).contains(query)) {
				result.add(definition);
			}
		}
		this.filtered = result;
		if (selected >= result.size()) {
			selected = Math.max(0, result.size() - 1);
		}
		return result;
	}

	private int visibleRows() {
		return Math.max(1, this.listH / ROW_H);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		String hint = switch (scope) {
			case "block" -> "события блоков";
			case "entity" -> "события сущностей";
			case "player" -> "события игрока";
			case "function" -> "функции мира";
			case "process" -> "процессы мира";
			default -> "события мира";
		};
		List<EventDefinition> visible = filtered();
		String title = "События: " + hint + " (" + visible.size() + ")";
		context.drawText(this.textRenderer, title, this.listX, 14, 0xFFE8E8E8, false);

		context.fill(this.listX - 4, this.listY - 4, this.listX + this.listW + 4, this.listY + this.listH, 0xFF14171D);

		int rows = visibleRows();
		int maxScroll = Math.max(0, visible.size() - rows);
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
		if (this.selected < this.scroll) {
			this.scroll = this.selected;
		} else if (this.selected >= this.scroll + rows) {
			this.scroll = this.selected - rows + 1;
		}

		boolean overList = mouseX >= this.listX && mouseX <= this.listX + this.listW
				&& mouseY >= this.listY && mouseY < this.listY + this.listH;
		boolean mouseMoved = mouseX != this.lastMouseX || mouseY != this.lastMouseY;
		this.lastMouseX = mouseX;
		this.lastMouseY = mouseY;

		for (int row = 0; row < rows; row++) {
			int index = this.scroll + row;
			if (index >= visible.size()) {
				break;
			}
			EventDefinition definition = visible.get(index);
			int y = this.listY + row * ROW_H;
			boolean hovered = overList && mouseY >= y && mouseY < y + ROW_H;
			if (hovered && mouseMoved) {
				this.selected = index;
			}
			if (index == this.selected) {
				context.fill(this.listX - 4, y, this.listX + this.listW + 4, y + ROW_H, 0x553A86FF);
			}
			context.drawText(this.textRenderer, definition.id(), this.listX, y + 2, 0xFF8AB4F8, false);
			String right = EventCatalog.scopeLabel(definition.scope()) + " • " + definition.name();
			context.drawText(this.textRenderer, right,
					this.listX + this.listW - this.textRenderer.getWidth(right), y + 2, 0xFF9AA4B2, false);
			context.drawText(this.textRenderer, definition.description(), this.listX, y + 12, 0xFF6F7683, false);
		}
		if (visible.isEmpty()) {
			context.drawText(this.textRenderer, "Ничего не найдено", this.listX, this.listY, 0xFF7A8085, false);
		}

		super.render(context, mouseX, mouseY, delta);

		String footer = "ЛКМ / Enter — вставить • ↑↓ — выбор • Esc — назад";
		context.drawText(this.textRenderer, footer, this.listX, this.height - 26, 0xFF6F7683, false);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		if (click.button() == 0 && mouseX >= this.listX - 4 && mouseX <= this.listX + this.listW + 4
				&& mouseY >= this.listY && mouseY < this.listY + this.listH) {
			int row = (int) ((mouseY - this.listY) / ROW_H);
			List<EventDefinition> visible = filtered();
			int index = this.scroll + row;
			if (row >= 0 && index >= 0 && index < visible.size()) {
				this.selected = index;
				pick(visible.get(index));
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX < this.listX - 4 || mouseX > this.listX + this.listW + 4) {
			return false;
		}
		int maxScroll = Math.max(0, filtered().size() - visibleRows());
		this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - (int) Math.signum(verticalAmount) * 3));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (this.search == null || !this.search.isFocused()) {
			List<EventDefinition> visible = filtered();
			switch (input.key()) {
				case GLFW.GLFW_KEY_UP -> {
					this.selected = Math.max(0, this.selected - 1);
					return true;
				}
				case GLFW.GLFW_KEY_DOWN -> {
					this.selected = Math.min(Math.max(0, visible.size() - 1), this.selected + 1);
					return true;
				}
				case GLFW.GLFW_KEY_PAGE_UP -> {
					this.selected = Math.max(0, this.selected - visibleRows());
					return true;
				}
				case GLFW.GLFW_KEY_PAGE_DOWN -> {
					this.selected = Math.min(Math.max(0, visible.size() - 1), this.selected + visibleRows());
					return true;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					if (this.selected >= 0 && this.selected < visible.size()) {
						pick(visible.get(this.selected));
						return true;
					}
				}
				default -> {
				}
			}
		}
		return super.keyPressed(input);
	}

	private void pick(EventDefinition definition) {
		String template;
		if ("xero".equalsIgnoreCase(language)) {
			template = "when " + definition.justmc() + " {\n\n}\n";
		} else if ("jmcc".equalsIgnoreCase(language)) {
			template = "event<" + definition.justmc() + "> {\n\n}\n";
		} else {
			template = ScriptParser.template("event", definition.id());
		}
		onPick.accept(template);
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}
}