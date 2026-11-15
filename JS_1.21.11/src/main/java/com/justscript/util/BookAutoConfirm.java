package com.justscript.util;

import com.justscript.Config;
import com.justscript.Log;
import com.justscript.mixin.BookScreenAccessor;
import com.justscript.publish.Publisher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Автоподтверждение книжки «ПРИНЯТЬ / ОТКАЗ», которую показывает
 * {@code /module load-url}: ищем в страницах книги кликабельный текст
 * с командой и выполняем её.
 */
public final class BookAutoConfirm {
	private static final Set<Object> HANDLED = Collections.newSetFromMap(new IdentityHashMap<>());

	private BookAutoConfirm() {
	}

	public static void tryConfirm(Object screen) {
		attempt(screen, 4);
	}

	private static void attempt(Object screen, int retriesLeft) {
		try {
			if (!Config.get().autoConfirmBook || !(screen instanceof BookScreen book)) {
				return;
			}
			if (HANDLED.contains(book)) {
				return;
			}
			if (MinecraftClient.getInstance().currentScreen != book) {
				return;
			}
			String command = findConfirmCommand(book);
			if (command == null) {
				if (retriesLeft > 0) {
					Publisher.schedule(15, () -> attempt(screen, retriesLeft - 1));
				} else {
					Log.LOGGER.info("Книжка открыта, но кнопка подтверждения не найдена — подтверди вручную");
				}
				return;
			}
			HANDLED.add(book);
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> {
				Chat.ok("Подтверждаю загрузку модуля…");
				Chat.sendCommand(command);
			});
		} catch (Exception e) {
			Log.LOGGER.warn("Автоподтверждение книжки не удалось", e);
		}
	}

	private static String findConfirmCommand(BookScreen book) {
		BookScreen.Contents contents = ((BookScreenAccessor) book).js$getContents();
		if (contents == null) {
			return null;
		}
		List<String[]> candidates = new ArrayList<>();
		for (int i = 0; i < contents.getPageCount(); i++) {
			Text page = contents.getPage(i);
			if (page != null) {
				collect(page, candidates);
			}
		}
		for (String[] candidate : candidates) {
			String label = candidate[0].toLowerCase();
			if (label.contains("принять") || label.contains("accept") || label.contains("подтверд")) {
				return candidate[1];
			}
		}
		return candidates.size() == 1 ? candidates.get(0)[1] : null;
	}

	private static void collect(Text text, List<String[]> out) {
		Style style = text.getStyle();
		if (style != null && style.getClickEvent() != null) {
			String command = runCommandOf(style.getClickEvent());
			if (command != null && !command.isBlank()) {
				out.add(new String[]{text.getString(), command});
			}
		}
		for (Text sibling : text.getSiblings()) {
			collect(sibling, out);
		}
	}

	/** Достаёт команду только у клика «выполнить команду». */
	private static String runCommandOf(ClickEvent event) {
		String name = event.getClass().getSimpleName().toLowerCase();
		if (!name.contains("runcommand") && !name.contains("run_command")) {
			return null;
		}
		try {
			Method method = event.getClass().getMethod("command");
			Object value = method.invoke(event);
			return value instanceof String string ? string : null;
		} catch (Exception e) {
			return null;
		}
	}
}
