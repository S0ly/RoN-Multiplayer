package com.ron.instance;

import net.minecraftforge.common.ForgeConfigSpec;

public class RonInstanceConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> MAPS_POOL_PATH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("maps");
        MAPS_POOL_PATH = builder
                .comment("Path to the shared maps pool directory")
                .define("pool", "maps");
        builder.pop();

        SPEC = builder.build();
    }
}
