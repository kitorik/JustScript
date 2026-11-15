package com.justscript.net;

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
import java.util.Locale;

/**
 * Заливает собранный модуль на публичный пейст и отдаёт ссылку, которую можно
 * скормить {@code /module load-url force <ссылка>}.
 *
 * <p>Рабочая цепочка (проверена вручную):
 * <ol>
 *   <li><b>paste.c-net.org</b> — обычный POST, ответ — готовая ссылка на сырой JSON;</li>
 *   <li><b>dpaste.com</b> — POST формы, ответ — ссылка; сырой текст через суффикс {@code .txt};</li>
 *   <li><b>tmpfiles.org</b> — multipart, ссылка преобразуется в {@code /dl/…};</li>
 *   <li><b>custom</b> — любой свой POST-эндпоинт (первое слово ответа — ссылка).</li>
 * </ol>
 *
 * <p>Провайдер {@code auto} пробует цепочку по порядку. Всё выполняется в фоне;
 * колбэк приходит из HTTP-потока, поэтому вызывающий код возвращается в поток
 * игры через {@code Minecraft.execute}.
 */
public final class Uploader {
	private static final String USER_AGENT = "JustScript/0.5 (Minecraft Fabric)";

	public interface Callback {
		void done(String url, String error);
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private Uploader() {
	}

	public static void upload(String json, Callback callback) {
		Config config = Config.get();
		String provider = config.uploadProvider == null
				? "auto" : config.uploadProvider.trim().toLowerCase(Locale.ROOT);

		List<String> order = new ArrayList<>();
		if ("custom".equals(provider)) {
			order.add("custom");
		} else {
			if ("dpaste.com".equals(provider)) {
				order.add("dpaste");
			} else if ("tmpfiles.org".equals(provider)) {
				order.add("tmpfiles");
			} else {
				order.add("c-net");
			}
			for (String fallback : List.of("c-net", "dpaste", "tmpfiles")) {
				if (!order.contains(fallback)) {
					order.add(fallback);
				}
			}
		}
		attempt(json, order, 0, new ArrayList<>(), callback);
	}

	private static void attempt(String json, List<String> order, int index,
			List<String> errors, Callback callback) {
		if (index >= order.size()) {
			callback.done(null, String.join("; ", errors));
			return;
		}
		String provider = order.get(index);
		Callback next = (url, error) -> {
			if (url != null) {
				callback.done(url, null);
				return;
			}
			if (error != null && !error.isBlank()) {
				errors.add(error);
			}
			attempt(json, order, index + 1, errors, callback);
		};
		switch (provider) {
			case "c-net" -> uploadCNet(json, next);
			case "dpaste" -> uploadDpaste(json, next);
			case "tmpfiles" -> uploadTmpfiles(json, next);
			case "custom" -> uploadCustom(Config.get(), json, next);
			default -> next.done(null, provider + ": неизвестный провайдер");
		}
	}

	/** paste.c-net.org: POST с телом, ответ — ссылка на сырой текст. */
	private static void uploadCNet(String json, Callback callback) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://paste.c-net.org/"))
					.timeout(Duration.ofSeconds(Config.get().uploadTimeoutSeconds))
					.header("User-Agent", USER_AGENT)
					.header("Content-Type", "text/plain; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.whenComplete((response, error) -> {
						if (error != null) {
							callback.done(null, "paste.c-net.org: " + error.getMessage());
							return;
						}
						String body = response.body() == null ? "" : response.body().trim();
						if (response.statusCode() / 100 == 2 && body.startsWith("http")) {
							Log.LOGGER.info("Модуль залит на paste.c-net.org: {}", body);
							callback.done(body, null);
						} else {
							callback.done(null, "paste.c-net.org: HTTP " + response.statusCode()
									+ " " + trim(body));
						}
					});
		} catch (Exception e) {
			callback.done(null, "paste.c-net.org: " + e.getMessage());
		}
	}

	/** dpaste.com: форма content=…, ответ — ссылка; сырой JSON — с суффиксом .txt. */
	private static void uploadDpaste(String json, Callback callback) {
		try {
			String form = "content=" + URLEncoder.encode(json, StandardCharsets.UTF_8)
					+ "&syntax=json&expiry_days=7";
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://dpaste.com/api/v2/"))
					.timeout(Duration.ofSeconds(Config.get().uploadTimeoutSeconds))
					.header("User-Agent", USER_AGENT)
					.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
					.header("Accept", "text/plain")
					.POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.whenComplete((response, error) -> {
						if (error != null) {
							callback.done(null, "dpaste.com: " + error.getMessage());
							return;
						}
						String body = response.body() == null ? "" : response.body().trim();
						if (response.statusCode() / 100 == 2 && body.startsWith("http")) {
							String raw = body.endsWith(".txt") ? body : body + ".txt";
							Log.LOGGER.info("Модуль залит на dpaste.com: {}", raw);
							callback.done(raw, null);
						} else {
							callback.done(null, "dpaste.com: HTTP " + response.statusCode()
									+ " " + trim(body));
						}
					});
		} catch (Exception e) {
			callback.done(null, "dpaste.com: " + e.getMessage());
		}
	}

	/** tmpfiles.org: multipart-загрузка, обычная ссылка превращается в прямую /dl/. */
	private static void uploadTmpfiles(String json, Callback callback) {
		try {
			String boundary = "----JustScript" + System.currentTimeMillis();
			String head = "--" + boundary + "\r\n"
					+ "Content-Disposition: form-data; name=\"file\"; filename=\"module.json\"\r\n"
					+ "Content-Type: application/json; charset=utf-8\r\n\r\n";
			String tail = "\r\n--" + boundary + "--\r\n";
			byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
			byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);
			byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
			byte[] payload = new byte[headBytes.length + bodyBytes.length + tailBytes.length];
			System.arraycopy(headBytes, 0, payload, 0, headBytes.length);
			System.arraycopy(bodyBytes, 0, payload, headBytes.length, bodyBytes.length);
			System.arraycopy(tailBytes, 0, payload, headBytes.length + bodyBytes.length, tailBytes.length);

			HttpRequest request = HttpRequest.newBuilder(URI.create("https://tmpfiles.org/api/v1/upload"))
					.timeout(Duration.ofSeconds(Config.get().uploadTimeoutSeconds))
					.header("User-Agent", USER_AGENT)
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(payload))
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.whenComplete((response, error) -> {
						if (error != null) {
							callback.done(null, "tmpfiles.org: " + error.getMessage());
							return;
						}
						String body = response.body() == null ? "" : response.body().trim();
						String url = extractUrl(body);
						if (response.statusCode() / 100 == 2 && url != null) {
							String direct = url.replace("https://tmpfiles.org/", "https://tmpfiles.org/dl/");
							Log.LOGGER.info("Модуль залит на tmpfiles.org: {}", direct);
							callback.done(direct, null);
						} else {
							callback.done(null, "tmpfiles.org: HTTP " + response.statusCode()
									+ " " + trim(body));
						}
					});
		} catch (Exception e) {
			callback.done(null, "tmpfiles.org: " + e.getMessage());
		}
	}

	private static void uploadCustom(Config config, String json, Callback callback) {
		if (config.customUploadUrl == null || config.customUploadUrl.isBlank()) {
			callback.done(null, "custom: в настройках не указан customUploadUrl");
			return;
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(config.customUploadUrl))
					.timeout(Duration.ofSeconds(config.uploadTimeoutSeconds))
					.header("User-Agent", USER_AGENT)
					.header("Content-Type", "application/json; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.whenComplete((response, error) -> {
						if (error != null) {
							callback.done(null, "custom: " + error.getMessage());
							return;
						}
						String body = response.body() == null ? "" : response.body().trim();
						if (response.statusCode() / 100 == 2 && !body.isEmpty()) {
							callback.done(body.split("\\s+")[0], null);
						} else {
							callback.done(null, "custom: HTTP " + response.statusCode() + " " + trim(body));
						}
					});
		} catch (Exception e) {
			callback.done(null, "custom: " + e.getMessage());
		}
	}

	/** Достаёт "url":"…" из JSON-ответа tmpfiles. */
	private static String extractUrl(String body) {
		int key = body.indexOf("\"url\"");
		if (key < 0) {
			return null;
		}
		int colon = body.indexOf(':', key);
		int quote = colon < 0 ? -1 : body.indexOf('"', colon);
		if (quote < 0) {
			return null;
		}
		int end = body.indexOf('"', quote + 1);
		if (end <= quote) {
			return null;
		}
		String value = body.substring(quote + 1, end).trim();
		return value.startsWith("http") ? value : null;
	}

	private static String trim(String value) {
		if (value == null) {
			return "";
		}
		String clean = value.replace('\n', ' ').trim();
		return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
	}
}