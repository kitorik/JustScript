package com.justscript.util;

import com.justscript.mixin.PlayerListHudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Objective;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определяет имя мира JustMC по табло/списку игроков и чистит суффикс.
 *
 * <p>В редакторе JustMC мир называется так же, как в игре, но с суффиксом
 * {@code _creativeplus_editor}. Суффикс срезается, чтобы мир и его редактор
 * попадали в один проект. Завершающие {@code _} тоже убираются.
 */
public final class WorldName {
	private static final Pattern LABEL = Pattern.compile(
			"(?iu)^\\s*(?:мир|world|локация|location|world\\s*name)\\s*[:\\-—]\\s*(.+?)\\s*$");
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,39}");
	private static final Set<String> STOPWORDS = Set.of(
			"justmc", "justmc.ru", "creative", "creativeplus", "creative_plus", "editor",
			"world", "мир", "сервер", "лобби", "lobby", "hub", "players", "игроки",
			"online", "онлайн", "menu", "меню");

	private WorldName() {
	}

	/** Имя мира или пустая строка, если определить не удалось. */
	public static String detect(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null) {
			return "";
		}
		for (String text : texts(minecraft)) {
			String name = extract(text);
			if (!name.isEmpty()) {
				return name;
			}
		}
		return "";
	}

	/**
	 * Игрок уже в мире кода JustMC ({@code _creativeplus_editor}).
	 * Там не нужны {@code /dev} и возврат через {@code /jointo}.
	 */
	public static boolean inCodeWorld(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null) {
			return false;
		}
		String dimension = minecraft.level.dimension().identifier().toString().toLowerCase(Locale.ROOT);
		if (isEditorMarker(dimension)) {
			return true;
		}
		for (String text : texts(minecraft)) {
			if (isEditorMarker(stripFormatting(text))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isEditorMarker(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.contains("creativeplus_editor") || lower.contains("creative_plus_editor");
	}

	/** Сырые строки табло и списка игроков — без очистки суффикса редактора. */
	private static List<String> texts(Minecraft minecraft) {
		List<String> texts = new ArrayList<>();
		PlayerTabOverlay listHud = minecraft.gui == null ? null : minecraft.gui.getTabList();
		if (listHud instanceof PlayerListHudAccessor accessor) {
			add(texts, accessor.js$getHeader());
			add(texts, accessor.js$getFooter());
		}
		Scoreboard scoreboard = minecraft.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective != null) {
			add(texts, objective.getDisplayName());
			for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
				add(texts, Component.literal(entry.owner()));
			}
		}
		return texts;
	}

	private static void add(List<String> target, Component text) {
		if (text != null) {
			target.add(text.getString());
		}
	}

	/** Ищет в строке осмысленное имя мира. */
	public static String extract(String raw) {
		if (raw == null) {
			return "";
		}
		for (String line : stripFormatting(raw).split("\\R")) {
			String text = line.trim();
			if (text.isEmpty()) {
				continue;
			}
			Matcher label = LABEL.matcher(text);
			if (label.matches()) {
				text = label.group(1).trim();
			}
			text = text.replaceAll("^[\\[<«(]+|[\\]>»)]+$", "").trim();
			if (text.endsWith("_")) {
				text = text.replaceAll("_+$", "");
			}
			if (text.isEmpty() || text.length() > 40) {
				continue;
			}
			Matcher token = TOKEN.matcher(text);
			if (!token.matches()) {
				continue;
			}
			String lower = text.toLowerCase(Locale.ROOT);
			if (STOPWORDS.contains(lower)) {
				continue;
			}
			String clean = sanitize(text);
			if (clean.length() >= 2 && !STOPWORDS.contains(clean.toLowerCase(Locale.ROOT))) {
				return clean;
			}
		}
		return "";
	}

	/** Убирает суффикс редактора, хвостовые подчёркивания и опасные для пути символы. */
	public static String sanitize(String name) {
		if (name == null) {
			return "";
		}
		String value = stripFormatting(name).trim();
		value = value.replaceAll("(?iu)_?creativeplus_editor$", "");
		value = value.replaceAll("_+$", "");
		value = value.replaceAll("[^A-Za-z0-9._-]", "_");
		value = value.replaceAll("_+", "_");
		value = value.replaceAll("^[._-]+|[._-]+$", "");
		return value;
	}

	public static String stripFormatting(String text) {
		return text == null ? "" : text.replaceAll("(?iu)\u00a7[0-9A-FK-ORX]", "");
	}
}