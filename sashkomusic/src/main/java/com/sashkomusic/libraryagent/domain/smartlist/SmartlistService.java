package com.sashkomusic.libraryagent.domain.smartlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.libraryagent.domain.entity.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartlistService {

    private final SmartlistRepository repository;
    private final SmartlistEvaluator evaluator;
    private final SmartlistM3uWriter m3uWriter;
    private final ObjectMapper objectMapper;

    public record SmartlistSummary(Long id, String name, int trackCount, String dslDescription) {}

    @Transactional
    public SmartlistSummary create(String name, SmartlistDsl dsl) {
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("smartlist '" + name + "' вже існує");
        }
        Smartlist sl = new Smartlist();
        sl.setName(name);
        sl.setDsl(serialise(dsl));
        repository.save(sl);
        List<Track> tracks = evaluator.evaluate(dsl);
        m3uWriter.write(name, tracks);
        sl.setLastGeneratedAt(Instant.now());
        return new SmartlistSummary(sl.getId(), name, tracks.size(), evaluator.describe(dsl));
    }

    @Transactional
    public SmartlistSummary rename(String oldName, String newName) {
        Smartlist sl = repository.findByName(oldName)
                .orElseThrow(() -> new IllegalArgumentException("smartlist '" + oldName + "' не знайдено"));
        if (!oldName.equals(newName) && repository.existsByName(newName)) {
            throw new IllegalArgumentException("smartlist '" + newName + "' вже існує");
        }
        sl.setName(newName);
        repository.save(sl);
        m3uWriter.rename(oldName, newName);
        SmartlistDsl dsl = parse(sl.getDsl());
        int count = evaluator.evaluate(dsl).size();
        return new SmartlistSummary(sl.getId(), newName, count, evaluator.describe(dsl));
    }

    @Transactional
    public boolean delete(String name) {
        Optional<Smartlist> opt = repository.findByName(name);
        if (opt.isEmpty()) return false;
        repository.delete(opt.get());
        m3uWriter.delete(name);
        return true;
    }

    @Transactional(readOnly = true)
    public List<SmartlistSummary> list() {
        return repository.findAll().stream()
                .map(sl -> {
                    SmartlistDsl dsl = parse(sl.getDsl());
                    int count = evaluator.evaluate(dsl).size();
                    return new SmartlistSummary(sl.getId(), sl.getName(), count, evaluator.describe(dsl));
                })
                .toList();
    }

    @Transactional
    public void regenerateAll() {
        for (Smartlist sl : repository.findAll()) {
            try {
                SmartlistDsl dsl = parse(sl.getDsl());
                List<Track> tracks = evaluator.evaluate(dsl);
                m3uWriter.write(sl.getName(), tracks);
                sl.setLastGeneratedAt(Instant.now());
            } catch (Exception e) {
                log.warn("Failed to regenerate smartlist '{}': {}", sl.getName(), e.getMessage());
            }
        }
    }

    public List<Track> previewTracks(SmartlistDsl dsl, int limit) {
        return evaluator.evaluate(dsl, limit);
    }

    public String describe(SmartlistDsl dsl) {
        return evaluator.describe(dsl);
    }

    public String serialise(SmartlistDsl dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise smartlist DSL", e);
        }
    }

    public SmartlistDsl parse(String json) {
        String stripped = stripJsonFences(json);
        try {
            return objectMapper.readValue(stripped, SmartlistDsl.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse smartlist DSL: " + stripped, e);
        }
    }

    /**
     * Strip Markdown code fences (```json ... ```) that LLMs sometimes wrap around
     * JSON despite a "no fences" prompt. Idempotent for already-clean JSON.
     */
    private String stripJsonFences(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(nl + 1);
            } else {
                t = t.substring(3);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        return t;
    }
}
