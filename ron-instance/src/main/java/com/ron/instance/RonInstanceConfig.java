package com.ron.instance;

import net.minecraftforge.common.ForgeConfigSpec;

public class RonInstanceConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> MAPS_POOL_PATH;

    // Match lifecycle timings (seconds). The mod owns the in-game countdown; these
    // govern the watchdog around it (readying window, grace periods, timeouts).
    public static final ForgeConfigSpec.IntValue READYING_SECONDS;
    public static final ForgeConfigSpec.IntValue GRACE_PERIOD_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_MATCH_SECONDS;
    public static final ForgeConfigSpec.IntValue READY_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue EMPTY_ABANDON_SECONDS;
    public static final ForgeConfigSpec.IntValue WELCOME_DELAY_SECONDS;
    public static final ForgeConfigSpec.IntValue MIN_READY_PLAYERS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("maps");
        MAPS_POOL_PATH = builder
                .comment("Path to the shared maps pool directory")
                .define("pool", "maps");
        builder.pop();

        builder.push("match");
        READYING_SECONDS = builder
                .comment("Seconds players have to pick start positions before the match auto-cancels.")
                .defineInRange("readying-seconds", 120, 1, 3600);
        GRACE_PERIOD_SECONDS = builder
                .comment("Seconds a disconnected player has to reconnect before forfeiting.")
                .defineInRange("grace-period-seconds", 120, 0, 3600);
        MAX_MATCH_SECONDS = builder
                .comment("Maximum match duration before a forced draw. 0 disables the cap.")
                .defineInRange("max-match-seconds", 7200, 0, 86400);
        READY_TIMEOUT_SECONDS = builder
                .comment("Seconds the server waits in READY before auto-resetting to IDLE. 0 disables.")
                .defineInRange("ready-timeout-seconds", 300, 0, 86400);
        EMPTY_ABANDON_SECONDS = builder
                .comment("Seconds to wait after all players disconnect mid-match before a forced draw.")
                .defineInRange("empty-abandon-seconds", 60, 1, 3600);
        WELCOME_DELAY_SECONDS = builder
                .comment("Delay before showing the welcome message to players after a match starts.")
                .defineInRange("welcome-delay-seconds", 5, 0, 600);
        MIN_READY_PLAYERS = builder
                .comment("Minimum readied players required to move from READYING to RUNNING.")
                .defineInRange("min-ready-players", 2, 2, 8);
        builder.pop();

        SPEC = builder.build();
    }
}
