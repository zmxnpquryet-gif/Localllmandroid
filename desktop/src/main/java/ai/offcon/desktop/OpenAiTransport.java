package ai.offcon.desktop;

import ai.offcon.desktop.BackendContracts.ChatMessage;
import ai.offcon.desktop.BackendContracts.ChatRequest;
import ai.offcon.desktop.BackendContracts.StreamEvent;
import ai.offcon.desktop.BackendContracts.ToolDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** OpenAI-compatible HTTP transport shared by remote/server backends. */
public final class OpenAiTransport {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public void chat(String baseUrl, String apiKey, ChatRequest request, Consumer<StreamEvent> listener) {
        String body = requestBody(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(chatUri(baseUrl))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                listener.accept(StreamEvent.error("HTTP " + response.statusCode() + ": " + error));
                return;
            }
            if (request.stream()) {
                readStream(response.body(), listener);
            } else {
                String json = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                readPayload(json, listener);
                listener.accept(StreamEvent.done());
            }
        } catch (Exception error) {
            listener.accept(StreamEvent.error(error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    public boolean modelsAvailable(String baseUrl, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(modelsUri(baseUrl))
                    .timeout(Duration.ofSeconds(8)).GET();
            if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean healthAvailable(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(baseUrl) + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void readStream(InputStream stream, Consumer<StreamEvent> listener) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith(":")) continue;
                String payload = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
                if (payload.equals("[DONE]")) {
                    listener.accept(StreamEvent.done());
                    return;
                }
                readPayload(payload, listener);
            }
            listener.accept(StreamEvent.done());
        }
    }

    private void readPayload(String payload, Consumer<StreamEvent> listener) {
        if (payload == null || payload.isBlank()) return;
        String error = extractString(payload, "error");
        if (error != null) {
            listener.accept(StreamEvent.error(error));
            return;
        }
        if (payload.contains("\"tool_calls\"")) listener.accept(StreamEvent.tool(payload));
        String content = extractString(payload, "content");
        String reasoning = extractString(payload, "reasoning_content");
        if (reasoning == null) reasoning = extractString(payload, "reasoning");
        if (content != null || reasoning != null) listener.accept(StreamEvent.delta(content, reasoning));
    }

    private static String requestBody(ChatRequest request) {
        StringBuilder json = new StringBuilder();
        json.append("{\"messages\":[");
        for (int i = 0; i < request.messages().size(); i++) {
            if (i > 0) json.append(',');
            ChatMessage message = request.messages().get(i);
            json.append("{\"role\":").append(quote(message.role()))
                    .append(",\"content\":").append(quote(message.content()));
            if (message.name() != null) json.append(",\"name\":").append(quote(message.name()));
            if (message.toolCallId() != null) json.append(",\"tool_call_id\":").append(quote(message.toolCallId()));
            json.append('}');
        }
        json.append("]");
        if (request.tools() != null && !request.tools().isEmpty()) {
            json.append(",\"tools\":[");
            for (int i = 0; i < request.tools().size(); i++) {
                if (i > 0) json.append(',');
                ToolDefinition tool = request.tools().get(i);
                json.append("{\"type\":\"function\",\"function\":{\"name\":")
                        .append(quote(tool.name())).append(",\"description\":").append(quote(tool.description()))
                        .append(",\"parameters\":").append(tool.parametersJson()).append("}}");
            }
            json.append(']');
        }
        json.append(",\"temperature\":").append(request.temperature())
                .append(",\"max_tokens\":").append(request.maxTokens())
                .append(",\"stream\":").append(request.stream()).append('}');
        return json.toString();
    }

    private static URI chatUri(String baseUrl) {
        String base = stripSlash(baseUrl);
        if (base.endsWith("/chat/completions")) return URI.create(base);
        if (base.endsWith("/v1")) return URI.create(base + "/chat/completions");
        return URI.create(base + "/v1/chat/completions");
    }

    private static URI modelsUri(String baseUrl) {
        String base = stripSlash(baseUrl);
        if (base.endsWith("/chat/completions")) base = base.substring(0, base.length() - "/chat/completions".length());
        if (base.endsWith("/v1")) return URI.create(base + "/models");
        return URI.create(base + "/v1/models");
    }

    private static String stripSlash(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }

    static String extractString(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int cursor = at + needle.length();
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor) != ':') return null;
        cursor++;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length()) return null;
        if (json.startsWith("null", cursor)) return null;
        if (json.charAt(cursor) != '"') {
            int end = cursor;
            while (end < json.length() && ",}]\n".indexOf(json.charAt(end)) < 0) end++;
            return json.substring(cursor, end).trim();
        }
        StringBuilder out = new StringBuilder();
        cursor++;
        while (cursor < json.length()) {
            char c = json.charAt(cursor++);
            if (c == '"') break;
            if (c != '\\') { out.append(c); continue; }
            if (cursor >= json.length()) break;
            char e = json.charAt(cursor++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (cursor + 4 <= json.length()) {
                        out.append((char) Integer.parseInt(json.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                }
                default -> out.append(e);
            }
        }
        return out.toString();
    }
}
