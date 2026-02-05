package xyz.imperiumsmp.rcon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RCONBlocker extends JavaPlugin {
    
    private Set<String> allowedIPs = new HashSet<>();
    private boolean blockAll = true;
    private boolean debug = false;
    private ServerSocketInterceptor interceptor;
    private final Map<String, Long> blockedIPs = new ConcurrentHashMap<>();
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        getLogger().info("RCONBlocker запущен. Режим: " + (blockAll ? "БЛОКИРОВКА ВСЕХ" : "БЕЛЫЙ СПИСОК"));
        
        try {
            interceptRCON();
            getLogger().info("Перехват RCON активирован");
        } catch (Exception e) {
            getLogger().severe("Не удалось перехватить RCON: " + e.getMessage());
        }
        
        getCommand("rconblocker").setExecutor(this);
    }
    
    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        blockAll = config.getBoolean("block-all", true);
        debug = config.getBoolean("debug", false);
        
        allowedIPs.clear();
        allowedIPs.addAll(config.getStringList("allowed-ips"));
        
        if (debug) {
            getLogger().info("Разрешенные IP: " + String.join(", ", allowedIPs));
        }
    }
    
    private void interceptRCON() throws Exception {
        Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        
        Object rconThread = null;
        try {
            rconThread = minecraftServer.getClass().getMethod("getRconThread").invoke(minecraftServer);
        } catch (Exception e) {
            getLogger().warning("RCON не активирован");
            return;
        }
        
        if (rconThread == null) {
            return;
        }
        
        Field serverSocketField = findField(rconThread.getClass(), "serverSocket", "a", "socket");
        if (serverSocketField == null) {
            getLogger().warning("Не найден ServerSocket в RCON");
            return;
        }
        
        serverSocketField.setAccessible(true);
        ServerSocket originalSocket = (ServerSocket) serverSocketField.get(rconThread);
        
        if (originalSocket == null) {
            getLogger().warning("ServerSocket равен null");
            return;
        }
        
        int port = originalSocket.getLocalPort();
        InetAddress bindAddr = originalSocket.getInetAddress();
        
        originalSocket.close();
        
        interceptor = new ServerSocketInterceptor(port, bindAddr, this);
        serverSocketField.set(rconThread, interceptor);
        
        getLogger().info("RCON перехвачен на порту " + port);
    }
    
    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
    
    public boolean isAllowed(String ip) {
        if (blockAll) {
            return false;
        }
        
        if (ip == null) {
            return false;
        }
        
        if (allowedIPs.contains(ip)) {
            return true;
        }
        
        if (ip.equals("127.0.0.1") && allowedIPs.contains("localhost")) {
            return true;
        }
        
        for (String allowed : allowedIPs) {
            if (allowed.equals("*")) {
                return true;
            }
            
            if (allowed.contains("/")) {
                if (isInSubnet(ip, allowed)) {
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
            int mask = Integer.parseInt(parts[1]);
            
            String[] ipParts = ip.split("\\.");
            String[] netParts = network.split("\\.");
            
            if (ipParts.length != 4 || netParts.length != 4) return false;
            
            int fullBytes = mask / 8;
            
            for (int i = 0; i < fullBytes; i++) {
                if (!ipParts[i].equals(netParts[i])) {
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    public void logBlock(String ip) {
        long now = System.currentTimeMillis();
        Long lastBlock = blockedIPs.get(ip);
        
        if (lastBlock == null || now - lastBlock > 60000) {
            getLogger().warning("БЛОКИРОВКА RCON от " + ip);
            
            String message = "§c[RCON] Блокировка от " + ip;
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOp())
                .forEach(p -> p.sendMessage(message));
        }
        
        blockedIPs.put(ip, now);
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                           @NotNull String label, @NotNull String[] args) {
        
        if (!sender.isOp()) {
            sender.sendMessage("§cНет прав");
            return true;
        }
        
        if (args.length == 0) {
            sender.sendMessage("§6RCONBlocker Команды:");
            sender.sendMessage("§f/rconblocker reload");
            sender.sendMessage("§f/rconblocker list");
            sender.sendMessage("§f/rconblocker add <IP>");
            sender.sendMessage("§f/rconblocker remove <IP>");
            sender.sendMessage("§f/rconblocker mode <whitelist|blockall>");
            sender.sendMessage("§f/rconblocker status");
            sender.sendMessage("§fТекущий режим: " + (blockAll ? "§cБЛОКИРОВКА ВСЕХ" : "§aБЕЛЫЙ СПИСОК"));
            return true;
        }
        
        String cmd = args[0].toLowerCase();
        
        switch (cmd) {
            case "reload":
                loadConfig();
                sender.sendMessage("§aКонфиг перезагружен");
                break;
                
            case "list":
                sender.sendMessage("§6Разрешенные IP:");
                if (allowedIPs.isEmpty()) {
                    sender.sendMessage("§cСписок пуст");
                } else {
                    int i = 1;
                    for (String ip : allowedIPs) {
                        sender.sendMessage("§e" + i + ". §f" + ip);
                        i++;
                    }
                }
                break;
                
            case "add":
                if (args.length < 2) {
                    sender.sendMessage("§c/rconblocker add <IP>");
                    return true;
                }
                
                String ipToAdd = args[1];
                if (allowedIPs.add(ipToAdd)) {
                    List<String> ips = new ArrayList<>(allowedIPs);
                    getConfig().set("allowed-ips", ips);
                    saveConfig();
                    sender.sendMessage("§aДобавлен IP: " + ipToAdd);
                } else {
                    sender.sendMessage("§cIP уже есть в списке");
                }
                break;
                
            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("§c/rconblocker remove <IP>");
                    return true;
                }
                
                String ipToRemove = args[1];
                if (allowedIPs.remove(ipToRemove)) {
                    List<String> ips = new ArrayList<>(allowedIPs);
                    getConfig().set("allowed-ips", ips);
                    saveConfig();
                    sender.sendMessage("§aУдален IP: " + ipToRemove);
                } else {
                    sender.sendMessage("§cIP не найден");
                }
                break;
                
            case "mode":
                if (args.length < 2) {
                    sender.sendMessage("§c/rconblocker mode <whitelist|blockall>");
                    return true;
                }
                
                String mode = args[1].toLowerCase();
                if (mode.equals("blockall")) {
                    blockAll = true;
                    sender.sendMessage("§cВключена блокировка всех");
                } else if (mode.equals("whitelist")) {
                    blockAll = false;
                    sender.sendMessage("§aВключен белый список");
                } else {
                    sender.sendMessage("§cДоступно: whitelist, blockall");
                    return true;
                }
                
                getConfig().set("block-all", blockAll);
                saveConfig();
                break;
                
            case "status":
                sender.sendMessage("§6Статус RCONBlocker:");
                sender.sendMessage("§fРежим: " + (blockAll ? "§cБлокировка всех" : "§aБелый список"));
                sender.sendMessage("§fРазрешенных IP: §e" + allowedIPs.size());
                sender.sendMessage("§fЗаблокированных IP: §e" + blockedIPs.size());
                break;
                
            case "debug":
                debug = !debug;
                getConfig().set("debug", debug);
                saveConfig();
                sender.sendMessage("§aDebug: " + (debug ? "ВКЛ" : "ВЫКЛ"));
                break;
                
            default:
                sender.sendMessage("§cНеизвестная команда");
                break;
        }
        
        return true;
    }
    
    @Override
    public void onDisable() {
        if (interceptor != null) {
            try {
                interceptor.close();
            } catch (IOException ignored) {}
        }
        getLogger().info("RCONBlocker выключен");
    }
    
    private static class ServerSocketInterceptor extends ServerSocket {
        private final RCONBlocker plugin;
        private final ServerSocket original;
        
        public ServerSocketInterceptor(int port, InetAddress bindAddr, RCONBlocker plugin) throws IOException {
            super(port, 50, bindAddr);
            this.plugin = plugin;
            this.original = null;
        }
        
        @Override
        public Socket accept() throws IOException {
            while (true) {
                Socket socket = super.accept();
                String ip = socket.getInetAddress().getHostAddress();
                
                if (plugin.isAllowed(ip)) {
                    if (plugin.debug) {
                        plugin.getLogger().info("Разрешено RCON от " + ip);
                    }
                    return socket;
                } else {
                    plugin.logBlock(ip);
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }
}
