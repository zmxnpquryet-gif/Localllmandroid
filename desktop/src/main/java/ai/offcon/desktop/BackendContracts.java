package ai.offcon.desktop;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class BackendContracts {
    private BackendContracts() {}

    public enum BackendKind { LLAMA_CPP, STRATA, VLLM }

    public enum DesktopEdition { LLM, ALL_IN_ONE }

    public record ModelSpec(String id, String localPath, String servedModelId) {
        public ModelSpec {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("model id is required");
        }
    }

    public record BackendCapabilities(
            boolean available,
            boolean streaming,
            boolean toolCalling,
            boolean imageGeneration,
            boolean nativeAudio,
            String reason) {
        public static BackendCapabilities unavailable(String reason) {
            return new BackendCapabilities(false, false, false, false, false, reason);
        }
    }

    public record ResourceSnapshot(
            long processId,
            long cpuMillis,
            long residentEstimateBytes,
            int activeRequests,
            Duration uptime,
            String detail) {}

    public record ToolDefinition(String name, String description, String parametersJson) {}

    public record ChatMessage(String role, String content, String name, String toolCallId) {
        public ChatMessage(String role, String content) {
            this(role, content, null, null);
        }
    }

    public record ChatRequest(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            int maxTokens,
            boolean stream) {}

    public record StreamEvent(
            String text,
            String reasoning,
            String rawToolCall,
            String error,
            boolean finished) {
        public static StreamEvent delta(String text, String reasoning) {
            return new StreamEvent(text, reasoning, null, null, false);
        }
        public static StreamEvent tool(String rawJson) {
            return new StreamEvent(null, null, rawJson, null, false);
        }
        public static StreamEvent error(String message) {
            return new StreamEvent(null, null, null, message, true);
        }
        public static StreamEvent done() {
            return new StreamEvent(null, null, null, null, true);
        }
    }

    public interface LlmBackend extends AutoCloseable {
        BackendKind kind();
        BackendCapabilities capabilities();
        void load(ModelSpec model);
        void unload();
        void stream(ChatRequest request, Consumer<StreamEvent> listener);
        ResourceSnapshot resources();
        Map<String, String> backendSpecificConfiguration();
    }

    public record ImageRequest(String model, String prompt, String size, String outputFormat) {}
    public record ImageResult(byte[] bytes, String mediaType) {}

    public interface ImageGenerationBackend extends AutoCloseable {
        boolean available();
        ImageResult generate(ImageRequest request);
    }
}
