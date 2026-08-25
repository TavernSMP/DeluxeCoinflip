/*
 * DeluxeCoinflip Plugin
 * Copyright (c) 2021 - 2022 Lewis D (ItsLewizzz). All rights reserved.
 */

package net.zithium.deluxecoinflip;

import co.aikar.commands.PaperCommandManager;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.zithium.deluxecoinflip.api.DeluxeCoinflipAPI;
import net.zithium.deluxecoinflip.command.CoinflipCommand;
import net.zithium.deluxecoinflip.config.ConfigHandler;
import net.zithium.deluxecoinflip.config.ConfigType;
import net.zithium.deluxecoinflip.config.Messages;
import net.zithium.deluxecoinflip.economy.EconomyManager;
import net.zithium.deluxecoinflip.economy.provider.EconomyProvider;
import net.zithium.deluxecoinflip.game.CoinflipGame;
import net.zithium.deluxecoinflip.game.GameManager;
import net.zithium.deluxecoinflip.hook.PlaceholderAPIHook;
import net.zithium.deluxecoinflip.listener.PlayerChatListener;
import net.zithium.deluxecoinflip.listener.PlayerListener;
import net.zithium.deluxecoinflip.menu.InventoryManager;
import net.zithium.deluxecoinflip.payout.PayoutManager;
import net.zithium.deluxecoinflip.storage.PlayerData;
import net.zithium.deluxecoinflip.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class DeluxeCoinflipPlugin extends JavaPlugin implements DeluxeCoinflipAPI {

    private Map<ConfigType, ConfigHandler> configMap;
    private StorageManager storageManager;
    private GameManager gameManager;
    private InventoryManager inventoryManager;
    private EconomyManager economyManager;
    private PayoutManager payoutManager;

    private Cache<UUID, CoinflipGame> listenerCache;

    public void onEnable() {
        listenerCache = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(500).build();

        // Register configurations
        configMap = new HashMap<>();
        registerConfig(ConfigType.CONFIG);
        registerConfig(ConfigType.MESSAGES);
        Messages.setConfiguration(configMap.get(ConfigType.MESSAGES).getConfig());

        // Load storage
        storageManager = new StorageManager(this);
        try {
            storageManager.onEnable();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "There was an issue attempting to load the storage handler.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economyManager = new EconomyManager(this);
        economyManager.onEnable();
        // Depends on the economy manager; must exist before any listener or command can owe money.
        payoutManager = new PayoutManager(this);
        gameManager = new GameManager(this);

        inventoryManager = new InventoryManager();
        inventoryManager.load(this);

        List<String> aliases = getConfigHandler(ConfigType.CONFIG).getConfig().getStringList("settings.command_aliases");

        PaperCommandManager paperCommandManager = new PaperCommandManager(this);
        paperCommandManager.getCommandCompletions().registerAsyncCompletion("providers", c -> economyManager.getEconomyProviders().values().stream().map(EconomyProvider::getDisplayName).collect(Collectors.toList()));
        paperCommandManager.getCommandReplacements().addReplacement("main", "coinflip|" + String.join("|", aliases));
        paperCommandManager.registerCommand(new CoinflipCommand(this).setExceptionHandler((command, registeredCommand, sender, args, t) -> {
            Messages.NO_PERMISSION.send(sender.getIssuer());
            return true;
        }));

        // Register listeners
        new PlayerChatListener(this);

        // PlaceholderAPI Hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderAPIHook(this).register();
        }

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        recoverOrphanedGames();

        // A /reload re-enables the plugin without anyone rejoining, so the join handler never
        // fires for players who are already connected and owed money.
        payoutManager.settleOnlinePlayers();
    }

    /**
     * Refunds games that were still open when the server last stopped.
     *
     * <p>Stored games are never loaded back into memory, so the old boot sequence deleted
     * them outright and the creators' stakes were destroyed by any unclean shutdown. Shutdown
     * now leaves the rows alone and this is the single place they are settled, which also
     * means there is no window where the same game could be refunded twice.
     */
    private void recoverOrphanedGames() {
        Map<UUID, CoinflipGame> orphaned = storageManager.getStorageHandler().getGames();
        if (orphaned.isEmpty()) {
            return;
        }

        orphaned.forEach((uuid, game) ->
                payoutManager.escrow(Bukkit.getOfflinePlayer(uuid), game.getAmount(), game.getProvider()));
        storageManager.getStorageHandler().dropGamesTable();

        getLogger().info("Refunded " + orphaned.size()
                + " coinflip game(s) that were still open when the server last stopped.");
    }


    @Override
    public void onDisable() {
        if (storageManager != null) storageManager.onDisable(true);
    }

    // Plugin reload handling
    public void reload() {
        configMap.values().forEach(ConfigHandler::reload);
        Messages.setConfiguration(configMap.get(ConfigType.MESSAGES).getConfig());

        inventoryManager.load(this);
        economyManager.onEnable();
    }

    // Method to register a configuration file
    private void registerConfig(ConfigType type) {
        ConfigHandler handler = new ConfigHandler(this, type.toString().toLowerCase());
        handler.saveDefaultConfig();
        configMap.put(type, handler);
    }

    /**
     * Clears all current coinflip games.
     *
     * @param returnMoney Should the money be returned to the game owner?
     */
    public void clearGames(boolean returnMoney) {
        if (!gameManager.getCoinflipGames().isEmpty()) {
            final Map<UUID, CoinflipGame> games = gameManager.getCoinflipGames();
            final List<UUID> gamesToRemove = new ArrayList<>();
            for (UUID uuid : games.keySet()) {
                CoinflipGame coinflipGame = gameManager.getCoinflipGames().get(uuid);
                if (returnMoney) {
                    // Recorded rather than deposited. This runs during shutdown, where a
                    // balance write may be discarded by the player-data sync, and it now also
                    // covers offline creators, whose refund was previously skipped entirely.
                    payoutManager.escrow(Bukkit.getOfflinePlayer(uuid), coinflipGame.getAmount(), coinflipGame.getProvider());
                }
                gamesToRemove.add(uuid);
                storageManager.getStorageHandler().deleteCoinfip(uuid);
            }
            for (UUID uuid : gamesToRemove) {
                gameManager.removeCoinflipGame(uuid);
            }

            storageManager.dropGames();
        }
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    public ConfigHandler getConfigHandler(ConfigType type) {
        return configMap.get(type);
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public PayoutManager getPayoutManager() {
        return payoutManager;
    }

    public Cache<UUID, CoinflipGame> getListenerCache() {
        return listenerCache;
    }

    // API methods
    @Override
    public void registerEconomyProvider(EconomyProvider provider, String requiredPlugin) {
        economyManager.registerEconomyProvider(provider, requiredPlugin);
    }

    @Override
    public Optional<PlayerData> getPlayerData(Player player) {
        return storageManager.getPlayer(player.getUniqueId());
    }
}