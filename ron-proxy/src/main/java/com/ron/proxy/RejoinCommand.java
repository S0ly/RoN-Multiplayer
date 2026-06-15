package com.ron.proxy;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RejoinCommand implements SimpleCommand {

    private final ProxyServer server;
    private final Logger logger;
    private final Object plugin;
    private final ActiveMatchTracker activeMatchTracker;
    private final InstanceTracker instanceTracker;
    private final PlayerRouter playerRouter;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public RejoinCommand(ProxyServer server, Logger logger, Object plugin,
                         ActiveMatchTracker activeMatchTracker,
                         InstanceTracker instanceTracker,
                         PlayerRouter playerRouter) {
        this.server = server;
        this.logger = logger;
        this.plugin = plugin;
        this.activeMatchTracker = activeMatchTracker;
        this.instanceTracker = instanceTracker;
        this.playerRouter = playerRouter;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();

        if (pending.contains(uuid)) {
            player.sendMessage(Component.text("[RoN] Already reconnecting, please wait...").color(NamedTextColor.YELLOW));
            return;
        }

        String activeInstance = activeMatchTracker.getActiveInstance(uuid);
        if (activeInstance == null) {
            player.sendMessage(Component.text("[RoN] You don't have an active match to rejoin.").color(NamedTextColor.RED));
            return;
        }

        var instances = instanceTracker.getAllInstances();
        var info = instances.get(activeInstance);
        if (info == null || !info.state().isJoinable()) {
            player.sendMessage(Component.text("[RoN] Your match is no longer active.").color(NamedTextColor.RED));
            activeMatchTracker.removePlayer(uuid);
            return;
        }

        pending.add(uuid);
        player.sendMessage(Component.text("[RoN] Reconnecting to " + activeInstance + " in 5 seconds...").color(NamedTextColor.GREEN));

        server.getScheduler().buildTask(plugin, () -> {
            pending.remove(uuid);
            if (player.isActive()) {
                playerRouter.transferPlayer(uuid, activeInstance);
                logger.info("Rejoin: {} -> {} (manual)", player.getUsername(), activeInstance);
            }
        }).delay(5, TimeUnit.SECONDS).schedule();
    }
}
