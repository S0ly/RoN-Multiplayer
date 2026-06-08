package com.ron.lobby.ui;

import org.bukkit.configuration.file.FileConfiguration;

public record UiSettings(
        boolean chatCommands,
        boolean chatMessages,
        boolean autoOpenHubOnJoin,
        boolean autoOpenVote,
        boolean handItems,
        boolean bossbarVote,
        boolean bossbarQueue,
        boolean scoreboardQueue,
        boolean actionbarStatus,
        boolean sounds
) {
    public static UiSettings load(FileConfiguration cfg) {
        return new UiSettings(
                cfg.getBoolean("ui.chat-commands", true),
                cfg.getBoolean("ui.chat-messages", true),
                cfg.getBoolean("ui.auto-open-hub-on-join", true),
                cfg.getBoolean("ui.auto-open-vote", true),
                cfg.getBoolean("ui.hand-items", false),
                cfg.getBoolean("ui.hud.bossbar-vote", true),
                cfg.getBoolean("ui.hud.bossbar-queue", true),
                cfg.getBoolean("ui.hud.scoreboard-queue", false),
                cfg.getBoolean("ui.hud.actionbar-status", false),
                cfg.getBoolean("ui.sounds", true)
        );
    }
}
