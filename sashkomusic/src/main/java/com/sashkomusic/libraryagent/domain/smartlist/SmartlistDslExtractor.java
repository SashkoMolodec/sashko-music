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
                {"op": "contains", "field": "<year|comment|label|genre>", "value": "<string>"},
                {"op": "range",    "field": "rating", "min": <1..5>, "max": <1..5>},
                {"op": "is",       "field": "<year|comment|label|genre|rating>", "value": "<string>" | null}
              ]
            }

            Allowed fields and ops:
            - comment, label, genre — "contains" (substring, case-insensitive) or "is" (exact match / null absence).
            - year — "contains" / "is" (string match) OR "range" with min/max as 4-digit integer years (e.g. min=1970, max=1989).
            - rating — "range" with min/max in [1..5] stars, or "is" with a single 1..5 value, or "is null" for unrated tracks.
            - "is" with value=null means the tag is absent on the track (e.g. {"op":"is","field":"rating","value":null} matches tracks WITHOUT a rating).
            - NEVER emit "range" on comment / label / genre — those are text fields. Use "contains" instead.

            Rules:
            - Conditions are joined with AND only.
            - Omit fields the user did not mention. Do not invent.
            - If the user gives a single rating (e.g. "rating 5"), set min=max.
            - If user says "rating ≥ 4" set min=4, max=5. If "rating ≤ 3" set min=1, max=3.
            - "year contains 2024" or "released in 2024" → {"op":"contains","field":"year","value":"2024"}.
            - The PREVIOUS DSL (if given) is the current state of the smartlist being edited.
              Treat the user message as a delta — keep existing conditions unless the user explicitly removes or replaces them.
              If the user message is the initial request (previousDsl is "none"), build the DSL from scratch.

            Examples:
            User: "house tracks from 2024 with rating 4 and above"
            Output: {"conditions":[{"op":"contains","field":"genre","value":"house"},{"op":"contains","field":"year","value":"2024"},{"op":"range","field":"rating","min":4,"max":5}]}

            User: "latina або bolero, по роках менше 1990"
            Output: {"conditions":[{"op":"contains","field":"genre","value":"latina"},{"op":"contains","field":"genre","value":"bolero"},{"op":"range","field":"year","min":0,"max":1989}]}
            (Multiple genre conditions are combined with AND, so this only matches tracks whose genre contains BOTH substrings — clarify with the user if they meant OR. When unsure prefer the looser of the two.)

            User: "70s і 80s"
            Output: {"conditions":[{"op":"range","field":"year","min":1970,"max":1989}]}

            User: "треки без рейтингу"
            Output: {"conditions":[{"op":"is","field":"rating","value":null}]}

            User: "year is exactly 2020"
            Output: {"conditions":[{"op":"is","field":"year","value":"2020"}]}

            User: "комент порожній і genre techno"
            Output: {"conditions":[{"op":"is","field":"comment","value":null},{"op":"contains","field":"genre","value":"techno"}]}

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
