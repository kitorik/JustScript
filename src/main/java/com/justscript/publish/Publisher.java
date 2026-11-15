package com.justscript.publish;

import com.justscript.Config;
import com.justscript.Log;
import com.justscript.net.Uploader;
import com.justscript.script.Compiler;
import com.justscript.script.ModuleRegistry;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.util.Chat;
import com.justscript.util.WorldName;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Цикл публикации: собрать всё → залить на пейст → {@code /dev} →
 * {@code /module load-url force <url>} → дождаться «Модуль … успешно импортирован» →
 * {@code /jointo <ник>}.
 *
 * <p>Игрок возвращается в мир только после подтверждения импорта от JustMC,
 * иначе после таймаута (на случай, если сервер так и не ответил).
 */
public final class Publisher {
	private record Task(int ticks, Runnable action) {
	}

	private static final List<Task> tasks = new ArrayList<>();

	/** Ждём подтверждение импорта модуля. */
	private static boolean waitingForImport;
	private static int importWaitTicks;
	private static String importModuleName = "";

	private Publisher() {
	}

	/** Подключает прослушивание чата: по нему узнаём об успешном импорте. */
	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!waitingForImport || message == null) {
				return;
			}
			String plain = message.getString().toLowerCase(Locale.ROOT);
			if (plain.contains("успешно импортирован") || plain.contains("successfully imported")) {
				finishImport();
			}
		});
	}

	public static void schedule(int ticks, Runnable action) {
		tasks.add(new Task(Math.max(0, ticks), action));
	}

	public static void tick() {
		if (waitingForImport) {
			importWaitTicks--;
			if (importWaitTicks <= 0) {
				waitingForImport = false;
				Chat.warn(Config.get().shortErrors
						? "Модуль не подтвердил импорт за отведённое время"
						: "JustMC не подтвердил импорт модуля за отведённое время");
				if (Config.get().autoReturn && !inCodeWorld()) {
					returnToWorld();
				}
			}
		}
		if (tasks.isEmpty()) {
			return;
		}
		List<Task> due = new ArrayList<>();
		List<Task> keep = new ArrayList<>();
		for (Task task : tasks) {
			if (task.ticks() <= 0) {
				due.add(task);
			} else {
				keep.add(new Task(task.ticks() - 1, task.action()));
			}
		}
		tasks.clear();
		tasks.addAll(keep);
		for (Task task : due) {
			try {
				task.action().run();
			} catch (Exception e) {
				Log.LOGGER.warn("Ошибка в отложенной задаче", e);
				Chat.error("Ошибка: " + e.getMessage());
			}
		}
	}

	/** Собирает модуль, пишет его в файл рядом с конфигом и (опционально) кладёт в буфер. */
	public static Compiler.Result compileOnly(boolean copyToClipboard) {
		Config config = Config.get();
		String name = config.moduleName.replace("%player%", Chat.playerName());
		Compiler.Result result = Compiler.compile(name, ScriptStore.get().all());
		if (!result.ok()) {
			List<String> errors = result.errors();
			if (config.shortErrors) {
				String first = errors.isEmpty() ? "неизвестная ошибка" : errors.get(0);
				String tail = errors.size() > 1 ? " (+" + (errors.size() - 1) + ")" : "";
				Chat.error("Сборка не удалась: " + shortError(first) + tail);
			} else {
				Chat.error("Сборка не удалась: " + errors.size() + " ошиб(ок)");
				for (int i = 0; i < Math.min(errors.size(), 8); i++) {
					Chat.raw(Component.literal("  • " + errors.get(i)).withStyle(ChatFormatting.GRAY));
				}
			}
			return result;
		}
		try {
			Path out = Config.rootDir().resolve("last-module.json");
			Files.createDirectories(out.getParent());
			Files.writeString(out, result.json(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Log.LOGGER.warn("Не удалось записать last-module.json", e);
		}
		ModuleRegistry.registerCompiled(name, result.json());
		if (copyToClipboard) {
			Chat.copy(result.json());
		}
		Chat.ok("Собрано обработчиков: " + result.handlers() + " (" + result.json().length() + " симв.)"
				+ (copyToClipboard ? ", JSON скопирован в буфер" : ""));
		return result;
	}

	/** Полный цикл публикации в мир. */
	public static void publish() {
		Minecraft minecraft = Minecraft.getInstance();
		Config config = Config.get();
		if (ScriptStore.get().isEmpty()) {
			Chat.warn("Нет ни одного скрипта — сначала напиши код");
			return;
		}

		Compiler.Result result = compileOnly(false);
		if (!result.ok()) {
			return;
		}

		boolean codeWorld = WorldName.inCodeWorld(minecraft);
		Chat.info(codeWorld ? "Уже в мире кода — заливаю модуль…" : "Заливаю модуль на пейст…");
		Uploader.upload(result.json(), (url, error) -> minecraft.execute(() -> {
			if (url == null) {
				if (config.shortErrors) {
					Chat.error("Не удалось залить модуль: " + shortError(error));
				} else {
					Chat.error("Не удалось залить модуль: " + error);
					Chat.warn("Собранный JSON лежит в config/justscript/last-module.json");
				}
				return;
			}
			Chat.ok("Ссылка: " + url);
			if (codeWorld) {
				// Мы уже в редакторе: /dev и /jointo не нужны.
				loadModule(config, url);
			} else {
				Chat.sendCommand("dev");
				schedule(config.devWaitTicks, () -> loadModule(config, url));
			}
		}));
	}

	private static void loadModule(Config config, String url) {
		String load = "module load-url force " + url;
		Chat.info("Загружаю модуль: /" + load);
		importModuleName = config.moduleName.replace("%player%", Chat.playerName());
		waitingForImport = true;
		importWaitTicks = Math.max(20, config.importTimeoutTicks);
		Chat.sendCommand(load);
	}

	/** JustMC сообщил, что модуль импортирован — можно возвращаться (если мы не в редакторе). */
	private static void finishImport() {
		waitingForImport = false;
		Chat.ok("Модуль импортирован: " + importModuleName);
		if (Config.get().autoReturn && !inCodeWorld()) {
			schedule(10, Publisher::returnToWorld);
		}
	}

	private static boolean inCodeWorld() {
		return WorldName.inCodeWorld(Minecraft.getInstance());
	}

	private static void returnToWorld() {
		if (inCodeWorld()) {
			return;
		}
		String player = Chat.playerName();
		if (player.isBlank()) {
			return;
		}
		Chat.sendCommand("jointo " + player);
	}

	/** Одна строка без переносов — для короткого режима ошибок. */
	private static String shortError(String error) {
		if (error == null || error.isBlank()) {
			return "нет ответа";
		}
		String value = error.replace('\n', ' ').replace('\r', ' ').trim();
		return value.length() > 140 ? value.substring(0, 137) + "…" : value;
	}

	/** Сколько скриптов попадёт в модуль. */
	public static int activeUnits() {
		int count = 0;
		for (ScriptUnit unit : ScriptStore.get().all()) {
			if (unit.code != null && !unit.code.isBlank()) {
				count++;
			}
		}
		return count;
	}
}
