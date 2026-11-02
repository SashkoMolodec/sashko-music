package com.sashkomusic.mainagent.bot;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** "копай <query>" is a raw Soulseek search; "копай ще" and friends ask MainAgent to dig deeper instead. */
class DirectSoulseekSearchRoutingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "копай burial untrue        | true",
            "Копай Drexciya             | true",
            "копай ще одну платівку      | true",
            "копай ще                   | false",
            "копай далі!                | false",
            "копай ще глибше            | false",
            "Копай Все                  | false",
            "копай                      | false",
            "ще копай                   | false",
            "покажи все                 | false",
    })
    void routes_only_real_queries_to_soulseek(String input, boolean soulseek) {
        assertThat(UserInteractionOrchestrator.isDirectSoulseekSearch(input)).isEqualTo(soulseek);
    }
}
