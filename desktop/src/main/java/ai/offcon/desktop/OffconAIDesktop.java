package ai.offcon.desktop;

import ai.offcon.desktop.BackendContracts.BackendCapabilities;
import ai.offcon.desktop.BackendContracts.ChatMessage;
import ai.offcon.desktop.BackendContracts.ChatRequest;
import ai.offcon.desktop.BackendContracts.DesktopEdition;
import ai.offcon.desktop.BackendContracts.ImageRequest;
import ai.offcon.desktop.BackendContracts.ImageResult;
import ai.offcon.desktop.BackendContracts.LlmBackend;
import ai.offcon.desktop.BackendContracts.ModelSpec;
import ai.offcon.desktop.BackendContracts.ResourceSnapshot;
import ai.offcon.desktop.BackendContracts.StreamEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** OffconAI desktop shell. Backend work runs on workers, never on the Swing EDT. */
public final class OffconAIDesktop {
    private final JFrame frame = new JFrame("OffconAI Desktop");
    private final JComboBox<DesktopEdition> edition = new JComboBox<>(DesktopEdition.values());
    private final JComboBox<String> backendChoice = new JComboBox<>(new String[]{"llama.cpp", "vLLM", "Strata"});
    private final CardLayout configCards = new CardLayout();
    private final JPanel configPanel = new JPanel(configCards);
    private final JTextField modelId = new JTextField("local-model", 18);
    private final JTextField llamaExecutable = new JTextField("llama-server", 20);
    private final JTextField llamaModelPath = new JTextField("", 28);
    private final JTextField llamaPort = new JTextField("8080", 6);
    private final JTextField llamaGpuLayers = new JTextField("0", 5);
    private final JTextField vllmEndpoint = new JTextField("http://127.0.0.1:8000", 24);
    private final JTextField vllmModelPath = new JTextField("", 28);
    private final JTextField vllmPort = new JTextField("8000", 6);
    private final JCheckBox vllmManaged = new JCheckBox("Start vLLM process", true);
    private final JTextField strataEndpoint = new JTextField("", 24);
    private final JTextField strataLaunch = new JTextField("", 28);
    private final JTextField imageEndpoint = new JTextField("http://127.0.0.1:8000", 24);
    private final JTextField imageModel = new JTextField("", 18);
    private final JTextField imageSize = new JTextField("1024x1024", 10);
    private final JTextArea imagePrompt = new JTextArea(5, 50);
    private final JTextField imageOutput = new JTextField("build/desktop/generated.png", 30);
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(4, 60);
    private final JLabel status = new JLabel("Select a backend and load a model.");
    private final JLabel resources = new JLabel("No runtime resources");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel imageTab = buildImageTab();
    private final List<ChatMessage> messages = new ArrayList<>();
    private LlmBackend activeBackend;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new OffconAIDesktop().show());
    }

    private OffconAIDesktop() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 680));
        frame.setLocationRelativeTo(null);
        frame.add(buildHeader(), BorderLayout.NORTH);
        tabs.addTab("LLM workspace", buildChatTab());
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(buildFooter(), BorderLayout.SOUTH);
        edition.addActionListener(e -> updateEdition());
        backendChoice.addActionListener(e -> { configCards.show(configPanel, backendChoice.getSelectedItem().toString()); updateBackendState(); });
        configCards.show(configPanel, "llama.cpp");
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { closeBackend(); }
        });
        Timer timer = new Timer(2000, e -> refreshResources());
        timer.start();
    }

    private void show() {
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 8));
        header.setBorder(new EmptyBorder(12, 16, 8, 16));
        JLabel title = new JLabel("OffconAI");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        header.add(title, BorderLayout.WEST);
        JPanel selectors = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        selectors.add(new JLabel("Edition")); selectors.add(edition);
        selectors.add(new JLabel("Backend")); selectors.add(backendChoice);
        header.add(selectors, BorderLayout.EAST);
        return header;
    }

    private JComponent buildChatTab() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(8, 12, 8, 12));
        root.add(buildConfiguration(), BorderLayout.NORTH);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        transcript.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(new JScrollPane(transcript), BorderLayout.CENTER);

        JPanel composer = new JPanel(new BorderLayout(8, 8));
        input.setLineWrap(true); input.setWrapStyleWord(true);
        composer.add(new JScrollPane(input), BorderLayout.CENTER);
        JButton send = new JButton("Send");
        send.addActionListener(e -> sendMessage());
        composer.add(send, BorderLayout.EAST);
        root.add(composer, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildConfiguration() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(BorderFactory.createTitledBorder("Runtime configuration"));
        JPanel common = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        common.add(new JLabel("Model label")); common.add(modelId);
        JButton load = new JButton("Load / connect");
        load.addActionListener(e -> loadModel());
        JButton unload = new JButton("Unload");
        unload.addActionListener(e -> { closeBackend(); status.setText("Runtime unloaded."); });
        common.add(load); common.add(unload); outer.add(common, BorderLayout.NORTH);

        configPanel.add(buildLlamaConfig(), "llama.cpp");
        configPanel.add(buildVllmConfig(), "vLLM");
        configPanel.add(buildStrataConfig(), "Strata");
        outer.add(configPanel, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildLlamaConfig() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel("llama-server")); panel.add(llamaExecutable);
        panel.add(new JLabel("GGUF path")); panel.add(llamaModelPath);
        panel.add(new JLabel("Port")); panel.add(llamaPort);
        panel.add(new JLabel("GPU layers (0 = CPU)")); panel.add(llamaGpuLayers);
        return panel;
    }

    private JPanel buildVllmConfig() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel("Endpoint")); panel.add(vllmEndpoint);
        panel.add(new JLabel("Model path/id")); panel.add(vllmModelPath);
        panel.add(new JLabel("Port")); panel.add(vllmPort); panel.add(vllmManaged);
        return panel;
    }

    private JPanel buildStrataConfig() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel("Gateway endpoint")); panel.add(strataEndpoint);
        panel.add(new JLabel("Launch command (optional)")); panel.add(strataLaunch);
        JLabel note = new JLabel("Strata releases expose different APIs; configure its OpenAI-compatible gateway.");
        note.setForeground(new Color(120, 85, 15)); panel.add(note);
        return panel;
    }

    private JPanel buildImageTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, c, 0, "OpenAI-compatible image endpoint", imageEndpoint);
        addRow(form, c, 1, "Image model", imageModel);
        addRow(form, c, 2, "Size", imageSize);
        addRow(form, c, 3, "Output path", imageOutput);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; form.add(new JLabel("Prompt"), c);
        c.gridy = 5; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        imagePrompt.setLineWrap(true); imagePrompt.setWrapStyleWord(true); form.add(new JScrollPane(imagePrompt), c);
        JButton generate = new JButton("Generate image");
        c.gridy = 6; c.weighty = 0; c.fill = GridBagConstraints.NONE; form.add(generate, c);
        generate.addActionListener(e -> generateImage());
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridy = row; c.gridx = 0; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.setBorder(new EmptyBorder(4, 12, 10, 12));
        footer.add(status, BorderLayout.WEST); footer.add(resources, BorderLayout.EAST);
        return footer;
    }

    private void updateEdition() {
        boolean allInOne = edition.getSelectedItem() == DesktopEdition.ALL_IN_ONE;
        if (allInOne && tabs.indexOfComponent(imageTab) < 0) tabs.addTab("Image generation", imageTab);
        if (!allInOne && tabs.indexOfComponent(imageTab) >= 0) tabs.remove(imageTab);
    }

    private void updateBackendState() {
        String selected = (String) backendChoice.getSelectedItem();
        configCards.show(configPanel, selected);
        status.setText(selected + " selected. Verify its typed configuration before loading.");
    }

    private void loadModel() {
        LlmBackend backend = createBackend();
        setBusy(true, "Loading backend...");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                backend.load(new ModelSpec(modelId.getText().trim(),
                        switch ((String) backendChoice.getSelectedItem()) {
                            case "llama.cpp" -> llamaModelPath.getText().trim();
                            case "vLLM" -> vllmModelPath.getText().trim();
                            default -> "";
                        }, modelId.getText().trim()));
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    closeBackend(); activeBackend = backend;
                    BackendCapabilities cap = backend.capabilities();
                    status.setText(cap.available() ? cap.reason() : "Unavailable: " + cap.reason());
                    setBusy(false, status.getText());
                } catch (Exception error) {
                    try { backend.close(); } catch (Exception ignored) {}
                    setBusy(false, "Load failed: " + rootMessage(error));
                }
            }
        }.execute();
    }

    private LlmBackend createBackend() {
        String choice = (String) backendChoice.getSelectedItem();
        if ("llama.cpp".equals(choice)) {
            return new Backends.LlamaCpp(new Backends.LlamaCppConfig(
                    llamaExecutable.getText().trim(), llamaModelPath.getText().trim(), "127.0.0.1",
                    parsePort(llamaPort.getText(), 8080), 4096,
                    Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                    Math.max(0, parseInt(llamaGpuLayers.getText(), 0)), List.of()));
        }
        if ("vLLM".equals(choice)) {
            return new Backends.Vllm(new Backends.VllmConfig(
                    vllmEndpoint.getText().trim(), System.getenv().getOrDefault("VLLM_API_KEY", ""),
                    modelId.getText().trim(), System.getenv().getOrDefault("PYTHON", "python"),
                    vllmModelPath.getText().trim(), vllmManaged.isSelected(),
                    parsePort(vllmPort.getText(), 8000), 0.85, 1, "auto", List.of()));
        }
        return new Backends.Strata(new Backends.StrataConfig(
                strataEndpoint.getText().trim(), System.getenv().getOrDefault("STRATA_API_KEY", ""),
                modelId.getText().trim(), parseCommand(strataLaunch.getText()), Path.of("."), List.of()));
    }

    private void sendMessage() {
        LlmBackend backend = activeBackend;
        String prompt = input.getText().trim();
        if (backend == null) { status.setText("Load a backend first."); return; }
        if (prompt.isEmpty()) return;
        input.setText("");
        transcript.append("\nYou\n" + prompt + "\n\nAssistant\n");
        messages.add(new ChatMessage("user", prompt));
        setBusy(true, "Generating...");
        ChatRequest request = new ChatRequest(new ArrayList<>(messages), List.of(), 0.7, 1024, true);
        StringBuilder assistant = new StringBuilder();
        new SwingWorker<Void, StreamEvent>() {
            @Override protected Void doInBackground() {
                backend.stream(request, event -> publish(event));
                return null;
            }
            @Override protected void process(List<StreamEvent> chunks) {
                for (StreamEvent event : chunks) {
                    if (event.text() != null) { transcript.append(event.text()); assistant.append(event.text()); }
                    if (event.reasoning() != null) transcript.append("\n[reasoning] " + event.reasoning());
                    if (event.rawToolCall() != null) transcript.append("\n[tool call received] " + event.rawToolCall());
                    if (event.error() != null) status.setText("Generation failed: " + event.error());
                }
            }
            @Override protected void done() {
                try { get(); } catch (Exception error) { status.setText("Generation failed: " + rootMessage(error)); }
                if (!assistant.isEmpty()) messages.add(new ChatMessage("assistant", assistant.toString()));
                setBusy(false, "Ready");
            }
        }.execute();
    }

    private void generateImage() {
        if (edition.getSelectedItem() != DesktopEdition.ALL_IN_ONE) return;
        String prompt = imagePrompt.getText().trim();
        if (prompt.isEmpty()) { status.setText("Image prompt is required."); return; }
        OpenAiImageBackend backend = new OpenAiImageBackend(imageEndpoint.getText(), System.getenv().getOrDefault("IMAGE_API_KEY", ""));
        setBusy(true, "Generating image...");
        new SwingWorker<ImageResult, Void>() {
            @Override protected ImageResult doInBackground() {
                return backend.generate(new ImageRequest(imageModel.getText().trim(), prompt, imageSize.getText().trim(), "png"));
            }
            @Override protected void done() {
                try {
                    ImageResult result = get();
                    Path output = Path.of(imageOutput.getText().trim());
                    if (output.getParent() != null) Files.createDirectories(output.getParent());
                    Files.write(output, result.bytes());
                    setBusy(false, "Image written to " + output.toAbsolutePath());
                } catch (Exception error) {
                    setBusy(false, "Image generation failed: " + rootMessage(error));
                } finally { backend.close(); }
            }
        }.execute();
    }

    private void refreshResources() {
        LlmBackend backend = activeBackend;
        if (backend == null) { resources.setText("No runtime resources"); return; }
        ResourceSnapshot snapshot = backend.resources();
        resources.setText(String.format("pid=%d  cpu=%dms  req=%d  up=%s", snapshot.processId(),
                snapshot.cpuMillis(), snapshot.activeRequests(), formatDuration(snapshot.uptime())));
    }

    private void closeBackend() {
        LlmBackend previous = activeBackend; activeBackend = null;
        if (previous != null) {
            try { previous.close(); } catch (Exception ignored) {}
        }
    }

    private void setBusy(boolean busy, String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            backendChoice.setEnabled(!busy); edition.setEnabled(!busy); input.setEnabled(!busy);
        });
    }

    private static int parsePort(String value, int fallback) {
        int port = parseInt(value, fallback); return port > 0 && port <= 65535 ? port : fallback;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static String formatDuration(java.time.Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static List<String> parseCommand(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char c : input.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0; else current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) { result.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
