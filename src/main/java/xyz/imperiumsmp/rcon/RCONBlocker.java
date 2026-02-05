package xyz.imperiumsmp.rcon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

public final class RCONBlocker extends JavaPlugin {

    private Set<String> allowedIPs = new HashSet<>();
    private boolean blockAll = true;
    private boolean debug = false;
    private int checkInterval = 10;
    private Map<String, Integer> connectionAttempts = new HashMap<>();
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        getLogger().info("RCONBlocker запущен. Режим: " + (blockAll ? "Блокировка всех" : "Белый список"));
        
        startMonitoring();
        
        getCommand("rconblocker").setExecutor(this);
    }
    
    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        blockAll = config.getBoolean("block-all", true);
        debug = config.getBoolean("debug", false);
        checkInterval = config.getInt("check-interval", 10);
        
        allowedIPs.clear();
        allowedIPs.addAll(config.getStringList("allowed-ips"));
        
        if (debug) {
            getLogger().info("Разрешенные IP: " + String.join(", ", allowedIPs));
        }
    }
    
    private void startMonitoring() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    blockUnauthorizedConnections();
                } catch (Exception e) {
                    if (debug) {
                        getLogger().warning("Ошибка мониторинга: " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(this, 0L, checkInterval);
    }
    
    private void blockUnauthorizedConnections() {
        try {
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            
            Object rconThread = null;
            try {
                rconThread = server.getClass().getMethod("getRconThread").invoke(server);
            } catch (Exception e) {
                return;
            }
            
            if (rconThread == null) {
                return;
            }
            
            Field clientsField = findField(rconThread.getClass(), "clients", "b", "clientList", "c");
            if (clientsField == null) {
                return;
            }
            
            clientsField.setAccessible(true);
            Object clients = clientsField.get(rconThread);
            
            if (clients instanceof Collection) {
                processClients((Collection<?>) clients);
            }
            
        } catch (Exception e) {
            if (debug) {
                getLogger().warning("Ошибка: " + e.getMessage());
            }
        }
    }
    
    private void processClients(Collection<?> clients) {
        List<Object> toRemove = new ArrayList<>();
        
        for (Object client : clients) {
            try {
                Socket socket = getSocket(client);
                if (socket == null || socket.isClosed()) {
                    continue;
                }
                
                InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
                if (address == null) {
                    continue;
                }
                
                String ip = address.getAddress().getHostAddress();
                
                if (!isIPAllowed(ip)) {
                    blockConnection(socket, ip, client);
                    toRemove.add(client);
                }
                
            } catch (Exception e) {
                if (debug) {
                    getLogger().warning("Ошибка обработки клиента: " + e.getMessage());
                }
            }
        }
        
        try {
            if (clients instanceof List) {
                ((List<?>) clients).removeAll(toRemove);
            } else {
                clients.removeAll(toRemove);
            }
        } catch (Exception e) {
        }
    }
    
    private Socket getSocket(Object client) throws Exception {
        Field socketField = findField(client.getClass(), "socket", "c", "connection", "sock");
        if (socketField == null) {
            return null;
        }
        
        socketField.setAccessible(true);
        Object socketObj = socketField.get(client);
        
        if (socketObj instanceof Socket) {
            return (Socket) socketObj;
        }
        
        return null;
    }
    
    private Field findField(Class<?> clazz, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
            }
        }
        return null;
    }
    
    private boolean isIPAllowed(String ip) {
        if (blockAll) {
            return false;
        }
        
        if (ip == null) {
            return false;
        }
        
        for (String allowed : allowedIPs) {
            if (allowed.equals(ip) || allowed.equals("*")) {
                return true;
            }
            if (allowed.equalsIgnoreCase("localhost") && 
                (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1"))) {
                return true;
            }
            if (allowed.contains("/")) {
                if (checkSubnet(ip, allowed)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean checkSubnet(String ip, String subnet) {
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
    
    private void blockConnection(Socket socket, String ip, Object client) {
        try {
            socket.close();
            
            int attempts = connectionAttempts.getOrDefault(ip, 0) + 1;
            connectionAttempts.put(ip, attempts);
            
            getLogger().warning("Блокировка RCON от " + ip + " (попытка " + attempts + ")");
            
            String message = "§c[RCON] Блокировка от " + ip;
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOp())
                .forEach(p -> p.sendMessage(message));
                
        } catch (IOException e) {
        }
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
            sender.sendMessage("§f/rconblocker reload - Перезагрузить");
            sender.sendMessage("§f/rconblocker list - Список IP");
            sender.sendMessage("§f/rconblocker add <IP> - Добавить IP");
            sender.sendMessage("§f/rconblocker remove <IP> - Удалить IP");
            sender.sendMessage("§f/rconblocker mode <whitelist|blockall> - Режим");
            sender.sendMessage("§f/rconblocker status - Статус");
            sender.sendMessage("§fТекущий режим: " + (blockAll ? "§cБлокировка всех" : "§aБелый список"));
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
                sender.sendMessage("§fПопыток подключений: §e" + connectionAttempts.size());
                break;
                
            default:
                sender.sendMessage("§cНеизвестная команда");
                break;
        }
        
        return true;
    }
    
    @Override
    public void onDisable() {
        getLogger().info("RCONBlocker выключен");
    }
}
