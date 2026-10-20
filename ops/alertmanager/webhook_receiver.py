"""本地 webhook 接收器：把 Alertmanager 发来的通知打到控制台，并追加到文件。

启动：python ops/alertmanager/webhook_receiver.py
它监听 0.0.0.0:9099，Alertmanager 通过 host.docker.internal:9099 访问它。
"""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


LOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "received-alerts.log")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", errors="replace")

        # 原样存一份，方便事后对照；同时打一份精简版到控制台
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(body + "\n")

        try:
            payload = json.loads(body)
            status = payload.get("status", "?")
            for alert in payload.get("alerts", []):
                print(
                    f"[{status}] {alert.get('labels', {}).get('alertname', '?')} "
                    f"severity={alert.get('labels', {}).get('severity', '?')} "
                    f"summary={alert.get('annotations', {}).get('summary', '')}",
                    flush=True,
                )
        except json.JSONDecodeError:
            print(body, flush=True)

        self.send_response(200)
        self.end_headers()


if __name__ == "__main__":
    print(f"webhook receiver listening on 0.0.0.0:9099, log -> {LOG_PATH}", flush=True)
    HTTPServer(("0.0.0.0", 9099), Handler).serve_forever()
