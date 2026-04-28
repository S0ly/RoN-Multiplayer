package com.ron.lobby;

import com.ron.lobby.commands.*;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.world.VoidWorldSetup;
import org.bukkit.plugin.java.JavaPlugin;

public class RonLobby extends JavaPlugin {

    public static RonLobby INSTANCE;
    public static MatchQueue matchQueue;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();

        // Initialize queue
        matchQueue = new MatchQueue(this);
        matchQueue.configureTimings(
            getConfig().getInt("queue.fill-seconds", 120),
            getConfig().getInt("queue.vote-seconds", 60)
        );

        // Register plugin messaging channels
        getServer().getMessenger().registerOutgoingPluginChannel(this, "ron:transfer");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "ron:match");
        LobbyMessaging listener = new LobbyMessaging();
        getServer().getMessenger().registerIncomingPluginChannel(this, "ron:match", listener);

        // Register events
        getServer().getPluginManager().registerEvents(new VoidWorldSetup(), this);
        getServer().getPluginManager().registerEvents(matchQueue, this);

        // Register commands
        getCommand("queue").setExecutor(new QueueCommand());
        getCommand("leave").setExecutor(new LeaveCommand());
        getCommand("spectate").setExecutor(new SpectateCommand());
        getCommand("leaderboard").setExecutor(new LeaderboardCommand());
        getCommand("rank").setExecutor(new RankCommand());
        getCommand("vote").setExecutor(new VoteCommand());
        getCommand("matches").setExecutor(new MatchesCommand());
        getCommand("ronstatus").setExecutor(new StatusCommand());

        // Periodic cleanup of stale messaging callbacks (every 60s)
        getServer().getScheduler().runTaskTimer(this, LobbyMessaging::cleanupStaleCallbacks, 1200L, 1200L);

        getLogger().info("RoN Lobby enabled");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
    }
}
