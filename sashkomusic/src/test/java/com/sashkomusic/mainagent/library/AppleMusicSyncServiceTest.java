package com.sashkomusic.mainagent.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor;
import com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor.CapturedResult;
import com.sashkomusic.events.AppleMusicSyncCompleteEvent;
import com.sashkomusic.events.AppleMusicSyncFinishedEvent;
import com.sashkomusic.libraryagent.config.AppleMusicSyncConfig;
import com.sashkomusic.mainagent.library.AppleMusicSyncService.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppleMusicSyncServiceTest {

    private static final String DIR = "/lib/working/artist/album";
    private static final String OUTPUT = """
            {"release": "/lib/working/artist/album", "staged": 2, "added": 2, "tracks": [
              {"source": "/lib/working/artist/album/01.flac", "name": "01.flac", "result": {"dbid": 11}},
              {"source": "/lib/working/artist/album/02.flac", "name": "02.flac", "result": {"dbid": 12}}]}
            """;

    private final ProcessCommandExecutor commandExecutor = mock(ProcessCommandExecutor.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AppleMusicSyncConfig config = new AppleMusicSyncConfig();
    private final List<Runnable> queued = new ArrayList<>();
    private AppleMusicSyncService sut;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        config.setScriptPath("/app/tools/applemusic_sync.py");
        config.setHost("vm");
        config.setPort(8765);
        config.setTimeoutSeconds(600);
        sut = new AppleMusicSyncService(commandExecutor, publisher, new ObjectMapper(), config, queued::add);
    }

    private void runQueued() {
        queued.forEach(Runnable::run);
        queued.clear();
    }

    private List<Object> published() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void submit_returns_immediately_and_reports_when_done() {
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(0, OUTPUT, false));

        assertThat(sut.submit("42", DIR, "Artist — Album")).isEqualTo(Submission.STARTED);
        verify(commandExecutor, never()).runCapturing(anyString(), anyLong(), any(String[].class));

        runQueued();

        assertThat(published()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                AppleMusicSyncCompleteEvent.class, c -> {
                    assertThat(c.tracks()).extracting(AppleMusicSyncCompleteEvent.TrackDbid::filePath)
                            .containsExactly("/lib/working/artist/album/01.flac", "/lib/working/artist/album/02.flac");
                    assertThat(c.tracks()).extracting(AppleMusicSyncCompleteEvent.TrackDbid::dbid).containsExactly(11L, 12L);
                }));
        assertThat(published()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                AppleMusicSyncFinishedEvent.class, f -> {
                    assertThat(f.conversationId()).isEqualTo("42");
                    assertThat(f.label()).isEqualTo("Artist — Album");
                    assertThat(f.success()).isTrue();
                }));
    }

    @Test
    void same_release_cannot_be_queued_twice_until_the_first_run_finishes() {
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(0, OUTPUT, false));

        assertThat(sut.submit("42", DIR, "x")).isEqualTo(Submission.STARTED);
        assertThat(sut.submit("42", DIR, "x")).isEqualTo(Submission.ALREADY_RUNNING);
        assertThat(sut.submit("42", DIR + "-other", "y")).isEqualTo(Submission.STARTED);

        runQueued();

        assertThat(sut.submit("42", DIR, "x")).isEqualTo(Submission.STARTED);
    }

    @Test
    void timeout_is_reported_as_error_and_frees_the_release() {
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(-1, "", true));

        sut.submit("42", DIR, "Artist — Album");
        runQueued();

        assertThat(published()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                AppleMusicSyncFinishedEvent.class, f -> {
                    assertThat(f.success()).isFalse();
                    assertThat(f.error()).contains("таймаут");
                }));
        assertThat(sut.submit("42", DIR, "x")).isEqualTo(Submission.STARTED);
    }

    @Test
    void script_crash_without_json_is_an_error() {
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(1, "", false));

        sut.submit("42", DIR, "Artist — Album");
        runQueued();

        assertThat(published()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                AppleMusicSyncFinishedEvent.class, f -> assertThat(f.error()).contains("код 1")));
    }

    @Test
    void partial_add_is_not_a_success() {
        String partial = """
                {"staged": 3, "added": 2, "tracks": [
                  {"source": "/a/01.flac", "result": {"dbid": 1}}, {"source": "/a/02.flac", "result": {"dbid": 2}},
                  {"source": "/a/03.flac", "result": {"error": "unsupported"}}]}
                """;
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(0, partial, false));

        sut.submit("42", DIR, "x");
        runQueued();

        assertThat(published()).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(
                AppleMusicSyncFinishedEvent.class, f -> {
                    assertThat(f.error()).isNull();
                    assertThat(f.added()).isEqualTo(2);
                    assertThat(f.staged()).isEqualTo(3);
                    assertThat(f.success()).isFalse();
                }));
    }

    @Test
    void no_notification_without_a_conversation() {
        when(commandExecutor.runCapturing(anyString(), anyLong(), any(String[].class)))
                .thenReturn(new CapturedResult(0, OUTPUT, false));

        sut.submit(null, DIR, "x");
        runQueued();

        assertThat(published()).noneMatch(AppleMusicSyncFinishedEvent.class::isInstance);
        assertThat(published()).anyMatch(AppleMusicSyncCompleteEvent.class::isInstance);
    }

    @Test
    void disabled_and_full_queue_are_reported_without_running() {
        config.setEnabled(false);
        assertThat(sut.submit("42", DIR, "x")).isEqualTo(Submission.DISABLED);

        config.setEnabled(true);
        var full = new AppleMusicSyncService(commandExecutor, publisher, new ObjectMapper(), config,
                task -> { throw new RejectedExecutionException(); });
        assertThat(full.submit("42", DIR, "x")).isEqualTo(Submission.QUEUE_FULL);
        assertThat(full.submit("42", DIR, "x")).isEqualTo(Submission.QUEUE_FULL);
    }
}
