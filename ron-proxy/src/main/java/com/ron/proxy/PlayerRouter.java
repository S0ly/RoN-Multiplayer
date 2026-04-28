package com.ron.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlayerRouter {

    private final ProxyServer server;
    private final Logger logger;
    private Object plugin;

    public PlayerRouter(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    public void setPlugin(Object plugin) {
        this.plugin = plugin;
    }

    public void transferPlayer(UUID playerUuid, String targetServerName) {
        Optional<Player> player = server.getPlayer(playerUuid);
        Optional<RegisteredServer> target = server.getServer(targetServerName);

        if (player.isEmpty()) {
            logger.warn("Cannot transfer — player {} not found", playerUuid);
            return;
        }
        if (target.isEmpty()) {
            logger.warn("Cannot transfer — server {} not found", targetServerName);
            return;
        }

        logger.info("Transferring {} to {}", player.get().getUsername(), targetServerName);
        player.get().createConnectionRequest(target.get()).fireAndForget();
    }

    public void transferAllFromServer(String sourceServerName, String targetServerName) {
        Optional<RegisteredServer> source = server.getServer(sourceServerName);
        Optional<RegisteredServer> target = server.getServer(targetServerName);

        if (source.isEmpty() || target.isEmpty()) {
            logger.warn("Cannot transfer all — source or target server not found");
            return;
        }

        List<Player> players = new ArrayList<>(source.get().getPlayersConnected());
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            long delayMs = i * 500L;
            server.getScheduler().buildTask(plugin, () -> {
                logger.info("Transferring {} from {} to {}", player.getUsername(), sourceServerName, targetServerName);
                player.createConnectionRequest(target.get()).fireAndForget();
            }).delay(delayMs, TimeUnit.MILLISECONDS).schedule();
        }
    }
}
