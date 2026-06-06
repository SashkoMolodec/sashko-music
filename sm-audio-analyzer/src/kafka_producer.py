import json
import logging
from kafka import KafkaProducer
from config import Config
from typing import Optional

logger = logging.getLogger(__name__)


class AnalysisCompleteProducer:
    """Kafka producer for sending analysis completion messages"""

    def __init__(self, config: Config):
        self.config = config

        self.producer = KafkaProducer(
            bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS,
            value_serializer=lambda v: json.dumps(v).encode('utf-8')
        )

        logger.info(f"Kafka producer initialized for topic: {config.ANALYSIS_COMPLETE_TOPIC}")

    def send_complete(self, track_id: int, json_result_path: str, success: bool, error_message: Optional[str] = None):
        """Send analysis complete message to Kafka"""

        message = {
            '@type': 'track_analysis_complete',
            'trackId': track_id,
            'jsonResultPath': json_result_path,
            'success': success,
            'errorMessage': error_message
        }

        # Spring Kafka потребує __TypeId__ header для десеріалізації
        headers = [
            ('__TypeId__', b'track_analysis_complete')
        ]

        self.producer.send(
            self.config.ANALYSIS_COMPLETE_TOPIC,
            value=message,
            headers=headers
        )
        self.producer.flush()

        logger.info(f"Sent completion message for trackId={track_id}, success={success}")

    def close(self):
        """Close producer connection"""
        self.producer.close()
