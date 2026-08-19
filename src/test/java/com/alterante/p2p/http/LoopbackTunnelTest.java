package com.alterante.p2p.http;

import com.alterante.p2p.net.CoordServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core path under `mvn test`: an in-JVM coordinator, an in-JVM HTTP app, and the
 * REAL serve + connect commands run exactly as the CLI runs them — then an HTTP
 * round-trip through the tunnel. Until this test, that path was covered only by
 * scripts/loopback.sh, which CI never executes; a regression in the command wiring
 * (e.g. the relay-vs-direct block distinction) would have passed `mvn test` silently.
 *
 * Also pins two documented contracts: serve --once exits 0 after its single session,
 * and connect exits NON-zero when the peer drops (that is what tells a supervisor to
 * reconnect).
 */
class LoopbackTunnelTest {

    static final String PSK = "itest-psk";
    static CoordServer coord;
    static Thread coordThread;
    static int coordPort;
    static HttpServer app;
    static int appPort;
    static final String BODY = "hello through the in-jvm tunnel";

    @BeforeAll
    static void up() throws Exception {
        coordPort = TestPorts.freePort();
        coord = new CoordServer(coordPort, PSK, 60);
        coordThread = new Thread(() -> {
            try {
                coord.start();
            } catch (Exception e) {
                if (coord.isRunning()) throw new RuntimeException(e);
            }
        }, "itest-coord");
        coordThread.setDaemon(true);
        coordThread.start();

        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/", ex -> {
            byte[] b = BODY.getBytes(StandardCharsets.UTF_8);
            if ("POST".equals(ex.getRequestMethod())) {
                try (InputStream in = ex.getRequestBody()) {
                    b = ("echo:" + new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
            }
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        app.start();
        appPort = app.getAddress().getPort();
        Thread.sleep(300);   // let the coord socket come up
    }

    @AfterAll
    static void down() {
        app.stop(0);
        coord.stop();
    }

    @Test
    void tunnelCarriesGetAndPostAndExitCodesHonorTheContract() throws Exception {
        int localPort = TestPorts.freePort();
        String session = "itest-" + System.nanoTime();

        AtomicInteger serveExit = new AtomicInteger(-1);
        AtomicInteger connectExit = new AtomicInteger(-1);
        CountDownLatch serveDone = new CountDownLatch(1);
        CountDownLatch connectDone = new CountDownLatch(1);

        // Fast dead-peer detection (3 x keepalive) so the exit-code half of the test
        // does not wait out the 9s default.
        String[] common = {"-s", session, "--psk", PSK,
                "--server", "127.0.0.1:" + coordPort, "--keepalive-ms", "500"};

        Thread serveThread = new Thread(() -> {
            String[] args = concat(new String[]{"serve", "--port", String.valueOf(appPort), "--once"}, common);
            serveExit.set(new CommandLine(new Main()).execute(args));
            serveDone.countDown();
        }, "itest-serve");
        Thread connectThread = new Thread(() -> {
            String[] args = concat(new String[]{"connect", "--local-port", String.valueOf(localPort)}, common);
            connectExit.set(new CommandLine(new Main()).execute(args));
            connectDone.countDown();
        }, "itest-connect");
        serveThread.start();
        Thread.sleep(200);
        connectThread.start();

        // GET through the tunnel (poll until the pair is up).
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://127.0.0.1:" + localPort + "/");
        String got = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) { got = r.body(); break; }
            } catch (Exception retry) {
                Thread.sleep(250);
            }
        }
        assertEquals(BODY, got, "GET through the tunnel");

        // POST through the tunnel: the body must arrive at the app and come back.
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofString("payload-123")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("echo:payload-123", post.body(), "POST through the tunnel");

        // Drop the host side: serve (--once) must exit 0; connect must notice the dead
        // peer via keepalive-death and exit non-zero — the supervisor contract.
        serveThread.interrupt();
        assertTrue(serveDone.await(15, TimeUnit.SECONDS), "serve should exit after interrupt");
        assertEquals(0, serveExit.get(), "serve --once exits 0 after its session");
        assertTrue(connectDone.await(20, TimeUnit.SECONDS), "connect should notice the dead peer");
        assertNotEquals(0, connectExit.get(), "connect exits non-zero on peer drop (supervisor contract)");
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
