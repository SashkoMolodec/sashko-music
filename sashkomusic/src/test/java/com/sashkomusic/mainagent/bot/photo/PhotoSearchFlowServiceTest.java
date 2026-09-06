package com.sashkomusic.mainagent.bot.photo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoSearchFlowServiceTest {

    @ParameterizedTest
    @CsvSource({
            "копай, true",
            "'копай будь ласка', true",
            "'це круто, копай!', true",
            "КОПАЙ, true",
            "'просто фото', false",
            "'', false"
    })
    void detects_direct_download_keyword_in_caption(String caption, boolean expected) {
        assertThat(PhotoSearchFlowService.isDirectDownloadCaption(caption)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    void null_caption_is_not_direct_download(String caption) {
        assertThat(PhotoSearchFlowService.isDirectDownloadCaption(caption)).isFalse();
    }
}
