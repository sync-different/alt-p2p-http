package com.alterante.p2p.http;

import com.alterante.p2p.command.TransferOptions;
import com.alterante.p2p.net.PeerConnection;
import picocli.CommandLine.Option;

import java.net.InetSocketAddress;

/** Shared alt-p2p connection options; builds and connects a {@link PeerConnection}. */
public class ConnectionOptions {

    @Option(names = {"--server"}, required = true, paramLabel = "<host:port>",
            description = "Coordination server address.")
    String server;

    @Option(names = {"-s", "--session"}, required = true, description = "Session id (must match the peer).")
    String session;

    @Option(names = {"--psk"}, required = true, description = "Pre-shared key.")
    String psk;

    @Option(names = {"--allow-relay"}, description = "Allow TCP-relay fallback if hole punching fails (recommended).")
    boolean allowRelay;

    /** Validated at parse time — a typo like "tpc" must fail loudly, not flow on silently. */
    enum RelayMode { tcp, udp }

    @Option(names = {"--relay-mode"}, defaultValue = "tcp", description = "Relay mode: ${COMPLETION-CANDIDATES}.")
    RelayMode relayMode;

    @Option(names = {"--force-relay"}, description = "Skip hole punching, go straight to relay.")
    boolean forceRelay;

    @Option(names = {"--keepalive-ms"}, defaultValue = "3000",
            description = "Keepalive interval (both peers). Dead-peer detection = 3x this; sets how fast `serve` re-pairs between clients.")
    int keepaliveMs;

    /** Peer-wait seconds; 0 = forever, null = default (120s). Set by the serve/connect command. */
    public Integer peerWaitSeconds;

    /** Parse {@code host:port}; separated from connect() so tests can cover it without a network. */
    static InetSocketAddress parseServer(String server) {
        int colon = server.lastIndexOf(':');
        if (colon <= 0 || colon == server.length() - 1) {
            throw new IllegalArgumentException("--server must be host:port");
        }
        int port;
        try {
            port = Integer.parseInt(server.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--server must be host:port (bad port: " + server.substring(colon + 1) + ")");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("--server port out of range: " + port);
        }
        return new InetSocketAddress(server.substring(0, colon), port);
    }

    /** Build and run the full connect flow; blocks until connected. */
    public PeerConnection connect() throws Exception {
        InetSocketAddress addr = parseServer(server);
        PeerConnection pc = new PeerConnection(addr, session, psk);
        TransferOptions o = new TransferOptions();
        o.allowRelay = allowRelay;
        o.relayMode = relayMode.name();
        o.forceRelay = forceRelay;
        o.keepaliveIntervalMs = keepaliveMs;
        o.peerWaitSeconds = peerWaitSeconds;
        pc.applyOptions(o);
        pc.connect();
        return pc;
    }
}
