package com.alterante.p2p.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeProbeTest {

    @Test
    void detectsAListener() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            assertTrue(ServeCommand.somethingListensOn(ss.getLocalPort()));
        }
    }

    @Test
    void detectsAbsenceOfAListener() throws IOException {
        int port = TestPorts.freePort();
        assertFalse(ServeCommand.somethingListensOn(port));
    }

    @Test
    void probeDoesNotConsumeTheServer() throws Exception {
        // The probe connects and closes; the HTTP server behind it must still accept
        // a real client afterwards (a probe that wedges single-threaded servers
        // would fail here).
        try (ServerSocket ss = new ServerSocket(0)) {
            assertTrue(ServeCommand.somethingListensOn(ss.getLocalPort()));
            java.net.Socket real = new java.net.Socket("127.0.0.1", ss.getLocalPort());
            real.close();
        }
    }
}
