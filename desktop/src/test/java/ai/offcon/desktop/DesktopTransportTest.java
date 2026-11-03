package ai.offcon.desktop;

import ai.offcon.desktop.BackendContracts.ChatMessage;
import ai.offcon.desktop.BackendContracts.ChatRequest;
import ai.offcon.desktop.BackendContracts.StreamEvent;
import ai.offcon.desktop.BackendContracts.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class DesktopTransportTest {
    private HttpServer server;

    @After public void stop() { if (server != null) server.stop(0); }

    @Test
    public void streamingResponsePreservesTextAndSendsToolSchema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"local\"}}]}\n\n" +
                    "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiTransport transport = new OpenAiTransport();
        List<StreamEvent> events = new ArrayList<>();
        transport.chat("http://127.0.0.1:" + server.getAddress().getPort(), null,
                new ChatRequest(List.of(new ChatMessage("user", "hi")),
                        List.of(new ToolDefinition("lookup", "look up", "{\"type\":\"object\"}")),
                        0.2, 32, true), events::add);

        Assert.assertEquals("Hello local", events.stream().map(StreamEvent::text)
                .filter(java.util.Objects::nonNull).reduce("", String::concat));
        Assert.assertTrue(events.get(events.size() - 1).finished());
        Assert.assertTrue(requestBody.get().contains("\"type\":\"function\""));
        Assert.assertTrue(requestBody.get().contains("\"name\":\"lookup\""));
    }

    @Test
    public void imageBackendDecodesBase64Response() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        String encoded = Base64.getEncoder().encodeToString(png);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            byte[] body = ("{\"data\":[{\"b64_json\":\"" + encoded + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiImageBackend backend = new OpenAiImageBackend(
                "http://127.0.0.1:" + server.getAddress().getPort(), null);
        BackendContracts.ImageResult result = backend.generate(
                new BackendContracts.ImageRequest("image-model", "a tree", "512x512", "png"));
        Assert.assertArrayEquals(png, result.bytes());
    }
}
