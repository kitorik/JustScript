package com.justscript;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки мода. Файл: {@code config/justscript/config.json}.
 * Читается один раз и сохраняется при изменении — на игру это не влияет.
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config instance;

	// --- язык и редактор ---
	/** Язык кода: justmc-json или xero. */
	public String language = "justmc-json";
	/** Включённые языки (переключаются в меню «Языки»). */
	public java.util.List<String> enabledLanguages = new java.util.ArrayList<>(
			java.util.List.of("justmc-json", "xero", "jmcc"));
	public boolean syntaxHighlight = true;
	/** Сколько старых копий скрипта хранить (0 — не хранить). */
	public int historyLimit = 20;

	// --- цикл публикации ---
	/** Сколько тиков ждать после /dev, прежде чем грузить модуль. */
	public int devWaitTicks = 40;
	/** Сколько тиков ждать подтверждения импорта, прежде чем вернуться всё равно. */
	public int importTimeoutTicks = 200;
	public boolean autoConfirmBook = true;
	public boolean autoReturn = true;
	/** Короткие ошибки: одна строка вместо длинного списка. */
	public boolean shortErrors = true;

	// --- гаечный ключ ---
	public boolean wrenchEnabled = true;
	/** RIGHT или LEFT. */
	public String wrenchButton = "RIGHT";
	/** CTRL, SHIFT, ALT или NONE. */
	public String wrenchModifier = "CTRL";

	// --- загрузка ---
	/** auto, paste.c-net.org, dpaste.com, tmpfiles.org или custom. */
	public String uploadProvider = "auto";
	public String customUploadUrl = "";
	public int uploadTimeoutSeconds = 30;

	// --- мир кода ---
	// Скрипты в мире кода считаются обычными: отдельных настроек больше нет.

	// --- ИИ-помощник ---
	/** free — встроенная бесплатная модель, custom — свой OpenAI-совместимый агрегатор. */
	public String aiProvider = "free";
	/** Бесплатный провайдер: OpenAI-совместимый эндпоинт, ключ не нужен. */
	public String aiFreeUrl = "https://text.pollinations.ai/openai";
	public String aiFreeModel = "openai";
	/** Свой агрегатор: любой OpenAI-совместимый чат-эндпоинт. */
	public String aiCustomUrl = "https://openrouter.ai/api/v1/chat/completions";
	public String aiCustomModel = "";
	/** Ключ агрегатора. Хранится локально в config.json и никуда больше не уходит. */
	public String aiCustomKey = "";
	public int aiTimeoutSeconds = 90;
	public int aiMaxTokens = 1800;
	/** Пробовать запасные бесплатные облака, если основное не ответило. */
	public boolean aiFallbackClouds = true;
	/** Локальная Ollama — последнее запасное «облако» (если запущена). */
	public boolean aiOllama = false;
	public String aiOllamaUrl = "http://localhost:11434/v1/chat/completions";
	public String aiOllamaModel = "llama3.2";
	/** Спрашивать разрешение перед созданием скрипта для объекта. */
	public boolean aiAskPermission = true;
	/** Виды объектов, для которых ИИ разрешено создавать скрипты без спроса. */
	public java.util.List<String> aiAlwaysAllow = new java.util.ArrayList<>();

	// --- 3D-редактор ---
	/** Цвет подсветки объектов под прицелом (блок, сущность, визуализатор). */
	public String editor3dHighlight = "#4FC3F7";
	/** Толщина линий подсветки. */
	public float editor3dOutlineWidth = 2.0f;
	/** Скорость полёта камеры в 3D-редакторе, блоков за тик. */
	public float editor3dSpeed = 0.6f;
	/** Показывать сетку 3×3 чанка вокруг точки входа. */
	public boolean editor3dGrid = true;
	/** Привязка к сетке включена (Tab в редакторе). */
	public boolean editor3dSnap = true;
	/** Шаг привязки в блоках: 1/16, 1/8, 1/4, 1/2 или 1. */
	public double editor3dSnapStep = 0.25;
	/** Оси гизмо: true — мировые, false — локальные (Q в редакторе). */
	public boolean editor3dGlobalAxes = true;

	// --- сборка ---
	/** Имя модуля; %player% подставляется ником. */
	public String moduleName = "%player%_script";

	// --- меню мира ---
	public boolean worldMenuButton = true;
	public String worldMenuTitle = "Меню мира";
	/** Предмет-кнопка в меню мира. */
	public String worldMenuIcon = "minecraft:writable_book";

	public static Config get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	// ---------------------------------------------------------------- ИИ

	public boolean aiCustom() {
		return "custom".equalsIgnoreCase(aiProvider);
	}

	/** Адрес активного провайдера ИИ. */
	public String aiUrl() {
		if (aiCustom()) {
			return aiCustomUrl == null ? "" : aiCustomUrl.trim();
		}
		return aiFreeUrl == null || aiFreeUrl.isBlank() ? "https://text.pollinations.ai/openai" : aiFreeUrl.trim();
	}

	/** Модель активного провайдера ИИ. */
	public String aiModel() {
		if (aiCustom()) {
			return aiCustomModel == null ? "" : aiCustomModel.trim();
		}
		return aiFreeModel == null || aiFreeModel.isBlank() ? "openai" : aiFreeModel.trim();
	}

	/** Ключ есть только у своего агрегатора. */
	public String aiKey() {
		return aiCustom() && aiCustomKey != null ? aiCustomKey.trim() : "";
	}

	/** Разрешено ли ИИ создавать скрипт такого вида без уведомления. */
	public boolean aiAllowed(String kind) {
		if (!aiAskPermission) {
			return true;
		}
		return kind != null && aiAlwaysAllow != null && aiAlwaysAllow.contains(kind);
	}

	/** Запоминает «Разр. всегда» для вида объекта. */
	public void aiAllowAlways(String kind) {
		if (aiAlwaysAllow == null) {
			aiAlwaysAllow = new java.util.ArrayList<>();
		}
		if (kind != null && !aiAlwaysAllow.contains(kind)) {
			aiAlwaysAllow.add(kind);
		}
		save();
	}

	/** Сбрасывает все выданные ИИ разрешения. */
	public void aiResetPermissions() {
		if (aiAlwaysAllow != null) {
			aiAlwaysAllow.clear();
		}
		save();
	}

	public static void load() {
		Path path = path();
		Config config = new Config();
		if (Files.exists(path)) {
			try {
				Config loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Config.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				Log.LOGGER.warn("Не удалось прочитать конфиг {}, беру значения по умолчанию", path, e);
			}
		}
		instance = config;
		save();
	}

	public static void save() {
		if (instance == null) {
			return;
		}
		try {
			Path path = path();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось сохранить конфиг", e);
		}
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("justscript").resolve("config.json");
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("justscript");
	}
}
