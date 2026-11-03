package ai.offcon.desktop;

import ai.offcon.desktop.BackendContracts.BackendCapabilities;
import ai.offcon.desktop.BackendContracts.BackendKind;
import ai.offcon.desktop.BackendContracts.ChatRequest;
import ai.offcon.desktop.BackendContracts.LlmBackend;
import ai.offcon.desktop.BackendContracts.ModelSpec;
import ai.offcon.desktop.BackendContracts.ResourceSnapshot;
import ai.offcon.desktop.BackendContracts.StreamEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Backend-specific adapters. Configuration stays typed instead of a generic map. */
public final class Backends {
    private Backends() {}

    public record LlamaCppConfig(
            String executable,
            String defaultModelPath,
            String host,
            int port,
            int contextSize,
            int threads,
            int gpuLayers,
            List<String> extraArgs) {}

    public record VllmConfig(
            String baseUrl,
            String apiKey,
            String modelId,
            String pythonExecutable,
            String modelPath,
            boolean autoStart,
            int port,
            double gpuMemoryUtilization,
            int tensorParallel,
            String dtype,
            List<String> extraArgs) {}

    public record StrataConfig(
            String baseUrl,
            String apiKey,
            String modelId,
            List<String> launchCommand,
            Path workingDirectory,
            List<String> extraArgs) {}

    public static final class LlamaCpp implements LlmBackend {
        private final LlamaCppConfig config;
        private final OpenAiTransport transport = new OpenAiTransport();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private Process process;
        private Instant startedAt;
        private String loadedModel;
        private Path logFile;

        public LlamaCpp(LlamaCppConfig config) { this.config = config; }

        @Override public BackendKind kind() { return BackendKind.LLAMA_CPP; }

        @Override
        public synchronized BackendCapabilities capabilities() {
            boolean serverUp = transport.healthAvailable(baseUrl());
            boolean executable = new File(config.executable()).isFile() || findOnPath(config.executable()) != null;
            if (!serverUp && !executable) return BackendCapabilities.unavailable("llama-server executable was not found");
            return new BackendCapabilities(true, true, true, false, false,
                    serverUp ? "llama-server is ready" : "llama-server can be launched; model file and backend must be verified on load");
        }

        @Override
        public synchronized void load(ModelSpec model) {
            unload();
            String modelPath = model.localPath() == null || model.localPath().isBlank()
                    ? config.defaultModelPath() : model.localPath();
            if (modelPath == null || !new File(modelPath).isFile()) {
                throw new IllegalArgumentException("llama.cpp requires an existing local GGUF model path");
            }
            String executable = resolveExecutable(config.executable());
            if (executable == null) throw new IllegalStateException("llama-server executable was not found: " + config.executable());
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("--model"); command.add(modelPath);
            command.add("--host"); command.add(config.host());
            command.add("--port"); command.add(Integer.toString(config.port()));
            command.add("--ctx-size"); command.add(Integer.toString(config.contextSize()));
            command.add("--threads"); command.add(Integer.toString(config.threads()));
            command.add("--n-gpu-layers"); command.add(Integer.toString(config.gpuLayers()));
            command.add("--jinja");
            if (config.extraArgs() != null) command.addAll(config.extraArgs());
            try {
                logFile = Path.of("build", "desktop", "llama-server.log").toAbsolutePath();
                Files.createDirectories(logFile.getParent());
                ProcessBuilder pb = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                process = pb.start();
                startedAt = Instant.now();
                loadedModel = model.id();
                if (!waitForHealth(Duration.ofSeconds(90))) {
                    String tail = logTail(logFile);
                    unload();
                    throw new IllegalStateException("llama-server did not become healthy. " + tail);
                }
            } catch (InterruptedException interrupted) {
                unload();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("llama-server startup was interrupted", interrupted);
            } catch (IOException error) {
                unload();
                throw new IllegalStateException("Could not launch llama-server: " + error.getMessage(), error);
            }
        }

        @Override public void stream(ChatRequest request, Consumer<StreamEvent> listener) {
            if (!transport.healthAvailable(baseUrl())) {
                listener.accept(StreamEvent.error("llama-server is not loaded or reachable at " + baseUrl()));
                return;
            }
            activeRequests.incrementAndGet();
            try { transport.chat(baseUrl(), null, request, listener); }
            finally { activeRequests.decrementAndGet(); }
        }

        @Override
        public synchronized void unload() {
            Process current = process;
            process = null;
            if (current != null && current.isAlive()) {
                current.destroy();
                try {
                    if (!current.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
                } catch (InterruptedException e) {
                    current.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            loadedModel = null;
        }

        @Override public void close() { unload(); }

        @Override
        public synchronized ResourceSnapshot resources() {
            Process current = process;
            if (current == null) return new ResourceSnapshot(-1, 0, -1, activeRequests.get(), Duration.ZERO, loadedModel == null ? "unloaded" : "server reachable");
            Duration uptime = startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
            return new ResourceSnapshot(current.pid(), current.info().totalCpuDuration().orElse(Duration.ZERO).toMillis(),
                    -1, activeRequests.get(), uptime, "llama-server log: " + logFile);
        }

        @Override public Map<String, String> backendSpecificConfiguration() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("host", config.host()); values.put("port", Integer.toString(config.port()));
            values.put("context", Integer.toString(config.contextSize()));
            values.put("threads", Integer.toString(config.threads()));
            values.put("gpu_layers", Integer.toString(config.gpuLayers()));
            values.put("procurement", "GGUF subprocess; effective GPU backend is reported by llama-server logs");
            return values;
        }

        private String baseUrl() { return "http://" + config.host() + ":" + config.port(); }

        private boolean waitForHealth(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (process != null && !process.isAlive()) return false;
                if (transport.healthAvailable(baseUrl())) return true;
                Thread.sleep(500);
            }
            return false;
        }
    }

    public static final class Vllm implements LlmBackend {
        private final VllmConfig config;
        private final OpenAiTransport transport = new OpenAiTransport();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private Process process;
        private Instant startedAt;
        private String loadedModel;
        private Path logFile;

        public Vllm(VllmConfig config) { this.config = config; }

        @Override public BackendKind kind() { return BackendKind.VLLM; }

        @Override
        public synchronized BackendCapabilities capabilities() {
            boolean available = transport.modelsAvailable(config.baseUrl(), config.apiKey());
            return available
                    ? new BackendCapabilities(true, true, true, false, false, "vLLM OpenAI API is reachable")
                    : new BackendCapabilities(false, false, false, false, false,
                    "vLLM endpoint is not reachable; enable managed start or start vLLM separately");
        }

        @Override
        public synchronized void load(ModelSpec model) {
            if (config.autoStart()) {
                unload();
                String python = config.pythonExecutable() == null || config.pythonExecutable().isBlank() ? "python" : config.pythonExecutable();
                String modelPath = model.localPath() == null || model.localPath().isBlank() ? config.modelPath() : model.localPath();
                if (modelPath == null || modelPath.isBlank()) modelPath = model.servedModelId();
                if (modelPath == null || modelPath.isBlank()) modelPath = model.id();
                if (modelPath.isBlank()) throw new IllegalArgumentException("vLLM managed start needs a model path or model id");
                List<String> command = new ArrayList<>(List.of(
                        python, "-m", "vllm.entrypoints.openai.api_server",
                        "--model", modelPath, "--host", "127.0.0.1", "--port", Integer.toString(config.port()),
                        "--gpu-memory-utilization", Double.toString(config.gpuMemoryUtilization()),
                        "--tensor-parallel-size", Integer.toString(config.tensorParallel())));
                if (config.dtype() != null && !config.dtype().isBlank()) {
                    command.add("--dtype"); command.add(config.dtype());
                }
                if (config.extraArgs() != null) command.addAll(config.extraArgs());
                try {
                    logFile = Path.of("build", "desktop", "vllm.log").toAbsolutePath();
                    Files.createDirectories(logFile.getParent());
                    process = new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())).start();
                    startedAt = Instant.now();
                    if (!waitForModels(Duration.ofMinutes(5))) {
                        String tail = logTail(logFile); unload();
                        throw new IllegalStateException("vLLM did not become ready. " + tail);
                    }
                } catch (InterruptedException interrupted) {
                    unload();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("vLLM startup was interrupted", interrupted);
                } catch (IOException error) {
                    unload(); throw new IllegalStateException("Could not start vLLM: " + error.getMessage(), error);
                }
            } else if (!transport.modelsAvailable(config.baseUrl(), config.apiKey())) {
                throw new IllegalStateException("External vLLM endpoint is not reachable: " + config.baseUrl());
            }
            loadedModel = model.servedModelId() == null || model.servedModelId().isBlank() ? model.id() : model.servedModelId();
        }

        @Override public void stream(ChatRequest request, Consumer<StreamEvent> listener) {
            activeRequests.incrementAndGet();
            try { transport.chat(config.baseUrl(), config.apiKey(), request, listener); }
            finally { activeRequests.decrementAndGet(); }
        }

        @Override public synchronized void unload() {
            Process current = process; process = null;
            stop(current); loadedModel = null;
        }
        @Override public void close() { unload(); }

        @Override public synchronized ResourceSnapshot resources() {
            Process current = process;
            Duration uptime = startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
            if (current == null) return new ResourceSnapshot(-1, 0, -1, activeRequests.get(), uptime,
                    loadedModel == null ? "external/unloaded" : "model: " + loadedModel);
            return new ResourceSnapshot(current.pid(), current.info().totalCpuDuration().orElse(Duration.ZERO).toMillis(),
                    -1, activeRequests.get(), uptime, "vLLM log: " + logFile);
        }

        @Override public Map<String, String> backendSpecificConfiguration() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("endpoint", config.baseUrl()); values.put("managed", Boolean.toString(config.autoStart()));
            values.put("gpu_memory_utilization", Double.toString(config.gpuMemoryUtilization()));
            values.put("tensor_parallel", Integer.toString(config.tensorParallel()));
            values.put("dtype", config.dtype()); return values;
        }

        private boolean waitForModels(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (process != null && !process.isAlive()) return false;
                if (transport.modelsAvailable(config.baseUrl(), config.apiKey())) return true;
                Thread.sleep(750);
            }
            return false;
        }
    }

    public static final class Strata implements LlmBackend {
        private final StrataConfig config;
        private final OpenAiTransport transport = new OpenAiTransport();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private Process process;
        private Instant startedAt;
        private String loadedModel;

        public Strata(StrataConfig config) { this.config = config; }

        @Override public BackendKind kind() { return BackendKind.STRATA; }

        @Override
        public BackendCapabilities capabilities() {
            if (config.baseUrl() == null || config.baseUrl().isBlank()) {
                return BackendCapabilities.unavailable("Strata deployments expose different APIs; configure an OpenAI-compatible gateway endpoint or launch command");
            }
            boolean available = transport.modelsAvailable(config.baseUrl(), config.apiKey());
            return new BackendCapabilities(available, available, available, false, false,
                    available ? "Strata-compatible endpoint is reachable" : "Configured Strata endpoint did not answer /v1/models");
        }

        @Override
        public synchronized void load(ModelSpec model) {
            if (config.launchCommand() != null && !config.launchCommand().isEmpty()) {
                unload();
                try {
                    List<String> command = new ArrayList<>(config.launchCommand());
                    if (config.extraArgs() != null) command.addAll(config.extraArgs());
                    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.appendTo(
                                    Path.of("build", "desktop", "strata.log").toAbsolutePath().toFile()));
                    if (config.workingDirectory() != null) builder.directory(config.workingDirectory().toFile());
                    Files.createDirectories(Path.of("build", "desktop"));
                    process = builder.start(); startedAt = Instant.now();
                } catch (IOException error) {
                    throw new IllegalStateException("Could not launch configured Strata command: " + error.getMessage(), error);
                }
            }
            if (config.baseUrl() == null || config.baseUrl().isBlank() ||
                    !transport.modelsAvailable(config.baseUrl(), config.apiKey())) {
                unload();
                throw new IllegalStateException("Strata adapter requires a reachable OpenAI-compatible endpoint; API paths differ by Strata release");
            }
            loadedModel = model.id();
        }

        @Override public void stream(ChatRequest request, Consumer<StreamEvent> listener) {
            activeRequests.incrementAndGet();
            try { transport.chat(config.baseUrl(), config.apiKey(), request, listener); }
            finally { activeRequests.decrementAndGet(); }
        }

        @Override public synchronized void unload() { Process current = process; process = null; stop(current); loadedModel = null; }
        @Override public void close() { unload(); }

        @Override public synchronized ResourceSnapshot resources() {
            Process current = process;
            Duration uptime = startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
            if (current == null) return new ResourceSnapshot(-1, 0, -1, activeRequests.get(), uptime,
                    loadedModel == null ? "external/unloaded" : "model: " + loadedModel);
            return new ResourceSnapshot(current.pid(), current.info().totalCpuDuration().orElse(Duration.ZERO).toMillis(),
                    -1, activeRequests.get(), uptime, "Strata adapter process");
        }

        @Override public Map<String, String> backendSpecificConfiguration() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("endpoint", config.baseUrl()); values.put("protocol", "OpenAI-compatible adapter");
            values.put("launch_command", config.launchCommand() == null ? "" : String.join(" ", config.launchCommand()));
            return values;
        }
    }

    private static void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    private static String findOnPath(String executable) {
        if (executable == null || executable.isBlank()) return null;
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate)) return candidate.toString();
            Path exe = Path.of(dir, executable + ".exe");
            if (Files.isRegularFile(exe)) return exe.toString();
        }
        return null;
    }

    private static String resolveExecutable(String executable) {
        if (executable != null && new File(executable).isFile()) return new File(executable).getAbsolutePath();
        return findOnPath(executable);
    }

    private static String logTail(Path log) {
        if (log == null || !Files.isRegularFile(log)) return "";
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 20);
            return "Log tail: " + String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException ignored) { return ""; }
    }
}
