package com.alterante.p2p.http;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionOptionsTest {

    @Test
    void parsesHostPort() {
        InetSocketAddress a = ConnectionOptions.parseServer("coord.example.com:9000");
        assertEquals("coord.example.com", a.getHostString());
        assertEquals(9000, a.getPort());
    }

    @Test
    void parsesIpPort() {
        InetSocketAddress a = ConnectionOptions.parseServer("107.174.42.68:9000");
        assertEquals("107.174.42.68", a.getHostString());
        assertEquals(9000, a.getPort());
    }

    @Test
    void rejectsMissingPort() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer("hostonly"));
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer("host:"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer(":9000"));
    }

    @Test
    void rejectsNonNumericAndOutOfRangePorts() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer("host:abc"));
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer("host:0"));
        assertThrows(IllegalArgumentException.class, () -> ConnectionOptions.parseServer("host:70000"));
    }
}
