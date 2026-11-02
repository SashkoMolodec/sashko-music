package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent turn opening stacks and button presses paging them now run in parallel lanes of the same
 * chat. Every read-modify-write of the search state must therefore keep the other side's changes.
 */
class SearchContextServiceConcurrencyTest {

    private static final String CONVERSATION = "42";
    private static final int WRITERS = 40;

    private final SearchContextService sut = new SearchContextService(new InMemoryChatStateStore(), Map.of());

    @Test
    void stacks_opened_at_the_same_time_are_all_kept() throws Exception {
        runConcurrently(i -> sut.openStack(CONVERSATION, "q" + i, request(), "label " + i, List.of(release(i))));

        List<SearchStack> stacks = sut.getStacks(CONVERSATION);
        assertThat(stacks).hasSize(WRITERS);
        assertThat(stacks).extracting(SearchStack::id).doesNotHaveDuplicates();
    }

    @Test
    void paging_while_stacks_are_opened_loses_neither() throws Exception {
        String paged = sut.openStack(CONVERSATION, "seed", request(), "seed", List.of(release(-1), release(-2)));

        runConcurrently(i -> {
            if (i % 2 == 0) sut.updateStackPage(CONVERSATION, paged, 1);
            else sut.openStack(CONVERSATION, "q" + i, request(), "label " + i, List.of(release(i)));
        });

        assertThat(sut.getStacks(CONVERSATION)).hasSize(1 + WRITERS / 2);
        assertThat(sut.findStack(CONVERSATION, paged)).get().extracting(SearchStack::currentPage).isEqualTo(1);
    }

    private static void runConcurrently(java.util.function.IntConsumer writer) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> writes = IntStream.range(0, WRITERS)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        writer.accept(i);
                    }, pool))
                    .toList();
            start.countDown();
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
        }
    }

    private static MetadataSearchRequest request() {
        return new MetadataSearchRequest(null, "Burial", "Untrue", "", DateRange.empty(), "", "", "", "", "", "", "");
    }

    private static ReleaseMetadata release(int i) {
        return new ReleaseMetadata("discogs:release:" + i, null, SearchEngine.DISCOGS, "Burial", "Untrue " + i, 100,
                List.of("2007"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), null);
    }
}
