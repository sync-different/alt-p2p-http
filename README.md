# alt-p2p-http

Tunnel a localhost HTTP port to a peer, encrypted, peer to peer — no public URL,
no cloud relay of your content, no account.

One side runs `serve` next to an HTTP server; the other runs `connect` and gets that
server on a local port. Traffic goes directly between the two machines over an
encrypted P2P link (UDP hole punching + DTLS); when networks are too strict for a
direct link, it falls back to a TCP relay through the coordination server — still
end-to-end encrypted, the relay never sees plaintext.

Built on [alt-p2p](https://github.com/sync-different/alt-p2p), the same stack behind
[alt-p2p-lore](https://github.com/sync-different/alt-p2p-lore) and
[alt-p2p-bbs](https://github.com/sync-different/alt-p2p-bbs).

## How it differs from ngrok-style tunnels

Both ends run this CLI and share a session name + pre-shared key. There is no public
URL and no anonymous access — that is the point: you are sharing a port with a *peer*,
not publishing it to the internet. The coordination server only introduces the two
peers (and carries encrypted bytes in relay fallback); you can self-host it
(`alt-p2p server`).

## Usage

```bash
# Machine A — has an HTTP server on localhost:3000
java -jar alt-p2p-http.jar serve --port 3000 \
    -s mysession --psk mysecret --server coord.example.com:9000 --allow-relay

# Machine B — wants to browse it
java -jar alt-p2p-http.jar connect --local-port 8080 \
    -s mysession --psk mysecret --server coord.example.com:9000 --allow-relay
# then open http://127.0.0.1:8080/
```

`serve` waits forever for a peer and serves successive clients (one at a time; the
next client can connect as soon as the previous one leaves). `connect` holds the
tunnel open until the peer drops, then exits non-zero — restart it (or wrap it in a
supervisor) to reconnect.

WebSockets, server-sent events, and HTTP/2 cleartext all work — the tunnel moves TCP
bytes and never parses HTTP.

## Requirements

- JDK 17+ on both machines.
- A coordination server both peers can reach: `java -jar alt-p2p.jar server --psk <key>`
  (UDP for coordination and hole punching; TCP port+1 for relay fallback).

## Build

```bash
# alt-p2p first (sibling repo), then this:
cd ../alt-p2p && mvn -DskipTests install
cd ../alt-p2p-http && mvn package     # → target/alt-p2p-http-0.1.0-SNAPSHOT.jar

mvn test                              # unit tests
./scripts/loopback.sh                 # end-to-end on one machine (direct path)
RELAY=1 ./scripts/loopback.sh         # same through the TCP relay
```

## License

AGPL-3.0, like its siblings.
