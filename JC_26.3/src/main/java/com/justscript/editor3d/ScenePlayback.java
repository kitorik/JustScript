package com.justscript.editor3d;

/**
 * Проигрывание анимации сцены: время, ключевые кадры и интерполяция.
 * <p>Пока анимация включена, {@link VisualizerEntities} берёт позицию и
 * поворот не из самого объекта, а из выборки ключевых кадров — сцена на диске
 * при этом не меняется.
 */
public final class ScenePlayback {
	private static final ScenePlayback INSTANCE = new ScenePlayback();
	private static final double MIN_LENGTH = 4.0;

	private boolean enabled;
	private boolean playing;
	private double time;
	private long lastMs;

	private ScenePlayback() {
	}

	public static ScenePlayback get() {
		return INSTANCE;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isPlaying() {
		return playing;
	}

	public double time() {
		return time;
	}

	public void play() {
		if (!hasKeys()) {
			return;
		}
		enabled = true;
		playing = true;
		lastMs = System.currentTimeMillis();
	}

	public void pause() {
		playing = false;
	}

	public void stop() {
		playing = false;
		enabled = false;
		time = 0;
	}

	public void toggle() {
		if (playing) {
			pause();
		} else {
			play();
		}
	}

	/** Ручная прокрутка: включает предпросмотр без проигрывания. */
	public void scrub(double value) {
		enabled = true;
		playing = false;
		time = Math.max(0, Math.min(length(), value));
	}

	/** Двигает время вперёд по реальным часам. */
	public void tick() {
		if (!playing) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastMs == 0) {
			lastMs = now;
			return;
		}
		double delta = Math.min(0.5, (now - lastMs) / 1000.0);
		lastMs = now;
		time += delta;
		double length = length();
		if (time > length) {
			time = length <= 0 ? 0 : time % length;
		}
	}

	/** Длина анимации: самый поздний ключевой кадр во всей сцене. */
	public double length() {
		double max = MIN_LENGTH;
		for (Visualizer visualizer : SceneStore.get().all()) {
			for (Visualizer.Key key : visualizer.keys) {
				max = Math.max(max, key.t);
			}
		}
		return max;
	}

	public boolean hasKeys() {
		for (Visualizer visualizer : SceneStore.get().all()) {
			if (!visualizer.keys.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Ключ ближайший к текущему времени у объекта (для индикатора). */
	public static boolean hasKeyAt(Visualizer visualizer, double time) {
		for (Visualizer.Key key : visualizer.keys) {
			if (Math.abs(key.t - time) <= 0.08) {
				return true;
			}
		}
		return false;
	}

	/** Ставит/обновляет ключ на текущем времени. */
	public void addKey(Visualizer visualizer, double at) {
		visualizer.keys.removeIf(key -> Math.abs(key.t - at) <= 0.02);
		visualizer.keys.add(new Visualizer.Key(at, visualizer.x, visualizer.y, visualizer.z,
				visualizer.scale, visualizer.yaw, visualizer.pitch, visualizer.roll));
		visualizer.keys.sort((a, b) -> Double.compare(a.t, b.t));
		enabled = true;
	}

	public boolean removeKey(Visualizer visualizer, double at) {
		return visualizer.keys.removeIf(key -> Math.abs(key.t - at) <= 0.08);
	}

	/**
	 * Текущая поза объекта: null — анимация выключена или ключей нет,
	 * иначе интерполированный ключ.
	 */
	public Visualizer.Key sample(Visualizer visualizer) {
		if (!enabled || visualizer.keys.isEmpty()) {
			return null;
		}
		Visualizer.Key before = null;
		Visualizer.Key after = null;
		for (Visualizer.Key key : visualizer.keys) {
			if (key.t <= this.time) {
				if (before == null || key.t > before.t) {
					before = key;
				}
			}
			if (key.t >= this.time) {
				if (after == null || key.t < after.t) {
					after = key;
				}
			}
		}
		if (before == null && after == null) {
			return null;
		}
		if (before == null) {
			return after;
		}
		if (after == null || after.t - before.t < 1e-4) {
			return before;
		}
		double span = after.t - before.t;
		double k = Math.max(0, Math.min(1, (this.time - before.t) / span));
		return new Visualizer.Key(this.time,
				before.x + (after.x - before.x) * k,
				before.y + (after.y - before.y) * k,
				before.z + (after.z - before.z) * k,
				before.scale + (after.scale - before.scale) * k,
				before.yaw + (after.yaw - before.yaw) * (float) k,
				before.pitch + (after.pitch - before.pitch) * (float) k,
				before.roll + (after.roll - before.roll) * (float) k);
	}
}
