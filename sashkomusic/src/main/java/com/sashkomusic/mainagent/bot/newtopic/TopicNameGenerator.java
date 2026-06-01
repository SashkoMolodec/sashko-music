package com.sashkomusic.mainagent.bot.newtopic;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface TopicNameGenerator {

    @SystemMessage("""
            Придумай коротку назву для нового чату в Telegram (2-4 слова, нижній регістр, без emoji, без лапок).
            Назва має відображати тему поточної розмови.
            Відповідай ТІЛЬКИ назвою, нічого більше.
            """)
    @UserMessage("Контекст розмови:\n{{it}}")
    String generateName(String conversationContext);

    @SystemMessage("""
            Ти пишеш коротке вступне повідомлення для нового тематичного чату (1-2 речення, нижній регістр, без emoji).
            Вкажи тему і що тут обговорюватимуть. Відповідай ТІЛЬКИ повідомленням, нічого більше.
            """)
    @UserMessage("Тема чату: {{topicName}}\n\nКонтекст попередньої розмови:\n{{context}}")
    String generateOpeningSummary(String topicName, String context);
}
