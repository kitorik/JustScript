package com.justscript.editor3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Визуализатор 3D-редактора: блок или предмет, поставленный в мире как
 * «декорация». Хранится в сцене проекта, а не в мире сервера.
 */
public final class Visualizer {
	/** Стабильный id: он же основа кастомного тега хитбокса. */
	public String id = UUID.randomUUID().toString();
	/** block или item. */
	public String kind = "block";
	/** ID блока или предмета, например {@code minecraft:stone}. */
	public String value = "minecraft:stone";
	public double x;
	public double y;
	public double z;
	/** Равномерный размер визуализатора (1 = один блок). */
	public double scale = 1.0;
	/** Неравномерный размер: множители к scale по осям (1 = как scale). */
	public double scaleX = 1.0;
	public double scaleY = 1.0;
	public double scaleZ = 1.0;
	public float yaw;
	public float pitch;
	public float roll;
	/** Накладывать ли на визуализатор хитбокс (сущность взаимодействия). */
	public boolean hitbox;
	/** Скрипт визуализатора (категория «Блоки»). */
	public String blockScriptId = "";
	/** Скрипт хитбокса: события сущности взаимодействия. */
	public String hitboxScriptId = "";

	/** Пользовательское имя объекта в аутлайнере (пусто — автоимя). */
	public String name = "";
	/** Слой/коллекция: объекты одного слоя можно прятать и блокировать. */
	public String layer = "Общее";
	public boolean visible = true;
	public boolean locked = false;
	/** Связанные копии: одинаковый linkId — общий вид, разные позиции. */
	public String linkId = "";
	/** Родитель: при перемещении родителя дети едут вместе с ним. */
	public String parentId = "";

	/** Ключевые кадры анимации (время в секундах). */
	public List<Key> keys = new ArrayList<>();

	public static final class Key {
		public double t;
		public double x;
		public double y;
		public double z;
		public double scale = 1.0;
		public float yaw;
		public float pitch;
		public float roll;

		public Key() {
		}

		public Key(double t, double x, double y, double z, double scale,
				float yaw, float pitch, float roll) {
			this.t = t;
			this.x = x;
			this.y = y;
			this.z = z;
			this.scale = scale;
			this.yaw = yaw;
			this.pitch = pitch;
			this.roll = roll;
		}

		public Key copy() {
			return new Key(t, x, y, z, scale, yaw, pitch, roll);
		}
	}

	/** Сдвиг как в JustMC: визуализаторы смещены на 0.5 по X, Y и Z. */
	public static final double JUSTMC_OFFSET = 0.5;

	public double sizeX() {
		return Math.max(0.02, scale * scaleX);
	}

	public double sizeY() {
		return Math.max(0.02, scale * scaleY);
	}

	public double sizeZ() {
		return Math.max(0.02, scale * scaleZ);
	}

	/** Кастомный тег сущности взаимодействия. */
	public String hitboxTag() {
		return "justcreativeplus:" + id;
	}

	public String displayName() {
		if (name != null && !name.isBlank()) {
			return name;
		}
		String shortValue = value == null ? "?" : value.replace("minecraft:", "");
		return (kind.equals("item") ? "Предмет " : "Блок ") + shortValue;
	}

	public Visualizer copy() {
		Visualizer copy = new Visualizer();
		copy.id = id;
		copy.kind = kind;
		copy.value = value;
		copy.x = x;
		copy.y = y;
		copy.z = z;
		copy.scale = scale;
		copy.scaleX = scaleX;
		copy.scaleY = scaleY;
		copy.scaleZ = scaleZ;
		copy.yaw = yaw;
		copy.pitch = pitch;
		copy.roll = roll;
		copy.hitbox = hitbox;
		copy.blockScriptId = blockScriptId;
		copy.hitboxScriptId = hitboxScriptId;
		copy.name = name;
		copy.layer = layer;
		copy.visible = visible;
		copy.locked = locked;
		copy.linkId = linkId;
		copy.parentId = parentId;
		copy.keys = new ArrayList<>();
		for (Key key : keys) {
			copy.keys.add(key.copy());
		}
		return copy;
	}
}
