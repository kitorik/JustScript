package com.justscript.ui;

import com.justscript.ai.AiProposal;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Всплывающие уведомления снизу справа.
 *
 * <p>Панель выезжает из-за правого края и плавно останавливается с отступом
 * от границы экрана. Внизу — три кнопки: «Отклонить», «Принять» и
 * «Разр. всегда». Работает и в меню (через события экранов), и в игре
 * (через HUD-элемент).
 *
 * <p>Кнопки нажимаются, пока открыт любой экран. В игре уведомление видно,
 * но чтобы ответить, нужно открыть меню мода клавишей {@code J}.
 */
public final class NotificationCenter {
	/** Отступ от границы экрана: примерно 0,75 см при обычном GUI. */
	private static final int MARGIN = 12;
	/** Ширина панели: около 7 см и больше — чтобы влезли три кнопки. */
	private static final int MIN_WIDTH = 280;
	private static final int MAX_WIDTH = 360;
	private static final int HEIGHT = 100;
	private static final int GAP = 8;
	private static final int IN_MS = 340;
	private static final int OUT_MS = 220;

	private static final int PANEL = 0xF01A1D22;
	private static final int EDGE = 0x33FFFFFF;
	private static final int ACCENT = 0xFFA3E635;
	private static final int TITLE = 0xFFE8ECF2;
	private static final int MESSAGE = 0xFFD6DEE8;
	private static final int DETAIL = 0xFF8B95A3;
	private static final int HINT = 0xFF6B7480;

	private static final int BUTTON_BG = 0xFFD5D9DF;
	private static final int BUTTON_HOVER = 0xFFE6EAEF;
	private static final int BUTTON_TEXT = 0xFF1B1F24;
	private static final int LIME_BG = 0xFFA3E635;
	private static final int LIME_HOVER = 0xFFBEF264;
	private static final int LIME_TEXT = 0xFF16200A;

	private static final NotificationCenter INSTANCE = new NotificationCenter();

	public enum Decision {
		DENY, ACCEPT, ALWAYS
	}

	public interface PermissionCallback {
		void done(Decision decision);
	}

	private static final class Toast {
		final String title;
		final String message;
		final String detail;
		final PermissionCallback callback;
		final long born = Util.getMillis();
		long closing = -1;

		int x;
		int y;
		int w;
		int h;
		final List<int[]> buttonRects = new ArrayList<>(3);

		Toast(String title, String message, String detail, PermissionCallback callback) {
			this.title = title;
			this.message = message;
			this.detail = detail;
			this.callback = callback;
		}
	}

	private final List<Toast> toasts = new ArrayList<>();

	private NotificationCenter() {
	}

	public static NotificationCenter get() {
		return INSTANCE;
	}

	/** Подключает отрисовку в меню и в игре. */
	public static void register() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("justscript", "notifications"),
				(context, delta) -> get().renderHud(context));

		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenEvents.afterExtract(screen).register((current, context, mouseX, mouseY, delta) ->
					get().render(context, mouseX, mouseY, false));
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> {
				if (event.button() != 0) {
					return true;
				}
				return !get().mouseClicked(event.x(), event.y());
			});
		});
	}

	// ---------------------------------------------------------------- запросы

	/** Показывает запрос ИИ на создание скрипта для объекта. */
	public void askPermission(AiProposal proposal, PermissionCallback callback) {
		if (proposal == null || !proposal.hasTarget()) {
			return;
		}
		toasts.add(new Toast("ИИ-помощник JustScript", proposal.permissionMessage(),
				proposal.targetDetail(), callback));
	}

	public boolean isEmpty() {
		return toasts.isEmpty();
	}

	// ---------------------------------------------------------------- отрисовка

	/** Отрисовка в игре: координат мыши нет, кнопки не нажимаются. */
	public void renderHud(GuiGraphicsExtractor context) {
		render(context, -1, -1, true);
	}

	/**
	 * Отрисовка уведомлений. {@code mouseX < 0} — режим HUD без курсора.
	 */
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hud) {
		if (toasts.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.font == null) {
			return;
		}
		long now = Util.getMillis();
		int screenW = context.guiWidth();
		int screenH = context.guiHeight();
		int width = toastWidth(screenW);

		int index = 0;
		for (int i = toasts.size() - 1; i >= 0; i--) {
			Toast toast = toasts.get(i);
			if (toast.closing >= 0 && now - toast.closing >= OUT_MS) {
				continue;
			}
			int restX = screenW - MARGIN - width;
			int y = screenH - MARGIN - HEIGHT - index * (HEIGHT + GAP);
			if (y < MARGIN) {
				continue;
			}

			float in = easeOut(clamp01((now - toast.born) / (float) IN_MS));
			int x = restX + Math.round((1f - in) * (width + MARGIN + 24));
			if (toast.closing >= 0) {
				float out = easeIn(clamp01((now - toast.closing) / (float) OUT_MS));
				x += Math.round(out * (width + MARGIN + 24));
			}

			draw(context, minecraft.font, toast, x, y, width, HEIGHT, mouseX, mouseY, hud);
			index++;
		}

		toasts.removeIf(toast -> toast.closing >= 0 && now - toast.closing >= OUT_MS);
	}

	private void draw(GuiGraphicsExtractor context, Font font, Toast toast,
			int x, int y, int w, int h, int mouseX, int mouseY, boolean hud) {
		toast.x = x;
		toast.y = y;
		toast.w = w;
		toast.h = h;
		toast.buttonRects.clear();

		context.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x40000000);
		context.fill(x, y, x + w, y + h, PANEL);
		context.fill(x, y, x + 3, y + h, ACCENT);
		context.fill(x, y, x + w, y + 1, EDGE);
		context.fill(x, y + h - 1, x + w, y + h, EDGE);
		context.fill(x + w - 1, y, x + w, y + h, EDGE);

		context.text(font, toast.title, x + 12, y + 9, TITLE, false);
		int lineY = y + 23;
		for (String line : wrap(font, toast.message, w - 24, 2)) {
			context.text(font, line, x + 12, lineY, MESSAGE, false);
			lineY += 10;
		}
		context.text(font, fit(font, toast.detail, w - 24), x + 12, lineY + 2, DETAIL, false);

		int buttonY = y + h - 10 - 20;
		if (hud) {
			context.text(font, "J — открыть меню и ответить", x + 12, buttonY - 12, HINT, false);
		}

		int pad = 12;
		int gap = 6;
		int buttonW = (w - pad * 2 - gap * 2) / 3;
		String[] labels = {"Отклонить", "Принять", "Разр. всегда"};
		for (int i = 0; i < labels.length; i++) {
			int bx = x + pad + i * (buttonW + gap);
			boolean hover = mouseX >= bx && mouseX < bx + buttonW
					&& mouseY >= buttonY && mouseY < buttonY + 20;
			int background = i == 2 ? (hover ? LIME_HOVER : LIME_BG)
					: (hover ? BUTTON_HOVER : BUTTON_BG);
			int textColor = i == 2 ? LIME_TEXT : BUTTON_TEXT;
			context.fill(bx, buttonY, bx + buttonW, buttonY + 20, background);
			int textW = font.width(labels[i]);
			context.text(font, labels[i], bx + (buttonW - textW) / 2, buttonY + 6, textColor, false);
			toast.buttonRects.add(new int[]{bx, buttonY, buttonW, 20, i});
		}
	}

	private static int toastWidth(int screenW) {
		int width = Math.max(MIN_WIDTH, screenW / 3);
		width = Math.min(width, MAX_WIDTH);
		return Math.min(width, screenW - MARGIN * 2);
	}

	private static String fit(Font font, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (!cut.isEmpty() && font.width(cut + "…") > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "…";
	}

	/** Переносит текст по словам; в последней строке ставит многоточие. */
	private static List<String> wrap(Font font, String text, int maxWidth, int maxLines) {
		List<String> lines = new ArrayList<>();
		String rest = text == null ? "" : text.trim();
		while (!rest.isEmpty() && lines.size() < maxLines) {
			if (font.width(rest) <= maxWidth) {
				lines.add(rest);
				break;
			}
			if (lines.size() == maxLines - 1) {
				lines.add(fit(font, rest, maxWidth));
				break;
			}
			int cut = rest.length();
			while (cut > 0 && font.width(rest.substring(0, cut)) > maxWidth) {
				cut--;
			}
			int space = rest.lastIndexOf(' ', Math.max(1, cut));
			if (space > 0) {
				cut = space;
			}
			if (cut <= 0) {
				cut = 1;
			}
			lines.add(rest.substring(0, cut).trim());
			rest = rest.substring(cut).trim();
		}
		return lines;
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	private static float easeOut(float value) {
		float inverse = 1f - value;
		return 1f - inverse * inverse * inverse;
	}

	private static float easeIn(float value) {
		return value * value * value;
	}

	// ---------------------------------------------------------------- клики

	/**
	 * Обрабатывает клик по уведомлению.
	 *
	 * @return {@code true}, если клик забрало уведомление
	 */
	public boolean mouseClicked(double mouseX, double mouseY) {
		long now = Util.getMillis();
		for (int i = toasts.size() - 1; i >= 0; i--) {
			Toast toast = toasts.get(i);
			if (toast.closing >= 0 || toast.w <= 0) {
				continue;
			}
			if (mouseX < toast.x || mouseX >= toast.x + toast.w
					|| mouseY < toast.y || mouseY >= toast.y + toast.h) {
				continue;
			}
			for (int[] rect : toast.buttonRects) {
				if (mouseX < rect[0] || mouseX >= rect[0] + rect[2]
						|| mouseY < rect[1] || mouseY >= rect[1] + rect[3]) {
					continue;
				}
				Decision decision = rect[4] == 0 ? Decision.DENY
						: rect[4] == 1 ? Decision.ACCEPT : Decision.ALWAYS;
				toast.closing = now;
				if (toast.callback != null) {
					toast.callback.done(decision);
				}
				return true;
			}
			return true;
		}
		return false;
	}
}
