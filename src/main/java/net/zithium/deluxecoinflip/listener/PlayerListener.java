package net.zithium.deluxecoinflip.listener;

import net.zithium.deluxecoinflip.DeluxeCoinflipPlugin;
import net.zithium.deluxecoinflip.game.CoinflipGame;
import net.zithium.deluxecoinflip.game.GameManager;
import net.zithium.deluxecoinflip.payout.PayoutManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.UUID;

public class PlayerListener implements Listener {
    private final DeluxeCoinflipPlugin plugin;
    private final GameManager gameManager;
    private final PayoutManager payoutManager;

    public PlayerListener(DeluxeCoinflipPlugin plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.payoutManager = plugin.getPayoutManager();
    }

    /**
     * Pays anything the player is owed once their data has finished loading.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        payoutManager.scheduleSettle(event.getPlayer());
    }

    /**
     * Refunds an open game the player never had accepted.
     *
     * <p>This deliberately does not deposit. A deposit made during {@code PlayerQuitEvent}
     * races the player-data sync saving the same balance in the same event, so it survived
     * or vanished depending on listener order. The refund is recorded instead and paid on
     * their next join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        CoinflipGame coinflipGame = gameManager.getCoinflipGames().get(uuid);
        if (coinflipGame != null) {
            payoutManager.escrow(player, coinflipGame.getAmount(), coinflipGame.getProvider());
            gameManager.removeCoinflipGame(uuid);
        }
    }

    /**
     * Deliberately does not refund or delete anything.
     *
     * <p>Open games stay in storage and are refunded on the next start. Refunding here as
     * well would mean two places could pay for the same game if the delete that follows it
     * ever failed, and a deposit during shutdown is discarded by the player-data sync anyway.
     */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(plugin)) {
            plugin.getLogger().info("Open coinflip games remain stored and will be refunded on next start.");
        }
    }
}
