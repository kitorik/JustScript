package com.justscript.ui;

import com.justscript.Config;
import com.justscript.ai.AiClient;
import com.justscript.events.EventCatalog;
import com.justscript.util.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Технические настройки: свой пейст, автокнижка, автовозврат, ИИ и служебные
 * кнопки. Открываются из «Настройки всех скриптов».
 */
public final class AdvancedSettingsScreen extends Screen {
	private final Screen parent;
	private int scroll;
	private int maxScroll;

	public AdvancedSettingsScreen(Screen parent) {
		super(Component.literal("JustScript — дополнительно"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rebuild();
	}

	@Override
	protected void repositionElements() {
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		Config config = Config.get();
		int panelW = Math.min(this.width - 40, 460);
		int x = (this.width - panelW) / 2;
		int top = 46 - this.scroll;
		int half = (panelW - 6) / 2;
		int right = x + half + 6;
		int rowY = top;

		textField(x, rowY, panelW, "свой URL загрузки (провайдер custom)", config.customUploadUrl,
				value -> config.customUploadUrl = value);
		rowY += 24;

		addRenderableWidget(toggle(x, rowY, half, "Автокнижка", () -> config.autoConfirmBook,
				value -> config.autoConfirmBook = value));
		addRenderableWidget(toggle(right, rowY, half, "Автовозврат /jointo", () -> config.autoReturn,
				value -> config.autoReturn = value));
		rowY += 24;

		addRenderableWidget(aiProviderButton(x, rowY, half));
		addRenderableWidget(Button.builder(Component.literal("Проверить ИИ"), button -> testAi())
				.bounds(right, rowY, half, 20).build());
		rowY += 24;

		addRenderableWidget(toggle(x, rowY, half, "ИИ: спрашивать разрешение",
				() -> config.aiAskPermission, value -> config.aiAskPermission = value));
		addRenderableWidget(Button.builder(Component.literal("ИИ: сбросить «Разр. всегда»"), button -> {
			config.aiResetPermissions();
			rebuild();
		}).bounds(right, rowY, half, 20).build());
		rowY += 24;

		if (config.aiCustom()) {
			textField(x, rowY, panelW, "ИИ: адрес агрегатора (…/v1 или полный /chat/completions)",
					config.aiCustomUrl, value -> config.aiCustomUrl = value);
			rowY += 20;
			textField(x, rowY, panelW, "ИИ: модель (напр. gpt-4o-mini)", config.aiCustomModel,
					value -> config.aiCustomModel = value);
			rowY += 20;
			textField(x, rowY, panelW, "ИИ: ключ (пусто — без ключа; хранится локально)", config.aiCustomKey,
					value -> config.aiCustomKey = value);
			rowY += 24;
		} else {
			textField(x, rowY, panelW, "ИИ: адрес бесплатной модели", config.aiFreeUrl,
					value -> config.aiFreeUrl = value);
			rowY += 20;
			textField(x, rowY, panelW, "ИИ: модель (openai)", config.aiFreeModel,
					value -> config.aiFreeModel = value);
			rowY += 24;
			addRenderableWidget(toggle(x, rowY, half, "Запасные облака", () -> config.aiFallbackClouds,
					value -> config.aiFallbackClouds = value));
			addRenderableWidget(toggle(right, rowY, half, "Ollama локально", () -> config.aiOllama,
					value -> config.aiOllama = value));
			rowY += 24;
		}

		// --- 3D-редактор: цвет и толщина подсветки, скорость полёта, сетка ---
		textField(x, rowY, panelW, "3D: цвет подсветки (#RRGGBB, напр. #4FC3F7)",
				config.editor3dHighlight, value -> config.editor3dHighlight = value);
		rowY += 20;
		addRenderableWidget(Button.builder(
						Component.literal("3D: линии " + formatWidth(config.editor3dOutlineWidth)),
						button -> {
							config.editor3dOutlineWidth = nextWidth(config.editor3dOutlineWidth);
							Config.save();
							rebuild();
						}).bounds(x, rowY, half, 20).build());
		addRenderableWidget(toggle(right, rowY, half, "3D-сетка 3×3", () -> config.editor3dGrid,
				value -> config.editor3dGrid = value));
		rowY += 24;
		addRenderableWidget(Button.builder(
						Component.literal("3D: скорость " + String.format("%.1f", config.editor3dSpeed)),
						button -> {
							config.editor3dSpeed = nextSpeed(config.editor3dSpeed);
							Config.save();
							rebuild();
						}).bounds(x, rowY, half, 20).build());
		addRenderableWidget(toggle(right, rowY, half, "3D: привязка к сетке", () -> config.editor3dSnap,
				value -> config.editor3dSnap = value));
		rowY += 24;
		addRenderableWidget(Button.builder(
						Component.literal("3D: шаг привязки " + formatSnap(config.editor3dSnapStep)),
						button -> {
							config.editor3dSnapStep = nextSnap(config.editor3dSnapStep);
							Config.save();
							rebuild();
						}).bounds(x, rowY, half, 20).build());
		addRenderableWidget(toggle(right, rowY, half, "3D: мировые оси (иначе локальные)",
				() -> config.editor3dGlobalAxes, value -> config.editor3dGlobalAxes = value));
		rowY += 24;
		addRenderableWidget(Button.builder(Component.literal("3D: сбросить цвет и линии"), button -> {
			config.editor3dHighlight = "#4FC3F7";
			config.editor3dOutlineWidth = 2.0f;
			config.editor3dSpeed = 0.6f;
			config.editor3dGrid = true;
			config.editor3dSnap = true;
			config.editor3dSnapStep = 0.25;
			config.editor3dGlobalAxes = true;
			Config.save();
			rebuild();
		}).bounds(right, rowY, half, 20).build());
		rowY += 24;

		addRenderableWidget(Button.builder(Component.literal("Экспорт каталога событий"),
				button -> exportCatalog()).bounds(x, rowY, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Порядок скриптов…"),
				button -> openOrder()).bounds(right, rowY, half, 20).build());
		rowY += 24;

		this.maxScroll = Math.max(0, (rowY + 26) - this.height + 8);
		this.scroll = Math.min(scroll, maxScroll);

		int footerY = this.height - 26;
		addRenderableWidget(Button.builder(Component.literal("Готово"), button -> onClose())
				.bounds(x + panelW / 2 - 60, footerY, 120, 20).build());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (maxScroll <= 0) {
			return false;
		}
		int next = (int) Math.round(scroll - verticalAmount * 18);
		int clamped = Math.max(0, Math.min(maxScroll, next));
		if (clamped == scroll) {
			return false;
		}
		scroll = clamped;
		rebuild();
		return true;
	}

	private EditBox textField(int x, int y, int width, String placeholder, String value,
			Consumer<String> setter) {
		EditBox field = new EditBox(this.font, x, y, width, 18,
				Component.literal(placeholder));
		field.setMaxLength(512);
		field.setValue(value == null ? "" : value);
		field.setHint(Component.literal(placeholder));
		field.setResponder(setter);
		this.addRenderableWidget(field);
		return field;
	}

	private Button toggle(int x, int y, int width, String label, java.util.function.Supplier<Boolean> getter,
			Consumer<Boolean> setter) {
		return Button.builder(Component.literal(label + ": " + (getter.get() ? "вкл" : "выкл")), button -> {
			setter.accept(!getter.get());
			button.setMessage(Component.literal(label + ": " + (getter.get() ? "вкл" : "выкл")));
			Config.save();
		}).bounds(x, y, width, 20).build();
	}

	/** Толщина линий подсветки перебирается по кругу. */
	private static float nextWidth(float current) {
		float[] values = {1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f};
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(values[i] - current) < 0.01f) {
				return values[(i + 1) % values.length];
			}
		}
		return 2.0f;
	}

	private static String formatWidth(float width) {
		return String.format("%.1f", width);
	}

	/** Скорость полёта камеры в 3D-редакторе. */
	private static float nextSpeed(float current) {
		float[] values = {0.3f, 0.6f, 1.0f, 1.5f, 2.0f, 3.0f};
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(values[i] - current) < 0.01f) {
				return values[(i + 1) % values.length];
			}
		}
		return 0.6f;
	}

	/** Шаг привязки в 3D-редакторе: 1/16 → 1/8 → 1/4 → 1/2 → 1. */
	private static double nextSnap(double current) {
		double[] values = {0.0625, 0.125, 0.25, 0.5, 1.0};
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(values[i] - current) < 1e-6) {
				return values[(i + 1) % values.length];
			}
		}
		return 0.25;
	}

	private static String formatSnap(double step) {
		if (Math.abs(step - 0.0625) < 1e-6) {
			return "1/16";
		}
		if (Math.abs(step - 0.125) < 1e-6) {
			return "1/8";
		}
		if (Math.abs(step - 0.25) < 1e-6) {
			return "1/4";
		}
		if (Math.abs(step - 0.5) < 1e-6) {
			return "1/2";
		}
		return "1";
	}

	private Button aiProviderButton(int x, int y, int width) {		Config config = Config.get();
		return Button.builder(Component.literal("ИИ: " + (config.aiCustom() ? "свой" : "бесплатный")),
				button -> {
					config.aiProvider = config.aiCustom() ? "free" : "custom";
					Config.save();
					rebuild();
				}).bounds(x, y, width, 20).build();
	}

	private void testAi() {
		Config.save();
		if (!AiClient.isConfigured()) {
			Chat.warn("ИИ не настроен");
			return;
		}
		Chat.info("Проверяю ИИ…");
		AiClient.generate("Ты — тестовый помощник. Отвечай одним словом.", "Ответь: OK", (code, error) -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> {
				if (error != null) {
					Chat.error("ИИ: " + error);
				} else {
					String line = code.replace('\n', ' ').trim();
					Chat.ok("ИИ отвечает: " + (line.length() > 80 ? line.substring(0, 80) + "…" : line));
				}
			});
		});
	}

	private void exportCatalog() {
		try {
			Path path = Config.rootDir().resolve("events-catalog.json");
			EventCatalog.export(path);
			Chat.ok("Каталог событий выгружен: " + path);
		} catch (Exception e) {
			Chat.error("Не удалось выгрузить каталог: " + e.getMessage());
		}
	}

	private void openOrder() {
		Config.save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(new ScriptOrderScreen(this));
		}
	}

	@Override
	public void onClose() {
		Config.save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Дополнительные настройки", 12, 10, 0xFFE8E8E8, false);
		context.text(this.font, "Для обычной работы хватает основных настроек",
				12, 24, 0xFF9AA4B2, false);

		if (maxScroll > 0) {
			int trackX = this.width - 6;
			int trackTop = 40;
			int trackHeight = this.height - 76;
			context.fill(trackX, trackTop, trackX + 3, trackTop + trackHeight, 0x40FFFFFF);
			int knobHeight = Math.max(16, trackHeight * trackHeight / (trackHeight + maxScroll));
			int knobTop = trackTop + (trackHeight - knobHeight) * scroll / maxScroll;
			context.fill(trackX, knobTop, trackX + 3, knobTop + knobHeight, 0xC0FFFFFF);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
	}
}