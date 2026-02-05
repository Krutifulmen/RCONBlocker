package xyz.imperiumsmp.rcon;

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
    private boolean debugMode = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        getServer().getPluginManager().registerEvents(this, this);
        
        getLogger().info("RCONBlocker успешно запущен!");
        getLogger().info("Разрешенные IP-адреса: " + allowedIPs);
        
        setupRCONInterceptor();
        
        // Регистрируем команду
        getCommand("rconblocker").setExecutor(this);
    }

    @Override
    public void onLoad() {
        // Создаем конфиг если его нет
        saveDefaultConfig();
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        config.addDefault("block-all", false);
        config.addDefault("allowed-ips", List.of("127.0.0.1", "192.168.1.1"));
        config.addDefault("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
        config.addDefault("debug", false);
        config.options().copyDefaults(true);
        saveConfig();
        
        allowedIPs.clear();
        allowedIPs.addAll(config.getStringList("allowed-ips"));
        blockAll = config.getBoolean("block-all");
        kickMessage = config.getString("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
        debugMode = config.getBoolean("debug", false);
        
        if (debugMode) {
            getLogger().info("Debug mode enabled");
        }
    }

    private void setupRCONInterceptor() {
        getLogger().info("Настройка перехватчика RCON соединений...");
        
        // Запускаем периодическую проверку соединений
        getServer().getScheduler().runTaskTimer(this, this::checkRCONConnections, 0L, 100L); // Каждые 5 секунд
        
        getLogger().info("Перехватчик RCON активирован");
    }

    private void checkRCONConnections() {
        try {
            // Получаем MinecraftServer
            Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            
            // Получаем RconThread
            Object rconThread = null;
            try {
                rconThread = minecraftServer.getClass().getMethod("getRconThread").invoke(minecraftServer);
            } catch (NoSuchMethodException e) {
                // Попробуем альтернативный метод
                rconThread = minecraftServer.getClass().getMethod("az").invoke(minecraftServer);
            }
            
            if (rconThread == null) {
                if (debugMode) {
                    getLogger().info("RCON не активирован на сервере");
                }
                return;
            }
            
            // Получаем клиентов RCON
            Object rconClientList = null;
            try {
                java.lang.reflect.Field clientsField = rconThread.getClass().getDeclaredField("b");
                clientsField.setAccessible(true);
                rconClientList = clientsField.get(rconThread);
            } catch (NoSuchFieldException e) {
                try {
                    java.lang.reflect.Field clientsField = rconThread.getClass().getDeclaredField("clients");
                    clientsField.setAccessible(true);
                    rconClientList = clientsField.get(rconThread);
                } catch (NoSuchFieldException e2) {
                    if (debugMode) {
                        getLogger().warning("Не удалось найти поле clients в RconThread");
                    }
                    return;
                }
            }
            
            if (rconClientList instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> clients = (java.util.List<Object>) rconClientList;
                
                for (int i = clients.size() - 1; i >= 0; i--) {
                    Object client = clients.get(i);
                    checkClientConnection(client, clients, i);
                }
            } else if (rconClientList instanceof java.util.Set) {
                @SuppressWarnings("unchecked")
                java.util.Set<Object> clients = (java.util.Set<Object>) rconClientList;
                java.util.Set<Object> toRemove = new java.util.HashSet<>();
                
                for (Object client : clients) {
                    if (checkClientConnection(client, null, -1)) {
                        toRemove.add(client);
                    }
                }
                
                clients.removeAll(toRemove);
            }
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "Ошибка при проверке RCON соединений: " + e.getMessage(), e);
            }
        }
    }

    private boolean checkClientConnection(Object client, java.util.List<Object> clientList, int index) {
        try {
            // Получаем сокет клиента
            java.lang.reflect.Field socketField;
            try {
                socketField = client.getClass().getDeclaredField("c");
            } catch (NoSuchFieldException e) {
                try {
                    socketField = client.getClass().getDeclaredField("socket");
                } catch (NoSuchFieldException e2) {
                    if (debugMode) {
                        getLogger().warning("Не удалось найти поле socket в RconClient");
                    }
                    return false;
                }
            }
            socketField.setAccessible(true);
            
            java.net.Socket socket = (java.net.Socket) socketField.get(client);
            
            if (socket == null || socket.isClosed()) {
                return false;
            }
            
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (address == null) {
                return false;
            }
            
            String ip = address.getAddress().getHostAddress();
            
            if (!isIPAllowed(ip)) {
                getLogger().warning("Блокируем RCON соединение с IP: " + ip);
                try {
                    socket.close();
                } catch (Exception e) {
                    // Игнорируем ошибки закрытия
                }
                
                // Удаляем клиента из списка
                if (clientList != null && index >= 0) {
                    clientList.remove(index);
                }
                
                return true;
            } else if (debugMode) {
                getLogger().info("Разрешено RCON соединение с IP: " + ip);
            }
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "Ошибка при проверке клиента RCON: " + e.getMessage(), e);
            }
        }
        return false;
    }

    private boolean isIPAllowed(String ip) {
        if (blockAll) {
            if (debugMode) {
                getLogger().info("Block-all mode enabled, denying IP: " + ip);
            }
            return false;
        }
        
        if (ip == null) {
            return false;
        }
        
        for (String allowedIP : allowedIPs) {
            if (allowedIP.equals(ip)) {
                return true;
            }
            
            if ("*".equals(allowedIP)) {
                return true;
            }
            
            if (allowedIP.contains("/")) {
                try {
                    if (isInSubnet(ip, allowedIP)) {
                        return true;
                    }
                } catch (Exception e) {
                    getLogger().warning("Ошибка при проверке подсети " + allowedIP + ": " + e.getMessage());
                }
            }
        }
        
        if (debugMode) {
            getLogger().info("IP " + ip + " не найден в белом списке");
        }
        return false;
    }

    private boolean isInSubnet(String ip, String subnet) {
        try {
            String[] parts = subnet.split("/");
            if (parts.length != 2) {
                return false;
            }
            
            String network = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);
            
            java.net.InetAddress ipAddr = java.net.InetAddress.getByName(ip);
            java.net.InetAddress networkAddr = java.net.InetAddress.getByName(network);
            
            byte[] ipBytes = ipAddr.getAddress();
            byte[] networkBytes = networkAddr.getAddress();
            
            if (ipBytes.length != networkBytes.length) {
                return false;
            }
            
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            
            // Проверяем полные байты
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            
            // Проверяем оставшиеся биты
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            
            return true;
            
        } catch (Exception e) {
            getLogger().warning("Ошибка при проверке подсети " + subnet + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                           @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("rconblocker")) {
            return false;
        }
        
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("rconblocker.reload")) {
                    sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                    return true;
                }
                
                loadConfig();
                sender.sendMessage("§aКонфигурация RCONBlocker перезагружена!");
                sender.sendMessage("§fРазрешенные IP (§e" + allowedIPs.size() + "§f): §e" + 
                                 String.join(", ", allowedIPs));
                sender.sendMessage("§fРежим блокировки всех: §e" + (blockAll ? "Включен" : "Выключен"));
                sender.sendMessage("§fDebug mode: §e" + (debugMode ? "Включен" : "Выключен"));
                return true;
                
            case "list":
                if (!sender.hasPermission("rconblocker.reload")) {
                    sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                    return true;
                }
                
                sender.sendMessage("§6=== RCONBlocker Whitelist ===");
                sender.sendMessage("§fВсего IP: §e" + allowedIPs.size());
                sender.sendMessage("§fБлокировка всех: §e" + (blockAll ? "Включена" : "Выключена"));
                sender.sendMessage("§fDebug mode: §e" + (debugMode ? "Включен" : "Выключен"));
                
                if (!allowedIPs.isEmpty()) {
                    sender.sendMessage("§fРазрешенные IP:");
                    int i = 1;
                    for (String ip : allowedIPs) {
                        sender.sendMessage("§e" + i + ". §f" + ip);
                        i++;
                    }
                } else {
                    sender.sendMessage("§cСписок разрешенных IP пуст!");
                }
                return true;
                
            case "add":
                if (!sender.hasPermission("rconblocker.reload")) {
                    sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                    return true;
                }
                
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker add <IP>");
                    return true;
                }
                
                String ipToAdd = args[1];
                if (allowedIPs.add(ipToAdd)) {
                    // Сохраняем в конфиг
                    List<String> currentIPs = getConfig().getStringList("allowed-ips");
                    currentIPs.add(ipToAdd);
                    getConfig().set("allowed-ips", currentIPs);
                    saveConfig();
                    
                    sender.sendMessage("§aIP §e" + ipToAdd + " §aдобавлен в белый список");
                } else {
                    sender.sendMessage("§cIP §e" + ipToAdd + " §cуже есть в белом списке");
                }
                return true;
                
            case "remove":
                if (!sender.hasPermission("rconblocker.reload")) {
                    sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                    return true;
                }
                
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker remove <IP>");
                    return true;
                }
                
                String ipToRemove = args[1];
                if (allowedIPs.remove(ipToRemove)) {
                    // Сохраняем в конфиг
                    List<String> currentIPs = getConfig().getStringList("allowed-ips");
                    currentIPs.remove(ipToRemove);
                    getConfig().set("allowed-ips", currentIPs);
                    saveConfig();
                    
                    sender.sendMessage("§aIP §e" + ipToRemove + " §aудален из белого списка");
                } else {
                    sender.sendMessage("§cIP §e" + ipToRemove + " §cне найден в белом списке");
                }
                return true;
                
            case "debug":
                if (!sender.hasPermission("rconblocker.reload")) {
                    sender.sendMessage("§cУ вас нет прав на использование этой команды!");
                    return true;
                }
                
                debugMode = !debugMode;
                getConfig().set("debug", debugMode);
                saveConfig();
                
                sender.sendMessage("§aDebug mode " + (debugMode ? "§eвключен" : "§eвыключен"));
                return true;
                
            default:
                sendHelp(sender);
                return true;
        }
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== RCONBlocker v" + getDescription().getVersion() + " ===");
        sender.sendMessage("§fКоманды:");
        sender.sendMessage("§e/rconblocker reload §f- Перезагрузить конфигурацию");
        sender.sendMessage("§e/rconblocker list §f- Показать белый список");
        sender.sendMessage("§e/rconblocker add <IP> §f- Добавить IP в белый список");
        sender.sendMessage("§e/rconblocker remove <IP> §f- Удалить IP из белого списка");
        sender.sendMessage("§e/rconblocker debug §f- Включить/выключить debug mode");
        sender.sendMessage("§fТекущий режим: §e" + (blockAll ? "Блокировка всех" : "Белый список"));
        sender.sendMessage("§fDebug mode: §e" + (debugMode ? "Включен" : "Выключен"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                    @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new java.util.ArrayList<>();
            String input = args[0].toLowerCase();
            
            if ("reload".startsWith(input)) completions.add("reload");
            if ("list".startsWith(input)) completions.add("list");
            if ("add".startsWith(input)) completions.add("add");
            if ("remove".startsWith(input)) completions.add("remove");
            if ("debug".startsWith(input)) completions.add("debug");
            
            return completions;
        }
        
        return new java.util.ArrayList<>();
    }

    @Override
    public void onDisable() {
        getLogger().info("RCONBlocker выключен");
    }
          }
