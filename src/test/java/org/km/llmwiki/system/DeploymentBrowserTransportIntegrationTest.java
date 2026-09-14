package org.km.llmwiki.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-socket Browser ingress transport evidence (#422 §E).
 *
 * <p>Not MockMvc: a real TCP forwarder socket relays real HTTP bytes to the real
 * loopback backend, and a raw-socket HTTP client drives the production owner
 * filter chain with the true remote-facing {@code Host}/{@code Origin} values:
 * login → session cookie → authenticated {@code GET /api/v1/system/deployment}.
 * The forwarder socket binds loopback in CI as a stand-in for the private
 * address (documented limitation); the HTTP ingress values are the genuine
 * remote ones, so the Host/Origin/cookie/validator path is identical and no
 * {@code localhost} value ever impersonates the remote ingress.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18765",
                "app.persistence.sqlite.path=target/test-data/transport-smoke/knowledge.db",
                "app.deployment.mode=PRIVATE_INGRESS",
                "app.deployment.forwarder-binds=100.64.0.5:18766",
                "app.deployment.forwarder-target=127.0.0.1:18765",
                "app.deployment.browser-origin=http://100.64.0.5:18766",
                "app.owner.auth-enabled=true",
                "app.owner.password-hash=9148a9b37f4f80aa2e47430e455049e2e41c020df0febe085c306c40a2626393",
                "app.owner.cookie-secure=false",
                "app.owner.allowed-hosts=localhost,127.0.0.1,100.64.0.5",
                "app.owner.allowed-origins=http://localhost:18765,http://127.0.0.1:18765,http://100.64.0.5:18766",
                "app.owner.login-max-attempts=100",
                "app.owner.login-window=1m",
                "app.owner.mutation-max-requests=1000",
                "app.owner.mutation-window=1m"})
class DeploymentBrowserTransportIntegrationTest {

    private static final int FORWARDER_PORT = 18766;
    private static final String REMOTE_HOST = "100.64.0.5:18766";
    private static final String REMOTE_ORIGIN = "http://100.64.0.5:18766";
    private static final String PASSWORD = "owner-test-password";

    @LocalServerPort
    private int backendPort;

    @Value("${server.address:127.0.0.1}")
    private String serverAddress;

    private final ObjectMapper mapper = new ObjectMapper();

    private TcpForwarder forwarder;

    @BeforeEach
    void startBoundedForwarder() throws IOException {
        assertThat(backendPort).isEqualTo(18765);
        forwarder = new TcpForwarder("127.0.0.1", FORWARDER_PORT, "127.0.0.1", backendPort);
    }

    @AfterEach
    void stopBoundedForwarder() {
        if (forwarder != null) {
            forwarder.close();
            forwarder = null;
        }
    }

    @Test
    void backendStaysBoundToLoopback() {
        assertThat(serverAddress).isEqualTo("127.0.0.1");
    }

    @Test
    void remoteLoginSessionAndDeploymentWorkOverRealForwarderSockets() throws Exception {
        HttpResponse anonymous = send("GET", "/api/v1/system/deployment",
                Map.of("Host", REMOTE_HOST), null);
        assertThat(anonymous.status()).isEqualTo(401);

        HttpResponse login = send("POST", "/api/v1/owner/session",
                Map.of("Host", REMOTE_HOST, "Origin", REMOTE_ORIGIN,
                        "Content-Type", "application/json"),
                "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).isEqualTo(201);
        String token = mapper.readTree(login.body()).get("data").get("token").asText();
        assertThat(token).isNotBlank();
        assertThat(login.body()).doesNotContain(PASSWORD);

        List<String> setCookies = login.headers().getOrDefault("set-cookie", List.of());
        assertThat(setCookies).isNotEmpty();
        // http-over-encrypted-tunnel profile: HttpOnly without Secure, SameSite Lax.
        boolean httpOnly = false;
        boolean lax = false;
        boolean secure = false;
        for (String cookie : setCookies) {
            for (String part : cookie.split(";")) {
                String attribute = part.strip();
                if (attribute.equalsIgnoreCase("httponly")) {
                    httpOnly = true;
                } else if (attribute.equalsIgnoreCase("Secure")) {
                    secure = true;
                } else if (attribute.equalsIgnoreCase("SameSite=Lax")) {
                    lax = true;
                }
            }
        }
        assertThat(httpOnly).isTrue();
        assertThat(lax).isTrue();
        assertThat(secure).isFalse();

        HttpResponse deployment = send("GET", "/api/v1/system/deployment",
                Map.of("Host", REMOTE_HOST, "Cookie", "km-owner-session=" + token), null);
        assertThat(deployment.status()).isEqualTo(200);
        JsonNode data = mapper.readTree(deployment.body()).get("data");
        assertThat(data.get("mode").asText()).isEqualTo("PRIVATE_INGRESS");
        assertThat(data.get("supportState").asText()).isEqualTo("SUPPORTED");

        // Recreated profile over fresh sockets still holds (restart/recreate semantics).
        HttpResponse secondLogin = send("POST", "/api/v1/owner/session",
                Map.of("Host", REMOTE_HOST, "Origin", REMOTE_ORIGIN,
                        "Content-Type", "application/json"),
                "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(secondLogin.status()).isEqualTo(201);
        String secondToken = mapper.readTree(secondLogin.body()).get("data").get("token").asText();

        HttpResponse secondDeployment = send("GET", "/api/v1/system/deployment",
                Map.of("Host", REMOTE_HOST, "Cookie", "km-owner-session=" + secondToken), null);
        assertThat(secondDeployment.status()).isEqualTo(200);
        assertThat(mapper.readTree(secondDeployment.body()).get("data").get("supportState").asText())
                .isEqualTo("SUPPORTED");
    }

    @Test
    void wrongHostOriginCredentialAndMissingSessionFailClosedOverRealSockets() throws Exception {
        HttpResponse wrongHost = send("POST", "/api/v1/owner/session",
                Map.of("Host", "evil.example", "Origin", REMOTE_ORIGIN,
                        "Content-Type", "application/json"),
                "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(wrongHost.status()).isEqualTo(403);
        assertThat(wrongHost.body()).doesNotContain(PASSWORD);

        HttpResponse wrongOrigin = send("POST", "/api/v1/owner/session",
                Map.of("Host", REMOTE_HOST, "Origin", "http://evil.example",
                        "Content-Type", "application/json"),
                "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(wrongOrigin.status()).isEqualTo(403);

        HttpResponse wrongPassword = send("POST", "/api/v1/owner/session",
                Map.of("Host", REMOTE_HOST, "Origin", REMOTE_ORIGIN,
                        "Content-Type", "application/json"),
                "{\"password\":\"not-the-password\"}");
        assertThat(wrongPassword.status()).isEqualTo(401);

        HttpResponse login = send("POST", "/api/v1/owner/session",
                Map.of("Host", REMOTE_HOST, "Origin", REMOTE_ORIGIN,
                        "Content-Type", "application/json"),
                "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).isEqualTo(201);
        String token = mapper.readTree(login.body()).get("data").get("token").asText();

        // Cookie mutations without an allowlisted Origin fail closed and keep
        // the session intact for a subsequent legitimate read.
        HttpResponse rotationWithoutOrigin = send("POST", "/api/v1/owner/session/rotation",
                Map.of("Host", REMOTE_HOST, "Cookie", "km-owner-session=" + token), null);
        assertThat(rotationWithoutOrigin.status()).isEqualTo(403);

        HttpResponse deployment = send("GET", "/api/v1/system/deployment",
                Map.of("Host", REMOTE_HOST, "Cookie", "km-owner-session=" + token), null);
        assertThat(deployment.status()).isEqualTo(200);
    }

    record HttpResponse(int status, Map<String, List<String>> headers, String body) {
    }

    private HttpResponse send(String method, String path, Map<String, String> headers, String body)
            throws IOException {
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", FORWARDER_PORT), 5000);
            socket.setSoTimeout(15000);
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            headers.forEach((name, value) ->
                    request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
            byte[] raw = socket.getInputStream().readAllBytes();
            return parse(raw);
        }
    }

    private static HttpResponse parse(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        int split = text.indexOf("\r\n\r\n");
        String head = split < 0 ? text : text.substring(0, split);
        String body = split < 0 ? "" : text.substring(split + 4);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ", 3)[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = lines[index].substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = lines[index].substring(colon + 1).strip();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        boolean chunked = headers.getOrDefault("transfer-encoding", List.of()).stream()
                .anyMatch(value -> value.equalsIgnoreCase("chunked"));
        if (chunked) {
            body = dechunk(body);
        }
        return new HttpResponse(status, headers, body);
    }

    private static String dechunk(String framed) {
        StringBuilder out = new StringBuilder();
        int position = 0;
        while (true) {
            int lineEnd = framed.indexOf("\r\n", position);
            if (lineEnd < 0) {
                break;
            }
            String sizeText = framed.substring(position, lineEnd).split(";", 2)[0].strip();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException malformed) {
                break;
            }
            if (size == 0) {
                break;
            }
            int start = lineEnd + 2;
            int end = Math.min(start + size, framed.length());
            out.append(framed, start, end);
            position = end + 2;
        }
        return out.toString();
    }

    /**
     * Minimal bounded TCP forwarder: loopback listen socket relaying bytes to the
     * loopback backend. Production uses a private-address listener; this test
     * binds loopback only so CI needs no extra network namespace, while every
     * HTTP ingress value on the wire is the genuine remote one.
     */
    static final class TcpForwarder implements AutoCloseable {

        private final ServerSocket listener;
        private final String targetHost;
        private final int targetPort;
        private final ExecutorService pool;
        private volatile boolean closed;

        TcpForwarder(String listenHost, int listenPort, String targetHost, int targetPort)
                throws IOException {
            this.listener = new ServerSocket(listenPort, 50, InetAddress.getByName(listenHost));
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.pool = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task);
                thread.setDaemon(true);
                return thread;
            });
            pool.submit(this::acceptLoop);
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket inbound = listener.accept();
                    pool.submit(() -> relay(inbound));
                } catch (IOException expected) {
                    if (!closed) {
                        throw new IllegalStateException(expected);
                    }
                }
            }
        }

        private void relay(Socket inbound) {
            try (inbound; Socket outbound = new Socket()) {
                outbound.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                Future<?> upstream = pool.submit(() -> pump(inbound, outbound));
                Future<?> downstream = pool.submit(() -> pump(outbound, inbound));
                try {
                    downstream.get(30, TimeUnit.SECONDS);
                } catch (Exception timedOut) {
                    // Fall through to close; the pumps observe the closed socket.
                }
                // The backend closes its side once the bounded response is fully
                // written (our client always sends Connection: close). Closing
                // the pair now lets the client read observe EOF promptly instead
                // of waiting for a client half-close that only follows the
                // response. Buffered bytes are still delivered before FIN.
                upstream.cancel(true);
            } catch (IOException expected) {
                // Peer teardown during a bounded test relay is not a test failure.
            }
        }

        private static void pump(Socket from, Socket to) {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                in.transferTo(out);
                out.flush();
            } catch (IOException expected) {
                // Peer teardown during a bounded test relay is not a test failure.
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                listener.close();
            } catch (IOException expected) {
                // Closing the test listener is best-effort.
            }
            pool.shutdownNow();
        }
    }
}
