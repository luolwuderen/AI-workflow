package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class HttpRequestExecutor implements NodeExecutor {
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final HttpSsrfGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpRequestExecutor(HttpSsrfGuard guard) {
        this.guard = guard;
    }

    @Override
    public String type() { return "http-request"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        String nodeId = context.node().id();
        Map<String, Object> config = context.node().config();
        String method = text(config.get("method"), "GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method))
            throw new NodeExecutionException(nodeId, "不支持的 HTTP 方法：" + method);
        String url = context.render(text(config.get("url"), "")).trim();
        if (url.isEmpty()) throw new NodeExecutionException(nodeId, "url 必填。");
        URI current = guarded(nodeId, url);
        Map<String, String> headers = headers(nodeId, context, config);
        String bodyType = text(config.get("bodyType"), "none");
        int timeoutMillis = timeout(config);
        Duration timeout = Duration.ofMillis(timeoutMillis);

        String currentMethod = method;
        String currentBodyType = BODY_METHODS.contains(method) ? bodyType : "none";
        String currentBody = currentBodyType.equals("none") ? "" : context.render(text(config.get("body"), ""));
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest request = request(nodeId, current, currentMethod, headers, currentBodyType, currentBody, timeout);
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                Optional<String> location = response.headers().firstValue("location");
                if (redirect(status) && location.isPresent()) {
                    close(response.body());
                    if (hop == MAX_REDIRECTS)
                        throw new NodeExecutionException(nodeId, "重定向超过 " + MAX_REDIRECTS + " 次。");
                    current = guarded(nodeId, current.resolve(location.get()).toString());
                    if (status == 303 || ((status == 301 || status == 302) && !"GET".equals(currentMethod) && !"HEAD".equals(currentMethod))) {
                        currentMethod = "GET";
                        currentBodyType = "none";
                        currentBody = "";
                    }
                    continue;
                }
                return result(nodeId, status, response);
            }
        } catch (HttpTimeoutException error) {
            throw new NodeExecutionException(nodeId, "请求超时（" + timeoutMillis + " ms）。");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NodeExecutionException(nodeId, "请求被中断。");
        } catch (IOException error) {
            throw new NodeExecutionException(nodeId, "请求失败：" + error.getMessage());
        }
        throw new NodeExecutionException(nodeId, "请求失败。");
    }

    private URI guarded(String nodeId, String url) {
        try {
            return guard.validate(url);
        } catch (IllegalArgumentException error) {
            throw new NodeExecutionException(nodeId, error.getMessage());
        }
    }

    private HttpRequest request(String nodeId, URI uri, String method, Map<String, String> headers,
                                String bodyType, String body, Duration timeout) {
        boolean sendBody = !"none".equals(bodyType);
        HttpRequest.BodyPublisher publisher = sendBody
                ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).method(method, publisher);
        Map<String, String> all = new LinkedHashMap<>(headers);
        if (sendBody && all.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("content-type"))) {
            all.put("Content-Type", switch (bodyType) {
                case "json" -> "application/json";
                case "form" -> "application/x-www-form-urlencoded";
                default -> "text/plain; charset=utf-8";
            });
        }
        for (Map.Entry<String, String> header : all.entrySet()) {
            if (header.getKey().isBlank())
                throw new NodeExecutionException(nodeId, "请求头名称不能为空。");
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException error) {
                throw new NodeExecutionException(nodeId, "不允许设置请求头 " + header.getKey() + "。");
            }
        }
        return builder.build();
    }

    private Map<String, String> headers(String nodeId, NodeExecutionContext context, Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        Object raw = config.get("headers");
        if (raw == null) return result;
        if (!(raw instanceof Map<?, ?> headers))
            throw new NodeExecutionException(nodeId, "headers 必须为对象。");
        for (Map.Entry<?, ?> header : headers.entrySet()) {
            if (header.getKey() == null || String.valueOf(header.getKey()).isBlank())
                throw new NodeExecutionException(nodeId, "请求头名称不能为空。");
            result.put(String.valueOf(header.getKey()), context.render(String.valueOf(header.getValue())));
        }
        return result;
    }

    private Map<String, Object> result(String nodeId, int status, HttpResponse<InputStream> response) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        byte[] bytes;
        try (InputStream stream = response.body()) {
            bytes = readLimited(nodeId, stream);
        }
        String contentType = headers.getOrDefault("content-type", "");
        String text = new String(bytes, charset(contentType));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("headers", headers);
        output.put("body", parseBody(text, contentType));
        output.put("url", response.uri().toString());
        return output;
    }

    private byte[] readLimited(String nodeId, InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES)
                throw new NodeExecutionException(nodeId, "响应体超过 " + (MAX_RESPONSE_BYTES / 1024 / 1024) + " MiB 限制。");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Object parseBody(String text, String contentType) {
        if (text.isBlank()) return "";
        String trimmed = text.stripLeading();
        boolean json = contentType.toLowerCase(Locale.ROOT).contains("json")
                || trimmed.startsWith("{") || trimmed.startsWith("[");
        if (!json) return text;
        try {
            return mapper.readValue(text, Object.class);
        } catch (IOException ignored) {
            return text;
        }
    }

    private static Charset charset(String contentType) {
        int index = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (index >= 0) {
            String name = contentType.substring(index + "charset=".length()).split(";")[0].trim().replace("\"", "");
            try {
                return Charset.forName(name);
            } catch (IllegalArgumentException ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static int timeout(Map<String, Object> config) {
        int value = config.get("timeoutMillis") instanceof Number number ? number.intValue() : 10000;
        return Math.max(1000, Math.min(60000, value));
    }

    private static String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 忽略关闭失败。
        }
    }
}
