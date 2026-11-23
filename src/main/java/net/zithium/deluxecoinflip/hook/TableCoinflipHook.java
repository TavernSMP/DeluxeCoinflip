package net.zithium.deluxecoinflip.hook;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Bridge to TavernItemCoinflip's ItemCoinflipService (item coinflip tables and the shared weekly
 * money cap). Looked up at call time through Bukkit's ServicesManager by class name, so this jar
 * has no compile or load dependency on TavernItemCoinflip. When it is missing or disabled every
 * method reports "not available" and /cf keeps its old behaviour.
 */
public final class TableCoinflipHook {
    private static final String SERVICE = "com.tavernmc.itemcoinflip.api.ItemCoinflipService";

    private TableCoinflipHook() {
    }

    private static Object[] lookup() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("TavernItemCoinflip");
            if (p == null || !p.isEnabled()) {
                return null;
            }
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (SERVICE.equals(c.getName())) {
                    RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
                    if (rsp != null && rsp.getPlugin().isEnabled()) {
                        return new Object[]{c, rsp.getProvider()};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean available() {
        return lookup() != null;
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Object[] found = lookup();
        if (found == null) {
            throw new IllegalStateException("TavernItemCoinflip is not available");
        }
        Method m = ((Class<?>)found[0]).getMethod(name, types);
        return m.invoke(found[1], args);
    }

    /** /cf <player>: true when TavernItemCoinflip handled it. */
    public static boolean challenge(Player player, String target) {
        try {
            call("challenge", new Class<?>[]{Player.class, String.class}, player, target);
            return true;
        } catch (Throwable t) {
            log("challenge", t);
            return false;
        }
    }

    public static boolean accept(Player player, String from) {
        try {
            call("accept", new Class<?>[]{Player.class, String.class}, player, from);
            return true;
        } catch (Throwable t) {
            log("accept", t);
            return false;
        }
    }

    public static boolean deny(Player player, String from) {
        try {
            call("deny", new Class<?>[]{Player.class, String.class}, player, from);
            return true;
        } catch (Throwable t) {
            log("deny", t);
            return false;
        }
    }

    /** Shared weekly money cap. Allowed when TavernItemCoinflip is missing (cap off). */
    public static boolean checkMoneyStake(Player player, long amount) {
        if (lookup() == null) {
            return true;
        }
        try {
            Object r = call("checkMoneyStake", new Class<?>[]{Player.class, long.class}, player, amount);
            return !(r instanceof Boolean) || (Boolean)r;
        } catch (Throwable t) {
            log("checkMoneyStake", t);
            return true;
        }
    }

    public static void recordMoneyStake(UUID player, long amount) {
        if (player == null || amount <= 0L || lookup() == null) {
            return;
        }
        try {
            call("recordMoneyStake", new Class<?>[]{UUID.class, long.class}, player, amount);
        } catch (Throwable t) {
            log("recordMoneyStake", t);
        }
    }

    private static void log(String what, Throwable t) {
        if (t instanceof IllegalStateException && "TavernItemCoinflip is not available".equals(t.getMessage())) {
            return;
        }
        Plugin self = Bukkit.getPluginManager().getPlugin("TavernCoinflip");
        if (self != null) {
            self.getLogger().log(Level.WARNING, "[itemcoinflip] " + what + " call failed", t);
        }
    }
}
