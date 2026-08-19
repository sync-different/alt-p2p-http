package com.alterante.p2p.http;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * alt-p2p-http — tunnel a localhost HTTP port to a peer over alt-p2p.
 *
 * One peer {@code serve}s a local HTTP server; the other {@code connect}s and gets it
 * on a local port. The tunnel is the generic TCP-over-P2P layer from alt-p2p
 * (direct-UDP hole-punched DTLS, TCP-relay fallback) — HTTP rides it unmodified,
 * WebSockets and SSE included. Nothing here parses HTTP.
 */
@Command(name = "alt-p2p-http", mixinStandardHelpOptions = true, versionProvider = Main.JarVersion.class,
        description = "Tunnel a localhost HTTP port between peers over alt-p2p.",
        subcommands = {ServeCommand.class, ConnectCommand.class})
public class Main {

    /** Version from the jar manifest — the deployed filename is stable, so this is the
     *  only way to tell which build is running. */
    public static class JarVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            String p2p = "";
            try (java.io.InputStream in = Main.class.getClassLoader()
                    .getResourceAsStream("META-INF/MANIFEST.MF")) {
                if (in != null) {
                    java.util.jar.Manifest m = new java.util.jar.Manifest(in);
                    String a = m.getMainAttributes().getValue("X-Alt-P2P-Version");
                    if (a != null) {
                        p2p = "  (alt-p2p " + a + ")";
                    }
                }
            } catch (java.io.IOException ignored) {
                // version reporting must never fail the command
            }
            return new String[]{"alt-p2p-http " + (v != null ? v : "dev (unpackaged)") + p2p};
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }
}
