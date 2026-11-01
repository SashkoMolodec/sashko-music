package com.sashkomusic.agents.config;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * langchain4j 1.10.0 writes {@code customParameters} twice — flattened, and again whole under
 * {@code custom_parameters} — and Anthropic answers every such request with a 400. Unit tests never
 * reach the HTTP layer, so this one lets the real model send a request to a local server and reads
 * the body that actually went out.
 */
class SonnetRequestBodyTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"captured\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(400, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void the_request_carries_no_custom_parameters_key() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("langchain4j.anthropic.chat-model.api-key", "test-key")
                .withProperty("langchain4j.anthropic.chat-model.base-url",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        ChatModel model = new AgentModelsConfig().sonnetChatModel(env);

        try {
            model.chat("hi");
        } catch (RuntimeException expected) {
            // the fake server always refuses; only the body it received matters
        }

        assertThat(body.get())
                .as("the body the model really sent")
                .isNotNull()
                .contains("\"model\"")
                .doesNotContain("custom_parameters");
    }
}
