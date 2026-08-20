# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## Project Overview

**alt-p2p-http** tunnels a localhost HTTP port between two peers over
[alt-p2p](https://github.com/sync-different/alt-p2p). `serve` exposes a local HTTP
server; `connect` makes it appear on a local port at the other end. Deliberately
peer-to-peer (session + PSK shared by both ends) — not an ngrok clone: no public URL,
no anonymous access.

**There is no HTTP-specific code here, by design.** The tunnel is alt-p2p's generic
`net/tunnel/` layer (`StreamMux`, `ForwardListener`, `ForwardConnector`, `Tunnels.carrier`),
which moves raw TCP — the same layer that carries alt-p2p-lore's gRPC (which *is* HTTP/2).
HTTP/1.1, h2c, WebSockets, and SSE ride it unmodified. If something HTTP-aware is ever
needed (Host rewriting, per-request logging), it lives here; generic tunnel improvements
go into alt-p2p. **Hard rule inherited from alt-p2p-lore: nothing protocol-specific goes
into alt-p2p.**

## Relationship to alt-p2p

Depends on `com.alterante:alt-p2p` (sibling repo `../alt-p2p`), consumed from the local
Maven repo and **shaded into the fat jar** — this is alt-p2p's third shading consumer
(with alt-p2p-lore and alt-p2p-bbs), so an alt-p2p version bump means updating
`<alt-p2p.version>` here too and rebuilding.

**Rebuild order:** changes in ../alt-p2p need `mvn -DskipTests install` there **before**
building here, or this project links a stale jar.

## Build & Test

```bash
mvn clean test                # 20 tests incl. in-JVM tunnel integration (use `clean` — stale IDE classes have
                              # produced false green runs in sibling repos)
mvn -DskipTests package       # → target/alt-p2p-http-0.1.0-SNAPSHOT.jar (fat jar)

./scripts/loopback.sh         # e2e: coord + python http.server + serve + connect + curl
RELAY=1 ./scripts/loopback.sh # same through the TCP relay carrier
```

JDK 17+, Maven 3.9+. The shade plugin excludes BouncyCastle signature files
(`META-INF/*.SF/*.DSA/*.RSA`) — same gotcha as every sibling. Version is stamped into
the manifest (`Implementation-Version` + `X-Alt-P2P-Version`); never hardcode it in code.

## Architecture

```
CLIENT (B)                                             HOST (A)
 browser ─TCP─▶ ForwardListener ─┐          ┌─▶ ForwardConnector ─▶ HTTP server (127.0.0.1:<port>)
 (http://127.0.0.1:8080)         │  StreamMux (per-connection streams)
                                 ▼          ▲
                         BytePipe over PeerConnection (direct UDP | TCP relay)
                                 └─ alt-p2p: coord → hole punch → DTLS ─┘
```

- `Main` — picocli entry; subcommands `serve`, `connect`.
- `ConnectionOptions` (@Mixin) — same flags as alt-p2p-lore (`--server`, `-s`, `--psk`,
  `--allow-relay`, `--relay-mode`, `--force-relay`, `--keepalive-ms`).
- `ServeCommand` — pre-flight probe (refuses to start if nothing listens on `--port`),
  then the lore host loop: wait forever (`--peer-wait 0` default), serve one client,
  recycle the session for the next (`--once` to exit after one).
- `ConnectCommand` — `--local-port` (default 8080), holds the tunnel until the peer
  drops, exits **non-zero** so a supervisor can restart it.

## Critical implementation notes

- **The relay-vs-direct block distinction is load-bearing** (both commands):
  `pc.isTcpRelay() ? mux.awaitClosed() : pc.awaitDisconnect()`. The relay path has no
  router, so `awaitDisconnect()` returns instantly there; the direct path's mux does not
  EOF on peer death, only the router notices. Copied from alt-p2p-lore; do not "simplify".
- **serve probes the target port once, at startup only.** If the HTTP server dies later,
  per-stream connects fail and individual streams close, but the tunnel stays up and the
  server coming back needs nothing restarted. Refusing only at startup catches the #1
  user error (wrong port) without turning the tunnel into a health checker.
- **One client at a time, successive clients via coord session recycling** (the lore
  model, deliberate — see internal/spec.md Q2). A second simultaneous `connect` on the
  same session will not pair. Validated: a second client re-pairs ~1s after the first
  dies (keepalive-death = 3× `--keepalive-ms`, default 9s, sets that latency).
- **Testing on this Mac:** the local security stack (Little Snitch etc.) silently drops
  inbound/WAN UDP to bare `java` — a coordinator on the Mac never hears LAN peers, and
  WAN coordinators never hear the Mac's CLI. Loopback (127.0.0.1) works; for cross-machine
  tests run the coordinator on the Linux box (see the fedora1 pattern in internal/).
- **`pkill -f` in ssh'd test scripts self-matches** the remote shell's command line when
  the pattern appears in it (killing the session, exit 255). Use `[b]racket` patterns or
  run a script file.

## Validation record (0.1.0, all 2026-08-19)

- `mvn clean test`: 20/20 — incl. LoopbackTunnelTest (in-JVM coord + real serve/connect commands, GET+POST round-trip, exit-code contracts) and ServeLoopTest (retry-with-pause policy).
- `scripts/loopback.sh`: 10/10 checks, direct AND `RELAY=1` (text, nested path, 2 MB binary
  byte-compare, 404 pass-through, index page, 8 concurrent fetches, keep-alive reuse, POST
  2 MB sha256-verified, POST chunked, POST multipart — the POST checks run on a second
  simultaneous session against scripts/post-echo.py).
- Session recycling: two successive clients on one session, second paired in ~1s.
- Cross-machine (Mac ↔ fedora1 over LAN, real hole punch, direct link in 1.1s): text +
  2 MB blob SHA-256-identical + 6 concurrent fetches identical.
- **Browser pass** (human, Mac browser → fedora1 python http.server through the tunnel):
  the alt-lore marketing pages with a 545 KB image + lightbox JS — "works fine".
- **HTTPS pass-through**: a TLS-wrapped http.server on the far end fetched through the
  tunnel; `openssl s_client` through the tunnel shows the FAR server's own cert
  (`CN=fedora1-m5-test`) — TLS terminates at the peer, the tunnel never touches it.
  The browser cert warning for a self-signed/mismatched cert is expected and correct.
- **WebSocket**: RFC6455 handshake (101) + masked-frame echo round-trip through the
  tunnel, stdlib-only client/server (kept in internal/ scratch, not shipped).
- **Progressive streaming (the LLM-endpoint use case)**: a chunked SSE stream emitting
  10 chunks at 300ms intervals arrived through the tunnel at ~300ms intervals — the
  tunnel does not buffer a response into a lump, so remote Ollama/llama.cpp token
  streaming feels live. (`curl -N` with per-line arrival timestamps, 2.74s spread.)
- **Two sessions at once** (m5https + m5ws): one host ran two `serve` processes on two
  sessions simultaneously — the one-client-at-a-time limit is per session, not per host.
- **First production use** (same day): alt-core's web UI (localhost:8081, the uiv5
  AngularJS app with its parallel API calls) served from the Mac through the LIVE cloud
  coordinator (107.174.42.68:9000), browsed from fedora2. Both peers behind one NAT: the
  coordinator's LAN-endpoint exchange kicked in and the punch went direct over the LAN
  (`transport=192.168.1.102`) — no hairpin, no relay. "Worked perfectly" (Alejandro).
  Note the earlier "this Mac drops WAN UDP from bare java" caveat did NOT reproduce —
  outbound coordination to the cloud worked; the block observed today was inbound (a
  coordinator ON the Mac never heard LAN peers).

## Status / not yet done

- 0.1.0 feature-complete and validated; not yet released (version still -SNAPSHOT,
  no tag, no git remote). Plan and spec in `internal/` (gitignored).
