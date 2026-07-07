package com.sashkomusic.mainagent.bot.newtopic;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface TopicEmojiPicker {

    @SystemMessage("""
            Вибери один emoji з наданого списку що найкраще відображає настрій теми чату.
            Відповідай ТІЛЬКИ одним emoji символом, нічого більше.
            """)
    @UserMessage("Тема: {{topicName}}\nДоступні emoji: {{availableEmojis}}")
    String pickEmoji(String topicName, String availableEmojis);
}
