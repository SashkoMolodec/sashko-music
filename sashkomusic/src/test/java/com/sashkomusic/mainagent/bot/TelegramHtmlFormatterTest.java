package com.sashkomusic.mainagent.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramHtmlFormatterTest {

    @Test
    void keeps_underscores_in_bare_url_intact() {
        String url = "https://music.youtube.com/playlist?list=OLAK5uy_lkQGrK3iYOpDt4hfvEsGey3sZQ2mH1Lk";

        assertThat(TelegramHtmlFormatter.format("слухай тут\n" + url)).contains(url);
    }

    @Test
    void does_not_italicize_across_two_urls_carrying_underscores() {
        String first = "https://youtu.be/aaa_bbb";
        String second = "https://youtu.be/ccc_ddd";

        String result = TelegramHtmlFormatter.format(first + "\n" + second);

        assertThat(result).contains(first).contains(second).doesNotContain("<i>");
    }

    @Test
    void renders_markdown_link_as_anchor() {
        String result = TelegramHtmlFormatter.format("(discogs [🔗](https://www.discogs.com/release/1_2))");

        assertThat(result).isEqualTo("(discogs <a href=\"https://www.discogs.com/release/1_2\">🔗</a>)");
    }

    @Test
    void escapes_ampersand_inside_link_href() {
        String result = TelegramHtmlFormatter.format("[▶️](https://youtube.com/watch?v=x&t=30)");

        assertThat(result).isEqualTo("<a href=\"https://youtube.com/watch?v=x&amp;t=30\">▶️</a>");
    }

    @Test
    void still_applies_bold_and_italic_outside_urls() {
        String result = TelegramHtmlFormatter.format("**жирний** і _курсив_");

        assertThat(result).isEqualTo("<b>жирний</b> і <i>курсив</i>");
    }

    @Test
    void keeps_code_block_and_inline_code_round_tripping() {
        assertThat(TelegramHtmlFormatter.format("ось `код` тут")).isEqualTo("ось <code>код</code> тут");
        assertThat(TelegramHtmlFormatter.format("```\nx < y\n```")).isEqualTo("<pre>x &lt; y\n</pre>");
    }

    @Test
    void leaves_no_sentinel_chars_in_output() {
        String result = TelegramHtmlFormatter.format("`код` і https://youtu.be/a_b і [x](https://y.z/q_r)");

        assertThat(result).doesNotContain("\u0001");
    }

    @Test
    void escapes_html_special_chars_in_plain_text() {
        assertThat(TelegramHtmlFormatter.format("a < b & c > d")).isEqualTo("a &lt; b &amp; c &gt; d");
    }
}
