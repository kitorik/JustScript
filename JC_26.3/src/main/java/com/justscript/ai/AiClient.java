package com.justscript.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justscript.Config;
import com.justscript.Log;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент ИИ: обычный OpenAI-совместимый {@code /chat/completions}.
 *
 * <p>Режимы (см. {@link Config#aiProvider}):
 * <ul>
 *   <li>{@code free} — бесплатное облако (Pollinations) без ключа; если не ответило,
 *       по очереди пробуются запасные: текстовый эндпоинт Pollinations и локальная Ollama;</li>
 *   <li>{@code custom} — свой агрегатор: адрес, модель и ключ задаются в настройках.</li>
 * </ul>
 *
 * <p>Запрос уходит в фоне, колбэк приходит из потока HTTP — вернуться в поток игры
 * нужно через {@code Minecraft.execute}.
 */
public final class AiClient {
	private static final String USER_AGENT = "JustScript/0.2 (Minecraft Fabric)";
	private static final Gson GSON = new Gson();
	private static final int MAX_GET_PROMPT = 5000;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public interface Callback {
		/** Ровно одно из полей не null. */
		void done(String code, String error);
	}

	/** Одно «облако»: чат-эндпоинт или простой GET с текстом. */
	private record Attempt(String name, String url, String model, String key, boolean plainText) {
	}

	private AiClient() {
	}

	/** Можно ли слать запрос с текущими настройками. */
	public static boolean isConfigured() {
		Config config = Config.get();
		return !config.aiUrl().isBlank() && !config.aiModel().isBlank();
	}

	/**
	 * Отправляет запрос и возвращает текст ответа модели (уже без markdown-обёртки).
	 */
	public static void generate(String systemPrompt, String userPrompt, Callback callback) {
		Config config = Config.get();
		String url = config.aiUrl();
		String model = config.aiModel();
		if (url.isBlank()) {
			callback.done(null, "Не указан адрес ИИ (меню J → Настройки)");
			return;
		}
		if (model.isBlank()) {
			callback.done(null, "Не указана модель ИИ (меню J → Настройки)");
			return;
		}

		List<Attempt> attempts = new ArrayList<>();
		if (config.aiCustom()) {
			attempts.add(new Attempt("свой агрегатор", url, model, config.aiKey(), false));
		} else {
			attempts.add(new Attempt("Pollinations", url, model, "", false));
			if (config.aiFallbackClouds) {
				attempts.add(new Attempt("Pollinations (text)", "", model, "", true));
				if (config.aiOllama) {
					attempts.add(new Attempt("Ollama", config.aiOllamaUrl, config.aiOllamaModel, "", false));
				}
			}
		}
		run(attempts, 0, new ArrayList<>(), systemPrompt, userPrompt, callback);
	}

	private static void run(List<Attempt> attempts, int index, List<String> errors,
			String systemPrompt, String userPrompt, Callback callback) {
		if (index >= attempts.size()) {
			callback.done(null, String.join(" | ", errors));
			return;
		}
		Attempt attempt = attempts.get(index);
		Log.LOGGER.debug("ИИ: пробую {} ({})", attempt.name(), attempt.model());
		Callback next = (code, error) -> {
			if (error == null) {
				callback.done(code, null);
			} else {
				errors.add(attempt.name() + ": " + error);
				run(attempts, index + 1, errors, systemPrompt, userPrompt, callback);
			}
		};
		if (attempt.plainText()) {
			sendPlain(attempt, systemPrompt, userPrompt, next);
		} else {
			sendChat(attempt, systemPrompt, userPrompt, next);
		}
	}

	// ---------------------------------------------------------------- OpenAI chat

	private static void sendChat(Attempt attempt, String systemPrompt, String userPrompt, Callback callback) {
		Config config = Config.get();
		JsonArray messages = new JsonArray();
		messages.add(message("system", systemPrompt));
		messages.add(message("user", userPrompt));

		JsonObject body = new JsonObject();
		body.addProperty("model", attempt.model());
		body.add("messages", messages);
		body.addProperty("temperature", 0.2);
		body.addProperty("max_tokens", Math.max(256, config.aiMaxTokens));

		HttpRequest.Builder builder;
		try {
			builder = HttpRequest.newBuilder(URI.create(endpoint(attempt.url())))
					.timeout(Duration.ofSeconds(Math.max(10, config.aiTimeoutSeconds)))
					.header("User-Agent", USER_AGENT)
					.header("Content-Type", "application/json; charset=utf-8")
					.header("Accept", "application/json");
		} catch (Exception e) {
			callback.done(null, "некорректный адрес: " + e.getMessage());
			return;
		}
		if (attempt.key() != null && !attempt.key().isBlank()) {
			builder.header("Authorization", "Bearer " + attempt.key());
		}
		builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));

		HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.whenComplete((response, error) -> {
					if (error != null) {
						callback.done(null, "сеть: " + rootMessage(error));
						return;
					}
					String text = response.body() == null ? "" : response.body();
					if (response.statusCode() / 100 != 2) {
						callback.done(null, "HTTP " + response.statusCode() + ": " + errorText(text));
						return;
					}
					Parsed parsed = parse(text);
					if (parsed.error() != null) {
						callback.done(null, parsed.error());
						return;
					}
					if (parsed.code() == null || parsed.code().isBlank()) {
						callback.done(null, "пустой ответ: " + trim(text));
						return;
					}
					callback.done(stripFences(parsed.code()), null);
				});
	}

	// ---------------------------------------------------------------- простой текст

	private static void sendPlain(Attempt attempt, String systemPrompt, String userPrompt, Callback callback) {
		Config config = Config.get();
		String prompt = systemPrompt + "\n\n" + userPrompt;
		if (prompt.length() > MAX_GET_PROMPT) {
			prompt = prompt.substring(0, MAX_GET_PROMPT) + "\n…";
		}
		String url;
		try {
			url = "https://text.pollinations.ai/"
					+ URLEncoder.encode(prompt, StandardCharsets.UTF_8).replace("+", "%20")
					+ "?model=" + URLEncoder.encode(attempt.model(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			callback.done(null, "не удалось собрать запрос: " + e.getMessage());
			return;
		}
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(Math.max(10, config.aiTimeoutSeconds)))
					.header("User-Agent", USER_AGENT)
					.header("Accept", "text/plain")
					.GET()
					.build();
		} catch (Exception e) {
			callback.done(null, "некорректный адрес: " + e.getMessage());
			return;
		}
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.whenComplete((response, error) -> {
					if (error != null) {
						callback.done(null, "сеть: " + rootMessage(error));
						return;
					}
					String text = response.body() == null ? "" : response.body();
					if (response.statusCode() / 100 != 2) {
						callback.done(null, "HTTP " + response.statusCode() + ": " + trim(text));
						return;
					}
					if (text.startsWith("<") || text.isBlank()) {
						callback.done(null, "пустой текстовый ответ");
						return;
					}
					callback.done(stripFences(text), null);
				});
	}

	// ---------------------------------------------------------------- разбор

	private record Parsed(String code, String error) {
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content == null ? "" : content);
		return message;
	}

	private static Parsed parse(String body) {
		try {
			JsonElement rootElement = JsonParser.parseString(body);
			if (!rootElement.isJsonObject()) {
				return new Parsed(null, "неожиданный ответ: " + trim(body));
			}
			JsonObject root = rootElement.getAsJsonObject();
			if (root.has("error") && !root.get("error").isJsonNull()) {
				JsonElement error = root.get("error");
				String text = error.isJsonObject() && error.getAsJsonObject().has("message")
						? error.getAsJsonObject().get("message").getAsString()
						: error.toString();
				return new Parsed(null, "ИИ вернул ошибку: " + trim(text));
			}
			if (!root.has("choices") || !root.get("choices").isJsonArray()) {
				return new Parsed(null, "в ответе нет choices: " + trim(body));
			}
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices.isEmpty()) {
				return new Parsed(null, "пустой список choices");
			}
			JsonObject choice = choices.get(0).getAsJsonObject();
			if (choice.has("message") && choice.get("message").isJsonObject()) {
				JsonObject message = choice.getAsJsonObject("message");
				if (message.has("content") && !message.get("content").isJsonNull()) {
					return new Parsed(message.get("content").getAsString(), null);
				}
			}
			if (choice.has("text") && !choice.get("text").isJsonNull()) {
				return new Parsed(choice.get("text").getAsString(), null);
			}
			return new Parsed(null, null);
		} catch (Exception e) {
			return new Parsed(null, "не разобрать ответ: " + trim(body));
		}
	}

	/** Убирает markdown-обёртку, если модель всё-таки прислала ```json … ```. */
	private static String stripFences(String raw) {
		String text = raw.replace("\r\n", "\n").trim();
		if (!text.startsWith("```")) {
			return text;
		}
		int firstLineEnd = text.indexOf('\n');
		if (firstLineEnd >= 0) {
			text = text.substring(firstLineEnd + 1);
		}
		int closing = text.lastIndexOf("```");
		if (closing >= 0) {
			text = text.substring(0, closing);
		}
		return text.trim();
	}

	/** База вида …/v1 превращается в …/v1/chat/completions; полный адрес не трогаем. */
	private static String endpoint(String value) {
		String url = value == null ? "" : value.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		if (url.isEmpty()) {
			return "";
		}
		if (url.matches("(?i).*/v\\d+")) {
			url = url + "/chat/completions";
		}
		return url;
	}

	private static String errorText(String body) {
		try {
			JsonElement root = JsonParser.parseString(body);
			if (root.isJsonObject()) {
				JsonElement error = root.getAsJsonObject().get("error");
				if (error != null && !error.isJsonNull()) {
					if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
						return trim(error.getAsJsonObject().get("message").getAsString());
					}
					return trim(error.toString());
				}
			}
		} catch (Exception ignored) {
			// ниже вернём просто тело
		}
		return trim(body);
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		String message = null;
		while (current != null) {
			if (current.getMessage() != null && !current.getMessage().isBlank()) {
				message = current.getMessage();
			}
			current = current.getCause();
		}
		return message == null ? error.getClass().getSimpleName() : message;
	}

	private static String trim(String value) {
		if (value == null) {
			return "";
		}
		String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
		return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
	}
}