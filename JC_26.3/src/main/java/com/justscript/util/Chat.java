package com.justscript.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Быстрые помощники: сообщения в чат и отправка команд на сервер. */
public final class Chat {
	private Chat() {
	}

	public static void info(String message) {
		add(Component.literal("[JustScript] ").withStyle(ChatFormatting.AQUA).append(Component.literal(message)));
	}

	public static void ok(String message) {
		add(Component.literal("[JustScript] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message).withStyle(ChatFormatting.GREEN)));
	}

	public static void warn(String message) {
		add(Component.literal("[JustScript] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
	}

	public static void error(String message) {
		add(Component.literal("[JustScript] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message).withStyle(ChatFormatting.RED)));
	}

	/** Просто строка без префикса (для длинных списков). */
	public static void raw(Component text) {
		add(text);
	}

	private static void add(Component text) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(text);
		}
	}

	/** Отправляет команду на сервер (без ведущего слеша — он не нужен). */
	public static void sendCommand(String command) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getConnection() == null) {
			return;
		}
		String clean = command.startsWith("/") ? command.substring(1) : command;
		if (clean.isBlank()) {
			return;
		}
		minecraft.getConnection().sendCommand(clean);
	}

	/** Ник текущего игрока или пустая строка. */
	public static String playerName() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getUser() == null ? "" : minecraft.getUser().getName();
	}

	public static void copy(String text) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			minecraft.keyboardHandler.setClipboard(text);
		}
	}
}
