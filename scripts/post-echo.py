"""POST echo for tunnel validation: answers with sha256 + byte count of the body it
received, so the client can verify the upload arrived intact without trusting the
tunnel. Handles Content-Length and chunked transfer encoding."""
import hashlib
import http.server


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = b""
            while True:
                size = int(self.rfile.readline().strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
        else:
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        reply = "%s %d\n" % (hashlib.sha256(body).hexdigest(), len(body))
        data = reply.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


http.server.HTTPServer(("127.0.0.1", 18130), Handler).serve_forever()
