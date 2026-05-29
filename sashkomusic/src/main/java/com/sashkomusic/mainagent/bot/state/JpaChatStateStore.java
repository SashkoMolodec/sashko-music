package com.sashkomusic.mainagent.bot.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JpaChatStateStore implements ChatStateStore {

    private final ChatStateRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public <T> Optional<T> get(long chatId, String flowKey, Class<T> type) {
        return repository.findByChatIdAndFlowKey(chatId, flowKey)
                .map(entity -> deserialise(entity.getPayload(), type));
    }

    @Override
    @Transactional
    public void put(long chatId, String flowKey, Object payload) {
        String json = serialise(payload);
        ChatStateEntity entity = repository.findByChatIdAndFlowKey(chatId, flowKey)
                .orElseGet(() -> {
                    var fresh = new ChatStateEntity();
                    fresh.setChatId(chatId);
                    fresh.setFlowKey(flowKey);
                    return fresh;
                });
        entity.setPayload(json);
        repository.save(entity);
    }

    @Override
    @Transactional
    public void remove(long chatId, String flowKey) {
        repository.deleteByChatIdAndFlowKey(chatId, flowKey);
    }

    @Override
    @Transactional
    public int clearAll(String flowKey) {
        int removed = repository.deleteByFlowKey(flowKey);
        log.info("Cleared {} chat_state rows for flowKey={}", removed, flowKey);
        return removed;
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise chat state payload", e);
        }
    }

    private <T> T deserialise(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialise chat state payload", e);
        }
    }
}
