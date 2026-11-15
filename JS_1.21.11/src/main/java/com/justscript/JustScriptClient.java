package com.justscript;

import com.justscript.editor3d.Editor3DRenderer;
import com.justscript.editor3d.SceneStore;
import com.justscript.input.Keybinds;
import com.justscript.input.WrenchHandler;
import com.justscript.publish.Publisher;
import com.justscript.script.ScriptStore;
import com.justscript.ui.NotificationCenter;
import com.justscript.ui.WorldHomeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * JustScript — скриптовый редактор для JustMC Creative+.
 * <ul>
 *   <li>Ctrl+ПКМ (настраивается) по блоку/сущности — код-меню;</li>
 *   <li>клавиша {@code J} (настраивается) — единое меню мода;</li>
 *   <li>сборка всего проекта в JSON-модуль JustMC;</li>
 *   <li>«Загрузить в мир» — залить на пейст, применить через
 *       {@code /module load-url force} и вернуться {@code /jointo}
 *       только после подтверждения импорта;</li>
 *   <li>клиентская кнопка в меню мира открывает то же меню.</li>
 * </ul>
 */
public final class JustScriptClient implements ClientModInitializer {
	public static final String MOD_ID = "justscript";

	@Override
	public void onInitializeClient() {
		Config.get();
		Keybinds.register();
		WrenchHandler.register();
		Publisher.register();
		Editor3DRenderer.register();
		NotificationCenter.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ScriptStore.get().updateProject(client);
			ScriptStore.get().saveIfDirty();
			SceneStore.get().updateProject();
			Publisher.tick();
			openMenuFromKey(client);
		});
	}

	/** Клавиша меню работает только в игре и когда не открыт другой экран/чат. */
	private static void openMenuFromKey(MinecraftClient client) {
		while (Keybinds.menuPressed()) {
			if (client.currentScreen == null && client.world != null) {
				client.setScreen(new WorldHomeScreen(null));
			}
		}
	}
}