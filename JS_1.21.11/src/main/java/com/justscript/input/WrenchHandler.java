package com.justscript.input;

import com.justscript.Config;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.ui.ScriptEditorScreen;
import com.justscript.util.WorldName;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/**
 * «Гаечный ключ»: Ctrl+ПКМ (комбинация настраивается) по блоку или сущности
 * в креативе открывает код-меню.
 *
 * <p>Скрипты в мире кода (/dev) считаются обычными: отдельной защиты больше нет.
 */
public final class WrenchHandler {
	private WrenchHandler() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (hand != Hand.MAIN_HAND || !isWrenchClick(world, true)) {
				return ActionResult.PASS;
			}
			if (hitResult instanceof BlockHitResult blockHit) {
				openBlock(blockHit.getBlockPos());
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (hand != Hand.MAIN_HAND || !isWrenchClick(world, false)) {
				return ActionResult.PASS;
			}
			openBlock(pos);
			return ActionResult.SUCCESS;
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand != Hand.MAIN_HAND || !isWrenchClick(world, true)) {
				return ActionResult.PASS;
			}
			openEntity(entity);
			return ActionResult.SUCCESS;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand != Hand.MAIN_HAND || !isWrenchClick(world, false)) {
				return ActionResult.PASS;
			}
			openEntity(entity);
			return ActionResult.SUCCESS;
		});
	}

	private static void openBlock(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> ScriptUnit.forBlock(client.world, pos));
		client.setScreen(new ScriptEditorScreen(unit, null));
	}

	private static void openEntity(Entity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (entity == null || client.world == null) {
			return;
		}
		World world = client.world;
		// Игрок — не отдельный скрипт: открываем глобальный скрипт мира.
		ScriptUnit unit = entity instanceof PlayerEntity
				? ScriptStore.get().getOrCreate(() -> ScriptUnit.forWorld(world))
				: ScriptStore.get().getOrCreate(() -> ScriptUnit.forEntity(entity));
		client.setScreen(new ScriptEditorScreen(unit, null));
	}

	/** Проверяет, что клик — именно «ключ»: нужная кнопка, модификатор, режим. */
	private static boolean isWrenchClick(World world, boolean rightClick) {
		Config config = Config.get();
		if (!config.wrenchEnabled || world == null || !world.isClient()) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return false;
		}
		// В мире кода (редакторе JustMC) код-меню по блокам отключено:
		// там клик по блоку должен работать как обычно.
		if (WorldName.inCodeWorld(client)) {
			return false;
		}
		// Код-меню работает только в креативе.
		if (client.interactionManager != null
				&& client.interactionManager.getCurrentGameMode() != GameMode.CREATIVE) {
			return false;
		}
		if (!Keybinds.modifierPressed()) {
			return false;
		}
		// Направление клика задано в биндах: если игрок выбрал ЛКМ, ПКМ не сработает.
		if (rightClick == Keybinds.leftButton()) {
			return false;
		}
		return Keybinds.buttonPressed();
	}

	/** Человекочитаемое описание комбинации для чата/настроек. */
	public static String describeCombo() {
		return Keybinds.describe() + " (только в креативе)";
	}
}