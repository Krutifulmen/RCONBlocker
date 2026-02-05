package xyz.imperiumsmp.rcon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.logging.Level;

public final class RCONBlocker extends JavaPlugin {

    private final Set<String> allowedIPs = new HashSet<>();
    private boolean blockAll = false;
    private String kickMessage = "&cВаш IP-адрес не имеет доступа к RCON";
    private boolean debugMode = false;
    private final List<Socket> trackedSockets = Collections.synchronizedList(new ArrayList<>());
    private boolean useReflection = true;
    
    // Для хранения последних проверенных IP
    private final Map<String, Long> lastConnectionAttempt = new HashMap<>();
    private static final long CONNECTION_COOLDOWN = 5000; // 5 секунд

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        getLogger().info("§aRCONBlocker успешно запущен!");
        getLogger().info("§fРазрешенные IP-адреса: §e" + allowedIPs);
        getLogger().info("§fРежим: §e" + (blockAll ? "Блокировка всех" : "Белый список"));
        
        // Пробуем метод рефлексии
        if (useReflection) {
            setupReflectionInterceptor();
        }
        
        // Альтернативный метод - перехват через сетевой слой
        setupNetworkInterceptor();
        
        // Команды
        Objects.requireNonNull(getCommand("rconblocker")).setExecutor(this);
        
        getLogger().info("§aПлагин активирован. Используйте §e/rconblocker §aдля управления");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        config.addDefault("block-all", false);
        config.addDefault("allowed-ips", Arrays.asList("127.0.0.1", "192.168.1.1"));
        config.addDefault("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
        config.addDefault("debug", false);
        config.addDefault("use-reflection", true);
        config.options().copyDefaults(true);
        saveConfig();
        
        allowedIPs.clear();
        allowedIPs.addAll(config.getStringList("allowed-ips"));
        blockAll = config.getBoolean("block-all");
        kickMessage = config.getString("kick-message", "&cВаш IP-адрес не имеет доступа к RCON");
        debugMode = config.getBoolean("debug", false);
        useReflection = config.getBoolean("use-reflection", true);
        
        if (debugMode) {
            getLogger().info("Debug mode enabled");
            getLogger().info("Use reflection: " + useReflection);
        }
    }

    private void setupReflectionInterceptor() {
        getServer().getScheduler().runTaskTimer(this, this::checkRCONConnectionsViaReflection, 20L, 60L);
        
        if (debugMode) {
            getLogger().info("Reflection interceptor scheduled");
        }
    }

    private void setupNetworkInterceptor() {
        // Метод для отслеживания сокетов на уровне сервера
        getServer().getScheduler().runTaskTimer(this, this::checkAllSockets, 40L, 80L);
        
        if (debugMode) {
            getLogger().info("Network interceptor scheduled");
        }
    }

    private void checkRCONConnectionsViaReflection() {
        if (!useReflection) {
            return;
        }
        
        try {
            Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            
            // Пробуем разные имена для получения RCON
            Object rconThread = getRconThread(minecraftServer);
            
            if (rconThread == null) {
                if (debugMode) {
                    getLogger().info("RCON thread not found or RCON disabled");
                }
                return;
            }
            
            // Пробуем найти клиентов
            findAndCheckClients(rconThread);
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "Reflection error: " + e.getMessage(), e);
            }
        }
    }

    private Object getRconThread(Object minecraftServer) throws Exception {
        // Пробуем разные методы для получения RCON
        String[] methodNames = {"getRconThread", "az", "getRconConsoleSource"};
        
        for (String methodName : methodNames) {
            try {
                return minecraftServer.getClass().getMethod(methodName).invoke(minecraftServer);
            } catch (NoSuchMethodException ignored) {
                // Пробуем следующий метод
            }
        }
        return null;
    }

    private void findAndCheckClients(Object rconThread) {
        // Пробуем разные имена полей для клиентов
        String[] fieldNames = {"clients", "b", "clientList", "connections"};
        
        for (String fieldName : fieldNames) {
            try {
                Field clientsField = rconThread.getClass().getDeclaredField(fieldName);
                clientsField.setAccessible(true);
                Object clients = clientsField.get(rconThread);
                
                if (clients instanceof Collection) {
                    checkClientCollection((Collection<?>) clients);
                    return;
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                if (debugMode) {
                    getLogger().fine("Field '" + fieldName + "' not found: " + e.getMessage());
                }
            }
        }
        
        if (debugMode) {
            getLogger().info("Could not find clients field in RconThread");
        }
    }

    @SuppressWarnings("unchecked")
    private void checkClientCollection(Collection<?> clients) {
        List<Object> clientsToRemove = new ArrayList<>();
        
        for (Object client : clients) {
            try {
                Socket socket = getSocketFromClient(client);
                if (socket != null && !socket.isClosed()) {
                    InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
                    if (address != null) {
                        String ip = address.getAddress().getHostAddress();
                        
                        if (!isIPAllowed(ip)) {
                            getLogger().warning("§cБлокируем RCON соединение с IP: " + ip);
                            socket.close();
                            clientsToRemove.add(client);
                            
                            // Записываем в лог попытку подключения
                            logConnectionAttempt(ip, false);
                        } else if (debugMode) {
                            getLogger().info("§aРазрешено RCON соединение с IP: " + ip);
                            logConnectionAttempt(ip, true);
                        }
                    }
                }
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().log(Level.FINE, "Error checking client: " + e.getMessage());
                }
            }
        }
        
        // Удаляем заблокированных клиентов
        if (clients instanceof List) {
            ((List<Object>) clients).removeAll(clientsToRemove);
        } else {
            clients.removeAll(clientsToRemove);
        }
    }

    private Socket getSocketFromClient(Object client) {
        // Пробуем разные имена полей для сокета
        String[] socketFieldNames = {"socket", "c", "connection", "sock"};
        
        for (String fieldName : socketFieldNames) {
            try {
                Field socketField = client.getClass().getDeclaredField(fieldName);
                socketField.setAccessible(true);
                Object socketObj = socketField.get(client);
                
                if (socketObj instanceof Socket) {
                    return (Socket) socketObj;
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                if (debugMode) {
                    getLogger().fine("Socket field '" + fieldName + "' not found: " + e.getMessage());
                }
            }
        }
        
        return null;
    }

    private void checkAllSockets() {
        // Этот метод отслеживает все активные сокеты на сервере
        try {
            // Получаем все потоки
            Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
            
            for (Thread thread : threadSet) {
                if (thread.getName().contains("RCON") || thread.getName().contains("rcon")) {
                    checkThreadForSocket(thread);
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "Error checking sockets: " + e.getMessage());
            }
        }
    }

    private void checkThreadForSocket(Thread thread) {
        try {
            // Пробуем получить сокет из потока
            Field[] fields = thread.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (Socket.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Socket socket = (Socket) field.get(thread);
                    
                    if (socket != null && !socket.isClosed()) {
                        checkAndBlockSocket(socket);
                    }
                }
            }
            
            // Проверяем также runnable, если есть
            Field targetField;
            try {
                targetField = thread.getClass().getDeclaredField("target");
            } catch (NoSuchFieldException e) {
                targetField = thread.getClass().getDeclaredField("runnable");
            }
            
            targetField.setAccessible(true);
            Object runnable = targetField.get(thread);
            
            if (runnable != null) {
                checkRunnableForSocket(runnable);
            }
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.FINE, "Error checking thread " + thread.getName() + ": " + e.getMessage());
            }
        }
    }

    private void checkRunnableForSocket(Object runnable) {
        try {
            Field[] fields = runnable.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (Socket.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Socket socket = (Socket) field.get(runnable);
                    
                    if (socket != null && !socket.isClosed()) {
                        checkAndBlockSocket(socket);
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.FINE, "Error checking runnable: " + e.getMessage());
            }
        }
    }

    private void checkAndBlockSocket(Socket socket) {
        try {
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (address == null) return;
            
            String ip = address.getAddress().getHostAddress();
            int port = address.getPort();
            
            // Проверяем, не проверяли ли мы этот сокет недавно
            String socketKey = ip + ":" + port;
            if (trackedSockets.contains(socket)) {
                return;
            }
            
            trackedSockets.add(socket);
            
            // Проверяем разрешен ли IP
            if (!isIPAllowed(ip)) {
                getLogger().warning("§c[СЕТЬ] Блокируем RCON соединение с " + ip + ":" + port);
                socket.close();
                logConnectionAttempt(ip, false);
            } else if (debugMode) {
                getLogger().info("§a[СЕТЬ] Разрешено соединение с " + ip + ":" + port);
                logConnectionAttempt(ip, true);
            }
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.FINE, "Error checking socket: " + e.getMessage());
            }
        }
    }

    private boolean isIPAllowed(String ip) {
        if (blockAll) {
            return false;
        }
        
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        // Проверяем кд на частые подключения
        Long lastAttempt = lastConnectionAttempt.get(ip);
        if (lastAttempt != null && System.currentTimeMillis() - lastAttempt < CONNECTION_COOLDOWN) {
            if (debugMode) {
                getLogger().info("IP " + ip + " is in cooldown");
            }
            return false;
        }
        
        for (String allowedIP : allowedIPs) {
            if (allowedIP.equals(ip) || "*".equals(allowedIP)) {
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
            getLogger().warning("Ошибка при проверке подсети " + subnet + ": " + e.getMessage());
            return false;
        }
    }

    private void logConnectionAttempt(String ip, boolean allowed) {
        lastConnectionAttempt.put(ip, System.currentTimeMillis());
        
        if (debugMode) {
            String status = allowed ? "§aРАЗРЕШЕНО" : "§cЗАБЛОКИРОВАНО";
            getLogger().info(status + " §fПодключение от " + ip);
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
        
        String subCommand = args[0].toLowerCase();
        
        if (!sender.hasPermission("rconblocker.reload")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }
        
        switch (subCommand) {
            case "reload":
                loadConfig();
                sender.sendMessage("§aКонфигурация перезагружена!");
                sender.sendMessage("§fРазрешенные IP (§e" + allowedIPs.size() + "§f)");
                sender.sendMessage("§fБлокировка всех: §e" + (blockAll ? "ВКЛ" : "ВЫКЛ"));
                sender.sendMessage("§fDebug: §e" + (debugMode ? "ВКЛ" : "ВЫКЛ"));
                sender.sendMessage("§fReflection: §e" + (useReflection ? "ВКЛ" : "ВЫКЛ"));
                return true;
                
            case "list":
                sender.sendMessage("§6=== RCONBlocker Whitelist ===");
                sender.sendMessage("§fВсего IP: §e" + allowedIPs.size());
                sender.sendMessage("§fБлокировка всех: §e" + (blockAll ? "ВКЛ" : "ВЫКЛ"));
                
                if (!allowedIPs.isEmpty()) {
                    sender.sendMessage("§fРазрешенные IP:");
                    int i = 1;
                    for (String ip : allowedIPs) {
                        sender.sendMessage("  §e" + i + ". §f" + ip);
                        i++;
                    }
                } else {
                    sender.sendMessage("§cСписок пуст! Добавьте IP командой /rconblocker add");
                }
                return true;
                
            case "add":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker add <IP>");
                    return true;
                }
                
                String ipToAdd = args[1];
                if (allowedIPs.add(ipToAdd)) {
                    List<String> currentIPs = getConfig().getStringList("allowed-ips");
                    currentIPs.add(ipToAdd);
                    getConfig().set("allowed-ips", currentIPs);
                    saveConfig();
                    
                    sender.sendMessage("§aIP §e" + ipToAdd + " §aдобавлен");
                } else {
                    sender.sendMessage("§cIP §e" + ipToAdd + " §cуже есть в списке");
                }
                return true;
                
            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker remove <IP>");
                    return true;
                }
                
                String ipToRemove = args[1];
                if (allowedIPs.remove(ipToRemove)) {
                    List<String> currentIPs = getConfig().getStringList("allowed-ips");
                    currentIPs.remove(ipToRemove);
                    getConfig().set("allowed-ips", currentIPs);
                    saveConfig();
                    
                    sender.sendMessage("§aIP §e" + ipToRemove + " §aудален");
                } else {
                    sender.sendMessage("§cIP §e" + ipToRemove + " §cне найден");
                }
                return true;
                
            case "debug":
                debugMode = !debugMode;
                getConfig().set("debug", debugMode);
                saveConfig();
                
                sender.sendMessage("§aDebug mode: §e" + (debugMode ? "ВКЛ" : "ВЫКЛ"));
                if (debugMode) {
                    sender.sendMessage("§7Детальные логи будут отображаться в консоли");
                }
                return true;
                
            case "test":
                // Тестовая команда для проверки работы
                sender.sendMessage("§6=== RCONBlocker Test ===");
                sender.sendMessage("§fПлагин активен: §a✓");
                sender.sendMessage("§fРежим блокировки: §e" + (blockAll ? "ALL" : "WHITELIST"));
                sender.sendMessage("§fРазрешенных IP: §e" + allowedIPs.size());
                sender.sendMessage("§fDebug mode: §e" + (debugMode ? "ON" : "OFF"));
                sender.sendMessage("§fReflection: §e" + (useReflection ? "ON" : "OFF"));
                sender.sendMessage("§fОтслеживаемых сокетов: §e" + trackedSockets.size());
                return true;
                
            default:
                sendHelp(sender);
                return true;
        }
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== RCONBlocker v" + getDescription().getVersion() + " ===");
        sender.sendMessage("§fУправление доступом к RCON");
        sender.sendMessage("§e/rconblocker reload §f- Перезагрузить настройки");
        sender.sendMessage("§e/rconblocker list §f- Показать белый список");
        sender.sendMessage("§e/rconblocker add <IP> §f- Добавить IP");
        sender.sendMessage("§e/rconblocker remove <IP> §f- Удалить IP");
        sender.sendMessage("§e/rconblocker debug §f- Вкл/Выкл debug");
        sender.sendMessage("§e/rconblocker test §f- Проверка работы");
        sender.sendMessage("§fТекущий режим: §e" + (blockAll ? "БЛОКИРОВКА ВСЕХ" : "БЕЛЫЙ СПИСОК"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                    @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            String[] commands = {"reload", "list", "add", "remove", "debug", "test"};
            
            for (String cmd : commands) {
                if (cmd.startsWith(input)) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            String input = args[1].toLowerCase();
            for (String ip : allowedIPs) {
                if (ip.toLowerCase().startsWith(input)) {
                    completions.add(ip);
                }
            }
        }
        
        return completions;
    }

    @Override
    public void onDisable() {
        // Очищаем отслеживаемые сокеты
        synchronized (trackedSockets) {
            for (Socket socket : trackedSockets) {
                try {
                    if (!socket.isClosed()) {
                        socket.close();
                    }
                } catch (Exception ignored) {}
            }
            trackedSockets.clear();
        }
        
        getLogger().info("§cRCONBlocker выключен");
    }
}
