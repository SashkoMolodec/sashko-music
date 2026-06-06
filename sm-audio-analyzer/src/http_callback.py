import json
import logging
import urllib.request
from typing import Optional

logger = logging.getLogger(__name__)


class AnalysisCallbackClient:

    def __init__(self, callback_url: str):
        self.callback_url = callback_url.rstrip('/')

    def send_complete(self, track_id: int, json_result_path: str, success: bool, error_message: Optional[str] = None):
        payload = {
            'trackId': track_id,
            'jsonResultPath': json_result_path,
            'success': success,
            'errorMessage': error_message
        }

        url = f"{self.callback_url}/internal/audio-analysis-complete"
        data = json.dumps(payload).encode('utf-8')

        request = urllib.request.Request(
            url,
            data=data,
            headers={'Content-Type': 'application/json'},
            method='POST'
        )

        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                logger.info(f"Callback sent for trackId={track_id}, success={success}, status={response.status}")
        except Exception as e:
            logger.error(f"Failed to send callback for trackId={track_id}: {e}", exc_info=True)
