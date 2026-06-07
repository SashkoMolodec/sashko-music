package com.sashkomusic.libraryagent.domain.smartlist;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface SmartlistDslExtractor {

    @SystemMessage("""
            You build a smartlist DSL from a natural-language description.

            Output ONLY a valid JSON object — no markdown, no code fences, no commentary.
            Format:
            {
              "conditions": [
                {"op": "contains", "field": "<year|comment|label|genre|sublibrary>", "value": "<string>"},
                {"op": "range",    "field": "<year|rating>", "min": <int>, "max": <int>},
                {"op": "is",       "field": "<year|comment|label|genre|rating|sublibrary>", "value": "<string>" | null},
                {"op": "gt"|"gte"|"lt"|"lte", "field": "<year|rating>", "value": <int>}
              ]
            }

            Allowed fields and ops:
            - comment, label, genre — "contains" (substring, case-insensitive) or "is" (exact match / null absence).
            - year — "contains" / "is" (string match) OR numeric: "range" with min/max as 4-digit integer years
              (e.g. min=1970, max=1989), or "gt"/"gte"/"lt"/"lte" with a single integer year.
            - rating — "range" with min/max in [1..5] stars, "gt"/"gte"/"lt"/"lte" with single 1..5 value,
              "is" with single 1..5 value, or "is null" for unrated tracks.
            - sublibrary — "is" or "contains". Values are "working" or "vault".
              Use when user says "тільки working", "з vault", "не vault" (use "is" for exact match).
            - "is" with value=null means the tag is absent on the track.
            - NEVER emit "range"/"gt"/"lt"/"gte"/"lte" on comment / label / genre / sublibrary — text fields only.
            - Prefer gt/lt/gte/lte over range when the user expresses a one-sided bound
              ("більше 1990", "менше ніж 4 зірки", "from 2000 onward", "≥ 4", "<2020").
              Use range only for closed two-sided intervals ("between 1980 and 1989", "70s and 80s").

            OR vs AND semantics:
            - Multiple conditions on DIFFERENT fields → AND (track must match ALL).
            - Multiple conditions on the SAME field → OR (track matches any of them).
              Example: genre contains "latina" + genre contains "bolero" → tracks whose genre has "latina" OR "bolero".
            - Emit separate condition objects for each value — the evaluator handles OR grouping automatically.

            Rules:
            - Omit fields the user did not mention. Do not invent.
            - If the user gives a single rating (e.g. "rating 5"), set min=max.
            - If user says "rating ≥ 4" set min=4, max=5. If "rating ≤ 3" set min=1, max=3.
            - "year contains 2024" or "released in 2024" → {"op":"contains","field":"year","value":"2024"}.
            - The PREVIOUS DSL (if given) is the current state of the smartlist being edited.
              Treat the user message as a delta — keep existing conditions unless the user explicitly removes or replaces them.
              If the user message is the initial request (previousDsl is "none"), build the DSL from scratch.

            Examples:
            User: "house tracks from 2024 with rating 4 and above"
            Output: {"conditions":[{"op":"contains","field":"genre","value":"house"},{"op":"contains","field":"year","value":"2024"},{"op":"gte","field":"rating","value":4}]}

            User: "latina або bolero, по роках менше 1990"
            Output: {"conditions":[{"op":"contains","field":"genre","value":"latina"},{"op":"contains","field":"genre","value":"bolero"},{"op":"lt","field":"year","value":1990}]}
            (Two genre conditions → OR: tracks whose genre contains "latina" OR "bolero", AND year < 1990.)

            User: "techno або house, тільки working"
            Output: {"conditions":[{"op":"contains","field":"genre","value":"techno"},{"op":"contains","field":"genre","value":"house"},{"op":"is","field":"sublibrary","value":"working"}]}

            User: "70s і 80s"
            Output: {"conditions":[{"op":"range","field":"year","min":1970,"max":1989}]}

            User: "rating 4 або вище"
            Output: {"conditions":[{"op":"gte","field":"rating","value":4}]}

            User: "релізи з 2010 і пізніше"
            Output: {"conditions":[{"op":"gte","field":"year","value":2010}]}

            User: "треки без рейтингу"
            Output: {"conditions":[{"op":"is","field":"rating","value":null}]}

            User: "year is exactly 2020"
            Output: {"conditions":[{"op":"is","field":"year","value":"2020"}]}

            User: "комент порожній і genre techno"
            Output: {"conditions":[{"op":"is","field":"comment","value":null},{"op":"contains","field":"genre","value":"techno"}]}

            User: "only vault releases"
            Output: {"conditions":[{"op":"is","field":"sublibrary","value":"vault"}]}

            User: "also only on Warp label"
            (with previousDsl = {"conditions":[{"op":"contains","field":"genre","value":"house"}]})
            Output: {"conditions":[{"op":"contains","field":"genre","value":"house"},{"op":"contains","field":"label","value":"Warp"}]}

            User: "remove the rating filter"
            (with previousDsl that contains a rating range)
            Output: previous DSL minus the rating condition.
            """)
    @UserMessage("""
            previousDsl: {{previous}}

            request: {{request}}
            """)
    String extractJson(@V("request") String request, @V("previous") String previousDslJson);
}
