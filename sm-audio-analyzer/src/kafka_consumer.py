import json
import logging
from kafka import KafkaConsumer
from typing import Callable
from config import Config

logger = logging.getLogger(__name__)


class AnalysisTaskConsumer:
    """Kafka consumer for track analysis tasks"""

    def __init__(self, config: Config, message_handler: Callable):
        self.config = config
        self.message_handler = message_handler

        self.consumer = KafkaConsumer(
            config.ANALYZE_TRACK_TOPIC,
            bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS,
            group_id=config.KAFKA_CONSUMER_GROUP,
            value_deserializer=lambda m: json.loads(m.decode('utf-8')),
            auto_offset_reset='earliest',
            enable_auto_commit=False,  # Manual commit for reliability
            session_timeout_ms=300000,  # 5 minutes
            heartbeat_interval_ms=60000,  # 1 minute
            max_poll_interval_ms=900000  # 15 minutes
        )

        logger.info(f"Kafka consumer initialized for topic: {config.ANALYZE_TRACK_TOPIC}")

    def start(self):
        """Start consuming messages"""
        logger.info("Starting to consume analysis tasks...")

        try:
            for message in self.consumer:
                task = message.value
                logger.info(f"Received task: trackId={task.get('trackId')}, path={task.get('localPath')}")

                try:
                    self.message_handler(task)
                    # Commit offset only after successful processing
                    self.consumer.commit()
                    logger.debug(f"Committed offset for trackId={task.get('trackId')}")
                except Exception as e:
                    logger.error(f"Failed to process task {task.get('trackId')}: {e}", exc_info=True)
                    # Don't commit on error - message will be reprocessed

        except KeyboardInterrupt:
            logger.info("Consumer interrupted by user")
        finally:
            self.consumer.close()
            logger.info("Consumer closed")
