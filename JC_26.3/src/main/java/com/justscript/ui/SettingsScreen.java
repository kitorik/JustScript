package com.justscript.ui;

import com.justscript.Config;
import com.justscript.input.Keybinds;
import com.justscript.script.CodeLanguage;
import com.justscript.util.Chat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Настройки всех скриптов проекта — «на поверхности», без команд и глубоких
 * переходов. Открываются из единого меню (J) и из меню мира.
 *
 * <p>Здесь только повседневное: название модуля, куда сохранять, клавиши,
 * языки, подсветка и память (сколько копий скрипта хранить).
 * Технические поля — в «Дополнительно…».
 */
public final class SettingsScreen extends Screen {
	private static final int PANEL = 0xFF14171D;
	private static final int PANEL_EDGE = 0xFF2A303C;
	private static final int MUTED = 0xFF9AA4B2;
	private static final int TOOLTIP_DELAY_MS = 900;
	/** Высота строки языка: 24 px при GUI scale 2 ≈ 1.25 см. */
	private static final int LANG_ROW_H = 24;
	/** Видно полторы строки — как просили. */
	private static final int LANG_VIEW_H = LANG_ROW_H * 3 / 2;
	private static final int LANG_W = 178;
	private static final int LANG_SCROLLBAR_W = 5;

	private static final List<String> PROVIDERS =
			List.of("auto", "paste.c-net.org", "dpaste.com", "tmpfiles.org", "custom");
	/** Сколько копий скрипта можно хранить. */
	private static final int[] HISTORY_LIMITS = {0, 5, 10, 20, 50, 100};

	private final Screen parent;
	private final Map<Button, String> tooltips = new HashMap<>();
	private Button hoveredTooltipButton;
	private Button memoryButton;
	private long hoverStarted;
	private String status = "";
	private int statusColor = MUTED;

	private int panelX;
	private int panelW;
	private int panelTop;
	private int panelBottom;

	// всплывающее меню языков: открывается у места клика
	private boolean languagePopup;
	private int langX;
	private int langY;
	private int langW;
	private int langH;
	private int langScroll;
	private boolean draggingLangScroll;
	private int lastClickX = -1;
	private int lastClickY = -1;

	public SettingsScreen(Screen parent) {
		super(Component.literal("JustScript — настройки всех скриптов"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.tooltips.clear();
		Config config = Config.get();
		this.panelW = Math.min(this.width - 40, 460);
		this.panelX = (this.width - this.panelW) / 2;
		int rowCount = 6;
		this.panelTop = Math.max(38, (this.height - rowCount * 22) / 2);
		this.panelBottom = this.panelTop + rowCount * 22 + 8;

		int x = this.panelX;
		int half = (this.panelW - 6) / 2;
		int right = x + half + 6;
		int y = this.panelTop;

		addRenderableWidget(button("Название модуля: " + moduleNameLabel(), x, y, this.panelW,
				button -> openModuleBook()));
		y += 22;

		addRenderableWidget(button("Куда сохранить: " + providerLabel(), x, y, this.panelW,
				button -> {
					cycleProvider();
					button.setMessage(Component.literal("Куда сохранить: " + providerLabel()));
				}));
		y += 22;

		addRenderableWidget(button("Клавиши вызова: " + Keybinds.describeMenu(), x, y, this.panelW,
				button -> openKeybinds()));
		y += 22;

		addRenderableWidget(button("Языки: " + CodeLanguage.byId(Config.get().language).displayName(),
				x, y, half, button -> openLanguages()));
		addRenderableWidget(toggle("Подсветка", right, y, half, () -> config.syntaxHighlight,
				value -> config.syntaxHighlight = value, null));
		y += 22;

		this.memoryButton = button(memoryButtonLabel(), x, y, half, button -> {
			cycleHistory();
			button.setMessage(Component.literal(memoryButtonLabel()));
		});
		this.tooltips.put(this.memoryButton, "Сколько копий скрипта хранить. Крути колесо мыши над кнопкой — "
				+ "число меняется точно (Shift — по 10). В редакторе «История…» откатывает к выбранному "
				+ "моменту, а Ctrl+Shift+K удаляет строку или выделение.");
		addRenderableWidget(this.memoryButton);
		addRenderableWidget(toggle("Ошибки кратко", right, y, half, () -> config.shortErrors,
				value -> config.shortErrors = value,
				"Одна строка вместо длинного списка при ошибке сборки или загрузки."));
		y += 22;

		addRenderableWidget(button("Дополнительно…", x, y, this.panelW, button -> openAdvanced()));

		addRenderableWidget(Button.builder(Component.literal("Готово"), button -> onClose())
				.bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
	}

	private Button button(String label, int x, int y, int width, Consumer<Button> action) {
		return Button.builder(Component.literal(label), button -> action.accept(button))
				.bounds(x, y, width, 20).build();
	}

	private Button toggle(String label, int x, int y, int width, Supplier<Boolean> getter,
			Consumer<Boolean> setter, String tooltip) {
		Button button = Button.builder(
				Component.literal(label + ": " + (getter.get() ? "вкл" : "выкл")), b -> {
					setter.accept(!getter.get());
					b.setMessage(Component.literal(label + ": " + (getter.get() ? "вкл" : "выкл")));
					Config.save();
				}).bounds(x, y, width, 20).build();
		if (tooltip != null) {
			this.tooltips.put(button, tooltip);
		}
		return button;
	}

	private String moduleNameLabel() {
		String name = Config.get().moduleName;
		return name == null || name.isBlank() ? "%player%_script" : name;
	}

	private String providerLabel() {
		String value = Config.get().uploadProvider;
		if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
			return "авто (несколько пейстов)";
		}
		if ("custom".equalsIgnoreCase(value)) {
			return "свой URL";
		}
		return value;
	}

	private String memoryLabel() {
		int limit = Math.max(0, Config.get().historyLimit);
		if (limit == 0) {
			return "не хранить";
		}
		return limit + " " + copiesWord(limit);
	}

	private String memoryButtonLabel() {
		return "Память: " + memoryLabel() + " · колесо";
	}

	private static String copiesWord(int count) {
		int tail = count % 100;
		if (tail >= 11 && tail <= 14) {
			return "копий";
		}
		return switch (count % 10) {
			case 1 -> "копия";
			case 2, 3, 4 -> "копии";
			default -> "копий";
		};
	}

	private void cycleProvider() {
		Config config = Config.get();
		int index = PROVIDERS.indexOf(config.uploadProvider);
		config.uploadProvider = PROVIDERS.get((index + 1) % PROVIDERS.size());
		Config.save();
	}

	/** Следующий лимит памяти кликом: 0 → 5 → 10 → 20 → 50 → 100 → 0. */
	private void cycleHistory() {
		int current = Math.max(0, Config.get().historyLimit);
		int next = HISTORY_LIMITS[0];
		for (int limit : HISTORY_LIMITS) {
			if (limit > current) {
				next = limit;
				break;
			}
		}
		setHistory(next);
	}

	/** Точное число копий: колесо мыши меняет на 1, Shift — на 10. */
	private void setHistory(int value) {
		int next = Math.max(0, Math.min(100, value));
		Config config = Config.get();
		config.historyLimit = next;
		Config.save();
		if (next == 0) {
			status = "Копии больше не сохраняются";
			statusColor = 0xFFFFC94D;
		} else {
			status = "Буду хранить " + next + " " + copiesWord(next) + " каждого скрипта";
			statusColor = 0xFF4ADE80;
		}
	}

	private void openModuleBook() {
		if (this.minecraft != null) {
			Config.save();
			this.minecraft.setScreenAndShow(new ModuleBookScreen(this));
		}
	}

	private void openKeybinds() {
		if (this.minecraft == null) {
			return;
		}
		Chat.info("Клавиши мода: Управление → Назначение клавиш → JustScript");
		this.minecraft.setScreenAndShow(new KeyBindsScreen(this, this.minecraft.options));
	}

	/**
	 * Языки: не отдельный экран, а всплывающий список текстовых кнопок.
	 * Открывается чуть правее клика; если справа нет места — слева.
	 * Видно полторы строки, справа — вертикальный ползунок.
	 */
	private void openLanguages() {
		this.languagePopup = true;
		this.langScroll = 0;
		this.draggingLangScroll = false;
		updateLanguagePopupBounds();
	}

	private List<CodeLanguage> languages() {
		return CodeLanguage.all();
	}

	private int langContentHeight() {
		return languages().size() * LANG_ROW_H;
	}

	private int langViewHeight() {
		return Math.min(LANG_VIEW_H, langContentHeight());
	}

	private int maxLangScroll() {
		return Math.max(0, langContentHeight() - langViewHeight());
	}

	private void updateLanguagePopupBounds() {
		this.langW = Math.min(LANG_W, this.width - 8);
		int viewH = langViewHeight();
		this.langH = viewH + 4;
		int baseX = this.lastClickX >= 0 ? this.lastClickX : this.panelX + this.panelW / 2;
		int baseY = this.lastClickY >= 0 ? this.lastClickY : this.height / 2;
		// изначально вправо от клика, при нехватке места — влево
		int x = baseX + 8;
		if (x + this.langW > this.width - 4) {
			x = baseX - this.langW - 8;
		}
		this.langX = Math.max(4, Math.min(x, this.width - this.langW - 4));
		this.langY = Math.max(4, Math.min(baseY - 4, this.height - this.langH - 4));
		this.langScroll = clampLangScroll(this.langScroll);
	}

	private int clampLangScroll(int value) {
		return Math.max(0, Math.min(value, maxLangScroll()));
	}

	private boolean insideLangPopup(double mouseX, double mouseY) {
		return languagePopup && mouseX >= langX && mouseX < langX + langW
				&& mouseY >= langY && mouseY < langY + langH;
	}

	private void renderLanguagePopup(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		if (!languagePopup) {
			return;
		}
		// рамка и фон
		context.fill(langX, langY, langX + langW, langY + langH, 0xFF3A4050);
		context.fill(langX + 1, langY + 1, langX + langW - 1, langY + langH - 1, 0xFF11151B);

		int viewX = langX + 2;
		int viewY = langY + 2;
		int viewW = langW - 4 - LANG_SCROLLBAR_W;
		int viewH = langH - 4;

		context.enableScissor(viewX, viewY, viewX + viewW, viewY + viewH);
		List<CodeLanguage> list = languages();
		for (int i = 0; i < list.size(); i++) {
			CodeLanguage language = list.get(i);
			int rowY = viewY + i * LANG_ROW_H - langScroll;
			if (rowY + LANG_ROW_H < viewY || rowY > viewY + viewH) {
				continue;
			}
			boolean current = language.id().equalsIgnoreCase(Config.get().language);
			boolean hovered = mouseX >= viewX && mouseX < viewX + viewW
					&& mouseY >= Math.max(rowY, viewY) && mouseY < Math.min(rowY + LANG_ROW_H, viewY + viewH);
			if (hovered) {
				context.fill(viewX, rowY, viewX + viewW, rowY + LANG_ROW_H - 1, 0x332F6FDB);
			}
			if (current) {
				context.fill(viewX, rowY, viewX + 2, rowY + LANG_ROW_H - 1, 0xFF7CDB4C);
			}
			String prefix = current ? "✓ " : "  ";
			int textY = rowY + (LANG_ROW_H - 8) / 2;
			context.text(this.font, prefix + language.displayName(), viewX + 8, textY,
					current ? 0xFF9CF07A : 0xFFD4D4D4, false);
		}
		context.disableScissor();

		renderLangScrollbar(context, mouseX, mouseY);

		// тонкая подсветка рамки поверх содержимого
		context.fill(langX, langY, langX + langW, langY + 1, 0xFF3A4050);
		context.fill(langX, langY + langH - 1, langX + langW, langY + langH, 0xFF3A4050);
		context.fill(langX, langY, langX + 1, langY + langH, 0xFF3A4050);
		context.fill(langX + langW - 1, langY, langX + langW, langY + langH, 0xFF3A4050);
	}

	private int[] langScrollbarThumb() {
		int trackTop = langY + 3;
		int trackH = Math.max(1, langH - 6);
		int content = langContentHeight();
		int view = langViewHeight();
		int thumb = content <= view ? trackH
				: Math.max(12, (int) ((long) trackH * view / Math.max(1, content)));
		thumb = Math.min(trackH, thumb);
		int travel = Math.max(0, trackH - thumb);
		int offset = maxLangScroll() <= 0 ? 0 : (int) ((long) travel * langScroll / maxLangScroll());
		return new int[]{trackTop + offset, thumb};
	}

	private void renderLangScrollbar(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int x = langX + langW - LANG_SCROLLBAR_W - 1;
		int top = langY + 3;
		int height = Math.max(1, langH - 6);
		context.fill(x, top, x + LANG_SCROLLBAR_W, top + height, 0xFF1D222B);
		int[] thumb = langScrollbarThumb();
		boolean hovered = mouseX >= x && mouseX < x + LANG_SCROLLBAR_W
				&& mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
		int color = draggingLangScroll ? 0xFF8FB4FF : hovered ? 0xFF6C82AD : 0xFF4E5A72;
		context.fill(x + 1, thumb[0], x + LANG_SCROLLBAR_W - 1, thumb[0] + thumb[1], color);
	}

	private void updateLangScroll(double mouseY) {
		int[] thumb = langScrollbarThumb();
		int top = langY + 3;
		int trackH = Math.max(1, langH - 6);
		int travel = Math.max(1, trackH - thumb[1]);
		double ratio = (mouseY - top - thumb[1] / 2.0) / travel;
		ratio = Math.max(0.0, Math.min(1.0, ratio));
		langScroll = (int) Math.round(ratio * maxLangScroll());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		this.lastClickX = (int) click.x();
		this.lastClickY = (int) click.y();

		if (languagePopup) {
			if (insideLangPopup(click.x(), click.y())) {
				if (click.button() == 0) {
					int sbX = langX + langW - LANG_SCROLLBAR_W - 1;
					if (click.x() >= sbX) {
						draggingLangScroll = true;
						updateLangScroll(click.y());
						return true;
					}
					int viewY = langY + 2;
					int viewH = langH - 4;
					int row = (int) ((click.y() - viewY + langScroll) / LANG_ROW_H);
					List<CodeLanguage> list = languages();
					if (row >= 0 && row < list.size()) {
						Config.get().language = list.get(row).id();
						Config.save();
						status = "Язык кода: " + list.get(row).displayName();
						statusColor = 0xFF4ADE80;
						languagePopup = false;
					}
				}
				return true;
			}
			// клик мимо — просто закрываем список
			languagePopup = false;
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
		if (draggingLangScroll) {
			updateLangScroll(click.y());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		if (draggingLangScroll) {
			draggingLangScroll = false;
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (insideLangPopup(mouseX, mouseY) && maxLangScroll() > 0) {
			langScroll = clampLangScroll(langScroll - (int) Math.signum(verticalAmount) * LANG_ROW_H);
			return true;
		}
		if (this.memoryButton != null && over(this.memoryButton, mouseX, mouseY)) {
			int direction = (int) Math.signum(verticalAmount);
			if (direction != 0) {
				boolean fast = this.minecraft != null && this.minecraft.getWindow() != null
						&& (InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
						|| InputConstants.isKeyDown(InputConstants.KEY_RSHIFT));
				setHistory(Config.get().historyLimit + direction * (fast ? 10 : 1));
				this.memoryButton.setMessage(Component.literal(memoryButtonLabel()));
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void openAdvanced() {
		if (this.minecraft != null) {
			Config.save();
			this.minecraft.setScreenAndShow(new AdvancedSettingsScreen(this));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "Настройки всех скриптов", 12, 10, 0xFFE8E8E8, false);
		context.text(this.font, "Общие для всех скриптов проекта", 12, 24, MUTED, false);

		context.fill(this.panelX - 10, this.panelTop - 10, this.panelX + this.panelW + 10,
				this.panelBottom + 6, PANEL_EDGE);
		context.fill(this.panelX - 8, this.panelTop - 8, this.panelX + this.panelW + 8,
				this.panelBottom + 4, PANEL);

		super.extractRenderState(context, mouseX, mouseY, delta);

		if (!status.isEmpty()) {
			context.text(this.font, status, 12, this.height - 42, statusColor, false);
		}
		renderLanguagePopup(context, mouseX, mouseY);
		renderTooltip(context, mouseX, mouseY);
	}

	/** Подсказка с задержкой, как у кнопок в ванильном Minecraft. */
	private void renderTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		Button hovered = null;
		for (Button button : this.tooltips.keySet()) {
			if (mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
					&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight()) {
				hovered = button;
				break;
			}
		}
		if (hovered == null) {
			this.hoveredTooltipButton = null;
			return;
		}
		if (hovered != this.hoveredTooltipButton) {
			this.hoveredTooltipButton = hovered;
			this.hoverStarted = System.currentTimeMillis();
			return;
		}
		if (System.currentTimeMillis() - this.hoverStarted < TOOLTIP_DELAY_MS) {
			return;
		}
		context.setComponentTooltipForNextFrame(this.font,
				wrap(this.tooltips.get(hovered), 48), mouseX, mouseY);
	}

	private List<Component> wrap(String text, int maxChars) {
		List<Component> lines = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
				lines.add(Component.literal(line.toString()));
				line.setLength(0);
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0) {
			lines.add(Component.literal(line.toString()));
		}
		return lines;
	}

	private static boolean over(Button button, double mouseX, double mouseY) {
		return mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
				&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
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
}