#!/usr/bin/env python3
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from config import Config
from analysis_core.analyzer import EssentiaAnalyzer
from analysis_core.json_writer import JsonWriter
from http_server import AnalysisHttpServer
from http_callback import AnalysisCallbackClient

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)

logger = logging.getLogger(__name__)


class AnalysisService:

    def __init__(self):
        self.config = Config()
        self.analyzer = EssentiaAnalyzer()
        self.json_writer = JsonWriter(self.config.ANALYSIS_RESULTS_PATH)
        self.callback_client = AnalysisCallbackClient(self.config.CALLBACK_URL)
        self.http_server = AnalysisHttpServer(self.config.HTTP_PORT, self.handle_task)

        logger.info("Audio Analysis Service initialized")
        logger.info(f"Results path: {self.config.ANALYSIS_RESULTS_PATH}")
        logger.info(f"Callback URL: {self.config.CALLBACK_URL}")
        logger.info(f"HTTP port: {self.config.HTTP_PORT}")

    def handle_task(self, task: dict):
        track_id = task.get('trackId')
        local_path = task.get('localPath')

        logger.info(f"Processing trackId={track_id}, path={local_path}")

        audio_path = Path(local_path)
        if not audio_path.exists():
            error_msg = f"File not found: {local_path}"
            logger.error(error_msg)
            json_path = self.json_writer.write_result(track_id, {}, success=False, error_message=error_msg)
            self.callback_client.send_complete(track_id, json_path, False, error_msg)
            return

        try:
            features = self.analyzer.analyze_track(str(audio_path))
            json_path = self.json_writer.write_result(track_id, features, success=True)
            self.callback_client.send_complete(track_id, json_path, True)
            logger.info(f"Successfully analyzed trackId={track_id}")

        except Exception as e:
            error_msg = f"Analysis failed: {str(e)}"
            logger.error(f"Error analyzing trackId={track_id}: {error_msg}", exc_info=True)
            json_path = self.json_writer.write_result(track_id, {}, success=False, error_message=error_msg)
            self.callback_client.send_complete(track_id, json_path, False, error_msg)

    def run(self):
        logger.info("Starting Audio Analysis Service...")
        try:
            self.http_server.start()
        except KeyboardInterrupt:
            logger.info("Service interrupted by user")
        except Exception as e:
            logger.error(f"Service error: {e}", exc_info=True)
        finally:
            self.http_server.stop()
            logger.info("Service stopped")


if __name__ == "__main__":
    service = AnalysisService()
    service.run()
