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
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

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
			if (hand != InteractionHand.MAIN_HAND || !isWrenchClick(world, true)) {
				return InteractionResult.PASS;
			}
			if (hitResult instanceof BlockHitResult blockHit) {
				openBlock(blockHit.getBlockPos());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (hand != InteractionHand.MAIN_HAND || !isWrenchClick(world, false)) {
				return InteractionResult.PASS;
			}
			openBlock(pos);
			return InteractionResult.SUCCESS;
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand != InteractionHand.MAIN_HAND || !isWrenchClick(world, true)) {
				return InteractionResult.PASS;
			}
			openEntity(entity);
			return InteractionResult.SUCCESS;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand != InteractionHand.MAIN_HAND || !isWrenchClick(world, false)) {
				return InteractionResult.PASS;
			}
			openEntity(entity);
			return InteractionResult.SUCCESS;
		});
	}

	private static void openBlock(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> ScriptUnit.forBlock(minecraft.level, pos));
		minecraft.setScreenAndShow(new ScriptEditorScreen(unit, null));
	}

	private static void openEntity(Entity entity) {
		Minecraft minecraft = Minecraft.getInstance();
		if (entity == null || minecraft.level == null) {
			return;
		}
		Level world = minecraft.level;
		// Игрок — не отдельный скрипт: открываем глобальный скрипт мира.
		ScriptUnit unit = entity instanceof Player
				? ScriptStore.get().getOrCreate(() -> ScriptUnit.forWorld(world))
				: ScriptStore.get().getOrCreate(() -> ScriptUnit.forEntity(entity));
		minecraft.setScreenAndShow(new ScriptEditorScreen(unit, null));
	}

	/** Проверяет, что клик — именно «ключ»: нужная кнопка, модификатор, режим. */
	private static boolean isWrenchClick(Level world, boolean rightClick) {
		Config config = Config.get();
		if (!config.wrenchEnabled || world == null || !world.isClientSide()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		// В мире кода (редакторе JustMC) код-меню по блокам отключено:
		// там клик по блоку должен работать как обычно.
		if (WorldName.inCodeWorld(minecraft)) {
			return false;
		}
		// Код-меню работает только в креативе.
		if (minecraft.gameMode != null
				&& minecraft.gameMode.getPlayerMode() != GameType.CREATIVE) {
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