package org.km.llmwiki.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin loopback HTTP client restricted to the public {@code /api/v1} surface
 * (Refs #429 §B: user actions only via public/application boundary).
 *
 * <p>No direct DB access, no filesystem writes, no internal service calls.
 * Proof-only inspection lives in a separate class.
 */
public final class ProductAcceptanceHttpClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductAcceptanceHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public record Response(int status, String body) {
        public JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (Exception failure) {
                throw new IllegalStateException("response is not JSON: " + body, failure);
            }
        }
    }

    public Response get(String path) {
        return send(requestBuilder(path).GET().build());
    }

    public Response postJson(String path, String json) {
        return send(requestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    public Response patchJson(String path, String json) {
        return send(requestBuilder(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    public Response delete(String path) {
        return send(requestBuilder(path).DELETE().build());
    }

    /** Multipart single-file upload for {@code POST /api/v1/inbox/files}. */
    public Response uploadFile(String fileName, String contentType, byte[] content) {
        String boundary = "acceptance-" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, fileName, contentType, content);
        HttpRequest request = requestBuilder("/api/v1/inbox/files")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String baseUrl() {
        return baseUrl;
    }

    private HttpRequest.Builder requestBuilder(String path) {
        String encoded = path.startsWith("/") ? path : "/" + path;
        return HttpRequest.newBuilder(URI.create(baseUrl + encoded))
                .timeout(Duration.ofSeconds(30));
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "HTTP call failed: " + request.method() + " " + request.uri(), failure);
        }
    }

    private static byte[] multipartBody(String boundary, String fileName, String contentType,
                                        byte[] content) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] prefix = header.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + content.length + footer.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(footer, 0, body, prefix.length + content.length, footer.length);
        return body;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
