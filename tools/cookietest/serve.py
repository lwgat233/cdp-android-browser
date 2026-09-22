import http.server, socketserver
BODY = b"<html><body><h1>cookie test</h1><script>document.cookie='jscookie=fromjs'</script></body></html>"
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Set-Cookie", "hocookie=ho_val; Path=/; HttpOnly; Max-Age=86400; SameSite=Lax")
        self.send_header("Set-Cookie", "sec_cookie=sec_val; Path=/; Secure; Max-Age=86400")
        self.send_header("Set-Cookie", "plain=plain_val; Path=/; Max-Age=3600")
        self.send_header("Set-Cookie", "session_only=sess_val; Path=/")
        self.send_header("Set-Cookie", "plus_attr=pv; Path=/; Max-Age=86400; HttpOnly; Secure; SameSite=None")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", 8899), H) as s:
    s.serve_forever()
