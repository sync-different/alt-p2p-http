package com.alterante.p2p.http;

import com.alterante.p2p.net.PeerConnection;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The serve loop's failure policy, without a network: a failed connect must retry
 * with a pause — not exit, and not spin hot. Uses a stub ConnectionOptions whose
 * connect() always throws; the loop is unwound by interrupting the thread (the
 * inter-retry sleep propagates InterruptedException out of call()).
 */
class ServeLoopTest {

    @Test
    void retriesWithPauseAfterFailedConnect() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ServerSocket app = new ServerSocket(0)) {   // the probe needs a listener
            ServeCommand serve = new ServeCommand();
            serve.port = app.getLocalPort();
            serve.conn = new ConnectionOptions() {
                @Override
                public PeerConnection connect() throws Exception {
                    attempts.incrementAndGet();
                    throw new java.io.IOException("stub: coordinator unreachable");
                }
            };
            serve.conn.session = "loop-test";

            Thread t = new Thread(() -> {
                try {
                    serve.call();
                } catch (Exception ignored) {
                    // InterruptedException from the inter-retry sleep — the intended exit
                }
            });
            long start = System.nanoTime();
            t.start();
            // Three attempts prove the loop retries rather than exiting on failure.
            long deadline = System.currentTimeMillis() + 15_000;
            while (attempts.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            t.interrupt();
            t.join(5_000);

            assertTrue(attempts.get() >= 3, "loop should retry after failures, got " + attempts.get());
            // Two pauses between three attempts: anything under ~2s means the sleep is gone
            // and the loop would hammer the coordinator.
            assertTrue(elapsedMs >= 1800, "retries arrived too fast (" + elapsedMs + "ms) — inter-retry pause lost");
            assertTrue(!t.isAlive(), "serve thread should unwind on interrupt");
        }
    }
}
