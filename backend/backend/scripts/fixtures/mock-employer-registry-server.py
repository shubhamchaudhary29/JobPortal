from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from urllib.parse import urlparse


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        board = path.split("/")[1] if len(path.split("/")) > 1 else ""
        if board == "invalid":
            self.send_response(404)
            self.end_headers()
            return
        if board == "unreachable":
            self.send_response(503)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if board == "malformed":
            self.wfile.write(b"{not-json")
            return
        if board == "empty":
            payload = {"jobs": []} if "/jobs" in path else []
        elif "/jobs" in path:
            payload = {"jobs": [{"id": 1, "absolute_url": "https://jobs.test/one"}]}
        else:
            payload = [{"id": "one", "hostedUrl": "https://jobs.test/one"}]
        self.wfile.write(json.dumps(payload).encode())

    def log_message(self, *_):
        pass


server = HTTPServer(("127.0.0.1", 0), Handler)
print(server.server_port, flush=True)
server.serve_forever()
