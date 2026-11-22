package com.acme.workflow.infra.execution;

import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRequestExecutorTest {
    private static HttpServer server;
    private static int port;

    private final HttpRequestExecutor executor = new HttpRequestExecutor(
            new HttpSsrfGuard("127.0.0.1", HttpRequestExecutorTest::resolve));

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json", exchange -> respond(exchange, 200, "application/json", "{\"ok\":true}"));
        server.createContext("/echo", exchange -> {
            String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\\", "\\\\").replace("\"", "\\\"");
            respond(exchange, 200, "application/json",
                    "{\"method\":\"" + exchange.getRequestMethod() + "\",\"body\":\"" + received + "\"}");
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, "text/plain", "missing"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://10.0.0.1:" + port + "/json");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(new byte[HttpRequestExecutor.MAX_RESPONSE_BYTES + 1024]);
            } catch (IOException ignored) {
                // 客户端提前断开时忽略写入失败。
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsStatusHeadersAndParsedJsonBody() {
        Map<String, Object> output = executor.execute(context(Map.of(
                "method", "GET", "url", "http://127.0.0.1:" + port + "/json", "timeoutMillis", 5000)));

        assertThat(output).containsEntry("status", 200);
        assertThat(output).containsEntry("body", Map.of("ok", true));
        assertThat(output).containsEntry("url", "http://127.0.0.1:" + port + "/json");
        assertThat((Map) output.get("headers")).containsKey("content-type");
    }

    @Test
    void sendsJsonBodyAndCustomHeaders() {
        Map<String, Object> output = executor.execute(context(Map.of(
                "method", "POST", "url", "http://127.0.0.1:" + port + "/echo", "bodyType", "json",
                "body", "{\"a\":1}", "headers", Map.of("X-Test", "yes"), "timeoutMillis", 5000)));

        assertThat(output.get("body")).isEqualTo(Map.of("method", "POST", "body", "{\"a\":1}"));
    }

    @Test
    void returnsNon2xxResponsesInsteadOfFailing() {
        Map<String, Object> output = executor.execute(context(Map.of(
                "method", "GET", "url", "http://127.0.0.1:" + port + "/missing", "timeoutMillis", 5000)));

        assertThat(output).containsEntry("status", 404).containsEntry("body", "missing");
    }

    @Test
    void revalidatesRedirectTargetsAgainstSsrfGuard() {
        Map<String, Object> config = Map.of("method", "GET",
                "url", "http://127.0.0.1:" + port + "/redirect", "timeoutMillis", 5000);

        assertThatThrownBy(() -> executor.execute(context(config)))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("SSRF");
    }

    @Test
    void rejectsResponsesAboveSizeLimit() {
        Map<String, Object> config = Map.of("method", "GET",
                "url", "http://127.0.0.1:" + port + "/big", "timeoutMillis", 5000);

        assertThatThrownBy(() -> executor.execute(context(config)))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("响应体超过");
    }

    @Test
    void rejectsInvalidMethodAndMissingUrl() {
        assertThatThrownBy(() -> executor.execute(context(Map.of("method", "TRACE", "url", "http://127.0.0.1/x"))))
                .hasMessageContaining("不支持的 HTTP 方法");
        assertThatThrownBy(() -> executor.execute(context(Map.of("method", "GET", "url", ""))))
                .hasMessageContaining("url 必填");
    }

    private static NodeExecutionContext context(Map<String, Object> config) {
        GraphNode node = new GraphNode("http", "http-request", "HTTP Request", new Position(0, 0), config);
        return new NodeExecutionContext("run", null, node, Map.of(), Map.of(), new LinkedHashMap<>(), (type, data) -> { });
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static List<InetAddress> resolve(String host) throws UnknownHostException {
        if ("10.0.0.1".equals(host)) return List.of(InetAddress.getByName("10.0.0.1"));
        throw new UnknownHostException(host);
    }
}
