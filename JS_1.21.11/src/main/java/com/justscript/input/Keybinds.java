package com.justscript.input;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Клавиши мода живут в ванильных настройках (Управление → Назначение клавиш → JustScript).
 *
 * <p>«Ключ» — это два бинда: кнопка (по умолчанию ПКМ) и модификатор (по умолчанию Ctrl).
 * Если модификатор отвязать (Esc), код-меню открывается без него.
 */
public final class Keybinds {
	public static final String CATEGORY = "category.justscript";
	/** Группа биндов в ванильных настройках: key.category.justscript.main. */
	private static final KeyBinding.Category GROUP =
			KeyBinding.Category.create(Identifier.of("justscript", "main"));

	private static KeyBinding wrenchButton;
	private static KeyBinding wrenchModifier;
	private static KeyBinding menu;

	private Keybinds() {
	}

	public static void register() {
		menu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.justscript.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, GROUP));
		wrenchButton = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.justscript.wrench", InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GROUP));
		wrenchModifier = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.justscript.modifier", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, GROUP));
	}

	/** Нажатие клавиши меню (сбрасывает счётчик, как wasPressed). */
	public static boolean menuPressed() {
		return menu != null && menu.wasPressed();
	}

	/** Текст привязанной клавиши меню для подсказок. */
	public static String describeMenu() {
		return menu == null ? "не задано" : menu.getBoundKeyLocalizedText().getString();
	}

	/** Кнопка код-меню: ПКМ, ЛКМ или другая — как назначено игроком. */
	public static String describeWrenchButton() {
		return wrenchButton == null ? "не задано" : wrenchButton.getBoundKeyLocalizedText().getString();
	}

	/** Модификатор код-меню; пустая строка — модификатор не нужен. */
	public static String describeModifier() {
		if (wrenchModifier == null) {
			return "";
		}
		InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(wrenchModifier);
		if (bound == null || bound.equals(InputUtil.UNKNOWN_KEY)) {
			return "";
		}
		return wrenchModifier.getBoundKeyLocalizedText().getString();
	}

	public static boolean buttonPressed() {
		return wrenchButton != null && wrenchButton.isPressed();
	}

	/** Ключ привязан к левой кнопке мыши. */
	public static boolean leftButton() {
		if (wrenchButton == null) {
			return false;
		}
		InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(wrenchButton);
		return bound != null && bound.getCategory() == InputUtil.Type.MOUSE
				&& bound.getCode() == GLFW.GLFW_MOUSE_BUTTON_LEFT;
	}

	/** Модификатор не задан (Esc) — значит, он не требуется. */
	public static boolean modifierPressed() {
		if (wrenchModifier == null) {
			return true;
		}
		InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(wrenchModifier);
		if (bound == null || bound.equals(InputUtil.UNKNOWN_KEY)) {
			return true;
		}
		return wrenchModifier.isPressed();
	}

	/** Текст для подсказок: «ПКМ + Left Control». */
	public static String describe() {
		if (wrenchButton == null) {
			return "не задано";
		}
		String modifier = "";
		if (wrenchModifier != null) {
			InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(wrenchModifier);
			if (bound != null && !bound.equals(InputUtil.UNKNOWN_KEY)) {
				modifier = wrenchModifier.getBoundKeyLocalizedText().getString() + " + ";
			}
		}
		return modifier + wrenchButton.getBoundKeyLocalizedText().getString();
	}
}