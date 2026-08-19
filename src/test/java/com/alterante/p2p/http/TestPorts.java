package com.alterante.p2p.http;

import java.io.IOException;
import java.net.ServerSocket;

/** Test helper: a port that was just free (best-effort — bound then released). */
final class TestPorts {
    private TestPorts() {}

    static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
