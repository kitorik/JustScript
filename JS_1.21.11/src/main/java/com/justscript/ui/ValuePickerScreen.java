package com.justscript.ui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Справочник значений: текст, число, формула, список, локация, предмет и т.д.
 * Клик вставляет готовый фрагмент в код на место курсора.
 */
public final class ValuePickerScreen extends Screen {
	private record ValueEntry(String title, String hint, String snippet) {
	}

	private final Screen parent;
	private final String language;
	private final Consumer<String> onPick;
	private final List<ValueEntry> entries = new ArrayList<>();

	private int scroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public ValuePickerScreen(Screen parent, String language, Consumer<String> onPick) {
		super(Text.literal("JustScript — значения"));
		this.parent = parent;
		this.language = language == null ? "justmc-json" : language;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		entries.clear();
		if ("xero".equalsIgnoreCase(language)) {
			addXero();
		} else {
			addJson();
		}
		this.listW = Math.min(this.width - 40, 560);
		this.listX = (this.width - this.listW) / 2;
		this.listY = 40;
		this.listH = this.height - this.listY - 30;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> close())
				.dimensions(this.width - 96, 10, 80, 20).build());
	}

	private void addJson() {
		entries.add(new ValueEntry("Текст", "plain — без цветов",
				"{ \"type\": \"text\", \"text\": \"текст\", \"parsing\": \"plain\" }"));
		entries.add(new ValueEntry("Цветной текст", "legacy — цвета через &",
				"{ \"type\": \"text\", \"text\": \"&aтекст\", \"parsing\": \"legacy\" }"));
		entries.add(new ValueEntry("Число", "целое или дробное", "{ \"type\": \"number\", \"number\": 1 }"));
		entries.add(new ValueEntry("Формула", "%math() внутри number",
				"{ \"type\": \"number\", \"number\": \"%math(1 + 1)\" }"));
		entries.add(new ValueEntry("Список", "array", "{ \"type\": \"array\", \"values\": [] }"));
		entries.add(new ValueEntry("Словарь", "map", "{ \"type\": \"map\", \"values\": {} }"));
		entries.add(new ValueEntry("Местоположение", "location",
				"{ \"type\": \"location\", \"x\": 0, \"y\": 64, \"z\": 0, \"yaw\": 0, \"pitch\": 0 }"));
		entries.add(new ValueEntry("Вектор", "vector", "{ \"type\": \"vector\", \"x\": 0, \"y\": 1, \"z\": 0 }"));
		entries.add(new ValueEntry("Предмет", "item — NBT-строка",
				"{ \"type\": \"item\", \"item\": \"{Count:1b,id:\\\"minecraft:diamond\\\"}\" }"));
		entries.add(new ValueEntry("Перечисление", "enum",
				"{ \"type\": \"enum\", \"enum\": \"SPACES\" }"));
		entries.add(new ValueEntry("Игровое значение", "game_value",
				"{ \"type\": \"game_value\", \"game_value\": \"event_player\", \"selection\": \"null\" }"));
		entries.add(new ValueEntry("Переменная", "variable: line/local/game/save",
				"{ \"type\": \"variable\", \"variable\": \"x\", \"scope\": \"game\" }"));
		entries.add(new ValueEntry("Звук", "sound",
				"{ \"type\": \"sound\", \"sound\": \"minecraft:entity.experience_orb.pickup\", \"pitch\": 1.0, \"volume\": 1.0 }"));
	}

	private void addXero() {
		entries.add(new ValueEntry("Текст", "\"текст\", цвета через &", "\"текст\""));
		entries.add(new ValueEntry("MiniMessage-текст", "mm\"…\"", "mm\"<red>текст</red>\""));
		entries.add(new ValueEntry("Число", "5, -0.5", "5"));
		entries.add(new ValueEntry("Истина / ложь", "true / false", "true"));
		entries.add(new ValueEntry("Формула", "${…} или выражение", "2 + 2"));
		entries.add(new ValueEntry("Местоположение", "loc(x, y, z[, yaw, pitch])", "loc(0, 64, 0)"));
		entries.add(new ValueEntry("Вектор", "vec(x, y, z)", "vec(0, 1, 0)"));
		entries.add(new ValueEntry("Предмет", "item(…)", "item(diamond, 1)"));
		entries.add(new ValueEntry("Список", "[…]", "[1, 2, 3]"));
		entries.add(new ValueEntry("Словарь", "{…}", "{\"hp\": 20}"));
		entries.add(new ValueEntry("Звук", "sound(…)", "sound(entity.player.levelup, volume: 1, pitch: 2)"));
		entries.add(new ValueEntry("Игровое значение", "value.*", "value.current_health"));
		entries.add(new ValueEntry("Подстановка", "${x} в тексте", "\"Счёт: ${x}\""));
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

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.drawText(this.textRenderer, "Значения (" + ("xero".equalsIgnoreCase(language) ? "Xero" : "JSON")
				+ ") — клик вставит фрагмент", this.listX, 14, 0xFFE8E8E8, false);
		context.fill(this.listX - 4, this.listY - 4, this.listX + this.listW + 4, this.listY + this.listH,
				0xFF14171D);

		int rowHeight = 24;
		int rows = Math.max(1, this.listH / rowHeight);
		this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, entries.size() - rows)));
		int shown = 0;
		for (int i = 0; i < entries.size() && shown < rows; i++) {
			int index = i + this.scroll;
			if (index >= entries.size()) {
				break;
			}
			ValueEntry entry = entries.get(index);
			int y = this.listY + shown * rowHeight;
			boolean hovered = mouseX >= this.listX - 4 && mouseX <= this.listX + this.listW + 4
					&& mouseY >= y && mouseY < y + rowHeight;
			if (hovered) {
				context.fill(this.listX - 4, y, this.listX + this.listW + 4, y + rowHeight, 0x553A86FF);
			}
			context.drawText(this.textRenderer, entry.title(), this.listX, y + 2, 0xFF8AB4F8, false);
			String hint = entry.hint();
			context.drawText(this.textRenderer, hint,
					this.listX + this.listW - this.textRenderer.getWidth(hint), y + 2, 0xFF6F7683, false);
			String snippet = entry.snippet();
			int maxWidth = this.listW - 8;
			if (this.textRenderer.getWidth(snippet) > maxWidth) {
				while (!snippet.isEmpty()
						&& this.textRenderer.getWidth(snippet + "…") > maxWidth) {
					snippet = snippet.substring(0, snippet.length() - 1);
				}
				snippet = snippet + "…";
			}
			context.drawText(this.textRenderer, snippet, this.listX, y + 12, 0xFFD4D4D4, false);
			shown++;
		}

		super.render(context, mouseX, mouseY, delta);
		context.drawText(this.textRenderer, "колесо — прокрутка • Esc — назад", this.listX, this.height - 18,
				0xFF6F7683, false);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		if (click.button() == 0 && mouseX >= this.listX - 4 && mouseX <= this.listX + this.listW + 4
				&& mouseY >= this.listY && mouseY < this.listY + this.listH) {
			int row = (int) ((mouseY - this.listY) / 24);
			int index = this.scroll + row;
			if (index >= 0 && index < entries.size()) {
				onPick.accept(entries.get(index).snippet());
				if (this.client != null) {
					this.client.setScreen(parent);
				}
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		this.scroll = Math.max(0, this.scroll - (int) Math.signum(verticalAmount) * 2);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_ENTER && !entries.isEmpty()) {
			onPick.accept(entries.get(0).snippet());
			if (this.client != null) {
				this.client.setScreen(parent);
			}
			return true;
		}
		return super.keyPressed(input);
	}
}