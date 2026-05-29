package com.sashkomusic.downloadagent.api.webhook;

import com.sashkomusic.downloadagent.api.webhook.dto.SlskdDownloadCompleteWebhook;
import com.sashkomusic.downloadagent.domain.model.DownloadBatch;
import com.sashkomusic.downloadagent.domain.DownloadContext;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadCompleteDto;
import com.sashkomusic.downloadagent.messaging.producer.DownloadBatchCompleteProducer;
import com.sashkomusic.downloadagent.messaging.producer.DownloadCompleteProducer;
import com.sashkomusic.downloadagent.messaging.producer.DownloadErrorProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/slskd")
@Slf4j
@RequiredArgsConstructor
public class SlskdWebhookController {

    private final DownloadCompleteProducer downloadCompleteProducer;
    private final DownloadBatchCompleteProducer batchCompleteProducer;
    private final DownloadErrorProducer errorProducer;
    private final DownloadContext downloadContext;

    @PostMapping("/download-complete")
    public ResponseEntity<Void> handleDownloadComplete(@RequestBody SlskdDownloadCompleteWebhook webhook) {
        log.info("Received download complete webhook: type={}, remoteFilename={}",
                webhook.type(), webhook.remoteFilename());
        log.info("Webhook details: size={} bytes, state={}, localFilename={}",
                webhook.transfer() != null ? webhook.transfer().size() : 0,
                webhook.transfer() != null ? webhook.transfer().state() : "null",
                webhook.localFilename());

        if (webhook.transfer() == null) {
            log.error("Webhook transfer is null for file: {}", webhook.remoteFilename());
            return ResponseEntity.ok().build();
        }

        try {
            DownloadBatch batch = downloadContext.markFileCompleted(webhook.remoteFilename(), webhook.localFilename());

            if (batch == null) {
                log.debug("No batch found for file: {} (possibly external download)", webhook.remoteFilename());
                return ResponseEntity.ok().build();
            }

            var fileDto = DownloadCompleteDto.of(batch.getChatId(), webhook.remoteFilename(), webhook.transfer().size());
            downloadCompleteProducer.sendComplete(fileDto);

            if (batch.isComplete()) {
                log.info("All files downloaded for release: {}", batch.getReleaseId());
                var batchDto = DownloadBatchCompleteDto.of(
                        batch.getChatId(),
                        batch.getReleaseId(),
                        batch.getLocalDirectoryPath(),
                        batch.getLocalFilenames()
                );
                batchCompleteProducer.sendBatchComplete(batchDto);
            }

        } catch (Exception e) {
            log.error("Failed to process download complete webhook for file={}: {}",
                    webhook.remoteFilename(), e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}