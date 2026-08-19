package com.alterante.p2p.http;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.net.tunnel.BytePipe;
import com.alterante.p2p.net.tunnel.ForwardListener;
import com.alterante.p2p.net.tunnel.StreamMux;
import com.alterante.p2p.net.tunnel.Tunnels;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Client side: connect to the peer and make its HTTP server available on a local
 * port. Holds the tunnel open until the peer drops, then exits non-zero — a
 * supervising caller decides whether to reconnect (same stance as alt-p2p-lore).
 */
@Command(name = "connect", description = "Make a peer's HTTP server available on a local port.")
class ConnectCommand implements Callable<Integer> {

    @Mixin ConnectionOptions conn = new ConnectionOptions();

    @Option(names = {"--local-port"}, defaultValue = "8080",
            description = "Local port to serve the peer's HTTP on (default: ${DEFAULT-VALUE}).")
    int localPort;

    @Override
    public Integer call() throws Exception {
        if (localPort < 1 || localPort > 65535) {
            System.err.println("connect: --local-port out of range: " + localPort);
            return 2;
        }
        PeerConnection pc = conn.connect();
        BytePipe pipe = Tunnels.carrier(pc);
        StreamMux mux = new StreamMux(pipe);   // initiator
        mux.start();
        try (ForwardListener listener = new ForwardListener(mux, "127.0.0.1", localPort)) {
            listener.start();
            int fp = listener.localPort();
            System.err.println("[alt-p2p-http] tunnel up ("
                    + (pc.isTcpRelay() ? "relay" : "direct") + ").");
            System.err.println("[alt-p2p-http]   browse:  http://127.0.0.1:" + fp + "/");
            System.err.println("[alt-p2p-http] leave this running; Ctrl-C to disconnect.");
            // Block until the peer drops (direct: router keepalive-death; relay: mux EOF).
            if (pc.isTcpRelay()) mux.awaitClosed();
            else pc.awaitDisconnect();
            System.err.println("[alt-p2p-http] peer disconnected; tunnel closed.");
            return 1;
        } finally {
            mux.close();
            pc.close();
        }
    }
}
