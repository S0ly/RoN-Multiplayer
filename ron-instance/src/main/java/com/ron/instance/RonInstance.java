package com.ron.instance;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("ron_instance")
public class RonInstance {

    public static final Logger LOGGER = LoggerFactory.getLogger("RoN Instance");

    public RonInstance() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RonInstanceConfig.SPEC, "ron-instance.toml");

        MinecraftForge.EVENT_BUS.register(MapSwapper.class);
        MinecraftForge.EVENT_BUS.register(MatchLifecycle.class);
        MinecraftForge.EVENT_BUS.register(MatchEndHandler.class);
        MinecraftForge.EVENT_BUS.register(RonCommands.class);
        MinecraftForge.EVENT_BUS.register(PlayerTracker.class);

        LOGGER.info("RoN Instance mod loaded");
    }
}
