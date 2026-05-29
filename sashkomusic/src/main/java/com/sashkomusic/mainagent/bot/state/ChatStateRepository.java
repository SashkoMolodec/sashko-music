package com.sashkomusic.mainagent.bot.state;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatStateRepository extends JpaRepository<ChatStateEntity, ChatStateId> {

    Optional<ChatStateEntity> findByChatIdAndFlowKey(long chatId, String flowKey);

    @Modifying
    void deleteByChatIdAndFlowKey(long chatId, String flowKey);

    @Modifying
    @Query("delete from ChatStateEntity c where c.flowKey = :flowKey")
    int deleteByFlowKey(@Param("flowKey") String flowKey);
}
