package com.justscript.editor3d;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Состояние 3D-редактора: активен ли он, где свободная камера, что выделено
 * (мультивыбор), где 3D-курсор и что сейчас под прицелом.
 */
public final class Editor3DState {
	private static final Editor3DState INSTANCE = new Editor3DState();

	private boolean active;
	private double camX;
	private double camY;
	private double camZ;
	private float yaw;
	private float pitch;

	/** Мультивыбор: первый элемент — «главный» объект панели. */
	private final Set<String> selected = new LinkedHashSet<>();
	private String hoveredVisualizer = "";
	private BlockPos hoveredBlock;
	private String hoveredEntity = "";

	/** 3D-курсор: точка, вокруг которой крутим камеру и ставим объекты. */
	private double cursorX;
	private double cursorY;
	private double cursorZ;
	private boolean cursorSet;

	/** Центр зоны 3×3 чанка: вокруг него рисуется сетка. */
	private double areaX;
	private double areaZ;

	private double savedX;
	private double savedY;
	private double savedZ;
	private float savedYaw;
	private float savedPitch;

	private Editor3DState() {
	}

	public static Editor3DState get() {
		return INSTANCE;
	}

	public boolean isActive() {
		return active;
	}

	public void enter(MinecraftClient client) {
		if (active || client.player == null) {
			return;
		}
		active = true;
		camX = client.player.getX();
		camY = client.player.getEyeY();
		camZ = client.player.getZ();
		yaw = client.player.getYaw();
		pitch = client.player.getPitch();
		savedX = client.player.getX();
		savedY = client.player.getY();
		savedZ = client.player.getZ();
		savedYaw = yaw;
		savedPitch = pitch;
		areaX = camX;
		areaZ = camZ;
		selected.clear();
		hoveredVisualizer = "";
		hoveredEntity = "";
		hoveredBlock = null;
		cursorX = camX;
		cursorY = camY - 1.6;
		cursorZ = camZ;
		cursorSet = false;
	}

	public void exit() {
		active = false;
		selected.clear();
		hoveredVisualizer = "";
		hoveredEntity = "";
		hoveredBlock = null;
	}

	public void moveCamera(double dx, double dy, double dz) {
		camX += dx;
		camY += dy;
		camZ += dz;
	}

	public void rotate(float deltaYaw, float deltaPitch) {
		yaw += deltaYaw;
		pitch = Math.max(-89.5f, Math.min(89.5f, pitch + deltaPitch));
	}

	public double camX() {
		return camX;
	}

	public double camY() {
		return camY;
	}

	public double camZ() {
		return camZ;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	public Vec3d cameraPos() {
		return new Vec3d(camX, camY, camZ);
	}

	/** Вектор взгляда камеры. */
	public Vec3d lookVector() {
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		double cosPitch = Math.cos(pitchRad);
		return new Vec3d(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad),
				Math.cos(yawRad) * cosPitch);
	}

	/** Направить камеру в точку, не двигая её. */
	public void setLookAt(Vec3d target) {
		Vec3d delta = target.subtract(cameraPos());
		double length = delta.length();
		if (length < 1e-5) {
			return;
		}
		yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		pitch = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -delta.y / length))));
		pitch = Math.max(-89.5f, Math.min(89.5f, pitch));
	}

	/** Орбита вокруг точки: тянем мышь, камера облетает пивот. */
	public void orbitAround(Vec3d pivot, double deltaYaw, double deltaPitch) {
		Vec3d offset = cameraPos().subtract(pivot);
		if (offset.length() < 0.4) {
			offset = new Vec3d(0.4, 0.4, 0.4);
		}
		Vec3d rotated = rotateYXZ(offset, deltaYaw, deltaPitch, 0);
		camX = pivot.x + rotated.x;
		camY = pivot.y + rotated.y;
		camZ = pivot.z + rotated.z;
		setLookAt(pivot);
	}

	/** Поворот вектора в порядке YXZ — как у визуализаторов. */
	public static Vec3d rotateYXZ(Vec3d v, double yawDeg, double pitchDeg, double rollDeg) {
		double yawRad = Math.toRadians(yawDeg);
		double pitchRad = Math.toRadians(pitchDeg);
		double rollRad = Math.toRadians(rollDeg);
		double cy = Math.cos(yawRad);
		double sy = Math.sin(yawRad);
		double cx = Math.cos(pitchRad);
		double sx = Math.sin(pitchRad);
		double cz = Math.cos(rollRad);
		double sz = Math.sin(rollRad);
		double x1 = v.x * cz - v.y * sz;
		double y1 = v.x * sz + v.y * cz;
		double z1 = v.z;
		double y2 = y1 * cx - z1 * sx;
		double z2 = y1 * sx + z1 * cx;
		double x3 = x1 * cy + z2 * sy;
		double z3 = -x1 * sy + z2 * cy;
		return new Vec3d(x3, y2, z3);
	}

	/** Вид спереди/сбоку/сверху — как Numpad 1/3/7 в Blender. */
	public void viewFrom(String direction) {
		switch (direction) {
			case "front" -> {
				yaw = 180f;
				pitch = 0f;
			}
			case "back" -> {
				yaw = 0f;
				pitch = 0f;
			}
			case "side" -> {
				yaw = 90f;
				pitch = 0f;
			}
			case "top" -> pitch = -89.5f;
			case "bottom" -> pitch = 89.5f;
			default -> {
			}
		}
	}

	// ------------------------------------------------------------- выделение

	public String selectedVisualizer() {
		for (String id : selected) {
			return id;
		}
		return "";
	}

	public Set<String> selectedIds() {
		return selected;
	}

	public boolean isSelected(String id) {
		return id != null && selected.contains(id);
	}

	public boolean hasSelection() {
		return !selected.isEmpty();
	}

	public int selectionSize() {
		return selected.size();
	}

	public void selectVisualizer(String id) {
		selected.clear();
		if (id != null && !id.isEmpty()) {
			selected.add(id);
		}
	}

	public void addSelected(String id) {
		if (id != null && !id.isEmpty()) {
			selected.add(id);
		}
	}

	public void toggleSelected(String id) {
		if (id == null || id.isEmpty()) {
			return;
		}
		if (!selected.remove(id)) {
			selected.add(id);
		}
	}

	public void selectAll(Collection<Visualizer> visualizers) {
		selected.clear();
		for (Visualizer visualizer : visualizers) {
			if (visualizer.visible && !visualizer.locked) {
				selected.add(visualizer.id);
			}
		}
	}

	public void clearSelection() {
		selected.clear();
	}

	/** Центр выделения или null, если ничего не выбрано. */
	public Vec3d selectionCenter() {
		double sx = 0;
		double sy = 0;
		double sz = 0;
		int count = 0;
		for (String id : selected) {
			Visualizer visualizer = SceneStore.get().get(id);
			if (visualizer == null) {
				continue;
			}
			sx += visualizer.x;
			sy += visualizer.y;
			sz += visualizer.z;
			count++;
		}
		if (count == 0) {
			return null;
		}
		return new Vec3d(sx / count, sy / count, sz / count);
	}

	/** Пивот трансформаций: центр выделения, иначе 3D-курсор. */
	public Vec3d pivot() {
		Vec3d center = selectionCenter();
		return center != null ? center : cursor();
	}

	// ------------------------------------------------------------- 3D-курсор

	public Vec3d cursor() {
		if (!cursorSet) {
			return cameraPos().add(lookVector().multiply(4));
		}
		return new Vec3d(cursorX, cursorY, cursorZ);
	}

	public void setCursor(Vec3d position) {
		cursorX = position.x;
		cursorY = position.y;
		cursorZ = position.z;
		cursorSet = true;
	}

	// ------------------------------------------------------------- прицел

	public String hoveredVisualizer() {
		return hoveredVisualizer;
	}

	public void setHovered(String visualizerId, BlockPos block, String entityId) {
		hoveredVisualizer = visualizerId == null ? "" : visualizerId;
		hoveredBlock = block;
		hoveredEntity = entityId == null ? "" : entityId;
	}

	public BlockPos hoveredBlock() {
		return hoveredBlock;
	}

	public String hoveredEntity() {
		return hoveredEntity;
	}

	public double areaX() {
		return areaX;
	}

	public double areaZ() {
		return areaZ;
	}

	/** Границы полотна 3×3 чанка (48×48 блоков). */
	public Box areaBounds() {
		return new Box(areaX - 24, camY - 32, areaZ - 24, areaX + 24, camY + 32, areaZ + 24);
	}

	/** Box визуализатора: он центрирован в своей точке. */
	public static Box visualizerBounds(Visualizer visualizer) {
		double halfX = Math.max(0.05, visualizer.sizeX() / 2.0);
		double halfY = Math.max(0.05, visualizer.sizeY() / 2.0);
		double halfZ = Math.max(0.05, visualizer.sizeZ() / 2.0);
		double offset = Visualizer.JUSTMC_OFFSET;
		return new Box(visualizer.x + offset - halfX, visualizer.y + offset - halfY,
				visualizer.z + offset - halfZ, visualizer.x + offset + halfX,
				visualizer.y + offset + halfY, visualizer.z + offset + halfZ);
	}

	/** Координаты игрока на момент входа — для возврата тела. */
	public double savedX() {
		return savedX;
	}

	public double savedY() {
		return savedY;
	}

	public double savedZ() {
		return savedZ;
	}

	public float savedYaw() {
		return savedYaw;
	}

	public float savedPitch() {
		return savedPitch;
	}
}
