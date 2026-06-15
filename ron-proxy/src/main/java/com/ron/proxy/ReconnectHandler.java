package com.ron.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

public class ReconnectHandler {

    private final ProxyServer server;
    private final Logger logger;
    private final Object plugin;
    private final ActiveMatchTracker activeMatchTracker;
    private final InstanceTracker instanceTracker;
    private final PlayerRouter playerRouter;

    public ReconnectHandler(ProxyServer server, Logger logger, Object plugin,
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

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();

        String currentServer = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("");
        if (!"lobby".equals(currentServer)) return;

        String activeInstance = activeMatchTracker.getActiveInstance(player.getUniqueId());
        if (activeInstance == null) return;

        var instances = instanceTracker.getAllInstances();
        var info = instances.get(activeInstance);
        if (info == null || !info.state().isJoinable()) {
            activeMatchTracker.removePlayer(player.getUniqueId());
            return;
        }

        // Notify player after lobby welcome screen (which has a 1s delay + clears chat)
        server.getScheduler().buildTask(plugin, () -> {
            if (player.isActive()) {
                player.sendMessage(Component.text("[RoN] You have an active match. Use /rejoin to reconnect.").color(NamedTextColor.YELLOW));
            }
        }).delay(3, TimeUnit.SECONDS).schedule();
    }
}
