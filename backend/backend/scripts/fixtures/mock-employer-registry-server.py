from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from urllib.parse import urlparse


PREFIXED_PROVIDERS = {"ashby", "smartrecruiters"}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        segments = path.split("/")
        # Greenhouse and Lever boards are served at the root; newer providers under /<provider>/<board>.
        provider = segments[1] if len(segments) > 2 and segments[1] in PREFIXED_PROVIDERS else None
        board = segments[2] if provider else (segments[1] if len(segments) > 1 else "")
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
        if provider == "ashby":
            payload = {"apiVersion": "1", "jobs": [] if board == "empty" else [
                {"id": "one", "isListed": True, "jobUrl": "https://jobs.test/one"},
                {"id": "hidden", "isListed": False, "jobUrl": "https://jobs.test/hidden"}]}
        elif provider == "smartrecruiters":
            content = [] if board == "empty" else [{"id": "744000000000001", "name": "Engineer"}]
            payload = {"offset": 0, "limit": 100, "totalFound": len(content), "content": content}
        elif board == "empty":
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
