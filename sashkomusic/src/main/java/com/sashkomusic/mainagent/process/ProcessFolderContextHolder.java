package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessFolderContextHolder {

    private static final String FLOW_KEY = "proc_sel";

    private final ChatStateStore stateStore;

    public record ProcessFolderState(
            String directoryPath,
            List<String> audioFiles,
            List<String> releaseIds,
            String tracksReleaseId  // set between step 1 (tracks pick) and step 2 (tags pick); null otherwise
    ) {}

    public void save(String conversationId, String directoryPath, List<String> audioFiles, List<String> releaseIds) {
        stateStore.put(conversationId, FLOW_KEY, new ProcessFolderState(directoryPath, audioFiles, releaseIds, null));
    }

    public Optional<ProcessFolderState> get(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, ProcessFolderState.class);
    }

    /** Records the step-1 (tracks) pick so step 2 (tags pick) can look it up after the round-trip to Telegram. */
    public void saveTracksPick(String conversationId, String releaseId) {
        get(conversationId).ifPresent(s -> stateStore.put(conversationId, FLOW_KEY,
                new ProcessFolderState(s.directoryPath(), s.audioFiles(), s.releaseIds(), releaseId)));
    }

    public String getReleaseIdByOption(String conversationId, int index) {
        return get(conversationId)
                .filter(s -> index >= 0 && index < s.releaseIds().size())
                .map(s -> s.releaseIds().get(index))
                .orElse(null);
    }

    public boolean hasActiveContext(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, ProcessFolderState.class).isPresent();
    }

    public void clear(String conversationId) {
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public void clearAll() {
        int count = stateStore.clearAll(FLOW_KEY);
        log.info("Cleared all process contexts: {}", count);
    }
}
