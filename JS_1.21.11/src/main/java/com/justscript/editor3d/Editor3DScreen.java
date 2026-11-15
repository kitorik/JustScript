package com.justscript.editor3d;

import com.justscript.Config;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.ui.ScriptEditorScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 3D-редактор в духе Blender: свободная камера, объекты-визуализаторы,
 * хитбоксы, слои, префабы и анимация.
 *
 * <p>Сверху — минимальная панель. Панель объекта появляется только после
 * клика по визуализатору. Размер, поворот и положение меняются гизмо:
 * <ul>
 *   <li>{@code Alt+1} / {@code G} — перемещение (стрелки);</li>
 *   <li>{@code Alt+2} / {@code R} — поворот (окружность);</li>
 *   <li>{@code Alt+3} / {@code S} — масштаб (стрелки с кубиками).</li>
 * </ul>
 * Помощь открывается внутри редактора клавишей {@code F1} или кнопкой {@code ?}.
 */
public final class Editor3DScreen extends Screen {
	private static final double REACH = 32.0;
	private static final int BAR_H = 28;
	private static final int PANEL_W = 212;
	private static final int OUT_W = 218;
	private static final int TIMELINE_H = 30;
	private static final int ROW_H = 18;

	private static final int PANEL_BG = 0xF00E1014;
	private static final int PANEL_EDGE = 0x33FFFFFF;
	private static final int PANEL_BUTTON = 0xFF171B21;
	private static final int PANEL_BUTTON_HOVER = 0xFF242C36;
	private static final int TEXT = 0xFFE6EDF5;
	private static final int MUTED = 0xFF93A0B0;
	private static final int ACCENT = 0xFF63D2FF;
	private static final int DANGER = 0xFFE05A5A;
	private static final int OK = 0xFF4ADE80;
	private static final int WARN = 0xFFFFC94D;
	private static final int[] AXIS_COLORS = {0xFFE05A5A, 0xFF6FD36F, 0xFF5AA9FF};

	private static final String[] FIELD_LABELS = {"X", "Y", "Z", "Поворот Y", "Поворот X", "Крен",
			"Масштаб", "Размер X", "Размер Y", "Размер Z"};

	/** Буфер обмена редактора: объекты и их относительные позы. */
	private static final List<Visualizer> CLIPBOARD = new ArrayList<>();

	private enum Gizmo {
		MOVE, ROTATE, SCALE
	}

	private enum Drag {
		NONE, LOOK, PAN, ORBIT, GIZMO, BOX, TIMELINE
	}

	private record Hit(int x, int y, int w, int h, int id) {
		boolean holds(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record ObjHit(int x, int y, int w, int h, String id, int kind) {
		boolean holds(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record FieldHit(int x, int y, int w, int h, int index) {
		boolean holds(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final Screen parent;
	private Gizmo gizmo = Gizmo.MOVE;
	private boolean helpOpen;
	private boolean numericOpen;
	private boolean outlinerOpen;
	private boolean timelineOpen = true;
	private boolean boxMode;

	private String status = "";
	private int statusColor = MUTED;

	private Drag drag = Drag.NONE;
	private double lastMouseX;
	private double lastMouseY;

	// --- гизмо
	private int gizmoAxis = -1;
	private double pivotX;
	private double pivotY;
	private double pivotZ;
	private double gizmoDirX;
	private double gizmoDirY;
	private double gizmoStartAlong;
	private double gizmoStartMouseX;
	private double gizmoStartMouseY;
	private double gizmoAxisWorld;
	private double gizmoAxisPixels;
	private final Map<String, double[]> startPoses = new LinkedHashMap<>();

	// --- рамка выделения
	private double boxX0;
	private double boxY0;
	private double boxX1;
	private double boxY1;

	// --- числовая панель
	private int fieldIndex = -1;
	private String fieldBuffer = "";

	// --- диалог имени
	private int promptKind;
	private String promptTitle = "";
	private String promptBuffer = "";

	// --- панели
	private final List<Hit> barHits = new ArrayList<>();
	private final List<Hit> panelHits = new ArrayList<>();
	private final List<ObjHit> outlinerHits = new ArrayList<>();
	private final List<FieldHit> numericHits = new ArrayList<>();
	private final List<ObjHit> prefabHits = new ArrayList<>();
	private int outlinerScroll;
	private int prefabScroll;
	private List<String> prefabNames = new ArrayList<>();

	public Editor3DScreen(Screen parent) {
		super(Text.literal("JustScript — 3D-редактор"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Editor3DState state = Editor3DState.get();
		if (this.client != null && !state.isActive()) {
			state.enter(this.client);
		}
		if (this.status.isEmpty()) {
			this.status = "Alt+1/2/3 — режим · ЛКМ — выбрать · ? — помощь";
			this.statusColor = MUTED;
		}
		this.drag = Drag.NONE;
		this.boxMode = false;
		this.prefabNames = Prefabs.names();
		this.barHits.clear();
		this.panelHits.clear();
		this.outlinerHits.clear();
		this.numericHits.clear();
		this.prefabHits.clear();
	}

	// ------------------------------------------------------------- утилиты

	private Visualizer primary() {
		return SceneStore.get().get(Editor3DState.get().selectedVisualizer());
	}

	private List<Visualizer> selection() {
		List<Visualizer> result = new ArrayList<>();
		for (String id : Editor3DState.get().selectedIds()) {
			Visualizer visualizer = SceneStore.get().get(id);
			if (visualizer != null) {
				result.add(visualizer);
			}
		}
		return result;
	}

	private void status(String message, int color) {
		this.status = message;
		this.statusColor = color;
	}

	private void warn(String message) {
		status(message, WARN);
	}

	private static boolean ctrl() {
		return keyDown(InputUtil.GLFW_KEY_LEFT_CONTROL);
	}

	private static boolean shift() {
		return keyDown(InputUtil.GLFW_KEY_LEFT_SHIFT)
				|| keyDown(InputUtil.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean alt() {
		return keyDown(InputUtil.GLFW_KEY_LEFT_ALT)
				|| keyDown(InputUtil.GLFW_KEY_RIGHT_ALT);
	}

	/** Проверка клавиши: в 1.21.11 нужен явный доступ к окну. */
	private static boolean keyDown(int key) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.getWindow() != null && InputUtil.isKeyPressed(client.getWindow(), key);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private boolean snapActive() {
		return Config.get().editor3dSnap || ctrl();
	}

	private double snapStep() {
		return Math.max(0.0625, Config.get().editor3dSnapStep);
	}

	private double snapValue(double value) {
		double step = snapStep();
		return Math.round(value / step) * step;
	}

	private double snapAngle(double value, boolean snap) {
		if (!snap) {
			return value;
		}
		return Math.round(value / 15.0) * 15.0;
	}

	// ------------------------------------------------------------- действия

	private Vector3d placementPoint() {
		Editor3DState state = Editor3DState.get();
		Vec3d from = state.cameraPos();
		Vec3d to = from.add(state.lookVector().multiply(REACH));
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			Vec3d point = from.add(state.lookVector().multiply(4));
			return new Vector3d(point.x, point.y, point.z);
		}
		BlockHitResult hit = client.world.raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			Direction direction = hit.getSide();
			return new Vector3d(hit.getBlockPos().getX() + 0.5 + direction.getOffsetX(),
					hit.getBlockPos().getY() + 0.5 + direction.getOffsetY(),
					hit.getBlockPos().getZ() + 0.5 + direction.getOffsetZ());
		}
		Vec3d point = from.add(state.lookVector().multiply(4));
		return new Vector3d(point.x, point.y, point.z);
	}

	/** Ставит визуализатор выбранного из инвентаря предмета или блока. */
	public void placeFromPicker(String kind, String value) {
		Vector3d point = placementPoint();
		SceneStore.get().beginEdit();
		Visualizer visualizer = new Visualizer();
		visualizer.kind = "item".equals(kind) ? "item" : "block";
		visualizer.value = value;
		visualizer.x = point.x;
		visualizer.y = point.y;
		visualizer.z = point.z;
		visualizer.hitbox = ctrl() && alt();
		SceneStore.get().add(visualizer);
		Editor3DState.get().selectVisualizer(visualizer.id);
		status("Создан " + visualizer.displayName()
				+ (visualizer.hitbox ? " с хитбоксом" : "") + " · Alt+1/2/3 — режим", OK);
	}

	private void openPicker(boolean blocks) {
		if (this.client != null) {
			this.client.setScreen(new VisualizerPickerScreen(this, blocks));
		}
	}

	private void toggleHitbox() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		SceneStore.get().beginEdit();
		boolean enable = !list.get(0).hitbox;
		for (Visualizer visualizer : list) {
			visualizer.hitbox = enable;
		}
		SceneStore.get().touch();
		status(enable ? "Хитбокс включён у " + list.size() + " объект(ов)" : "Хитбокс выключен", OK);
	}

	private void duplicateSelected() {
		duplicate(false);
	}

	private void duplicate(boolean linked) {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		SceneStore.get().beginEdit();
		Map<String, String> remap = new HashMap<>();
		List<Visualizer> copies = new ArrayList<>();
		for (Visualizer visualizer : list) {
			Visualizer copy = visualizer.copy();
			copy.id = UUID.randomUUID().toString();
			remap.put(visualizer.id, copy.id);
			copy.x += 1.0;
			copy.blockScriptId = "";
			copy.hitboxScriptId = "";
			if (linked) {
				copy.linkId = visualizer.linkId == null || visualizer.linkId.isBlank()
						? visualizer.id
						: visualizer.linkId;
			} else {
				copy.linkId = "";
			}
			copies.add(copy);
		}
		Editor3DState state = Editor3DState.get();
		state.clearSelection();
		for (Visualizer copy : copies) {
			if (!copy.parentId.isEmpty() && remap.containsKey(copy.parentId)) {
				copy.parentId = remap.get(copy.parentId);
			}
			SceneStore.get().add(copy);
			state.addSelected(copy.id);
		}
		status(linked ? "Связанная копия: " + copies.size() : "Копия: " + copies.size(), OK);
	}

	private void copySelection() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Нечего копировать");
			return;
		}
		CLIPBOARD.clear();
		for (Visualizer visualizer : list) {
			CLIPBOARD.add(visualizer.copy());
		}
		status("Скопировано: " + CLIPBOARD.size() + " · Ctrl+V — вставить", OK);
	}

	private void pasteClipboard() {
		if (CLIPBOARD.isEmpty()) {
			warn("Буфер пуст: сначала Ctrl+C");
			return;
		}
		SceneStore.get().beginEdit();
		Editor3DState state = Editor3DState.get();
		state.clearSelection();
		for (Visualizer source : CLIPBOARD) {
			Visualizer copy = source.copy();
			copy.id = UUID.randomUUID().toString();
			copy.x += 1.0;
			copy.blockScriptId = "";
			copy.hitboxScriptId = "";
			copy.parentId = "";
			SceneStore.get().add(copy);
			state.addSelected(copy.id);
		}
		status("Вставлено: " + CLIPBOARD.size(), OK);
	}

	/** Массив: копии вдоль оси X с шагом в один блок. */
	private void arraySelected() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		SceneStore.get().beginEdit();
		Editor3DState state = Editor3DState.get();
		for (int step = 1; step <= 4; step++) {
			for (Visualizer visualizer : list) {
				Visualizer copy = visualizer.copy();
				copy.id = UUID.randomUUID().toString();
				copy.x += step;
				copy.blockScriptId = "";
				copy.hitboxScriptId = "";
				copy.linkId = "";
				SceneStore.get().add(copy);
				state.addSelected(copy.id);
			}
		}
		status("Массив: +4 копии по X", OK);
	}

	/** Кольцо: копии по окружности вокруг пивота. */
	private void ringSelected() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		Vec3d pivot = Editor3DState.get().pivot();
		SceneStore.get().beginEdit();
		Editor3DState state = Editor3DState.get();
		int count = 8;
		for (int i = 1; i < count; i++) {
			double angle = 360.0 * i / count;
			for (Visualizer visualizer : list) {
				Visualizer copy = visualizer.copy();
				copy.id = UUID.randomUUID().toString();
				Vec3d rel = new Vec3d(visualizer.x - pivot.x, visualizer.y - pivot.y,
						visualizer.z - pivot.z);
				if (rel.length() < 0.5) {
					rel = new Vec3d(2, 0, 0);
				}
				Vec3d rotated = Editor3DState.rotateYXZ(rel, angle, 0, 0);
				copy.x = pivot.x + rotated.x;
				copy.y = pivot.y + rotated.y;
				copy.z = pivot.z + rotated.z;
				copy.yaw = visualizer.yaw + (float) angle;
				copy.blockScriptId = "";
				copy.hitboxScriptId = "";
				copy.linkId = "";
				SceneStore.get().add(copy);
				state.addSelected(copy.id);
			}
		}
		status("Кольцо: " + count + " позиций вокруг пивота", OK);
	}

	private void centerSelected() {
		Editor3DState state = Editor3DState.get();
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		SceneStore.get().beginEdit();
		Vec3d look = state.lookVector();
		Vec3d point = state.cameraPos().add(look.multiply(4));
		for (Visualizer visualizer : list) {
			visualizer.x = point.x;
			visualizer.y = point.y;
			visualizer.z = point.z;
		}
		SceneStore.get().touch();
		status("Перенесено к центру взгляда: " + list.size(), OK);
	}

	/** F: навести камеру на выделение, сохранив направление взгляда. */
	private void focusSelected() {
		Editor3DState state = Editor3DState.get();
		Vec3d center = state.selectionCenter();
		if (center == null) {
			warn("Сначала выбери визуализатор");
			return;
		}
		double distance = 8.0;
		Visualizer visualizer = primary();
		if (visualizer != null) {
			distance = clamp(visualizer.scale * 4.0, 4.0, 24.0);
		}
		Vec3d look = state.lookVector();
		state.moveCamera(center.x - look.x * distance - state.camX(),
				center.y - look.y * distance - state.camY(),
				center.z - look.z * distance - state.camZ());
		status("Камера наведена на выделение", ACCENT);
	}

	private void deleteSelected() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Сначала выбери визуализатор");
			return;
		}
		if (primary() != null && primary().locked) {
			warn("Объект заблокирован — сними замок в аутлайнере");
			return;
		}
		SceneStore.get().beginEdit();
		for (Visualizer visualizer : list) {
			SceneStore.get().remove(visualizer.id);
		}
		Editor3DState.get().clearSelection();
		status("Удалено: " + list.size(), WARN);
	}

	private void undo() {
		if (SceneStore.get().undo()) {
			Editor3DState.get().clearSelection();
			status("Отменено (Ctrl+Y — вернуть)", ACCENT);
		} else {
			status("История пуста", MUTED);
		}
	}

	private void redo() {
		if (SceneStore.get().redo()) {
			Editor3DState.get().clearSelection();
			status("Возвращено", ACCENT);
		} else {
			status("Нечего возвращать", MUTED);
		}
	}

	private void exportSelection() {
		List<Visualizer> list = selection();
		String json = SceneStore.get().exportJson(list);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.keyboard.setClipboard(json);
		}
		status("JSON скопирован в буфер (" + (list.isEmpty() ? "вся сцена" : list.size() + " объект(ов)") + ")", OK);
	}

	/** Импорт модели в настоящий мир: команды summon в буфер и в файл. */
	private void exportCommands() {
		SceneStore store = SceneStore.get();
		String commands = store.commands();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.keyboard.setClipboard(commands);
		}
		java.nio.file.Path saved = store.writeCommands();
		status("Команды сцены скопированы (" + commands.lines().count() + " строк)"
				+ (saved == null ? "" : " · " + saved.getFileName()), OK);
	}

	/** Масштаб для статуса: один множитель или X×Y×Z. */
	private static String scaleText(Visualizer visualizer) {
		boolean uniform = Math.abs(visualizer.scaleX - 1) < 1e-6
				&& Math.abs(visualizer.scaleY - 1) < 1e-6
				&& Math.abs(visualizer.scaleZ - 1) < 1e-6;
		if (uniform) {
			return String.format(Locale.ROOT, "%.2f", visualizer.scale);
		}
		return String.format(Locale.ROOT, "%.2f×%.2f×%.2f",
				visualizer.sizeX(), visualizer.sizeY(), visualizer.sizeZ());
	}

	private void importClipboard() {
		MinecraftClient client = MinecraftClient.getInstance();
		String json = client == null ? "" : client.keyboard.getClipboard();
		int added = SceneStore.get().importJson(json);
		if (added > 0) {
			status("Импортировано объектов: " + added, OK);
		} else {
			warn("В буфере нет сцены JustScript");
		}
	}

	private void savePrefab() {
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			warn("Выбери объекты для префаба");
			return;
		}
		this.promptKind = 1;
		this.promptTitle = "Имя префаба (" + list.size() + " объект(ов))";
		this.promptBuffer = "prefab-" + (this.prefabNames.size() + 1);
	}

	private void insertPrefab(String name) {
		Vector3d point = placementPoint();
		List<Visualizer> objects = Prefabs.build(name, point.x, point.y, point.z);
		if (objects.isEmpty()) {
			warn("Префаб пуст: " + name);
			return;
		}
		SceneStore.get().beginEdit();
		Editor3DState state = Editor3DState.get();
		state.clearSelection();
		for (Visualizer visualizer : objects) {
			SceneStore.get().add(visualizer);
			state.addSelected(visualizer.id);
		}
		status("Префаб «" + name + "»: " + objects.size() + " объект(ов)", OK);
	}

	private void beginRename() {
		Visualizer visualizer = primary();
		if (visualizer == null) {
			warn("Сначала выбери визуализатор");
			return;
		}
		this.promptKind = 2;
		this.promptTitle = "Имя объекта";
		this.promptBuffer = visualizer.displayName();
	}

	private void setParent() {
		List<Visualizer> list = selection();
		if (list.size() < 2) {
			warn("Выдели родителя и детей (Shift+клик)");
			return;
		}
		SceneStore.get().beginEdit();
		Visualizer parent = list.get(0);
		for (int i = 1; i < list.size(); i++) {
			Visualizer child = list.get(i);
			if (!child.id.equals(parent.id)) {
				child.parentId = parent.id;
			}
		}
		SceneStore.get().touch();
		status("Родитель: " + parent.displayName(), OK);
	}

	private void clearParent() {
		List<Visualizer> list = selection();
		SceneStore.get().beginEdit();
		for (Visualizer visualizer : list) {
			visualizer.parentId = "";
		}
		SceneStore.get().touch();
		status("Связь с родителем снята", ACCENT);
	}

	/** Двигает детей вместе с родителем (после перетаскивания гизмо). */
	private void propagateChildren() {
		SceneStore store = SceneStore.get();
		Map<String, Vec3d> deltas = new LinkedHashMap<>();
		for (Map.Entry<String, double[]> entry : this.startPoses.entrySet()) {
			Visualizer visualizer = store.get(entry.getKey());
			if (visualizer == null) {
				continue;
			}
			double[] start = entry.getValue();
			deltas.put(visualizer.id, new Vec3d(visualizer.x - start[0],
					visualizer.y - start[1], visualizer.z - start[2]));
		}
		for (Map.Entry<String, Vec3d> entry : deltas.entrySet()) {
			moveChildren(entry.getKey(), entry.getValue(), 0);
		}
	}

	private void moveChildren(String parentId, Vec3d delta, int depth) {
		if (depth > 8 || (Math.abs(delta.x) < 1e-9 && Math.abs(delta.y) < 1e-9
				&& Math.abs(delta.z) < 1e-9)) {
			return;
		}
		for (Visualizer child : SceneStore.get().childrenOf(parentId)) {
			if (this.startPoses.containsKey(child.id)) {
				continue;
			}
			child.x += delta.x;
			child.y += delta.y;
			child.z += delta.z;
			moveChildren(child.id, delta, depth + 1);
		}
	}

	// ------------------------------------------------------------- скрипты

	private void openVisualizerScript() {
		Visualizer visualizer = primary();
		if (visualizer == null) {
			warn("Сначала выбери визуализатор");
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		BlockPos pos = BlockPos.ofFloored(visualizer.x, visualizer.y, visualizer.z);
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> {
			ScriptUnit created = ScriptUnit.forBlock(client.world, pos);
			created.blockId = "";
			created.label = "3D: " + visualizer.displayName() + " @ "
					+ pos.getX() + " " + pos.getY() + " " + pos.getZ();
			return created;
		});
		visualizer.blockScriptId = unit.id;
		SceneStore.get().touch();
		status("Скрипт визуализатора — в категории «Блоки»", OK);
		client.setScreen(new ScriptEditorScreen(unit, this));
	}

	private void openHitboxScript() {
		Visualizer visualizer = primary();
		if (visualizer == null) {
			warn("Сначала выбери визуализатор");
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		SceneStore.get().beginEdit();
		if (!visualizer.hitbox) {
			visualizer.hitbox = true;
			SceneStore.get().touch();
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> {
			ScriptUnit created = new ScriptUnit();
			created.kind = "entity";
			created.dimension = client.world.getRegistryKey().getValue().toString();
			created.entityUuid = visualizer.id;
			created.entityName = visualizer.hitboxTag();
			created.entityMatch = "name";
			created.id = "entity|" + visualizer.id;
			created.label = "Хитбокс: " + visualizer.hitboxTag();
			return created;
		});
		visualizer.hitboxScriptId = unit.id;
		SceneStore.get().touch();
		status("Скрипт хитбокса: события сущности взаимодействия", OK);
		client.setScreen(new ScriptEditorScreen(unit, this));
	}

	private void openBlockScript(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> ScriptUnit.forBlock(client.world, pos));
		client.setScreen(new ScriptEditorScreen(unit, this));
	}

	private void openEntityScript(Entity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> ScriptUnit.forEntity(entity));
		client.setScreen(new ScriptEditorScreen(unit, this));
	}

	// ------------------------------------------------------------- тик и ввод

	@Override
	public void tick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			close();
			return;
		}
		ScenePlayback.get().tick();
		if (Editor3DState.get().isActive()) {
			VisualizerEntities.sync(client.world);
			if (!isTyping()) {
				moveCamera(client);
			}
			updateHover(client);
		}
	}

	private boolean isTyping() {
		return this.helpOpen || this.promptKind != 0 || (this.numericOpen && this.fieldIndex >= 0);
	}

	private void moveCamera(MinecraftClient client) {
		Editor3DState state = Editor3DState.get();
		float speed = Math.max(0.05f, Config.get().editor3dSpeed);
		if (ctrl()) {
			speed *= 3.0f;
		}
		Vec3d look = state.lookVector();
		Vec3d forward = new Vec3d(look.x, 0, look.z);
		if (forward.lengthSquared() < 1e-4) {
			forward = new Vec3d(0, 0, 1);
		}
		forward = forward.normalize();
		Vec3d right = new Vec3d(-forward.z, 0, forward.x);

		double dx = 0;
		double dy = 0;
		double dz = 0;
		if (keyDown(InputUtil.GLFW_KEY_W)) {
			dx += forward.x * speed;
			dz += forward.z * speed;
			dy += look.y * speed;
		}
		if (keyDown(InputUtil.GLFW_KEY_S)) {
			dx -= forward.x * speed;
			dz -= forward.z * speed;
			dy -= look.y * speed;
		}
		if (keyDown(InputUtil.GLFW_KEY_D)) {
			dx += right.x * speed;
			dz += right.z * speed;
		}
		if (keyDown(InputUtil.GLFW_KEY_A)) {
			dx -= right.x * speed;
			dz -= right.z * speed;
		}
		if (keyDown(InputUtil.GLFW_KEY_SPACE)) {
			dy += speed;
		}
		if (keyDown(InputUtil.GLFW_KEY_LEFT_SHIFT)) {
			dy -= speed;
		}
		if (dx != 0 || dy != 0 || dz != 0) {
			state.moveCamera(dx, dy, dz);
		}
		float turn = 2.0f;
		if (keyDown(InputUtil.GLFW_KEY_LEFT)) {
			state.rotate(-turn, 0);
		}
		if (keyDown(InputUtil.GLFW_KEY_RIGHT)) {
			state.rotate(turn, 0);
		}
		if (keyDown(InputUtil.GLFW_KEY_UP)) {
			state.rotate(0, -turn);
		}
		if (keyDown(InputUtil.GLFW_KEY_DOWN)) {
			state.rotate(0, turn);
		}
	}

	private void updateHover(MinecraftClient client) {
		Editor3DState state = Editor3DState.get();
		Vec3d from = state.cameraPos();
		Vec3d to = from.add(state.lookVector().multiply(REACH));
		Visualizer visualizer = VisualizerEntities.pick(from, to);

		BlockHitResult blockHit = client.world.raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
		EntityHitResult entityHit = ProjectileUtil.getEntityCollision(client.world, client.player, from, to,
				new Box(from, to).expand(1.0),
				entity -> !(entity instanceof DisplayEntity) && !(entity instanceof InteractionEntity)
						&& !(entity instanceof PlayerEntity), 0.3f);

		double visualizerDistance = visualizer == null ? Double.MAX_VALUE
				: from.squaredDistanceTo(Editor3DState.visualizerBounds(visualizer).getCenter());
		double blockDistance = blockHit != null && blockHit.getType() == HitResult.Type.BLOCK
				? from.squaredDistanceTo(blockHit.getPos()) : Double.MAX_VALUE;
		double entityDistance = entityHit != null ? from.squaredDistanceTo(entityHit.getPos()) : Double.MAX_VALUE;

		if (visualizer != null && visualizerDistance <= blockDistance && visualizerDistance <= entityDistance) {
			state.setHovered(visualizer.id, null, "");
		} else if (blockDistance <= entityDistance && blockHit != null) {
			state.setHovered("", blockHit.getBlockPos(), "");
		} else if (entityHit != null) {
			state.setHovered("", null, entityHit.getEntity().getUuidAsString());
		} else {
			state.setHovered("", null, "");
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		double mx = click.x();
		double my = click.y();
		int button = click.button();

		// 1. диалог имени
		if (this.promptKind != 0) {
			return true;
		}
		// 2. помощь
		if (this.helpOpen) {
			if (button == 0) {
				this.helpOpen = false;
			}
			return true;
		}
		// 3. числовая панель
		if (this.numericOpen && handleNumericClick(mx, my)) {
			return true;
		}
		// 4. панели
		if (button == 0 && handleOutlinerClick(mx, my)) {
			return true;
		}
		if (button == 0 && handleTimelineClick(mx, my)) {
			return true;
		}
		if (button == 0 && handleBarClick(mx, my)) {
			return true;
		}
		if (button == 0 && handlePanelClick(mx, my)) {
			return true;
		}

		// 5. рамка выделения
		if (button == 0 && this.boxMode) {
			this.drag = Drag.BOX;
			this.boxX0 = mx;
			this.boxY0 = my;
			this.boxX1 = mx;
			this.boxY1 = my;
			return true;
		}

		// 6. гизмо
		if (button == 0) {
			int axis = gizmoAxisAt(mx, my);
			if (axis >= 0) {
				beginGizmoDrag(axis, mx, my);
				return true;
			}
		}

		// 7. выбор / обзор
		if (button == 0) {
			Editor3DState state = Editor3DState.get();
			if (!state.hoveredVisualizer().isEmpty()) {
				if (shift()) {
					state.toggleSelected(state.hoveredVisualizer());
				} else {
					state.selectVisualizer(state.hoveredVisualizer());
				}
				Visualizer visualizer = primary();
				status(visualizer == null ? "" : visualizer.displayName()
						+ " · масштаб " + scaleText(visualizer)
						+ (visualizer.hitbox ? " · хитбокс " : " · без хитбокса")
						+ " · выбрано " + state.selectionSize(), MUTED);
				return true;
			}
			if (!shift()) {
				state.clearSelection();
			}
			this.drag = alt() ? Drag.ORBIT : Drag.LOOK;
			this.lastMouseX = mx;
			this.lastMouseY = my;
			return true;
		}

		if (button == 1) {
			Editor3DState state = Editor3DState.get();
			if (shift()) {
				// Shift+ПКМ — поставить 3D-курсор
				if (state.hoveredBlock() != null) {
					state.setCursor(new Vec3d(state.hoveredBlock().getX() + 0.5,
							state.hoveredBlock().getY() + 0.5, state.hoveredBlock().getZ() + 0.5));
				} else {
					state.setCursor(state.cameraPos().add(state.lookVector().multiply(4)));
				}
				status("3D-курсор поставлен", ACCENT);
				return true;
			}
			if (!state.hoveredVisualizer().isEmpty()) {
				state.selectVisualizer(state.hoveredVisualizer());
				openVisualizerScript();
				return true;
			}
			if (state.hoveredBlock() != null) {
				openBlockScript(state.hoveredBlock());
				return true;
			}
			if (!state.hoveredEntity().isEmpty()) {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.world != null) {
					for (Entity entity : client.world.getEntities()) {
						if (entity.getUuidAsString().equals(state.hoveredEntity())) {
							openEntityScript(entity);
							return true;
						}
					}
				}
			}
			return true;
		}

		if (button == 2) {
			this.drag = shift() ? Drag.PAN : Drag.LOOK;
			this.lastMouseX = mx;
			this.lastMouseY = my;
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (this.drag == Drag.GIZMO && click.button() == 0) {
			dragGizmo(click.x(), click.y());
			return true;
		}
		if (this.drag == Drag.LOOK) {
			Editor3DState.get().rotate((float) (click.x() - this.lastMouseX) * 0.25f,
					(float) (click.y() - this.lastMouseY) * 0.25f);
			this.lastMouseX = click.x();
			this.lastMouseY = click.y();
			return true;
		}
		if (this.drag == Drag.ORBIT) {
			Editor3DState.get().orbitAround(Editor3DState.get().pivot(),
					(click.x() - this.lastMouseX) * 0.4, (click.y() - this.lastMouseY) * 0.4);
			this.lastMouseX = click.x();
			this.lastMouseY = click.y();
			return true;
		}
		if (this.drag == Drag.PAN) {
			panCamera(click.x() - this.lastMouseX, click.y() - this.lastMouseY);
			this.lastMouseX = click.x();
			this.lastMouseY = click.y();
			return true;
		}
		if (this.drag == Drag.BOX) {
			this.boxX1 = click.x();
			this.boxY1 = click.y();
			return true;
		}
		if (this.drag == Drag.TIMELINE) {
			ScenePlayback.get().scrub(timeAt(click.x()));
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (this.drag == Drag.BOX) {
			selectInBox();
			this.boxMode = false;
		}
		if (this.drag == Drag.GIZMO) {
			propagateChildren();
			SceneStore.get().touch();
			Visualizer visualizer = primary();
			if (visualizer != null) {
				status(String.format(Locale.ROOT, "%s · %.2f %.2f %.2f · масштаб %.2f",
						gizmoName(), visualizer.x, visualizer.y, visualizer.z, visualizer.scale),
						ACCENT);
			}
		}
		this.drag = Drag.NONE;
		this.gizmoAxis = -1;
		this.startPoses.clear();
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (this.helpOpen || this.promptKind != 0) {
			return true;
		}
		if (this.numericOpen && this.fieldIndex >= 0) {
			return true;
		}
		if (this.outlinerOpen && mouseX < OUT_W && mouseY > BAR_H) {
			if (mouseY > outlinerPrefabTop()) {
				this.prefabScroll = Math.max(0, this.prefabScroll - (int) Math.signum(vertical));
			} else {
				this.outlinerScroll = Math.max(0, this.outlinerScroll - (int) Math.signum(vertical) * 2);
			}
			return true;
		}
		double step = Math.max(0.4, Config.get().editor3dSpeed) * (vertical > 0 ? 2.5 : -2.5);
		Vec3d look = Editor3DState.get().lookVector();
		Editor3DState.get().moveCamera(look.x * step, look.y * step, look.z * step);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();

		// диалог имени
		if (this.promptKind != 0) {
			if (key == InputUtil.GLFW_KEY_ESCAPE) {
				this.promptKind = 0;
				return true;
			}
			if (key == InputUtil.GLFW_KEY_ENTER || key == InputUtil.GLFW_KEY_KP_ENTER) {
				commitPrompt();
				return true;
			}
			if (key == InputUtil.GLFW_KEY_BACKSPACE) {
				if (!this.promptBuffer.isEmpty()) {
					this.promptBuffer = this.promptBuffer.substring(0, this.promptBuffer.length() - 1);
				}
				return true;
			}
			return true;
		}

		// числовая панель
		if (this.numericOpen && this.fieldIndex >= 0) {
			if (key == InputUtil.GLFW_KEY_ESCAPE) {
				this.fieldIndex = -1;
				this.numericOpen = false;
				return true;
			}
			if (key == InputUtil.GLFW_KEY_ENTER || key == InputUtil.GLFW_KEY_KP_ENTER) {
				commitField();
				return true;
			}
			if (key == InputUtil.GLFW_KEY_TAB) {
				commitField();
				this.fieldIndex = (this.fieldIndex + 1) % FIELD_LABELS.length;
				this.fieldBuffer = currentFieldValue();
				return true;
			}
			if (key == InputUtil.GLFW_KEY_BACKSPACE) {
				if (!this.fieldBuffer.isEmpty()) {
					this.fieldBuffer = this.fieldBuffer.substring(0, this.fieldBuffer.length() - 1);
				}
				return true;
			}
			return true;
		}

		if (key == InputUtil.GLFW_KEY_F1) {
			this.helpOpen = !this.helpOpen;
			return true;
		}
		if (this.helpOpen) {
			if (key == InputUtil.GLFW_KEY_ESCAPE) {
				this.helpOpen = false;
				return true;
			}
			return true;
		}
		if (key == InputUtil.GLFW_KEY_ESCAPE) {
			if (this.numericOpen) {
				this.numericOpen = false;
				return true;
			}
			if (this.boxMode) {
				this.boxMode = false;
				return true;
			}
			close();
			return true;
		}

		// режимы гизмо
		if (alt() && key == InputUtil.GLFW_KEY_1) {
			setGizmo(Gizmo.MOVE);
			return true;
		}
		if (alt() && key == InputUtil.GLFW_KEY_2) {
			setGizmo(Gizmo.ROTATE);
			return true;
		}
		if (alt() && key == InputUtil.GLFW_KEY_3) {
			setGizmo(Gizmo.SCALE);
			return true;
		}
		// виды (Numpad 1/3/7 или Ctrl+1/3/7)
		if (key == InputUtil.GLFW_KEY_KP_1 || (ctrl() && key == InputUtil.GLFW_KEY_1)) {
			Editor3DState.get().viewFrom("front");
			status("Вид спереди", ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_KP_3 || (ctrl() && key == InputUtil.GLFW_KEY_3)) {
			Editor3DState.get().viewFrom("side");
			status("Вид сбоку", ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_KP_7 || (ctrl() && key == InputUtil.GLFW_KEY_7)) {
			Editor3DState.get().viewFrom("top");
			status("Вид сверху", ACCENT);
			return true;
		}

		if (key == InputUtil.GLFW_KEY_G) {
			setGizmo(Gizmo.MOVE);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_R) {
			setGizmo(Gizmo.ROTATE);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_S && !ctrl()) {
			setGizmo(Gizmo.SCALE);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_Q) {
			Config.get().editor3dGlobalAxes = !Config.get().editor3dGlobalAxes;
			Config.save();
			status("Оси: " + (Config.get().editor3dGlobalAxes ? "мировые" : "локальные"), ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_TAB) {
			Config.get().editor3dSnap = !Config.get().editor3dSnap;
			Config.save();
			status("Привязка: " + (Config.get().editor3dSnap ? snapLabel() : "выкл"), ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_N) {
			this.numericOpen = !this.numericOpen;
			this.fieldIndex = -1;
			return true;
		}
		if (key == InputUtil.GLFW_KEY_O) {
			this.outlinerOpen = !this.outlinerOpen;
			return true;
		}
		if (key == InputUtil.GLFW_KEY_T) {
			this.timelineOpen = !this.timelineOpen;
			return true;
		}
		if (key == InputUtil.GLFW_KEY_B && !ctrl()) {
			this.boxMode = !this.boxMode;
			status(this.boxMode ? "Рамка: тяни ЛКМ по экрану" : "Рамка выключена", ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_F) {
			focusSelected();
			return true;
		}
		if (key == InputUtil.GLFW_KEY_F2) {
			beginRename();
			return true;
		}
		if (key == InputUtil.GLFW_KEY_DELETE) {
			deleteSelected();
			return true;
		}
		if (key == InputUtil.GLFW_KEY_P && !ctrl()) {
			ScenePlayback playback = ScenePlayback.get();
			playback.toggle();
			status(playback.isPlaying() ? "Проигрывание" : "Пауза", ACCENT);
			return true;
		}
		if (key == InputUtil.GLFW_KEY_K) {
			Visualizer visualizer = primary();
			if (visualizer == null) {
				warn("Выбери объект для ключевого кадра");
			} else if (shift()) {
				SceneStore.get().beginEdit();
				ScenePlayback.get().removeKey(visualizer, ScenePlayback.get().time());
				SceneStore.get().touch();
				status("Ключ удалён", WARN);
			} else {
				SceneStore.get().beginEdit();
				ScenePlayback.get().addKey(visualizer, ScenePlayback.get().time());
				SceneStore.get().touch();
				status("Ключевой кадр на " + String.format(Locale.ROOT, "%.1f", ScenePlayback.get().time()) + " с", OK);
			}
			return true;
		}
		if (key == InputUtil.GLFW_KEY_HOME) {
			ScenePlayback.get().scrub(0);
			return true;
		}

		// Ctrl/Alt-комбинации
		if (ctrl()) {
			switch (key) {
				case InputUtil.GLFW_KEY_Z -> {
					if (shift()) {
						redo();
					} else {
						undo();
					}
					return true;
				}
				case InputUtil.GLFW_KEY_Y -> {
					redo();
					return true;
				}
				case InputUtil.GLFW_KEY_D -> {
					duplicateSelected();
					return true;
				}
				case InputUtil.GLFW_KEY_C -> {
					copySelection();
					return true;
				}
				case InputUtil.GLFW_KEY_V -> {
					pasteClipboard();
					return true;
				}
				case InputUtil.GLFW_KEY_A -> {
					Editor3DState.get().selectAll(SceneStore.get().visible());
					status("Выбрано: " + Editor3DState.get().selectionSize(), ACCENT);
					return true;
				}
				case InputUtil.GLFW_KEY_P -> {
					setParent();
					return true;
				}
				default -> {
				}
			}
		}
		if (alt()) {
			switch (key) {
				case InputUtil.GLFW_KEY_D -> {
					duplicate(true);
					return true;
				}
				case InputUtil.GLFW_KEY_P -> {
					clearParent();
					return true;
				}
				case InputUtil.GLFW_KEY_E -> {
					if (ctrl()) {
						exportCommands();
					} else {
						exportSelection();
					}
					return true;
				}
				case InputUtil.GLFW_KEY_I -> {
					importClipboard();
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (this.promptKind != 0) {
			if (input.isValidChar() && this.promptBuffer.length() < 48) {
				this.promptBuffer += input.asString();
			}
			return true;
		}
		if (this.numericOpen && this.fieldIndex >= 0) {
			String ch = input.asString();
			if (ch.length() == 1 && (Character.isDigit(ch.charAt(0)) || ch.equals(".")
					|| ch.equals("-") || ch.equals(","))) {
				this.fieldBuffer += ch;
			}
			return true;
		}
		return super.charTyped(input);
	}

	// ------------------------------------------------------------- диалоги

	private void commitPrompt() {
		SceneStore store = SceneStore.get();
		if (this.promptKind == 1) {
			List<Visualizer> list = selection();
			if (Prefabs.save(this.promptBuffer.trim(), list)) {
				this.prefabNames = Prefabs.names();
				status("Префаб сохранён: " + this.promptBuffer.trim(), OK);
			} else {
				warn("Не удалось сохранить префаб");
			}
		} else if (this.promptKind == 2) {
			Visualizer visualizer = primary();
			if (visualizer != null) {
				store.beginEdit();
				visualizer.name = this.promptBuffer.trim();
				store.touch();
				status("Переименовано: " + visualizer.displayName(), OK);
			}
		}
		this.promptKind = 0;
	}

	private String currentFieldValue() {
		Visualizer visualizer = primary();
		if (visualizer == null) {
			return "";
		}
		return switch (this.fieldIndex) {
			case 0 -> String.format(Locale.ROOT, "%.3f", visualizer.x);
			case 1 -> String.format(Locale.ROOT, "%.3f", visualizer.y);
			case 2 -> String.format(Locale.ROOT, "%.3f", visualizer.z);
			case 3 -> String.format(Locale.ROOT, "%.1f", visualizer.yaw);
			case 4 -> String.format(Locale.ROOT, "%.1f", visualizer.pitch);
			case 5 -> String.format(Locale.ROOT, "%.1f", visualizer.roll);
			case 6 -> String.format(Locale.ROOT, "%.3f", visualizer.scale);
			case 7 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleX);
			case 8 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleY);
			case 9 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleZ);
			default -> "";
		};
	}

	private void commitField() {
		Visualizer visualizer = primary();
		if (visualizer == null || this.fieldIndex < 0) {
			return;
		}
		try {
			String raw = this.fieldBuffer.trim().replace(',', '.');
			if (raw.isEmpty()) {
				return;
			}
			double value = Double.parseDouble(raw);
			SceneStore.get().beginEdit();
			switch (this.fieldIndex) {
				case 0 -> visualizer.x = value;
				case 1 -> visualizer.y = value;
				case 2 -> visualizer.z = value;
				case 3 -> visualizer.yaw = (float) value;
				case 4 -> visualizer.pitch = (float) value;
				case 5 -> visualizer.roll = (float) value;
				case 6 -> visualizer.scale = clamp(value, 0.02, 32);
				case 7 -> visualizer.scaleX = clamp(value, 0.05, 16);
				case 8 -> visualizer.scaleY = clamp(value, 0.05, 16);
				case 9 -> visualizer.scaleZ = clamp(value, 0.05, 16);
				default -> {
				}
			}
			SceneStore.get().touch();
			status(FIELD_LABELS[this.fieldIndex] + " = " + raw, OK);
			this.fieldBuffer = currentFieldValue();
		} catch (NumberFormatException e) {
			warn("Нужно число, а не \"" + this.fieldBuffer + "\"");
		}
	}

	// ------------------------------------------------------------- гизмо

	private Vec3d axisDir(int axis, Visualizer primary) {
		Vec3d base = switch (axis) {
			case 0 -> new Vec3d(1, 0, 0);
			case 1 -> new Vec3d(0, 1, 0);
			default -> new Vec3d(0, 0, 1);
		};
		if (Config.get().editor3dGlobalAxes || primary == null) {
			return base;
		}
		return Editor3DState.rotateYXZ(base, primary.yaw, primary.pitch, primary.roll);
	}

	private double gizmoLength(Vec3d pivot) {
		double distance = Editor3DState.get().cameraPos().distanceTo(pivot);
		return Math.max(0.8, Math.min(4.0, distance * 0.12));
	}

	/** Две оси, образующие плоскость кольца вокруг оси axis (0=X, 1=Y, 2=Z). */
	private Vec3d[] ringBasis(int axis, Visualizer visualizer) {
		return new Vec3d[]{axisDir((axis + 1) % 3, visualizer), axisDir((axis + 2) % 3, visualizer)};
	}

	/** Ближайшее расстояние курсора до кольца поворота, в пикселях. */
	private double ringDistance(int axis, Vec3d pivot, Visualizer visualizer, double radius,
			double mx, double my) {
		Vec3d[] basis = ringBasis(axis, visualizer);
		double best = Double.MAX_VALUE;
		double[] previous = null;
		int steps = 48;
		for (int i = 0; i <= steps; i++) {
			double angle = Math.PI * 2 * i / steps;
			Vec3d point = pivot.add(basis[0].multiply(Math.cos(angle) * radius))
					.add(basis[1].multiply(Math.sin(angle) * radius));
			double[] screen = project(point);
			if (screen == null) {
				previous = null;
				continue;
			}
			if (previous != null) {
				best = Math.min(best, distanceToSegment(mx, my, previous[0], previous[1],
						screen[0], screen[1]));
			}
			previous = screen;
		}
		return best;
	}

	/** Поворот вектора вокруг оси (0=X, 1=Y, 2=Z) на угол в градусах. */
	private static Vec3d rotateAround(Vec3d vec, int axis, double degrees) {
		double angle = Math.toRadians(degrees);
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return switch (axis) {
			case 0 -> new Vec3d(vec.x, vec.y * cos - vec.z * sin, vec.y * sin + vec.z * cos);
			case 1 -> new Vec3d(vec.x * cos + vec.z * sin, vec.y, -vec.x * sin + vec.z * cos);
			default -> new Vec3d(vec.x * cos - vec.y * sin, vec.x * sin + vec.y * cos, vec.z);
		};
	}

	/** Проекция точки мира в экран: {x, y, глубина} или null позади камеры. */
	private double[] project(Vec3d world) {
		Editor3DState state = Editor3DState.get();
		Vec3d delta = world.subtract(state.cameraPos());
		Vec3d forward = state.lookVector();
		Vec3d right = new Vec3d(-forward.z, 0, forward.x);
		if (right.lengthSquared() < 1e-6) {
			right = new Vec3d(1, 0, 0);
		} else {
			right = right.normalize();
		}
		Vec3d up = right.crossProduct(forward).normalize();
		double depth = delta.dotProduct(forward);
		if (depth <= 0.05) {
			return null;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		double fov = Math.toRadians(Math.max(30, client.options.getFov().getValue()));
		double focal = (this.height / 2.0) / Math.tan(fov / 2.0);
		double screenX = this.width / 2.0 + delta.dotProduct(right) / depth * focal;
		double screenY = this.height / 2.0 - delta.dotProduct(up) / depth * focal;
		return new double[]{screenX, screenY, depth};
	}

	private int gizmoAxisAt(double mx, double my) {
		Vec3d pivot = Editor3DState.get().selectionCenter();
		Visualizer visualizer = primary();
		if (pivot == null || visualizer == null || this.numericOpen || this.helpOpen) {
			return -1;
		}
		double[] screenPivot = project(pivot);
		if (screenPivot == null) {
			return -1;
		}
		double length = gizmoLength(pivot);
		if (this.gizmo == Gizmo.ROTATE) {
			int best = -1;
			double bestDistance = 9;
			for (int axis = 0; axis < 3; axis++) {
				double distance = ringDistance(axis, pivot, visualizer, length, mx, my);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = axis;
				}
			}
			return best;
		}
		for (int axis = 0; axis < 3; axis++) {
			double[] edge = project(pivot.add(axisDir(axis, visualizer).multiply(length)));
			if (edge == null) {
				continue;
			}
			if (distanceToSegment(mx, my, screenPivot[0], screenPivot[1], edge[0], edge[1]) <= 7) {
				return axis;
			}
		}
		return -1;
	}

	private void beginGizmoDrag(int axis, double mx, double my) {
		Vec3d pivot = Editor3DState.get().selectionCenter();
		Visualizer visualizer = primary();
		if (pivot == null || visualizer == null) {
			return;
		}
		double[] screenPivot = project(pivot);
		if (screenPivot == null) {
			return;
		}
		double length = gizmoLength(pivot);
		Vec3d direction = axisDir(axis, visualizer);
		double[] edge = project(pivot.add(direction.multiply(length)));
		if (edge == null) {
			return;
		}
		double dx = edge[0] - screenPivot[0];
		double dy = edge[1] - screenPivot[1];
		double pixels = Math.max(1, Math.hypot(dx, dy));

		SceneStore.get().beginEdit();
		this.drag = Drag.GIZMO;
		this.gizmoAxis = axis;
		this.pivotX = pivot.x;
		this.pivotY = pivot.y;
		this.pivotZ = pivot.z;
		this.gizmoDirX = dx / pixels;
		this.gizmoDirY = dy / pixels;
		this.gizmoStartAlong = (mx - screenPivot[0]) * this.gizmoDirX
				+ (my - screenPivot[1]) * this.gizmoDirY;
		this.gizmoStartMouseX = mx;
		this.gizmoStartMouseY = my;
		this.gizmoAxisWorld = length;
		this.gizmoAxisPixels = pixels;
		this.startPoses.clear();
		for (Visualizer object : selection()) {
			this.startPoses.put(object.id, new double[]{object.x, object.y, object.z,
					object.scale, object.yaw, object.pitch, object.roll});
		}
		status("Тяни: " + gizmoName() + " · Ctrl — шаг", ACCENT);
	}

	private void dragGizmo(double mx, double my) {
		if (this.startPoses.isEmpty()) {
			return;
		}
		boolean snap = snapActive();
		if (this.gizmo == Gizmo.ROTATE) {
			double dx = mx - this.gizmoStartMouseX;
			double dy = my - this.gizmoStartMouseY;
			double angle = (dx + dy) * 0.6;
			if (snap) {
				angle = Math.round(angle / 15.0) * 15.0;
			}
			int axis = Math.max(0, Math.min(2, this.gizmoAxis));
			for (Visualizer object : selection()) {
				double[] start = this.startPoses.get(object.id);
				if (start == null) {
					continue;
				}
				Vec3d rel = new Vec3d(start[0] - this.pivotX, start[1] - this.pivotY,
						start[2] - this.pivotZ);
				Vec3d rotated = rotateAround(rel, axis, angle);
				object.x = this.pivotX + rotated.x;
				object.y = this.pivotY + rotated.y;
				object.z = this.pivotZ + rotated.z;
				switch (axis) {
					case 0 -> object.pitch = (float) snapAngle(start[5] + angle, snap);
					case 1 -> object.yaw = (float) snapAngle(start[4] + angle, snap);
					default -> object.roll = (float) snapAngle(start[6] + angle, snap);
				}
			}
		} else {
			double now = (mx - this.gizmoStartMouseX) * this.gizmoDirX
					+ (my - this.gizmoStartMouseY) * this.gizmoDirY + this.gizmoStartAlong;
			double world = (now - this.gizmoStartAlong) * this.gizmoAxisWorld
					/ Math.max(1.0, this.gizmoAxisPixels);
			if (snap) {
				world = Math.round(world / snapStep()) * snapStep();
			}
			Vec3d dir = axisDir(this.gizmoAxis, primary());
			for (Visualizer object : selection()) {
				double[] start = this.startPoses.get(object.id);
				if (start == null) {
					continue;
				}
				if (this.gizmo == Gizmo.MOVE) {
					object.x = start[0] + dir.x * world;
					object.y = start[1] + dir.y * world;
					object.z = start[2] + dir.z * world;
				} else {
					double factor = 1.0 + world * 0.5;
					double scale = start[3] * Math.max(0.05, factor);
					if (snap) {
						scale = Math.round(scale * 20.0) / 20.0;
					}
					object.scale = clamp(scale, 0.02, 32);
				}
			}
		}
		SceneStore.get().touch();
		Visualizer visualizer = primary();
		if (visualizer != null) {
			status(String.format(Locale.ROOT, "%s · %.2f %.2f %.2f · масштаб %.2f",
					gizmoName(), visualizer.x, visualizer.y, visualizer.z, visualizer.scale), MUTED);
		}
	}

	private static double distanceToSegment(double px, double py,
			double x1, double y1, double x2, double y2) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double lengthSq = dx * dx + dy * dy;
		if (lengthSq < 1e-9) {
			return Math.hypot(px - x1, py - y1);
		}
		double t = ((px - x1) * dx + (py - y1) * dy) / lengthSq;
		t = Math.max(0, Math.min(1, t));
		return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
	}

	private void setGizmo(Gizmo mode) {
		this.gizmo = mode;
		status("Режим: " + gizmoName() + " · тяни гизмо мышью", ACCENT);
	}

	private String gizmoName() {
		return switch (this.gizmo) {
			case MOVE -> "Перемещение";
			case ROTATE -> "Поворот";
			case SCALE -> "Масштаб";
		};
	}

	private String snapLabel() {
		double step = Config.get().editor3dSnapStep;
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

	private void panCamera(double dx, double dy) {
		Editor3DState state = Editor3DState.get();
		Vec3d look = state.lookVector();
		Vec3d right = new Vec3d(-look.z, 0, look.x);
		if (right.lengthSquared() > 1e-6) {
			right = right.normalize();
		} else {
			right = new Vec3d(1, 0, 0);
		}
		Vec3d up = right.crossProduct(look).normalize();
		double k = 0.05;
		state.moveCamera(-right.x * dx * k + up.x * dy * k,
				-right.y * dx * k + up.y * dy * k,
				-right.z * dx * k + up.z * dy * k);
	}

	private void selectInBox() {
		double minX = Math.min(this.boxX0, this.boxX1);
		double maxX = Math.max(this.boxX0, this.boxX1);
		double minY = Math.min(this.boxY0, this.boxY1);
		double maxY = Math.max(this.boxY0, this.boxY1);
		Editor3DState state = Editor3DState.get();
		if (!shift()) {
			state.clearSelection();
		}
		int found = 0;
		for (Visualizer visualizer : SceneStore.get().visible()) {
			if (visualizer.locked) {
				continue;
			}
			double[] screen = project(new Vec3d(visualizer.x, visualizer.y, visualizer.z));
			if (screen == null) {
				continue;
			}
			if (screen[0] >= minX && screen[0] <= maxX && screen[1] >= minY && screen[1] <= maxY) {
				state.addSelected(visualizer.id);
				found++;
			}
		}
		status("В рамке: " + found, ACCENT);
	}

	// ------------------------------------------------------------- время

	private double timeAt(double mouseX) {
		double left = 46;
		double right = this.width - 64;
		double length = ScenePlayback.get().length();
		double ratio = clamp((mouseX - left) / Math.max(1, right - left), 0, 1);
		return ratio * length;
	}

	private double timelineX(double time) {
		double left = 46;
		double right = this.width - 64;
		return left + time / Math.max(0.001, ScenePlayback.get().length()) * (right - left);
	}

	// ------------------------------------------------------------- клики по панелям

	private boolean handleBarClick(double mx, double my) {
		for (Hit hit : this.barHits) {
			if (!hit.holds(mx, my)) {
				continue;
			}
			switch (hit.id()) {
				case 0 -> openPicker(true);
				case 1 -> openPicker(false);
				case 2 -> setGizmo(Gizmo.MOVE);
				case 3 -> setGizmo(Gizmo.ROTATE);
				case 4 -> setGizmo(Gizmo.SCALE);
				case 5 -> this.helpOpen = !this.helpOpen;
				case 6 -> close();
				case 7 -> {
					double step = Config.get().editor3dSnapStep;
					Config.get().editor3dSnapStep = step <= 0.0625 ? 0.125
							: step <= 0.125 ? 0.25 : step <= 0.25 ? 0.5 : step <= 0.5 ? 1.0 : 0.0625;
					Config.get().editor3dSnap = true;
					Config.save();
					status("Привязка: " + snapLabel(), ACCENT);
				}
				case 8 -> {
					Config.get().editor3dGlobalAxes = !Config.get().editor3dGlobalAxes;
					Config.save();
					status("Оси: " + (Config.get().editor3dGlobalAxes ? "мировые" : "локальные"), ACCENT);
				}
				case 9 -> this.timelineOpen = !this.timelineOpen;
				case 10 -> this.outlinerOpen = !this.outlinerOpen;
				case 11 -> {
					this.numericOpen = !this.numericOpen;
					this.fieldIndex = -1;
				}
				default -> {
				}
			}
			return true;
		}
		return false;
	}

	private boolean handlePanelClick(double mx, double my) {
		if (!Editor3DState.get().hasSelection()) {
			return false;
		}
		for (Hit hit : this.panelHits) {
			if (!hit.holds(mx, my)) {
				continue;
			}
			switch (hit.id()) {
				case 0 -> openVisualizerScript();
				case 1 -> openHitboxScript();
				case 2 -> toggleHitbox();
				case 3 -> duplicateSelected();
				case 4 -> duplicate(true);
				case 5 -> centerSelected();
				case 6 -> deleteSelected();
				case 7 -> arraySelected();
				case 8 -> ringSelected();
				case 9 -> savePrefab();
				case 10 -> beginRename();
				default -> {
				}
			}
			return true;
		}
		return false;
	}

	private boolean handleOutlinerClick(double mx, double my) {
		if (!this.outlinerOpen || mx >= OUT_W) {
			return false;
		}
		for (ObjHit hit : this.outlinerHits) {
			if (!hit.holds(mx, my)) {
				continue;
			}
			Visualizer visualizer = SceneStore.get().get(hit.id());
			if (visualizer == null) {
				continue;
			}
			switch (hit.kind()) {
				case 0 -> {
					if (visualizer.locked) {
						warn("Объект заблокирован — сними замок");
					} else if (shift()) {
						Editor3DState.get().toggleSelected(visualizer.id);
					} else {
						Editor3DState.get().selectVisualizer(visualizer.id);
					}
				}
				case 1 -> {
					SceneStore.get().beginEdit();
					visualizer.visible = !visualizer.visible;
					SceneStore.get().touch();
				}
				case 2 -> {
					SceneStore.get().beginEdit();
					visualizer.locked = !visualizer.locked;
					SceneStore.get().touch();
				}
				default -> {
				}
			}
			return true;
		}
		for (ObjHit hit : this.prefabHits) {
			if (hit.holds(mx, my)) {
				insertPrefab(hit.id());
				return true;
			}
		}
		return true;
	}

	private boolean handleNumericClick(double mx, double my) {
		for (FieldHit hit : this.numericHits) {
			if (hit.holds(mx, my)) {
				this.fieldIndex = hit.index();
				this.fieldBuffer = currentFieldValue();
				return true;
			}
		}
		if (this.fieldIndex >= 0) {
			this.fieldIndex = -1;
		}
		return false;
	}

	private boolean handleTimelineClick(double mx, double my) {
		if (!this.timelineOpen) {
			return false;
		}
		int top = this.height - TIMELINE_H;
		if (my < top) {
			return false;
		}
		if (mx < 36) {
			ScenePlayback.get().toggle();
			status(ScenePlayback.get().isPlaying() ? "Проигрывание" : "Пауза", ACCENT);
			return true;
		}
		ScenePlayback.get().scrub(timeAt(mx));
		this.drag = Drag.TIMELINE;
		return true;
	}

	// ------------------------------------------------------------- отрисовка

	private void drawTopBar(DrawContext context, int mouseX, int mouseY) {
		context.fill(0, 0, this.width, BAR_H, 0xF00B0D10);
		context.fill(0, BAR_H - 1, this.width, BAR_H, PANEL_EDGE);
		this.barHits.clear();

		int y = 4;
		int h = 20;
		int x = 8;
		x = barButton(context, x, y, 58, h, "+ Блок", 0, mouseX, mouseY, false);
		x = barButton(context, x, y, 74, h, "+ Предмет", 1, mouseX, mouseY, false);
		x += 8;
		x = barButton(context, x, y, 22, h, "M", 2, mouseX, mouseY, this.gizmo == Gizmo.MOVE);
		x = barButton(context, x, y, 22, h, "R", 3, mouseX, mouseY, this.gizmo == Gizmo.ROTATE);
		x = barButton(context, x, y, 22, h, "S", 4, mouseX, mouseY, this.gizmo == Gizmo.SCALE);
		x += 6;
		x = barButton(context, x, y, 58, h, "Snap " + (Config.get().editor3dSnap ? snapLabel() : "—"),
				7, mouseX, mouseY, Config.get().editor3dSnap);
		x = barButton(context, x, y, 42, h, Config.get().editor3dGlobalAxes ? "Мир" : "Лок",
				8, mouseX, mouseY, !Config.get().editor3dGlobalAxes);

		int exitX = this.width - 8 - 54;
		barButton(context, exitX, y, 54, h, "Выйти", 6, mouseX, mouseY, false);
		int rightX = exitX - 6;
		rightX -= 22;
		barButton(context, rightX, y, 22, h, "?", 5, mouseX, mouseY, this.helpOpen);
		rightX -= 24;
		barButton(context, rightX, y, 22, h, "N", 11, mouseX, mouseY, this.numericOpen);
		rightX -= 24;
		barButton(context, rightX, y, 22, h, "O", 10, mouseX, mouseY, this.outlinerOpen);
		rightX -= 24;
		barButton(context, rightX, y, 22, h, "T", 9, mouseX, mouseY, this.timelineOpen);

		String hint = gizmoName() + " · " + (Config.get().editor3dGlobalAxes ? "оси мир" : "оси лок")
				+ " · привязка " + (Config.get().editor3dSnap ? snapLabel() : "выкл");
		if (x + 6 + this.textRenderer.getWidth(hint) < rightX - 8) {
			context.drawText(this.textRenderer, hint, x + 6, y + 6, MUTED, false);
		}
	}

	private int barButton(DrawContext context, int x, int y, int width, int height,
			String label, int id, int mouseX, int mouseY, boolean active) {
		boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		int background = active ? 0xFF1E3A4C : hover ? 0xFF232A33 : 0xFF171B21;
		int color = active ? ACCENT : TEXT;
		context.fill(x, y, x + width, y + height, background);
		context.fill(x, y + height - 1, x + width, y + height, active ? ACCENT : 0x22FFFFFF);
		int textWidth = this.textRenderer.getWidth(label);
		context.drawText(this.textRenderer, label, x + (width - textWidth) / 2, y + (height - 8) / 2, color, false);
		this.barHits.add(new Hit(x, y, width, height, id));
		return x + width + 4;
	}

	private void drawObjectPanel(DrawContext context, int mouseX, int mouseY, int top) {
		this.panelHits.clear();
		List<Visualizer> list = selection();
		if (list.isEmpty()) {
			return;
		}
		Visualizer visualizer = list.get(0);
		String[] labels = {
				"Скрипт визуализатора", "Скрипт хитбокса",
				visualizer.hitbox ? "Выключить хитбокс" : "Включить хитбокс",
				"Копия (Ctrl+D)", "Связанная (Alt+D)", "К центру взгляда",
				"Удалить (Delete)", "Массив ×4 по X", "Кольцо ×8",
				"Сохранить префаб", "Переименовать (F2)"
		};
		int x = this.width - PANEL_W - 10;
		int y = top;
		int w = PANEL_W;
		int h = 84 + labels.length * 20 + 4;

		context.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x40000000);
		context.fill(x, y, x + w, y + h, PANEL_BG);
		context.fill(x, y, x + 3, y + h, ACCENT);
		context.fill(x, y, x + w, y + 1, PANEL_EDGE);
		context.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
		context.fill(x + w - 1, y, x + w, y + h, PANEL_EDGE);

		context.drawText(this.textRenderer, "ОБЪЕКТ" + (list.size() > 1 ? " · ещё " + (list.size() - 1) : ""),
				x + 12, y + 8, MUTED, false);
		context.drawText(this.textRenderer, fit(visualizer.displayName(), w - 24), x + 12, y + 20, TEXT, false);
		context.drawText(this.textRenderer, String.format(Locale.ROOT, "%.2f  %.2f  %.2f",
				visualizer.x, visualizer.y, visualizer.z), x + 12, y + 33, 0xFFC8D4E2, false);
		context.drawText(this.textRenderer, String.format(Locale.ROOT, "масштаб %.2f · %.0f° / %.0f° / %.0f°",
				visualizer.scale, visualizer.yaw, visualizer.pitch, visualizer.roll),
				x + 12, y + 45, MUTED, false);
		context.drawText(this.textRenderer, "слой: " + fit(SceneStore.layerOf(visualizer), w - 90),
				x + 12, y + 57, MUTED, false);
		context.drawText(this.textRenderer, "хитбокс: " + (visualizer.hitbox ? "вкл" : "выкл"),
				x + 12, y + 69, visualizer.hitbox ? OK : MUTED, false);

		int buttonY = y + 84;
		for (int i = 0; i < labels.length; i++) {
			int color = i == 6 ? DANGER : TEXT;
			panelButton(context, x + 12, buttonY, w - 24, labels[i], i, mouseX, mouseY, color);
			buttonY += 20;
		}
	}

	private void panelButton(DrawContext context, int x, int y, int width,
			String label, int id, int mouseX, int mouseY, int textColor) {
		boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 18;
		context.fill(x, y, x + width, y + 18, hover ? PANEL_BUTTON_HOVER : PANEL_BUTTON);
		context.fill(x, y + 17, x + width, y + 18, 0x22FFFFFF);
		context.drawText(this.textRenderer, fit(label, width - 12), x + 7, y + 5, textColor, false);
		this.panelHits.add(new Hit(x, y, width, 18, id));
	}

	private int outlinerPrefabTop() {
		return this.height - (this.timelineOpen ? TIMELINE_H : 0) - 128;
	}

	private void drawOutliner(DrawContext context, int mouseX, int mouseY) {
		this.outlinerHits.clear();
		this.prefabHits.clear();
		if (!this.outlinerOpen) {
			return;
		}
		int bottom = this.height - (this.timelineOpen ? TIMELINE_H : 0);
		int top = BAR_H + 6;
		int height = bottom - top - 6;
		context.fill(0, top, OUT_W, top + height, 0xF00B0D10);
		context.fill(OUT_W - 1, top, OUT_W, top + height, PANEL_EDGE);
		context.drawText(this.textRenderer, "СЦЕНА · " + SceneStore.get().all().size(), 10, top + 6, MUTED, false);
		context.drawText(this.textRenderer, "O — скрыть", OUT_W - 10 - this.textRenderer.getWidth("O — скрыть"),
				top + 6, 0xFF5C6673, false);

		int listTop = top + 20;
		int prefabTop = Math.max(listTop + 60, outlinerPrefabTop());
		List<Visualizer> objects = new ArrayList<>(SceneStore.get().list());
		objects.sort((a, b) -> {
			int byLayer = SceneStore.layerOf(a).compareToIgnoreCase(SceneStore.layerOf(b));
			return byLayer != 0 ? byLayer : a.displayName().compareToIgnoreCase(b.displayName());
		});

		context.enableScissor(0, listTop, OUT_W, prefabTop - 4);
		int rowY = listTop - this.outlinerScroll * ROW_H;
		String currentLayer = null;
		for (Visualizer visualizer : objects) {
			String layer = SceneStore.layerOf(visualizer);
			if (!layer.equals(currentLayer)) {
				currentLayer = layer;
				if (rowY >= listTop - ROW_H && rowY < prefabTop) {
					context.drawText(this.textRenderer, fit(layer.toUpperCase(Locale.ROOT), OUT_W - 16),
							10, rowY + 4, ACCENT, false);
				}
				rowY += 14;
			}
			if (rowY >= listTop - ROW_H && rowY < prefabTop) {
				boolean selected = Editor3DState.get().isSelected(visualizer.id);
				boolean hover = mouseX < OUT_W && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
				context.fill(6, rowY, OUT_W - 8, rowY + ROW_H - 2,
						selected ? 0xFF1B2A38 : hover ? 0xFF171D24 : 0x00000000);
				// глаз
				int eyeColor = visualizer.visible ? OK : 0xFF4A525C;
				context.fill(10, rowY + 5, 18, rowY + 12, eyeColor);
				// замок
				int lockColor = visualizer.locked ? WARN : 0xFF3C444E;
				context.fill(22, rowY + 5, 28, rowY + 12, lockColor);
				String label = visualizer.displayName();
				if (!visualizer.linkId.isBlank()) {
					label = "∞ " + label;
				}
				if (!visualizer.parentId.isBlank()) {
					label = "↳ " + label;
				}
				context.drawText(this.textRenderer, fit(label, OUT_W - 50), 34, rowY + 4,
						visualizer.visible ? TEXT : 0xFF6B7480, false);
				// мелкие кнопки добавляем первыми: они перекрывают строку
				this.outlinerHits.add(new ObjHit(8, rowY + 2, 12, 14, visualizer.id, 1));
				this.outlinerHits.add(new ObjHit(20, rowY + 2, 12, 14, visualizer.id, 2));
				this.outlinerHits.add(new ObjHit(6, rowY, OUT_W - 14, ROW_H - 2, visualizer.id, 0));
			}
			rowY += ROW_H;
		}
		context.disableScissor();

		// префабы
		context.fill(6, prefabTop - 2, OUT_W - 8, prefabTop, PANEL_EDGE);
		context.drawText(this.textRenderer, "ПРЕФАБЫ · ЛКМ — вставить", 10, prefabTop + 3, MUTED, false);
		int prefabY = prefabTop + 16;
		int visible = 5;
		for (int i = this.prefabScroll; i < this.prefabNames.size() && i < this.prefabScroll + visible; i++) {
			String name = this.prefabNames.get(i);
			boolean hover = mouseX < OUT_W && mouseY >= prefabY && mouseY < prefabY + 17;
			context.fill(8, prefabY, OUT_W - 10, prefabY + 16, hover ? PANEL_BUTTON_HOVER : PANEL_BUTTON);
			context.drawText(this.textRenderer, fit(name, OUT_W - 30), 14, prefabY + 4, TEXT, false);
			this.prefabHits.add(new ObjHit(8, prefabY, OUT_W - 20, 16, name, 0));
			prefabY += 18;
		}
		if (this.prefabNames.size() > visible) {
			context.drawText(this.textRenderer, (this.prefabScroll + 1) + "–"
					+ Math.min(this.prefabNames.size(), this.prefabScroll + visible)
					+ " / " + this.prefabNames.size(),
					10, prefabY + 2, 0xFF5C6673, false);
		}
	}

	private void drawNumeric(DrawContext context, int mouseX, int mouseY) {
		this.numericHits.clear();
		if (!this.numericOpen) {
			return;
		}
		Visualizer visualizer = primary();
		int w = 300;
		int h = 40 + FIELD_LABELS.length * 20;
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		context.fill(0, 0, this.width, this.height, 0x66000000);
		context.fill(x, y, x + w, y + h, 0xF00E1014);
		context.fill(x, y, x + w, y + 2, ACCENT);
		context.fill(x, y, x + 3, y + h, ACCENT);
		context.drawText(this.textRenderer, "ТОЧНЫЕ ЗНАЧЕНИЯ", x + 14, y + 10, TEXT, false);
		String title = visualizer == null ? "нет выделения" : fit(visualizer.displayName(), w - 160);
		context.drawText(this.textRenderer, title, x + w - 14 - this.textRenderer.getWidth(title),
				y + 10, MUTED, false);

		int rowY = y + 28;
		for (int i = 0; i < FIELD_LABELS.length; i++) {
			boolean focused = this.fieldIndex == i;
			context.drawText(this.textRenderer, FIELD_LABELS[i], x + 14, rowY + 5, MUTED, false);
			int fx = x + 104;
			int fw = w - 118;
			context.fill(fx, rowY, fx + fw, rowY + 17, focused ? 0xFF1B2A38 : PANEL_BUTTON);
			context.fill(fx, rowY + 16, fx + fw, rowY + 17, focused ? ACCENT : 0x22FFFFFF);
			String value = focused ? this.fieldBuffer : fieldValue(visualizer, i);
			context.drawText(this.textRenderer, value + (focused ? "_" : ""), fx + 6, rowY + 5,
					focused ? ACCENT : TEXT, false);
			this.numericHits.add(new FieldHit(fx, rowY, fw, 17, i));
			rowY += 20;
		}
		context.drawText(this.textRenderer, "N — закрыть · Tab — следующее поле · Enter — применить",
				x + 14, y + h - 12, 0xFF7D8794, false);
	}

	private String fieldValue(Visualizer visualizer, int index) {
		if (visualizer == null) {
			return "—";
		}
		return switch (index) {
			case 0 -> String.format(Locale.ROOT, "%.3f", visualizer.x);
			case 1 -> String.format(Locale.ROOT, "%.3f", visualizer.y);
			case 2 -> String.format(Locale.ROOT, "%.3f", visualizer.z);
			case 3 -> String.format(Locale.ROOT, "%.1f", visualizer.yaw);
			case 4 -> String.format(Locale.ROOT, "%.1f", visualizer.pitch);
			case 5 -> String.format(Locale.ROOT, "%.1f", visualizer.roll);
			case 6 -> String.format(Locale.ROOT, "%.3f", visualizer.scale);
			case 7 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleX);
			case 8 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleY);
			case 9 -> String.format(Locale.ROOT, "%.3f", visualizer.scaleZ);
			default -> "";
		};
	}

	private void drawTimeline(DrawContext context, int mouseX, int mouseY) {
		if (!this.timelineOpen) {
			return;
		}
		int top = this.height - TIMELINE_H;
		context.fill(0, top, this.width, this.height, 0xF00B0D10);
		context.fill(0, top, this.width, top + 1, PANEL_EDGE);

		ScenePlayback playback = ScenePlayback.get();
		boolean playHover = mouseX < 36 && mouseY >= top;
		context.fill(4, top + 5, 32, top + TIMELINE_H - 5, playHover ? 0xFF232C37 : 0xFF171B21);
		String icon = playback.isPlaying() ? "II" : "▶";
		context.drawText(this.textRenderer, icon, 18 - this.textRenderer.getWidth(icon) / 2, top + 10,
				playback.isPlaying() ? ACCENT : TEXT, false);

		int left = 46;
		int right = this.width - 64;
		int lineY = top + 15;
		context.fill(left, lineY, right, lineY + 1, 0x44FFFFFF);
		double length = playback.length();
		for (double t = 0; t <= length + 0.001; t += 1.0) {
			int x = (int) timelineX(t);
			context.fill(x, lineY - 3, x + 1, lineY + 5, 0x33FFFFFF);
		}
		// ключи всех объектов
		for (Visualizer visualizer : SceneStore.get().all()) {
			boolean selected = Editor3DState.get().isSelected(visualizer.id);
			for (Visualizer.Key key : visualizer.keys) {
				int x = (int) timelineX(key.t);
				int color = selected ? WARN : 0xFF5C6673;
				context.fill(x - 2, lineY - 2, x + 2, lineY + 3, color);
			}
		}
		int playhead = (int) timelineX(playback.time());
		context.fill(playhead, top + 5, playhead + 1, top + TIMELINE_H - 5, ACCENT);
		context.fill(playhead - 2, top + 5, playhead + 3, top + 8, ACCENT);

		String label = String.format(Locale.ROOT, "%.1f / %.1f с", playback.time(), length);
		context.drawText(this.textRenderer, label, right + 8, top + 11, MUTED, false);
		if (playback.isEnabled()) {
			context.drawText(this.textRenderer, "ANIM", left + 4, top + 11, OK, false);
		}
	}

	private void drawGizmo(DrawContext context, int mouseX, int mouseY) {
		Visualizer visualizer = primary();
		Vec3d pivot = Editor3DState.get().selectionCenter();
		if (visualizer == null || pivot == null || this.helpOpen || this.promptKind != 0) {
			return;
		}
		double[] screenPivot = project(pivot);
		if (screenPivot == null) {
			return;
		}
		int hoverAxis = this.drag == Drag.GIZMO ? this.gizmoAxis : gizmoAxisAt(mouseX, mouseY);
		double length = gizmoLength(pivot);

		context.fill((int) screenPivot[0] - 2, (int) screenPivot[1] - 2,
				(int) screenPivot[0] + 3, (int) screenPivot[1] + 3, 0xFFEAF6FF);

		if (this.gizmo == Gizmo.ROTATE) {
			for (int axis = 0; axis < 3; axis++) {
				Vec3d[] basis = ringBasis(axis, visualizer);
				int color = axis == hoverAxis ? 0xFFFFE066 : AXIS_COLORS[axis];
				int thickness = axis == hoverAxis ? 3 : 2;
				double prevX = 0;
				double prevY = 0;
				boolean hasPrev = false;
				int steps = 56;
				for (int i = 0; i <= steps; i++) {
					double angle = Math.PI * 2 * i / steps;
					Vec3d point = pivot.add(basis[0].multiply(Math.cos(angle) * length))
							.add(basis[1].multiply(Math.sin(angle) * length));
					double[] screen = project(point);
					if (screen == null) {
						hasPrev = false;
						continue;
					}
					if (hasPrev) {
						line(context, prevX, prevY, screen[0], screen[1], color, thickness);
					}
					prevX = screen[0];
					prevY = screen[1];
					hasPrev = true;
				}
			}
			return;
		}

		for (int axis = 0; axis < 3; axis++) {
			double[] edge = project(pivot.add(axisDir(axis, visualizer).multiply(length)));
			if (edge == null) {
				continue;
			}
			int color = axis == hoverAxis ? 0xFFFFE066 : AXIS_COLORS[axis];
			line(context, screenPivot[0], screenPivot[1], edge[0], edge[1], color,
					axis == hoverAxis ? 4 : 3);
			double dx = edge[0] - screenPivot[0];
			double dy = edge[1] - screenPivot[1];
			double len = Math.max(1, Math.hypot(dx, dy));
			double ux = dx / len;
			double uy = dy / len;
			if (this.gizmo == Gizmo.MOVE) {
				double tip = 8;
				line(context, edge[0], edge[1], edge[0] - ux * tip - uy * tip * 0.5,
						edge[1] - uy * tip + ux * tip * 0.5, color, 3);
				line(context, edge[0], edge[1], edge[0] - ux * tip + uy * tip * 0.5,
						edge[1] - uy * tip - ux * tip * 0.5, color, 3);
			} else {
				context.fill((int) edge[0] - 3, (int) edge[1] - 3,
						(int) edge[0] + 4, (int) edge[1] + 4, color);
			}
			String label = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
			context.drawText(this.textRenderer, label, (int) edge[0] + 5, (int) edge[1] - 4, color, false);
		}
	}

	private static void line(DrawContext context, double x1, double y1,
			double x2, double y2, int color, int thickness) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
		int half = thickness / 2;
		for (int i = 0; i <= steps; i++) {
			int x = (int) Math.round(x1 + dx * i / steps);
			int y = (int) Math.round(y1 + dy * i / steps);
			context.fill(x - half, y - half, x - half + thickness, y - half + thickness, color);
		}
	}

	private void drawPrompt(DrawContext context) {
		if (this.promptKind == 0) {
			return;
		}
		int w = 360;
		int h = 78;
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		context.fill(0, 0, this.width, this.height, 0x66000000);
		context.fill(x, y, x + w, y + h, 0xF00E1014);
		context.fill(x, y, x + w, y + 2, ACCENT);
		context.drawText(this.textRenderer, this.promptTitle, x + 12, y + 10, TEXT, false);
		context.fill(x + 12, y + 28, x + w - 12, y + 50, PANEL_BUTTON);
		context.fill(x + 12, y + 49, x + w - 12, y + 50, ACCENT);
		context.drawText(this.textRenderer, this.promptBuffer + "_", x + 18, y + 35, ACCENT, false);
		context.drawText(this.textRenderer, "Enter — сохранить · Esc — отмена", x + 12, y + 58, 0xFF7D8794, false);
	}

	private void drawBox(DrawContext context) {
		if (this.drag != Drag.BOX) {
			return;
		}
		int x0 = (int) Math.min(this.boxX0, this.boxX1);
		int y0 = (int) Math.min(this.boxY0, this.boxY1);
		int x1 = (int) Math.max(this.boxX0, this.boxX1);
		int y1 = (int) Math.max(this.boxY0, this.boxY1);
		context.fill(x0, y0, x1, y0 + 1, ACCENT);
		context.fill(x0, y1, x1, y1 + 1, ACCENT);
		context.fill(x0, y0, x0 + 1, y1, ACCENT);
		context.fill(x1, y0, x1 + 1, y1, ACCENT);
		context.fill(x0, y0, x1, y1, 0x1863D2FF);
	}

	private void drawHelp(DrawContext context) {
		int w = Math.min(this.width - 30, 760);
		int h = Math.min(this.height - 30, 340);
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		context.fill(0, 0, this.width, this.height, 0x80000000);
		context.fill(x, y, x + w, y + h, 0xF00E1014);
		context.fill(x, y, x + w, y + 2, ACCENT);
		context.fill(x, y, x + 3, y + h, ACCENT);
		context.drawText(this.textRenderer, "3D-редактор — помощь", x + 14, y + 10, TEXT, false);
		context.drawText(this.textRenderer, "F1 или ? — открыть/закрыть · Esc — закрыть",
				x + 14, y + 23, MUTED, false);

		String[][] columns = {
				{
						"КАМЕРА",
						"W A S D — полёт",
						"Space / Shift — вверх / вниз",
						"Ctrl — ускорение полёта",
						"СКМ — обзор, Shift+СКМ — сдвиг",
						"ЛКМ по пустому — обзор",
						"Alt+ЛКМ — орбита вокруг пивота",
						"Колесо — ближе / дальше",
						"Стрелки — поворот камеры",
						"F — навести на выделение",
						"Numpad 1/3/7 — вид спереди/сбоку/сверху",
						"Shift+ПКМ — поставить 3D-курсор",
				},
				{
						"ОБЪЕКТЫ",
						"Alt+1 / G — перемещение",
						"Alt+2 / R — поворот",
						"Alt+3 / S — масштаб",
						"Q — мировые / локальные оси",
						"Shift+ЛКМ — добавить к выделению",
						"Ctrl+A — выделить всё · B — рамка",
						"Ctrl во время тяги — привязка",
						"Tab — привязка вкл/выкл",
						"N — точные значения",
						"F2 — переименовать",
						"ПКМ — скрипт объекта",
				},
				{
						"СЦЕНА",
						"Ctrl+Z / Ctrl+Y — отмена / возврат",
						"Ctrl+D — копия · Alt+D — связанная",
						"Ctrl+C / Ctrl+V — копировать / вставить",
						"Delete — удалить",
						"Ctrl+P — родитель · Alt+P — отвязать",
						"O — аутлайнер, слои, префабы",
						"T — тайм-лайн · P — проиграть",
						"K — ключ · Shift+K — убрать ключ",
						"Alt+E / Alt+I — экспорт / импорт JSON",
						"Ctrl+Alt+E — команды сцены в мир (буфер + файл)",
						"Alt+2 — поворот: 3 кольца (X / Y / Z)",
						"«+ Блок» / «+ Предмет» — инвентарь",
				},
		};
		int columnWidth = (w - 28) / 3;
		for (int c = 0; c < columns.length; c++) {
			int columnX = x + 14 + c * columnWidth;
			for (int i = 0; i < columns[c].length; i++) {
				int color = i == 0 ? ACCENT : 0xFFC8D4E2;
				context.drawText(this.textRenderer, fit(columns[c][i], columnWidth - 8),
						columnX, y + 42 + i * 13, color, false);
			}
		}
		context.drawText(this.textRenderer, "Сетка: 3×3 чанка вокруг входа · сцена хранится в config/justscript/scenes",
				x + 14, y + h - 26, MUTED, false);
		context.drawText(this.textRenderer, "Esc — закрыть помощь, затем Esc — выйти из редактора",
				x + 14, y + h - 13, 0xFF7D8794, false);
	}

	private String fit(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (this.textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (!cut.isEmpty() && this.textRenderer.getWidth(cut + "…") > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "…";
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Мир должен быть виден: фон не заливаем.
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		drawTopBar(context, mouseX, mouseY);
		drawOutliner(context, mouseX, mouseY);
		drawGizmo(context, mouseX, mouseY);
		drawObjectPanel(context, mouseX, mouseY, BAR_H + 12);
		drawTimeline(context, mouseX, mouseY);
		drawBox(context);

		// прицел
		int cx = this.width / 2;
		int cy = this.height / 2;
		int color = 0xFFEAF6FF;
		context.fill(cx - 7, cy, cx - 1, cy + 1, color);
		context.fill(cx + 1, cy, cx + 7, cy + 1, color);
		context.fill(cx, cy - 7, cx + 1, cy - 1, color);
		context.fill(cx, cy + 1, cx + 1, cy + 7, color);

		// 3D-курсор
		double[] cursor = project(Editor3DState.get().cursor());
		if (cursor != null) {
			int ccx = (int) cursor[0];
			int ccy = (int) cursor[1];
			int cursorColor = 0xFFFF9F43;
			context.fill(ccx - 6, ccy, ccx - 1, ccy + 1, cursorColor);
			context.fill(ccx + 2, ccy, ccx + 7, ccy + 1, cursorColor);
			context.fill(ccx, ccy - 6, ccx + 1, ccy - 1, cursorColor);
			context.fill(ccx, ccy + 2, ccx + 1, ccy + 7, cursorColor);
		}

		Editor3DState state = Editor3DState.get();
		int hudX = this.outlinerOpen ? OUT_W + 10 : 10;
		String coords = String.format(Locale.ROOT, "%.1f %.1f %.1f · %.0f° / %.0f°",
				state.camX(), state.camY(), state.camZ(), state.yaw(), state.pitch());
		context.drawText(this.textRenderer, coords, hudX,
				this.height - (this.timelineOpen ? TIMELINE_H : 0) - 34, 0xFFBFE0FF, false);

		if (!this.status.isEmpty()) {
			context.drawText(this.textRenderer, fit(this.status, this.width - PANEL_W - 40), hudX,
					this.height - (this.timelineOpen ? TIMELINE_H : 0) - 20, this.statusColor, false);
		}
		String hint = boxMode
				? "Рамка: тяни ЛКМ · B — выйти"
				: "ЛКМ — выбрать/тянуть · Alt+ЛКМ — орбита · СКМ — обзор · Tab — привязка · F1 — помощь";
		context.drawText(this.textRenderer, fit(hint, this.width - 20), hudX,
				this.height - (this.timelineOpen ? TIMELINE_H : 0) - 46, 0xFF8FA48A, false);

		if (this.numericOpen) {
			drawNumeric(context, mouseX, mouseY);
		}
		drawPrompt(context);
		if (this.helpOpen) {
			drawHelp(context);
		}
	}

	@Override
	public void close() {
		ScenePlayback.get().stop();
		VisualizerEntities.clear();
		Editor3DState.get().exit();
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** Простая тройка координат, чтобы не тянуть лишние зависимости. */
	private record Vector3d(double x, double y, double z) {
	}
}
