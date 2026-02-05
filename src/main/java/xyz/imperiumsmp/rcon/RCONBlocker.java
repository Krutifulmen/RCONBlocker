package xyz.imperiumsmp.rcon;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerException;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class RCONBlocker extends JavaPlugin implements Listener {

    private final Set<String> allowedIPs = new HashSet<>();
    private boolean blockAll = false;
    private String kickMessage = "&cВаш IP-адрес не имеет доступа к RCON";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        getServer().getPluginManager().registerEvents(this, this);
        
        getSLF4JLogger().info("RCONBlocker успешно запущен!");
        getSLF4JLogger().info("Разрешенные IP-адреса: " + allowedIPs);
        
        setupRCONInterceptor();
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        config.addDefault("block-all", false);
        config.addDefault("allowed-ips", List.of("127.0.0.1", "192.168.1.1"));
        config.addDefault("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
        config.options().copyDefaults(true);
        saveConfig();
        
        allowedIPs.clear();
        allowedIPs.addAll(config.getStringList("allowed-ips"));
        blockAll = config.getBoolean("block-all");
        kickMessage = config.getString("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
    }

    private void setupRCONInterceptor() {
        try {
            Class<?> rconConsoleSourceClass = Class.forName("net.minecraft.server.rcon.RconConsoleSource");
            Class<?> rconClientClass = Class.forName("net.minecraft.server.rcon.RconClient");
            
            getSLF4JLogger().info("Найден класс RconClient, перехватчик установлен");
            
            getServer().getGlobalRegionScheduler().run(this, task -> {
                try {
                    checkAndBlockRCONConnections();
                } catch (Exception e) {
                    getSLF4JLogger().error("Ошибка при проверке RCON соединений", e);
                }
            });
            
        } catch (ClassNotFoundException e) {
            getSLF4JLogger().warn("Класс RconClient не найден. Используется альтернативный метод.");
            setupAlternativeInterceptor();
        }
    }

    private void setupAlternativeInterceptor() {
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            try {
                Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                Object rconListener = minecraftServer.getClass().getMethod("getRconConsoleSource").invoke(minecraftServer);
                
                if (rconListener != null) {
                    checkActiveRCONConnections(rconListener);
                }
            } catch (Exception e) {
                getSLF4JLogger().debug("Не удалось проверить RCON соединения: " + e.getMessage());
            }
        }, 0, 20, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void checkActiveRCONConnections(Object rconListener) {
        try {
            java.lang.reflect.Field threadsField = rconListener.getClass().getDeclaredField("threads");
            threadsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<Thread> threads = (Set<Thread>) threadsField.get(rconListener);
            
            for (Thread thread : threads) {
                if (thread.getName().contains("RCON Client")) {
                    checkThreadConnection(thread);
                }
            }
        } catch (Exception e) {
            getSLF4JLogger().debug("Ошибка при проверке потоков RCON: " + e.getMessage());
        }
    }

    private void checkThreadConnection(Thread thread) {
        try {
            java.lang.reflect.Field clientField = thread.getClass().getDeclaredField("client");
            clientField.setAccessible(true);
            Object client = clientField.get(thread);
            
            java.lang.reflect.Field socketField = client.getClass().getDeclaredField("socket");
            socketField.setAccessible(true);
            java.net.Socket socket = (java.net.Socket) socketField.get(client);
            
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            String ip = address.getAddress().getHostAddress();
            
            if (!isIPAllowed(ip)) {
                getSLF4JLogger().warning("Блокируем RCON соединение с IP: " + ip);
                socket.close();
                thread.interrupt();
            }
        } catch (Exception e) {
            getSLF4JLogger().debug("Не удалось проверить соединение потока: " + e.getMessage());
        }
    }

    private void checkAndBlockRCONConnections() {
        try {
            Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object rconListener = minecraftServer.getClass().getMethod("getRconConsoleSource").invoke(minecraftServer);
            
            if (rconListener != null) {
                java.lang.reflect.Field clientsField = rconListener.getClass().getDeclaredField("clients");
                clientsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Set<Object> clients = (Set<Object>) clientsField.get(rconListener);
                
                Set<Object> toRemove = new HashSet<>();
                
                for (Object client : clients) {
                    try {
                        java.lang.reflect.Field socketField = client.getClass().getDeclaredField("socket");
                        socketField.setAccessible(true);
                        java.net.Socket socket = (java.net.Socket) socketField.get(client);
                        
                        if (socket != null && !socket.isClosed()) {
                            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
                            String ip = address.getAddress().getHostAddress();
                            
                            if (!isIPAllowed(ip)) {
                                getSLF4JLogger().warning("Блокируем RCON соединение с IP: " + ip);
                                socket.close();
                                toRemove.add(client);
                            }
                        }
                    } catch (Exception e) {
                        getSLF4JLogger().debug("Ошибка при проверке клиента RCON: " + e.getMessage());
                    }
                }
                
                clients.removeAll(toRemove);
            }
        } catch (Exception e) {
            getSLF4JLogger().debug("Ошибка при проверке RCON клиентов: " + e.getMessage());
        }
    }

    private boolean isIPAllowed(String ip) {
        if (blockAll) return false;
        if (ip == null) return false;
        
        for (String allowedIP : allowedIPs) {
            if (allowedIP.equals(ip) || allowedIP.equals("*")) {
                return true;
            }
            if (allowedIP.contains("/")) {
                if (isInSubnet(ip, allowedIP)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInSubnet(String ip, String subnet) {
        try {
            String[] parts = subnet.split("/");
            if (parts.length != 2) return false;
            
            String network = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);
            
            java.net.InetAddress ipAddr = java.net.InetAddress.getByName(ip);
            java.net.InetAddress networkAddr = java.net.InetAddress.getByName(network);
            
            byte[] ipBytes = ipAddr.getAddress();
            byte[] networkBytes = networkAddr.getAddress();
            
            if (ipBytes.length != networkBytes.length) return false;
            
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) return false;
            }
            
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @EventHandler
    public void onServerException(ServerExceptionEvent event) {
        ServerException exception = event.getException();
        if (exception.getMessage().contains("RCON") || exception.getMessage().contains("rcon")) {
            getSLF4JLogger().warning("Обнаружена ошибка RCON: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                           @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("rconblocker")) {
            if (!sender.hasPermission("rconblocker.reload")) {
                sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                return true;
            }
            
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                loadConfig();
                sender.sendMessage("§aКонфигурация RCONBlocker перезагружена!");
                sender.sendMessage("§fРазрешенные IP: §e" + String.join(", ", allowedIPs));
                return true;
            }
            
            sender.sendMessage("§6RCONBlocker v" + getDescription().getVersion());
            sender.sendMessage("§fИспользование: §e/rconblocker reload");
            sender.sendMessage("§fТекущий режим: §e" + (blockAll ? "Блокировка всех" : "Белый список"));
            sender.sendMessage("§fРазрешенные IP (§e" + allowedIPs.size() + "§f): §e" + 
                             String.join(", ", allowedIPs));
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        getSLF4JLogger().info("RCONBlocker выключен");
    }
}
