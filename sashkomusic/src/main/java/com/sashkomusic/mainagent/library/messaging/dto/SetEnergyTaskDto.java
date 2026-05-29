package com.sashkomusic.mainagent.library.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("set_energy_task")
public record SetEnergyTaskDto(
        Long trackId,
        String energy,  // E1-E5
        long chatId
) {
}
