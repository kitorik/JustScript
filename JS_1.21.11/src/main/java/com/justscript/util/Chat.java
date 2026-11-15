package com.justscript.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Быстрые помощники: сообщения в чат и отправка команд на сервер. */
public final class Chat {
	private Chat() {
	}

	public static void info(String message) {
		add(Text.literal("[JustScript] ").formatted(Formatting.AQUA).append(Text.literal(message)));
	}

	public static void ok(String message) {
		add(Text.literal("[JustScript] ").formatted(Formatting.AQUA)
				.append(Text.literal(message).formatted(Formatting.GREEN)));
	}

	public static void warn(String message) {
		add(Text.literal("[JustScript] ").formatted(Formatting.AQUA)
				.append(Text.literal(message).formatted(Formatting.YELLOW)));
	}

	public static void error(String message) {
		add(Text.literal("[JustScript] ").formatted(Formatting.AQUA)
				.append(Text.literal(message).formatted(Formatting.RED)));
	}

	/** Просто строка без префикса (для длинных списков). */
	public static void raw(Text text) {
		add(text);
	}

	private static void add(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(text);
		}
	}

	/** Отправляет команду на сервер (без ведущего слеша — он не нужен). */
	public static void sendCommand(String command) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return;
		}
		String clean = command.startsWith("/") ? command.substring(1) : command;
		if (clean.isBlank()) {
			return;
		}
		client.getNetworkHandler().sendChatCommand(clean);
	}

	/** Ник текущего игрока или пустая строка. */
	public static String playerName() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.getSession() == null ? "" : client.getSession().getUsername();
	}

	public static void copy(String text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.keyboard.setClipboard(text);
		}
	}
}
