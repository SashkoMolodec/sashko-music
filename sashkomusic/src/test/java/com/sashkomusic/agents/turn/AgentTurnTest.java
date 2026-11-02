package com.sashkomusic.agents.turn;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTurnTest {

    private final List<String> progress = new ArrayList<>();
    private final AgentTurn turn = new AgentTurn(ConversationContext.topic(-100L, 7), progress::add);

    @Test
    void a_tool_finds_its_turn_in_the_parameters_the_agent_was_given() {
        assertThat(AgentTurn.of(turn.parameters())).isSameAs(turn);
        assertThat(AgentTurn.of(turn.parameters()).conversationId()).isEqualTo("-100:7");
    }

    @Test
    void parameters_without_a_turn_are_a_wiring_bug() {
        assertThatThrownBy(() -> AgentTurn.of(new InvocationParameters())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AgentTurn.of(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_same_progress_line_is_sent_once_per_turn() {
        turn.progress("🔍 шукаю...");
        turn.progress("📚 бібліотека...");
        turn.progress("🔍 шукаю...");

        assertThat(progress).containsExactly("🔍 шукаю...", "📚 бібліотека...");
    }

    @Test
    void shown_cards_keep_their_order_and_cannot_be_changed_from_outside() {
        BotResponse first = BotResponse.text("one");
        BotResponse second = BotResponse.text("two");
        turn.show(List.of(first));
        turn.show(null);
        turn.show(List.of(second));

        assertThat(turn.shown()).containsExactly(first, second);
        assertThatThrownBy(() -> turn.shown().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
