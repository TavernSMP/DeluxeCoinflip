/*
 * DeluxeCoinflip Plugin
 * Copyright (c) 2021 - 2022 Lewis D (ItsLewizzz). All rights reserved.
 */

package net.zithium.deluxecoinflip.payout;

import net.zithium.deluxecoinflip.DeluxeCoinflipPlugin;
import net.zithium.deluxecoinflip.economy.EconomyManager;
import net.zithium.deluxecoinflip.economy.provider.EconomyProvider;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Holds money that is owed to a player but cannot safely be paid right now.
 *
 * <p>Nothing may be deposited into a player who is offline or in the middle of
 * disconnecting: the network's player-data sync snapshots a balance on quit and restores it
 * on the next join, so a write made outside that window is silently discarded and the money
 * is gone. Everything that used to deposit in those moments now records the debt here and it
 * is paid the next time that player is online and loaded.
 *
 * <p>Backed by a small flat file so a crash, restart or reload cannot lose a debt. The file
 * only ever holds unsettled entries, so it stays close to empty in normal operation.
 */
public class PayoutManager {

    private static final String ROOT = "payouts";

    /**
     * Ticks to wait after a join before paying. The sync layer restores a player's balance
     * shortly after they connect; paying inside that window would be overwritten by it.
     */
    private static final long SETTLE_DELAY_TICKS = 60L;

    private final DeluxeCoinflipPlugin plugin;
    private final EconomyManager economyManager;
    private final File file;

    private final Map<UUID, PendingPayout> payouts = new LinkedHashMap<>();

    public PayoutManager(DeluxeCoinflipPlugin plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomyManager();
        this.file = new File(plugin.getDataFolder(), "payouts.yml");
        load();
    }

    private synchronized void load() {
        payouts.clear();
        if (!file.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }

        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                continue;
            }

            try {
                UUID id = UUID.fromString(rawId);
                UUID player = UUID.fromString(section.getString("uuid", ""));
                double amount = section.getDouble("amount");
                String provider = section.getString("provider");

                if (amount <= 0 || provider == null) {
                    plugin.getLogger().warning("Discarding malformed pending payout " + rawId);
                    continue;
                }
                payouts.put(id, new PendingPayout(id, player, amount, provider, section.getLong("created-at")));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Discarding unreadable pending payout " + rawId);
            }
        }

        if (!payouts.isEmpty()) {
            plugin.getLogger().info("Recovered " + payouts.size()
                    + " unpaid coinflip payout(s); they will be paid as those players return.");
        }
    }

    /**
     * Writes the whole file. Called on every change on purpose — losing a debt to a crash is
     * worse than the cost of a write, and the file only holds what is currently unsettled.
     */
    private synchronized void save() {
        FileConfiguration config = new YamlConfiguration();
        for (PendingPayout payout : payouts.values()) {
            String base = ROOT + "." + payout.id();
            config.set(base + ".uuid", payout.player().toString());
            config.set(base + ".amount", payout.amount());
            config.set(base + ".provider", payout.provider());
            config.set(base + ".created-at", payout.createdAt());
        }

        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save pending coinflip payouts to " + file, ex);
        }
    }

    /**
     * Records money as owed without attempting to pay it.
     *
     * <p>Use this whenever the recipient may be offline or on their way out — a quit refund,
     * a shutdown refund, or a payout being reserved before an animation runs.
     *
     * @return the payout id, for a later {@link #settle(UUID)}
     */
    public synchronized UUID escrow(OfflinePlayer target, double amount, String provider) {
        UUID id = UUID.randomUUID();
        if (amount <= 0) {
            return id;
        }

        payouts.put(id, new PendingPayout(id, target.getUniqueId(), amount, provider, System.currentTimeMillis()));
        save();
        return id;
    }

    /**
     * Pays a previously escrowed amount if the recipient is online, otherwise leaves it
     * queued for their next join. Safe to call for an id that was already settled.
     */
    public void settle(UUID payoutId) {
        PendingPayout payout;
        synchronized (this) {
            payout = payouts.get(payoutId);
            if (payout == null) {
                return;
            }

            Player online = Bukkit.getPlayer(payout.player());
            if (online == null || !online.isOnline()) {
                return;
            }
            // Remove before depositing: a failure between the two loses one payout, whereas
            // the reverse order would pay it twice on the next join.
            payouts.remove(payoutId);
            save();
        }
        deposit(payout);
    }

    /**
     * Pays immediately when that is safe, and records the debt when it is not.
     */
    public void pay(OfflinePlayer target, double amount, String provider) {
        if (amount <= 0) {
            return;
        }

        Player online = target.isOnline() ? target.getPlayer() : null;
        if (online != null) {
            EconomyProvider economyProvider = economyManager.getEconomyProvider(provider);
            if (economyProvider != null) {
                economyProvider.deposit(online, amount);
                return;
            }
        }
        escrow(target, amount, provider);
    }

    /** Pays everything owed to a player who is online and loaded. */
    public void settleAll(Player player) {
        List<PendingPayout> owed = new ArrayList<>();
        synchronized (this) {
            for (PendingPayout payout : new ArrayList<>(payouts.values())) {
                if (payout.player().equals(player.getUniqueId())) {
                    payouts.remove(payout.id());
                    owed.add(payout);
                }
            }
            if (owed.isEmpty()) {
                return;
            }
            save();
        }
        owed.forEach(this::deposit);
    }

    /**
     * Queues a settle for a player who just joined, once the sync layer has finished
     * restoring their balance.
     */
    public void scheduleSettle(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                settleAll(player);
            }
        }, SETTLE_DELAY_TICKS);
    }

    /** Settles every online player. Used on enable so a reload does not strand anyone. */
    public void settleOnlinePlayers() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline()) {
                    settleAll(player);
                }
            }
        }, SETTLE_DELAY_TICKS);
    }

    private void deposit(PendingPayout payout) {
        EconomyProvider provider = economyManager.getEconomyProvider(payout.provider());
        if (provider == null) {
            plugin.getLogger().severe("Cannot pay " + payout.amount() + " to " + payout.player()
                    + ": economy provider '" + payout.provider() + "' is not registered. Re-queued.");
            synchronized (this) {
                payouts.put(payout.id(), payout);
                save();
            }
            return;
        }
        provider.deposit(Bukkit.getOfflinePlayer(payout.player()), payout.amount());
    }

    public synchronized int pendingCount() {
        return payouts.size();
    }
}
