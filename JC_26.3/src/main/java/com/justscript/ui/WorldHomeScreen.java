package com.justscript.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.justscript.Config;
import com.justscript.editor3d.Editor3DScreen;
import com.justscript.input.Keybinds;
import com.justscript.publish.Publisher;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Единый хаб JustScript: открывается клавишей клавиши меню и кнопкой в меню мира.
 *
 * <ul>
 *   <li>три карточки: «Глобальные скрипты», «Модули», «Настройки»;</li>
 *   <li>дерево проекта, растущее снизу вверх: проект → модуль → ветки → скрипты;
 *       игрок, сущности и блоки — отдельными ветками, как на JustMC;</li>
 *   <li>Shift+клик выделяет диапазон скриптов, повторный клик снимает выделение,
 *       «Удалить» удаляет выделенные (с подтверждением);</li>
 *   <li>кнопка текущей клавиши — открывает смену клавиши;</li>
 *   <li>сборка и загрузка модуля в мир.</li>
 * </ul>
 */
public final class WorldHomeScreen extends Screen {
	private static final int GOLD_BASE = 0xFF4A3A18;
	private static final int GOLD_HOVER = 0xFF6B5220;
	private static final int GOLD_BORDER = 0xFFFFD966;
	private static final int GOLD_TITLE = 0xFFFFE8A3;
	private static final int GOLD_TEXT = 0xFFD8C79B;

	private static final int GREEN_BASE = 0xFF17301E;
	private static final int GREEN_HOVER = 0xFF214A2C;
	private static final int GREEN_BORDER = 0xFF7CDB4C;
	private static final int GREEN_TITLE = 0xFFCFF5BC;
	private static final int GREEN_TEXT = 0xFF9FC896;

	private static final int BLUE_BASE = 0xFF172A42;
	private static final int BLUE_HOVER = 0xFF20405F;
	private static final int BLUE_BORDER = 0xFF63B3FF;
	private static final int BLUE_TITLE = 0xFFBFE0FF;
	private static final int BLUE_TEXT = 0xFF9CBFE0;

	private static final int ROW_H = 15;
	private static final int MUTED = 0xFF9AA4B2;
	private static final int WARN = 0xFFFFC94D;
	private static final int OK = 0xFF4ADE80;

	private final Screen parent;

	private int cardX;
	private int cardY;
	private int cardW;
	private int cardH;
	private int cardGap = 10;

	private int treeX;
	private int treeY;
	private int treeW;
	private int treeH;

	private final List<TreeItem> tree = new ArrayList<>();
	/** Скрипты в том же порядке, что и в дереве, — для Shift-диапазона. */
	private final List<ScriptUnit> scriptOrder = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();
	private final Set<String> deleteConfirm = new LinkedHashSet<>();
	private String anchorId = "";
	private int treeScroll;
	private Button deleteButton;
	private String status = "";
	private int statusColor = MUTED;
	private int statusY;

	// ПКМ-меню в дереве
	private static final int MENU_ROW_H = 16;
	private static final String[] MENU_ITEMS = {"Открыть", "Дублировать", "Переименовать", "Удалить"};
	private static final int MENU_OPEN = 0;
	private static final int MENU_DUPLICATE = 1;
	private static final int MENU_RENAME = 2;
	private static final int MENU_DELETE = 3;
	private ScriptUnit contextUnit;
	private int menuX;
	private int menuY;
	private int menuW;

	private record TreeItem(String label, int depth, ScriptUnit unit, int color, String branch) {
	}

	public WorldHomeScreen(Screen parent) {
		super(Component.literal("JustScript"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelW = Math.min(this.width - 32, 720);
		int panelX = (this.width - panelW) / 2;

		this.cardW = (panelW - this.cardGap * 2) / 3;
		this.cardH = 62;
		this.cardX = panelX;
		this.cardY = 40;

		this.treeX = panelX;
		this.treeY = this.cardY + this.cardH + 34;
		this.treeW = panelW;

		int footerY = this.height - 28;
		int centerStart = this.width / 2 - 106;
		int helpW = 70;
		int deleteW = 96;
		int keyW = 150;
		// на узком окне нижние кнопки не помещаются в один ряд — клавишу и помощь
		// ставим строкой выше, сборку/загрузку/«Готово» оставляем внизу
		boolean compact = this.treeX + keyW + 6 + helpW + 10 > centerStart;
		if (compact) {
			keyW = Math.max(90, this.treeW - helpW - deleteW - 18);
		}
		int topY = compact ? this.height - 52 : footerY;
		this.statusY = compact ? this.treeY - 14 : this.height - 42;
		this.treeH = Math.max(60, this.height - this.treeY - (compact ? 68 : 42));

		this.addRenderableWidget(Button.builder(
						Component.literal("Клавиша: " + Keybinds.describeMenu() + " — сменить"), button -> openKeybinds())
				.bounds(this.treeX, topY, keyW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Помощь"), button -> openHelp())
				.bounds(this.treeX + keyW + 6, topY, helpW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Собрать"), button -> compile())
				.bounds(centerStart, footerY, 68, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Загрузить"), button -> publish())
				.bounds(centerStart + 72, footerY, 68, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), button -> onClose())
				.bounds(centerStart + 144, footerY, 68, 20).build());
		this.deleteButton = Button.builder(Component.literal("Удалить"), button -> deleteSelected())
				.bounds(this.treeX + this.treeW - deleteW, topY, deleteW, 20).build();
		this.addRenderableWidget(this.deleteButton);

		// кнопка 3D-редактора (помощь теперь внутри него)
		int editorW = 96;
		int editorY = 8;
		this.addRenderableWidget(Button.builder(Component.literal("3D-редактор"), button -> openEditor3D())
				.bounds(this.width - 10 - editorW, editorY, editorW, 20).build());

		buildTree();
		deleteConfirm.clear();
		closeContextMenu();
		refreshDeleteButton();
	}

	/** Дерево снизу вверх: проект → модуль → ветки → скрипты. */
	private void buildTree() {
		tree.clear();
		scriptOrder.clear();
		String moduleName = Config.get().moduleName.replace("%player%", com.justscript.util.Chat.playerName());
		tree.add(new TreeItem("Проект: " + ScriptStore.get().projectName(), 0, null, 0xFFE8E8E8, null));
		tree.add(new TreeItem("Модуль: " + moduleName, 1, null, 0xFFBFE0FF, null));

		List<ScriptUnit> globals = new ArrayList<>();
		List<ScriptUnit> entities = new ArrayList<>();
		List<ScriptUnit> blocks = new ArrayList<>();
		for (ScriptUnit unit : ScriptStore.get().all()) {
			switch (unit.kind) {
				case "global" -> globals.add(unit);
				case "entity" -> entities.add(unit);
				default -> blocks.add(unit);
			}
		}
		sortUnits(globals, true);
		sortUnits(entities, false);
		sortUnits(blocks, false);

		addBranch("Глобальные скрипты", globals, "global", GOLD_BORDER, "★ ");
		addBranch("Сущности", entities, "entity", 0xFFB9C2D0, "◆ ");
		addBranch("Блоки", blocks, "block", 0xFF7FB2FF, "■ ");
	}

	private void addBranch(String title, List<ScriptUnit> units, String branch, int color, String icon) {
		tree.add(new TreeItem(title + " (" + units.size() + ")", 1, null, color, branch));
		for (ScriptUnit unit : units) {
			tree.add(new TreeItem(icon + unit.shortLabel(), 2, unit, 0xFFD4D4D4, branch));
			scriptOrder.add(unit);
		}
	}

	private static void sortUnits(List<ScriptUnit> units, boolean byName) {
		if (byName) {
			units.sort(Comparator.comparing(ScriptUnit::shortLabel, String.CASE_INSENSITIVE_ORDER));
			return;
		}
		units.sort(Comparator.comparingInt((ScriptUnit unit) -> unit.position)
				.thenComparing(ScriptUnit::shortLabel, String.CASE_INSENSITIVE_ORDER));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "JustScript", 12, 12, 0xFF63D2FF, false);
		context.text(this.font, "Проект: " + ScriptStore.get().projectName(),
				12, 26, MUTED, false);

		boolean first = hovered(mouseX, mouseY, 0);
		boolean second = hovered(mouseX, mouseY, 1);
		boolean third = hovered(mouseX, mouseY, 2);
		drawCard(context, cardX, first, "★", "Глобальные скрипты", "События игрока и мира, функции и процессы",
				GOLD_BASE, GOLD_HOVER, GOLD_BORDER, GOLD_TITLE, GOLD_TEXT);
		drawCard(context, cardX + cardW + cardGap, second, "▦", "Модули", "Установленные модули и вызовы функций",
				GREEN_BASE, GREEN_HOVER, GREEN_BORDER, GREEN_TITLE, GREEN_TEXT);
		drawCard(context, cardX + (cardW + cardGap) * 2, third, "⚙", "Настройки", "Название, клавиши, языки, память",
				BLUE_BASE, BLUE_HOVER, BLUE_BORDER, BLUE_TITLE, BLUE_TEXT);

		drawTree(context, mouseX, mouseY);

		super.extractRenderState(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.text(this.font, status, 12, statusY, statusColor, false);
		}
		drawContextMenu(context, mouseX, mouseY);
	}

	// ---------------------------------------------------------------- ПКМ-меню

	private void openContextMenu(ScriptUnit unit, double mouseX, double mouseY) {
		this.contextUnit = unit;
		this.menuW = 112;
		this.menuX = Math.min((int) mouseX, this.width - this.menuW - 4);
		int menuH = MENU_ROW_H * MENU_ITEMS.length;
		this.menuY = Math.max(18, Math.min((int) mouseY, this.height - menuH - 4));
	}

	private void closeContextMenu() {
		this.contextUnit = null;
	}

	private boolean isContextMenuOpen() {
		return this.contextUnit != null;
	}

	private void drawContextMenu(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		if (!isContextMenuOpen()) {
			return;
		}
		int h = MENU_ROW_H * MENU_ITEMS.length;
		context.fill(menuX - 1, menuY - 1, menuX + menuW + 1, menuY + h + 1, 0xFFFFD966);
		context.fill(menuX, menuY, menuX + menuW, menuY + h, 0xFF1B1F27);
		for (int i = 0; i < MENU_ITEMS.length; i++) {
			int rowY = menuY + i * MENU_ROW_H;
			boolean rowHovered = mouseX >= menuX && mouseX < menuX + menuW
					&& mouseY >= rowY && mouseY < rowY + MENU_ROW_H;
			boolean enabled = i != MENU_DUPLICATE || canDuplicate(contextUnit);
			if (rowHovered) {
				context.fill(menuX, rowY, menuX + menuW, rowY + MENU_ROW_H - 1,
						enabled ? 0xFF3A4454 : 0xFF2A2F3A);
			}
			int color = !enabled ? 0xFF6F7683 : rowHovered ? 0xFFFFFFFF
					: i == MENU_DELETE ? 0xFFFF8A8A : 0xFFD4D4D4;
			context.text(this.font, MENU_ITEMS[i], menuX + 8, rowY + 4, color, false);
		}
		context.text(this.font, trim(contextUnit.shortLabel(), menuW + 60), menuX + 6, menuY - 12,
				0xFF9AA4B2, false);
	}

	private boolean canDuplicate(ScriptUnit unit) {
		return unit != null && "global".equals(unit.kind);
	}

	/** @return true, если клик обработан меню (или закрыл его) */
	private boolean contextMenuClicked(MouseButtonEvent click) {
		if (!isContextMenuOpen() || click.button() != 0) {
			return false;
		}
		int h = MENU_ROW_H * MENU_ITEMS.length;
		if (click.x() < menuX || click.x() >= menuX + menuW || click.y() < menuY || click.y() >= menuY + h) {
			closeContextMenu();
			return false;
		}
		int index = (int) ((click.y() - menuY) / MENU_ROW_H);
		ScriptUnit unit = contextUnit;
		closeContextMenu();
		runMenuAction(index, unit);
		return true;
	}

	private void runMenuAction(int index, ScriptUnit unit) {
		if (unit == null) {
			return;
		}
		switch (index) {
			case MENU_OPEN -> {
				selected.clear();
				selected.add(unit.id);
				anchorId = unit.id;
				refreshDeleteButton();
				openScript(unit);
			}
			case MENU_DUPLICATE -> duplicate(unit);
			case MENU_RENAME -> rename(unit);
			case MENU_DELETE -> {
				selected.clear();
				selected.add(unit.id);
				anchorId = unit.id;
				deleteConfirm.clear();
				refreshDeleteButton();
				deleteSelected();
			}
			default -> {
			}
		}
	}

	private void duplicate(ScriptUnit unit) {
		if (!canDuplicate(unit)) {
			status = "Копия возможна только для глобального скрипта: у блока и сущности копия "
					+ "осталась бы на том же объекте и срабатывала бы дважды";
			statusColor = WARN;
			return;
		}
		ScriptUnit copy = ScriptStore.get().duplicate(unit.id);
		if (copy == null) {
			status = "Не удалось скопировать скрипт";
			statusColor = WARN;
			return;
		}
		ScriptStore.get().save();
		buildTree();
		status = "Создана копия: " + copy.shortLabel();
		statusColor = OK;
	}

	/** Ctrl+D: копирует выделенные глобальные скрипты. */
	private void duplicateSelected() {
		if (selected.isEmpty()) {
			status = "Сначала выдели глобальные скрипты в дереве";
			statusColor = WARN;
			return;
		}
		int made = 0;
		int skipped = 0;
		for (String id : new ArrayList<>(selected)) {
			ScriptUnit unit = ScriptStore.get().get(id);
			if (canDuplicate(unit)) {
				if (ScriptStore.get().duplicate(id) != null) {
					made++;
				}
			} else {
				skipped++;
			}
		}
		if (made == 0) {
			status = "Копии возможны только для глобальных скриптов";
			statusColor = WARN;
			return;
		}
		ScriptStore.get().save();
		buildTree();
		status = "Создано копий: " + made + (skipped > 0 ? " · пропущено не глобальных: " + skipped : "");
		statusColor = OK;
	}

	private void rename(ScriptUnit unit) {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new RenameScreen(this, unit));
	}

	private boolean hovered(double mouseX, double mouseY, int index) {
		int x = cardX + (cardW + cardGap) * index;
		return mouseX >= x && mouseX < x + cardW && mouseY >= cardY && mouseY < cardY + cardH;
	}

	private void drawCard(GuiGraphicsExtractor context, int x, boolean hovered, String icon, String title, String subtitle,
			int base, int hover, int border, int titleColor, int textColor) {
		int y = cardY;
		context.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, hovered ? border : 0x553A4050);
		context.fill(x, y, x + cardW, y + cardH, hovered ? hover : base);
		if (hovered) {
			context.fill(x, y, x + cardW, y + 2, border);
			context.fill(x, y + cardH - 2, x + cardW, y + cardH, border);
		}
		context.text(this.font, icon, x + 10, y + 10, titleColor, false);
		context.text(this.font, trim(title, cardW - 34), x + 26, y + 10, titleColor, false);
		context.text(this.font, trim(subtitle, cardW - 20), x + 10, y + 24, textColor, false);
		context.text(this.font, hovered ? "нажми, чтобы открыть" : "ЛКМ", x + 10, y + cardH - 14,
				hovered ? border : textColor, false);
	}

	private void drawTree(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		context.fill(treeX - 6, treeY - 6, treeX + treeW + 6, treeY + treeH + 6, 0xFF14171D);
		int visible = Math.max(1, treeH / ROW_H);
		int maxScroll = Math.max(0, tree.size() - visible);
		treeScroll = Math.max(0, Math.min(treeScroll, maxScroll));
		int count = Math.min(tree.size() - treeScroll, visible);
		// рисуем снизу вверх
		for (int i = 0; i < count; i++) {
			TreeItem item = tree.get(treeScroll + i);
			int rowY = treeY + treeH - ROW_H * (i + 1);
			int x = treeX + 6 + item.depth() * 12;
			boolean clickable = item.unit() != null;
			boolean rowHovered = clickable
					&& mouseX >= treeX && mouseX <= treeX + treeW
					&& mouseY >= rowY && mouseY < rowY + ROW_H;
			boolean rowSelected = item.unit() != null && selected.contains(item.unit().id);
			if (rowSelected) {
				context.fill(treeX, rowY, treeX + treeW, rowY + ROW_H - 1, 0x664ADE80);
			} else if (rowHovered) {
				context.fill(treeX, rowY, treeX + treeW, rowY + ROW_H - 1, 0x332F6FDB);
			}
			// ветка
			if (item.depth() > 0) {
				int branchX = treeX + 6 + (item.depth() - 1) * 12 + 3;
				context.fill(branchX, rowY + ROW_H - 1, branchX + 1, treeY + treeH - ROW_H * i, 0x553A4050);
				context.fill(branchX, rowY + ROW_H / 2, branchX + 7, rowY + ROW_H / 2 + 1, 0x553A4050);
			}
			String label = item.label();
			if (rowSelected) {
				label = "✓ " + label;
			}
			int color = rowSelected ? 0xFFB9FFC9 : rowHovered ? 0xFFFFFFFF : item.color();
			context.text(this.font, trim(label, treeX + treeW - x - 8), x, rowY + 3, color, false);
		}
		if (maxScroll > 0) {
			if (treeScroll + visible < tree.size()) {
				context.text(this.font, "…", treeX + treeW - 12, treeY + 2, 0xFF6F7683, false);
			}
			if (treeScroll > 0) {
				context.text(this.font, "…", treeX + treeW - 12, treeY + treeH - 11,
						0xFF6F7683, false);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == 1) {
			TreeItem item = treeItemAt(click.x(), click.y());
			if (item != null && item.unit() != null) {
				selected.clear();
				selected.add(item.unit().id);
				anchorId = item.unit().id;
				refreshDeleteButton();
				openContextMenu(item.unit(), click.x(), click.y());
				return true;
			}
			closeContextMenu();
			return super.mouseClicked(click, doubled);
		}
		if (click.button() == 0) {
			if (contextMenuClicked(click)) {
				return true;
			}
			for (int index = 0; index < 3; index++) {
				if (hovered(click.x(), click.y(), index)) {
					if (index == 0) {
						openGlobal();
					} else if (index == 1) {
						openModules();
					} else {
						openSettings();
					}
					return true;
				}
			}
			TreeItem item = treeItemAt(click.x(), click.y());
			if (item != null) {
				if (item.unit() != null) {
					boolean shift = (click.modifiers() & InputConstants.MOD_SHIFT) != 0;
					clickScript(item.unit(), shift);
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	/** Строка дерева под мышью (или null). */
	private TreeItem treeItemAt(double x, double y) {
		if (x < treeX || x > treeX + treeW || y < treeY || y >= treeY + treeH) {
			return null;
		}
		int visible = Math.max(1, treeH / ROW_H);
		int fromBottom = (int) ((treeY + treeH - y) / ROW_H);
		int index = treeScroll + fromBottom;
		if (fromBottom < 0 || index < 0 || index >= tree.size() || fromBottom >= visible) {
			return null;
		}
		return tree.get(index);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (isContextMenuOpen()) {
			closeContextMenu();
		}
		if (mouseX >= treeX - 6 && mouseX <= treeX + treeW + 6
				&& mouseY >= treeY && mouseY <= treeY + treeH) {
			int visible = Math.max(1, treeH / ROW_H);
			int maxScroll = Math.max(0, tree.size() - visible);
			treeScroll = Math.max(0, Math.min(maxScroll,
					treeScroll + (int) Math.signum(verticalAmount)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	/**
	 * Клик по скрипту: обычный — открыть и выделить, повторный — снять выделение,
	 * Shift — выделить диапазон от прошлого клика до этого.
	 */
	private void clickScript(ScriptUnit unit, boolean shift) {
		// Shift+клик — только выделение и якорь, редактор не открывается никогда
		if (shift) {
			if (!anchorId.isEmpty()) {
				int from = indexOf(anchorId);
				int to = indexOf(unit.id);
				if (from >= 0 && to >= 0) {
					selected.clear();
					int min = Math.min(from, to);
					int max = Math.max(from, to);
					for (int i = min; i <= max; i++) {
						selected.add(scriptOrder.get(i).id);
					}
					status = "Выделено скриптов: " + selected.size();
					statusColor = OK;
					refreshDeleteButton();
					return;
				}
			}
			selected.clear();
			selected.add(unit.id);
			anchorId = unit.id;
			status = "Выделено скриптов: 1 — Shift+клик по другому задаёт диапазон";
			statusColor = OK;
			refreshDeleteButton();
			return;
		}
		if (selected.contains(unit.id)) {
			selected.remove(unit.id);
			anchorId = "";
			status = "Выделение снято";
			statusColor = MUTED;
			refreshDeleteButton();
			return;
		}
		selected.clear();
		selected.add(unit.id);
		anchorId = unit.id;
		refreshDeleteButton();
		openScript(unit);
	}

	private int indexOf(String id) {
		for (int i = 0; i < scriptOrder.size(); i++) {
			if (scriptOrder.get(i).id.equals(id)) {
				return i;
			}
		}
		return -1;
	}

	private void refreshDeleteButton() {
		if (deleteButton == null) {
			return;
		}
		deleteButton.setMessage(Component.literal(selected.isEmpty() ? "Удалить" : "Удалить: " + selected.size()));
		deleteButton.active = !selected.isEmpty();
	}

	private void deleteSelected() {
		if (selected.isEmpty()) {
			status = "Сначала выдели скрипты в дереве";
			statusColor = WARN;
			return;
		}
		if (!deleteConfirm.equals(selected)) {
			deleteConfirm.clear();
			deleteConfirm.addAll(selected);
			status = "Нажми «Удалить» ещё раз: будет удалено " + selected.size() + " скрипт(ов)";
			statusColor = WARN;
			return;
		}
		int removed = 0;
		for (String id : new ArrayList<>(selected)) {
			if (ScriptStore.get().remove(id)) {
				removed++;
			}
		}
		ScriptStore.get().save();
		selected.clear();
		deleteConfirm.clear();
		anchorId = "";
		buildTree();
		refreshDeleteButton();
		status = "Удалено скриптов: " + removed;
		statusColor = OK;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		boolean ctrl = (input.modifiers() & InputConstants.MOD_CONTROL) != 0;
		if (input.key() == InputConstants.KEY_ESCAPE && isContextMenuOpen()) {
			closeContextMenu();
			return true;
		}
		if (ctrl && input.key() == InputConstants.KEY_D) {
			duplicateSelected();
			return true;
		}
		if (input.key() == InputConstants.KEY_DELETE) {
			deleteSelected();
			return true;
		}
		if (input.key() == InputConstants.KEY_1) {
			openGlobal();
			return true;
		}
		if (input.key() == InputConstants.KEY_2) {
			openModules();
			return true;
		}
		if (input.key() == InputConstants.KEY_3) {
			openSettings();
			return true;
		}
		return super.keyPressed(input);
	}

	private void openScript(ScriptUnit unit) {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new ScriptEditorScreen(unit, this));
	}

	private void openGlobal() {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		if (minecraft.level == null) {
			status = "Сначала зайди в мир";
			statusColor = WARN;
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> ScriptUnit.forWorld(minecraft.level));
		openScript(unit);
	}

	private void openEditor3D() {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		if (minecraft.level == null || minecraft.player == null) {
			status = "Сначала зайди в мир";
			statusColor = WARN;
			return;
		}
		minecraft.setScreenAndShow(new Editor3DScreen(this));
	}

	private void openModules() {		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new ModulesScreen(this));
	}

	private void openSettings() {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new SettingsScreen(this));
	}

	private void openKeybinds() {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new KeyBindsScreen(this, minecraft.options));
	}

	private void openHelp() {
		Minecraft minecraft = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
		minecraft.setScreenAndShow(new HelpScreen(this));
	}

	private void compile() {
		Config.save();
		Publisher.compileOnly(true);
		status = "Сборка запущена — результат в чате";
		statusColor = MUTED;
	}

	private void publish() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(null);
		}
		Publisher.publish();
	}

	private String trim(String value, int maxWidth) {
		if (value == null) {
			return "";
		}
		if (this.font.width(value) <= maxWidth) {
			return value;
		}
		String result = value;
		while (!result.isEmpty() && this.font.width(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
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
