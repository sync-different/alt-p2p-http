package com.alterante.p2p.http;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI surface tests: required flags enforced, defaults as documented, and the
 * cheap validations that run before any network activity (so they are testable
 * here and fail fast for users).
 */
class CliParseTest {

    private static CommandLine cli() {
        return new CommandLine(new Main());
    }

    @Test
    void serveRequiresConnectionFlagsAndPort() {
        // Missing everything → picocli usage error (exit 2), no network touched.
        int exit = cli().execute("serve");
        assertEquals(2, exit);
    }

    @Test
    void connectRequiresConnectionFlags() {
        int exit = cli().execute("connect");
        assertEquals(2, exit);
    }

    @Test
    void serveRejectsOutOfRangePort() {
        int exit = cli().execute("serve", "--server", "h:9000", "-s", "x", "--psk", "k", "--port", "70000");
        assertEquals(2, exit);
    }

    @Test
    void serveRefusesWhenNothingListens() {
        // A port in range with (almost certainly) no listener: the pre-flight probe
        // must refuse with exit 2 before any coordination traffic.
        int freePort = com.alterante.p2p.http.TestPorts.freePort();
        int exit = cli().execute("serve", "--server", "h:9000", "-s", "x", "--psk", "k",
                "--port", String.valueOf(freePort));
        assertEquals(2, exit);
    }

    @Test
    void connectRejectsOutOfRangeLocalPort() {
        int exit = cli().execute("connect", "--server", "h:9000", "-s", "x", "--psk", "k",
                "--local-port", "0");
        assertEquals(2, exit);
    }

    @Test
    void connectDefaultsLocalPortTo8080() {
        CommandLine cmd = cli();
        CommandLine.ParseResult pr = cmd.parseArgs("connect", "--server", "h:9000", "-s", "x", "--psk", "k");
        ConnectCommand cc = (ConnectCommand) pr.subcommand().commandSpec().userObject();
        assertEquals(8080, cc.localPort);
    }

    @Test
    void serveDefaultsPeerWaitForever() {
        CommandLine cmd = cli();
        CommandLine.ParseResult pr = cmd.parseArgs("serve", "--server", "h:9000", "-s", "x", "--psk", "k",
                "--port", "3000");
        ServeCommand sc = (ServeCommand) pr.subcommand().commandSpec().userObject();
        assertEquals(0, sc.peerWaitSeconds);
    }

    @Test
    void versionProviderNeverThrows() {
        String[] v = new Main.JarVersion().getVersion();
        assertEquals(1, v.length);
        assertTrue(v[0].startsWith("alt-p2p-http "));
        assertNotEquals("alt-p2p-http ", v[0]);
    }
}
