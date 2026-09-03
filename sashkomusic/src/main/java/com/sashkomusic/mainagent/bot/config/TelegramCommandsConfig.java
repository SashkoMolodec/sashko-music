package com.sashkomusic.mainagent.bot.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonCommands;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramCommandsConfig {

    private final TelegramClient telegramClient;

    @PostConstruct
    public void registerCommands() {
        try {
            List<BotCommand> commands = List.of(
                    new BotCommand("np", "шо наразі грає"),
                    new BotCommand("npalbum", "інфо про поточний альбом"),
                    new BotCommand("markers", "список міток"),
                    new BotCommand("smartlists", "список смартлистів"),
                    new BotCommand("library", "команда для бібліотеки (вкажи запит)"),
                    new BotCommand("discovery", "пошук музики (вкажи запит)"),
                    new BotCommand("clearctx", "очистити контекст чату"),
                    new BotCommand("newtopic", "створити новий топік з поточного контексту (опційно: назва)")
            );

            SetMyCommands setMyCommands = SetMyCommands.builder()
                    .commands(commands)
                    .scope(new BotCommandScopeDefault())
                    .build();

            telegramClient.execute(setMyCommands);

            telegramClient.execute(SetChatMenuButton.builder()
                    .menuButton(new MenuButtonCommands())
                    .build());

            log.info("✅ Bot commands registered successfully");
        } catch (Exception e) {
            log.error("❌ Failed to register bot commands: {}", e.getMessage(), e);
        }
    }
}