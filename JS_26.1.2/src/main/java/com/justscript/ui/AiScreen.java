package com.justscript.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.justscript.Config;
import com.justscript.ai.AiClient;
import com.justscript.ai.AiProposal;
import com.justscript.ai.AiPrompts;
import com.justscript.script.ScriptStore;
import com.justscript.script.ScriptUnit;
import com.justscript.util.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * ИИ-помощник: слева-сверху запрос, снизу — ответ модели.
 * Ответ можно вставить в код на место курсора, заменить им весь скрипт или скопировать.
 *
 * <p>Запрос выполняется в фоне; кнопка «Сгенерировать» неактивна, пока идёт ответ.
 */
public final class AiScreen extends Screen {
	private static final int OK = 0xFF4ADE80;
	private static final int WARN = 0xFFFFC94D;
	private static final int ERROR = 0xFFF87171;
	private static final int INFO = 0xFF9AA4B2;

	private final ScriptEditorScreen parent;
	private final ScriptUnit unit;
	private final String initialPrompt;
	private final boolean autoGenerate;

	private CodeEditorWidget promptField;
	private CodeEditorWidget resultField;
	private Button generateButton;
	private int panelX;
	private int panelW;

	private String status = "";
	private int statusColor = INFO;
	private boolean busy;
	private int requestId;
	private boolean autoRequested;

	public AiScreen(ScriptEditorScreen parent, String initialPrompt, boolean autoGenerate) {
		super(Component.literal("JustScript — ИИ-помощник"));
		this.parent = parent;
		this.unit = parent == null ? null : parent.unit();
		this.initialPrompt = initialPrompt == null ? "" : initialPrompt;
		this.autoGenerate = autoGenerate;
	}

	@Override
	protected void init() {
		this.panelW = Math.min(this.width - 20, 640);
		this.panelX = (this.width - this.panelW) / 2;

		int promptLabelY = 34;
		int promptH = Math.max(38, Math.min(90, (this.height - 150) / 3));
		int promptY = promptLabelY + 11;
		int resultLabelY = promptY + promptH + 6;
		int resultY = resultLabelY + 11;
		int buttonsY = this.height - 48;
		int resultH = Math.max(24, buttonsY - 6 - resultY);

		this.promptField = new CodeEditorWidget(this.panelX, promptY, this.panelW, promptH, this.font);
		this.promptField.setHighlight(false);
		this.promptField.setHint("Что должен делать скрипт? Например: "
				+ "«при ПКМ по блоку выдать игроку алмаз и написать сообщение»");
		this.promptField.setValue(this.initialPrompt);
		this.addRenderableWidget(this.promptField);

		this.resultField = new CodeEditorWidget(this.panelX, resultY, this.panelW, resultH, this.font);
		this.resultField.setHighlight(true);
		this.resultField.setHint("Здесь появится код от ИИ");
		this.addRenderableWidget(this.resultField);

		int gap = 4;
		int buttonW = (this.panelW - gap * 3) / 4;
		this.generateButton = this.addRenderableWidget(Button.builder(Component.literal("Сгенерировать"),
						button -> generate())
				.bounds(this.panelX, buttonsY, buttonW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Вставить"), button -> apply(false))
				.bounds(this.panelX + buttonW + gap, buttonsY, buttonW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Заменить"), button -> apply(true))
				.bounds(this.panelX + (buttonW + gap) * 2, buttonsY, buttonW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Копировать"), button -> copyResult())
				.bounds(this.panelX + (buttonW + gap) * 3, buttonsY, buttonW, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Назад"), button -> onClose())
				.bounds(this.panelX + this.panelW - 90, this.height - 24, 90, 20).build());

		this.promptField.setFocused(true);
		if (this.autoGenerate && !this.autoRequested) {
			this.autoRequested = true;
			generate();
		}
	}

	// ---------------------------------------------------------------- действия

	private void generate() {
		if (this.busy) {
			return;
		}
		String request = this.promptField == null ? "" : this.promptField.getValue().trim();
		if (request.isEmpty()) {
			setStatus("Опиши, что должен делать скрипт", WARN);
			return;
		}
		if (!AiClient.isConfigured()) {
			setStatus("ИИ выключен или не настроен — меню J → Настройки", WARN);
			return;
		}
		this.busy = true;
		if (this.generateButton != null) {
			this.generateButton.active = false;
		}
		setStatus("Запрос к ИИ…", INFO);

		int id = ++this.requestId;
		String currentCode = this.unit == null ? "" : this.unit.code;
		AiClient.generate(AiPrompts.system(this.unit), AiPrompts.user(request, currentCode), (code, error) -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> {
				if (id != this.requestId) {
					return;
				}
				this.busy = false;
				if (this.generateButton != null) {
					this.generateButton.active = true;
				}
				if (error != null) {
					setStatus(error, ERROR);
					return;
				}
				AiProposal proposal = AiProposal.parse(code);
				if (this.resultField != null) {
					this.resultField.setValue(proposal.code());
				}
				if (proposal.invalid()) {
					setStatus("ИИ: " + proposal.error(), ERROR);
					return;
				}
				if (proposal.hasTarget() && proposal.requiresPermission(this.unit)) {
					handlePermission(proposal);
					return;
				}
				setStatus("Готово — проверь код и вставь", OK);
			});
		});
	}

	/**
	 * ИИ предложил скрипт для другого объекта: спрашиваем разрешение
	 * во всплывающем уведомлении снизу справа.
	 */
	private void handlePermission(AiProposal proposal) {
		if (Config.get().aiAllowed(proposal.kind())) {
			acceptProposal(proposal, false);
			return;
		}
		setStatus("Агент просит разрешение — ответь в уведомлении справа внизу", WARN);
		NotificationCenter.get().askPermission(proposal, decision -> {
			if (decision == NotificationCenter.Decision.DENY) {
				setStatus("Агент: создание скрипта отклонено", WARN);
				Chat.warn("ИИ: создание скрипта отклонено — " + proposal.targetDetail());
				return;
			}
			acceptProposal(proposal, decision == NotificationCenter.Decision.ALWAYS);
		});
	}

	/** Создаёт скрипт для цели, кладёт туда код и открывает редактор. */
	private void acceptProposal(AiProposal proposal, boolean always) {
		if (always) {
			Config.get().aiAllowAlways(proposal.kind());
		}
		String generated = proposal.code() == null ? "" : proposal.code().replace("\r\n", "\n").trim();
		if (generated.isEmpty()) {
			setStatus("ИИ не прислал код", ERROR);
			return;
		}
		ScriptUnit target = proposal.buildTarget();
		if (target == null) {
			String message = "Не удалось найти объект: " + proposal.targetDetail();
			setStatus(message, ERROR);
			Chat.error("ИИ: " + message);
			return;
		}
		ScriptUnit unit = ScriptStore.get().getOrCreate(() -> target);
		boolean existed = unit.code != null && !unit.code.isBlank();
		if (existed) {
			unit.code = unit.code.replace("\r\n", "\n").trim() + "\n\n" + generated + "\n";
		} else {
			unit.code = generated + "\n";
		}
		unit.language = Config.get().language;
		ScriptStore.get().touch(unit);
		ScriptStore.get().save();
		proposal.linkVisualizer(unit);
		Chat.ok((existed ? "ИИ дополнил скрипт: " : "ИИ создал скрипт: ") + unit.shortLabel());

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			minecraft.setScreenAndShow(new ScriptEditorScreen(unit, this.parent));
		}
	}

	private void apply(boolean replace) {
		String code = this.resultField == null ? "" : this.resultField.getValue().trim();
		if (code.isEmpty()) {
			setStatus("Сначала сгенерируй код", WARN);
			return;
		}
		if (this.parent == null) {
			Chat.copy(code + "\n");
			setStatus("Не из редактора — код скопирован в буфер", OK);
			return;
		}
		if (replace) {
			this.parent.replaceAiCode(code);
		} else {
			this.parent.insertAiCode(code);
		}
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	private void copyResult() {
		String code = this.resultField == null ? "" : this.resultField.getValue();
		if (code.isBlank()) {
			setStatus("Пока нечего копировать", WARN);
			return;
		}
		Chat.copy(code);
		setStatus("Код скопирован в буфер", OK);
	}

	private void setStatus(String text, int color) {
		this.status = text == null ? "" : text;
		this.statusColor = color;
	}

	// ---------------------------------------------------------------- отрисовка

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xF00B0D11);
		context.text(this.font, "ИИ-помощник JustScript", 12, 10, 0xFFE8E8E8, false);

		Config config = Config.get();
		String provider = config.aiCustom() ? "свой агрегатор" : "бесплатная модель";
		String model = provider + ": " + (config.aiModel().isBlank() ? "—" : config.aiModel());
		context.text(this.font, model, 12, 22, 0xFF7A8085, false);

		int promptLabelY = 34;
		int promptH = Math.max(38, Math.min(90, (this.height - 150) / 3));
		int resultLabelY = promptLabelY + 11 + promptH + 6;
		context.text(this.font, "Запрос", this.panelX, promptLabelY, 0xFFB8C0CC, false);
		context.text(this.font, "Код от ИИ", this.panelX, resultLabelY, 0xFFB8C0CC, false);
		context.text(this.font, "Ctrl+Enter — сгенерировать", this.panelX + 90, resultLabelY,
				0xFF5A616B, false);

		super.extractRenderState(context, mouseX, mouseY, delta);

		if (!this.status.isEmpty()) {
			String shown = trimToWidth(this.status, this.panelW - 100);
			context.text(this.font, shown, this.panelX, this.height - 18, this.statusColor, false);
		}
	}

	private String trimToWidth(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (!cut.isEmpty() && this.font.width(cut + "…") > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "…";
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		boolean ctrl = (input.modifiers() & InputConstants.MOD_CONTROL) != 0;
		if (ctrl && input.key() == InputConstants.KEY_RETURN) {
			generate();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void onClose() {
		// Отменяем «хвостовые» ответы, которые придут после закрытия экрана.
		this.requestId++;
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}