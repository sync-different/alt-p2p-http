package com.alterante.p2p.http;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.net.tunnel.BytePipe;
import com.alterante.p2p.net.tunnel.ForwardConnector;
import com.alterante.p2p.net.tunnel.StreamMux;
import com.alterante.p2p.net.tunnel.Tunnels;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Callable;

/**
 * Host side: expose an already-running localhost HTTP server to a peer. Stays up
 * until interrupted, serving successive clients — one at a time, one coordination
 * session; each client's departure recycles the session for the next (the lore host
 * model, coord recycling included).
 */
@Command(name = "serve", description = "Expose a localhost HTTP port to a peer over alt-p2p.")
class ServeCommand implements Callable<Integer> {

    @Mixin ConnectionOptions conn = new ConnectionOptions();

    @Option(names = {"--port"}, required = true, paramLabel = "<port>",
            description = "Localhost port the HTTP server listens on.")
    int port;

    @Option(names = {"--once"}, description = "Serve a single client then exit (default: loop for successive clients).")
    boolean once;

    @Option(names = {"--peer-wait"}, defaultValue = "0",
            description = "Seconds to wait for a peer before retrying; 0 = wait forever (default).")
    int peerWaitSeconds;

    private volatile boolean running = true;

    /**
     * One TCP probe: is anything listening? Catches the #1 user error (wrong port, server
     * not started) before hours of waiting for a peer. Only refuses at startup — if the
     * server later goes down, per-stream connects fail and streams close, but the tunnel
     * stays; the server coming back needs no restart here.
     */
    static boolean somethingListensOn(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Integer call() throws Exception {
        if (port < 1 || port > 65535) {
            System.err.println("serve: --port out of range: " + port);
            return 2;
        }
        if (!somethingListensOn(port)) {
            System.err.println("serve: nothing is listening on 127.0.0.1:" + port
                    + " — start your HTTP server first (or check the port).");
            return 2;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> running = false));
        conn.peerWaitSeconds = peerWaitSeconds;

        while (running) {
            System.err.println("[alt-p2p-http] waiting for a peer on session '" + conn.session + "'…");
            PeerConnection pc;
            try {
                pc = conn.connect();
            } catch (Exception e) {
                if (!running) break;
                System.err.println("[alt-p2p-http] connect failed: " + e.getMessage() + " — retrying");
                Thread.sleep(1000);
                continue;
            }
            BytePipe pipe = Tunnels.carrier(pc);
            StreamMux mux = new StreamMux(pipe);   // acceptor
            ForwardConnector connector = new ForwardConnector(mux, "127.0.0.1", port);
            mux.start();
            System.err.println("[alt-p2p-http] peer connected ("
                    + (pc.isTcpRelay() ? "relay" : "direct") + "); forwarding to 127.0.0.1:" + port);
            try {
                // Direct path: block on router keepalive-death. Relay path has no router
                // (awaitDisconnect returns instantly), so block on the mux reader ending
                // (relay TCP EOF when the peer leaves).
                if (pc.isTcpRelay()) mux.awaitClosed();
                else pc.awaitDisconnect();
            } catch (InterruptedException ignored) {
            } finally {
                connector.close();
                mux.close();
                pc.close();
            }
            System.err.println("[alt-p2p-http] client left; session ended.");
            if (once) break;
        }
        return 0;
    }
}
