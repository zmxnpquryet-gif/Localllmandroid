package ai.offcon.desktop;

import ai.offcon.desktop.BackendContracts.ImageGenerationBackend;
import ai.offcon.desktop.BackendContracts.ImageRequest;
import ai.offcon.desktop.BackendContracts.ImageResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Real image API adapter used only by the All-in-One edition. */
public final class OpenAiImageBackend implements ImageGenerationBackend {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenAiImageBackend(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    @Override public boolean available() { return !baseUrl.isBlank(); }

    @Override
    public ImageResult generate(ImageRequest request) {
        if (!available()) throw new IllegalStateException("Image endpoint is not configured");
        String endpoint = baseUrl.endsWith("/v1") ? baseUrl + "/images/generations" : baseUrl + "/v1/images/generations";
        String body = "{\"model\":" + OpenAiTransport.quote(request.model())
                + ",\"prompt\":" + OpenAiTransport.quote(request.prompt())
                + ",\"size\":" + OpenAiTransport.quote(request.size())
                + ",\"response_format\":\"b64_json\"}";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Image API HTTP " + response.statusCode() + ": " + response.body());
            }
            String base64 = OpenAiTransport.extractString(response.body(), "b64_json");
            if (base64 != null && !base64.isBlank()) {
                return new ImageResult(Base64.getDecoder().decode(base64), "image/png");
            }
            String url = OpenAiTransport.extractString(response.body(), "url");
            if (url != null && !url.isBlank()) {
                HttpResponse<byte[]> image = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (image.statusCode() >= 200 && image.statusCode() < 300) {
                    String type = image.headers().firstValue("Content-Type").orElse("image/png");
                    return new ImageResult(image.body(), type);
                }
                throw new IllegalStateException("Image download HTTP " + image.statusCode());
            }
            throw new IllegalStateException("Image response contained neither b64_json nor url");
        } catch (Exception error) {
            throw new IllegalStateException("Image generation failed: " + error.getMessage(), error);
        }
    }

    @Override public void close() {}
}
