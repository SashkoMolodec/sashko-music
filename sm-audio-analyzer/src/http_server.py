import logging
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import threading
from typing import Callable

logger = logging.getLogger(__name__)


class AnalysisRequestHandler(BaseHTTPRequestHandler):

    task_handler: Callable = None

    def do_POST(self):
        if self.path != '/analyze':
            self.send_response(404)
            self.end_headers()
            return

        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)

        try:
            task = json.loads(body.decode('utf-8'))
            logger.info(f"Received analysis request for trackId={task.get('trackId')}")

            threading.Thread(target=self.task_handler, args=(task,), daemon=True).start()

            self.send_response(202)
            self.end_headers()
        except Exception as e:
            logger.error(f"Failed to handle request: {e}", exc_info=True)
            self.send_response(500)
            self.end_headers()

    def log_message(self, format, *args):
        logger.debug(format % args)


class AnalysisHttpServer:

    def __init__(self, port: int, task_handler: Callable):
        AnalysisRequestHandler.task_handler = task_handler
        self.server = HTTPServer(('0.0.0.0', port), AnalysisRequestHandler)
        self.port = port

    def start(self):
        logger.info(f"HTTP server listening on port {self.port}")
        self.server.serve_forever()

    def stop(self):
        self.server.shutdown()
