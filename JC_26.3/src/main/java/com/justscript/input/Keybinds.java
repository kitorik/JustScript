package com.justscript.input;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;

/**
 * Клавиши мода живут в ванильных настройках (Управление → Назначение клавиш → JustScript).
 *
 * <p>«Ключ» — это два бинда: кнопка (по умолчанию ПКМ) и модификатор (по умолчанию Ctrl).
 * Если модификатор отвязать (Esc), код-меню открывается без него.
 */
public final class Keybinds {
	public static final String CATEGORY = "category.justscript";
	/** Группа биндов в ванильных настройках: key.category.justscript.main. */
	private static final KeyMapping.Category GROUP =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("justscript", "main"));

	private static KeyMapping wrenchButton;
	private static KeyMapping wrenchModifier;
	private static KeyMapping menu;

	private Keybinds() {
	}

	public static void register() {
		menu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.justscript.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, GROUP));
		wrenchButton = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.justscript.wrench", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_RIGHT, GROUP));
		wrenchModifier = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.justscript.modifier", InputConstants.Type.KEYBOARD, InputConstants.KEY_LCONTROL, GROUP));
	}

	/** Нажатие клавиши меню (сбрасывает счётчик, как wasPressed). */
	public static boolean menuPressed() {
		return menu != null && menu.consumeClick();
	}

	/** Текст привязанной клавиши меню для подсказок. */
	public static String describeMenu() {
		return menu == null ? "не задано" : menu.getTranslatedKeyMessage().getString();
	}

	/** Кнопка код-меню: ПКМ, ЛКМ или другая — как назначено игроком. */
	public static String describeWrenchButton() {
		return wrenchButton == null ? "не задано" : wrenchButton.getTranslatedKeyMessage().getString();
	}

	/** Модификатор код-меню; пустая строка — модификатор не нужен. */
	public static String describeModifier() {
		if (wrenchModifier == null) {
			return "";
		}
		InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(wrenchModifier);
		if (bound == null || bound.equals(InputConstants.UNKNOWN)) {
			return "";
		}
		return wrenchModifier.getTranslatedKeyMessage().getString();
	}

	public static boolean buttonPressed() {
		return wrenchButton != null && wrenchButton.isDown();
	}

	/** Ключ привязан к левой кнопке мыши. */
	public static boolean leftButton() {
		if (wrenchButton == null) {
			return false;
		}
		InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(wrenchButton);
		return bound != null && bound.getType() == InputConstants.Type.MOUSE
				&& bound.getValue() == InputConstants.MOUSE_BUTTON_LEFT;
	}

	/** Модификатор не задан (Esc) — значит, он не требуется. */
	public static boolean modifierPressed() {
		if (wrenchModifier == null) {
			return true;
		}
		InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(wrenchModifier);
		if (bound == null || bound.equals(InputConstants.UNKNOWN)) {
			return true;
		}
		return wrenchModifier.isDown();
	}

	/** Текст для подсказок: «ПКМ + Left Control». */
	public static String describe() {
		if (wrenchButton == null) {
			return "не задано";
		}
		String modifier = "";
		if (wrenchModifier != null) {
			InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(wrenchModifier);
			if (bound != null && !bound.equals(InputConstants.UNKNOWN)) {
				modifier = wrenchModifier.getTranslatedKeyMessage().getString() + " + ";
			}
		}
		return modifier + wrenchButton.getTranslatedKeyMessage().getString();
	}
}