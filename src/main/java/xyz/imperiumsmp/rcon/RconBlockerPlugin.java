package xyz.imperiumsmp.rcon;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class RconBlockerPlugin extends JavaPlugin {

    private static RconBlockerPlugin instance;
    private List<String> allowedIps;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        allowedIps = getConfig().getStringList("allowed-ips");

        getLogger().info("RCON Blocker enabled");
        RconInjector.inject();
    }

    public static RconBlockerPlugin getInstance() {
        return instance;
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }
}
