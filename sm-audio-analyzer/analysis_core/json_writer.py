import json
import logging
from pathlib import Path
from typing import Dict

logger = logging.getLogger(__name__)


class JsonWriter:
    """Writes analysis results to JSON files"""

    def __init__(self, results_dir: str):
        self.results_dir = Path(results_dir)
        self.results_dir.mkdir(parents=True, exist_ok=True)

    def write_result(self, track_id: int, features: Dict, success: bool = True, error_message: str = None) -> str:
        """
        Write analysis result to JSON file.

        Returns the path to the written JSON file.
        """
        filename = f"{track_id}_analysis.json"
        file_path = self.results_dir / filename

        result = {
            "trackId": track_id,
            "success": success,
            "errorMessage": error_message,
            "features": features if success else None
        }

        try:
            with open(file_path, 'w') as f:
                json.dump(result, f, indent=2)

            logger.info(f"Wrote analysis result to: {file_path}")
            return str(file_path)

        except Exception as e:
            logger.error(f"Failed to write JSON file {file_path}: {e}")
            raise
