package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.SearchStack;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Turns the stacks a search tool opened into the facts MainAgent writes its intro from. */
final class StackReport {

    private static final int MAX_STACKS_NAMED_IN_INTRO = 3;
    /** Groups need room: each one has to say how it connects, which 600 characters cannot do for three. */
    private static final int MAX_GROUPED_INTRO_CHARS = 900;
    private static final String UNGROUPED = "other picks in this answer";
    /** Left to fill the gaps itself, the model reaches for memory, and its memory of small scenes is wrong. */
    private static final String ONLY_THESE_FACTS = "Use only the facts above: every artist, label, year, place, "
            + "genre or scene you mention must appear in these lines, and do not describe an artist beyond them, "
            + "however well you know it. This overrides your usual rules about length, adding your own context "
            + "and naming every stack.";

    private StackReport() {}

    /**
     * What MainAgent reads to write its intro. One stack collapses to the old aggregate line; several
     * are listed separately, because with three stacks on screen the intro has to address all three.
     * Grouped stacks are listed by group, since the groups are the reasoning the intro has to explain.
     */
    static String forMainAgent(List<SearchStack> stacks) {
        if (stacks.size() == 1) {
            return describe(stacks.getFirst().releases());
        }
        if (stacks.stream().anyMatch(StackReport::hasGroup)) {
            return formatGrouped(stacks);
        }
        String perStack = stacks.stream()
                .map(StackReport::stackLine)
                .collect(Collectors.joining("\n"));
        // The intro is capped at 600 characters, which ten stacks cannot each get a mention in.
        String instruction = stacks.size() > MAX_STACKS_NAMED_IN_INTRO
                ? "Say in 2-4 sentences what connects them, without naming each one; do not list releases."
                : "Introduce all of them in 2-4 sentences; do not list releases.";
        return "Showed %d separate card stacks:\n%s\n%s %s".formatted(stacks.size(), perStack, instruction, ONLY_THESE_FACTS);
    }

    private static String formatGrouped(List<SearchStack> stacks) {
        Map<String, List<SearchStack>> groups = stacks.stream().collect(Collectors.groupingBy(
                stack -> hasGroup(stack) ? stack.group() : UNGROUPED, LinkedHashMap::new, Collectors.toList()));
        String perGroup = groups.entrySet().stream()
                .map(group -> "[%s]\n%s".formatted(group.getKey(), group.getValue().stream()
                        .map(StackReport::stackLine)
                        .collect(Collectors.joining("\n"))))
                .collect(Collectors.joining("\n"));
        String instruction = ("Write one plain paragraph of at most %d characters, with no markdown, headings or "
                + "lists, that goes through these groups in the order given: say how each group is connected to the "
                + "seed and name a couple of its artists as examples. "
                + "Be plain that co-listened artists have no known real connection. Do not list releases.")
                .formatted(MAX_GROUPED_INTRO_CHARS);
        return "Showed %d separate card stacks, grouped by why they were picked:\n%s\n%s %s"
                .formatted(stacks.size(), perGroup, instruction, ONLY_THESE_FACTS);
    }

    private static boolean hasGroup(SearchStack stack) {
        return stack.group() != null && !stack.group().isBlank();
    }

    private static String stackLine(SearchStack stack) {
        return "• %s — %s".formatted(
                stack.label() == null || stack.label().isBlank() ? "стос " + stack.id() : stack.label(),
                describe(stack.releases()));
    }

    static String describe(List<ReleaseMetadata> releases) {
        String sources = releases.stream()
                .map(ReleaseMetadata::source)
                .filter(Objects::nonNull)
                .map(SearchEngine::getName)
                .distinct()
                .collect(Collectors.joining(", "));

        var allYears = releases.stream()
                .filter(r -> r.years() != null)
                .flatMap(r -> r.years().stream())
                .filter(y -> y != null && y.matches("\\d{4}"))
                .map(Integer::parseInt)
                .sorted()
                .toList();
        String yearsStr = allYears.isEmpty() ? "" :
                allYears.size() == 1 ? allYears.getFirst().toString() :
                        allYears.getFirst() + "–" + allYears.getLast();

        var typeCounts = releases.stream()
                .filter(r -> r.types() != null)
                .flatMap(r -> r.types().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.toLowerCase().trim(), Collectors.counting()));
        String typesStr = typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));

        String labelsStr = releases.stream()
                .map(ReleaseMetadata::label)
                .filter(l -> l != null && !l.isBlank())
                .collect(Collectors.groupingBy(l -> l, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        String tagsStr = releases.stream()
                .filter(r -> r.tags() != null)
                .flatMap(r -> r.tags().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.toLowerCase().trim(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        var sb = new StringBuilder();
        sb.append(releases.size()).append(" releases");
        if (!sources.isEmpty()) sb.append(" (").append(sources).append(")");
        sb.append(".");
        if (!yearsStr.isEmpty()) sb.append(" Years: ").append(yearsStr).append(".");
        if (!typesStr.isEmpty()) sb.append(" Types: ").append(typesStr).append(".");
        if (!labelsStr.isEmpty()) sb.append(" Labels: ").append(labelsStr).append(".");
        if (!tagsStr.isEmpty()) sb.append(" Tags: ").append(tagsStr).append(".");
        return sb.toString();
    }
}
