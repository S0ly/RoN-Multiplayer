package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
import com.ron.lobby.queue.PrivateLobbyView;
import com.ron.lobby.ui.menu.RonMenuHolder.MenuId;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        ItemStack clicked = event.getCurrentItem();
        boolean isOurMenu = holder instanceof RonMenuHolder;
        boolean isOurItem = MenuItems.readAction(clicked) != null
                || MenuItems.readAction(event.getCursor()) != null;

        // Lock down any of our menus or attempts to move our hotbar shortcut items.
        if (!isOurMenu && !isOurItem) return;
        event.setCancelled(true);

        if (!isOurMenu) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String action = MenuItems.readAction(clicked);
        if (action == null || action.equals("noop")) return;
        String payload = MenuItems.readPayload(clicked);

        dispatch(player, (RonMenuHolder) holder, action, payload);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RonMenuHolder holder)) return;
        MenuId id = holder.menuId();
        if (id != MenuId.VOTE && id != MenuId.VOTE_MODES) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE, () -> {
            MatchQueue q = RonLobby.matchQueue;
            if (!q.isVoteActive()) return;
            if (q.hasVoted(player.getUniqueId())) return;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof RonMenuHolder) return;
            player.sendMessage(ChatColor.GRAY + "[RoN] Vote screen closed — type "
                    + ChatColor.WHITE + "/menu vote" + ChatColor.GRAY + " to reopen.");
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ChatPrompt.cancel(event.getPlayer().getUniqueId());
        VoteBossBar.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        String action = MenuItems.readAction(item);
        if (action == null || action.equals("noop")) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK
                && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        event.setCancelled(true);
        String payload = MenuItems.readPayload(item);
        Player player = event.getPlayer();
        if (action.equals("open-hub")) {
            MenuService.openHub(player);
            return;
        }
        dispatch(player, null, action, payload);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (MenuItems.readAction(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (MenuItems.readAction(event.getMainHandItem()) != null
                || MenuItems.readAction(event.getOffHandItem()) != null) {
            event.setCancelled(true);
        }
    }

    private void dispatch(Player player, RonMenuHolder holder, String action, String payload) {
        MatchQueue q = RonLobby.matchQueue;
        switch (action) {
            case "queue-toggle" -> {
                java.util.UUID uid = player.getUniqueId();
                boolean wasQueued = q.isInAnyQueue(uid);
                if (wasQueued) {
                    q.leaveQueue(uid, player.getName());
                } else {
                    q.joinPublicQueue(uid, player.getName());
                }
                boolean nowQueued = q.isInAnyQueue(uid);
                if (!wasQueued && nowQueued) {
                    player.sendMessage(ChatColor.GREEN + "[RoN] Joined the queue.");
                } else if (wasQueued && !nowQueued) {
                    player.sendMessage(ChatColor.YELLOW + "[RoN] Left the queue.");
                }
                // Refresh hub so the tile updates and the player sees confirmation.
                Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE,
                        () -> MenuService.openHub(player), 1L);
            }
            case "back" -> {
                if (holder != null && holder.menuId() == MenuId.VOTE_MODES) {
                    Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE,
                            () -> MenuService.openVote(player), 1L);
                } else {
                    Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE,
                            () -> MenuService.openHub(player), 1L);
                }
            }
            case "open-private" -> MenuService.openPrivate(player);
            case "open-matches" -> MenuService.openMatches(player);
            case "open-leaderboard" -> MenuService.openLeaderboard(player);
            case "open-rank" -> {
                player.closeInventory();
                MenuService.openRank(player);
            }
            case "private-create" -> {
                String code = q.createPrivateLobby(player.getUniqueId(), player.getName());
                if (code != null) {
                    Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE,
                            () -> MenuService.openPrivate(player), 2L);
                }
            }
            case "private-join" -> ChatPrompt.prompt(player, "Type the lobby code:",
                    code -> {
                        String upper = code.toUpperCase();
                        q.joinPrivateLobby(player.getUniqueId(), player.getName(), upper);
                        if (q.getPrivateLobbyView(player.getUniqueId()) != null) {
                            player.sendMessage(ChatColor.GREEN + "[RoN] Joined lobby " + upper + ".");
                            Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE,
                                    () -> MenuService.openPrivate(player), 2L);
                        }
                        // Invalid-code path is already handled in joinPrivateLobby
                        // via tellPlayer "Invalid lobby code".
                    });
            case "private-leave" -> {
                q.leaveQueue(player.getUniqueId(), player.getName());
                player.closeInventory();
            }
            case "private-start" -> {
                q.startPrivate(player.getUniqueId());
                player.closeInventory();
            }
            case "vote-random" -> {
                List<MapOption> opts = q.getVoteMapOptions();
                int randomIdx = opts.size() + 1;
                q.castVote(player.getUniqueId(), randomIdx, null);
                player.closeInventory();
            }
            case "vote-map" -> handleVoteMap(player, q, payload);
            case "vote-mode" -> handleVoteMode(player, q, payload);
            case "spectate" -> {
                player.closeInventory();
                player.performCommand("spectate " + payload);
            }
            case "rejoin" -> {
                player.closeInventory();
                player.sendMessage(ChatColor.AQUA + "[RoN] Reconnecting to " + payload + "...");
                LobbyMessaging.sendTransfer(java.util.List.of(player.getUniqueId().toString()), payload);
            }
        }
    }

    private void handleVoteMap(Player player, MatchQueue q, String mapFolder) {
        if (mapFolder == null) return;
        List<MapOption> opts = q.getVoteMapOptions();
        MapOption map = opts.stream().filter(m -> m.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null) {
            player.sendMessage(ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }
        List<ModeOption> modes = map.modes() != null ? map.modes() : List.of();
        if (modes.size() > 1) {
            MenuService.openVoteModes(player, mapFolder);
            return;
        }
        int choice = opts.indexOf(map) + 1;
        String modeName = modes.isEmpty() ? null : modes.get(0).name();
        q.castVote(player.getUniqueId(), choice, modeName);
        player.closeInventory();
    }

    private void handleVoteMode(Player player, MatchQueue q, String payload) {
        if (payload == null) return;
        int slash = payload.indexOf('/');
        if (slash < 0) return;
        String mapFolder = payload.substring(0, slash);
        String modeName = payload.substring(slash + 1);
        List<MapOption> opts = q.getVoteMapOptions();
        MapOption map = opts.stream().filter(m -> m.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null) return;
        int choice = opts.indexOf(map) + 1;
        q.castVote(player.getUniqueId(), choice, modeName);
        player.closeInventory();
    }
}
