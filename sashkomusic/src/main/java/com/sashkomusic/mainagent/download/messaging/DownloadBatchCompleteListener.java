package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadBatchCompleteEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadBatchCompleteListener {

    private final ProcessFolderFlowService processFolderFlowService;
    private final TelegramChatBot telegramBot;

    @EventListener
    @Async
    public void handleBatchComplete(DownloadBatchCompleteEvent event) {
        DownloadBatchCompleteDto dto = event.payload();
        log.info("Received download batch complete for conversationId={}, releaseId={}, files={}",
                dto.conversationId(), dto.releaseId(), dto.totalFiles());

        ConversationContext ctx = ConversationContext.from(dto.conversationId());

        telegramBot.sendMessage(ctx, buildFileListMessage(dto.allFiles(), dto.directoryPath()));

        processFolderFlowService.process(ctx, dto.directoryPath())
                .forEach(msg -> telegramBot.sendResponse(ctx, msg));
    }

    private static String buildFileListMessage(List<String> allFiles, String directoryPath) {
        var sb = new StringBuilder();
        sb.append("✅ завантажено ").append(allFiles.size()).append(" файл")
          .append(fileSuffix(allFiles.size()));
        if (directoryPath != null && !directoryPath.isBlank()) {
            sb.append("\n📁 `").append(directoryPath).append("`");
        }
        sb.append(":\n\n");

        allFiles.stream()
                .map(f -> Path.of(f).getFileName().toString())
                .sorted()
                .forEach(name -> sb.append("   📄 `").append(name).append("`\n"));

        return sb.toString().stripTrailing();
    }

    private static String fileSuffix(int count) {
        if (count % 10 == 1 && count % 100 != 11) return "";
        if (count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 10 || count % 100 >= 20)) return "и";
        return "ів";
    }
}
