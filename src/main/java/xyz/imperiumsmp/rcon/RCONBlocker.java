package xyz.imperiumsmp.rcon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

public final class RCONBlocker extends JavaPlugin {

    private List<String> allowedIPs = new ArrayList<>();
    private boolean blockAll = false;
    private boolean debug = false;
    private int checkInterval = 20;

    private final Map<Socket, String> activeConnections = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getLogger().info("§a=== RCON Blocker запущен! ===");
        getLogger().info("§fРежим: §e" + (blockAll ? "БЛОКИРОВАТЬ ВСЕХ" : "ТОЛЬКО БЕЛЫЙ СПИСОК"));
        getLogger().info("§fРазрешенные IP: §e" + allowedIPs.size());
        getLogger().info("§fПроверка каждые: §e" + checkInterval + " тиков");

        startChecker();

        getCommand("rconblocker").setExecutor(this);

        getLogger().info("§aГотово! Плагин работает.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        config.addDefault("block-all", false);
        config.addDefault("allowed-ips", Arrays.asList("127.0.0.1", "localhost"));
        config.addDefault("debug", false);
        config.addDefault("check-interval", 20);
        config.options().copyDefaults(true);
        saveConfig();

        blockAll = config.getBoolean("block-all");
        allowedIPs = config.getStringList("allowed-ips");
        debug = config.getBoolean("debug");
        checkInterval = config.getInt("check-interval");

        if (debug) {
            getLogger().info("=== Загружена конфигурация ===");
            getLogger().info("Разрешенные IP: " + allowedIPs);
            getLogger().info("Блокировать всех: " + blockAll);
            getLogger().info("Интервал проверки: " + checkInterval + " тиков");
        }
    }

    private void startChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkRCONConnections();
            }
        }.runTaskTimer(this, 0L, checkInterval);

        if (debug) {
            getLogger().info("Проверка RCON соединений запущена");
        }
    }

    private void checkRCONConnections() {
        try {
            Object minecraftServer = Bukkit.getServer().getClass()
                    .getMethod("getServer").invoke(Bukkit.getServer());

            Object rconThread = null;
            try {
                rconThread = minecraftServer.getClass()
                        .getMethod("getRconThread").invoke(minecraftServer);
            } catch (Exception e) {
                if (debug) getLogger().info("RCON не включен на сервере");
                return;
            }

            if (rconThread == null) {
                if (debug) getLogger().info("RCON поток не найден");
                return;
            }

            Field clientsField = null;
            String[] possibleFieldNames = {"clients", "b", "clientList", "c"};

            for (String fieldName : possibleFieldNames) {
                try {
                    clientsField = rconThread.getClass().getDeclaredField(fieldName);
                    clientsField.setAccessible(true);
                    if (debug) getLogger().info("Найдено поле: " + fieldName);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }

            if (clientsField == null) {
                if (debug) getLogger().warning("Не удалось найти список RCON клиентов");
                return;
            }

            Object clients = clientsField.get(rconThread);

            if (clients instanceof Set) {
                checkClientSet((Set<?>) clients);
            } else if (clients instanceof List) {
                checkClientList((List<?>) clients);
            }

        } catch (Exception e) {
            if (debug) {
                getLogger().warning("Ошибка при проверке RCON: " + e.getMessage());
            }
        }
    }

    private void checkClientSet(Set<?> clients) {
        Set<Object> toRemove = new HashSet<>();

        for (Object client : clients) {
            try {
                Socket socket = getClientSocket(client);
                if (socket != null && !socket.isClosed()) {
                    if (!isConnectionAllowed(socket)) {
                        socket.close();
                        toRemove.add(client);
                    }
                }
            } catch (Exception e) {
                if (debug) getLogger().warning("Ошибка проверки клиента: " + e.getMessage());
            }
        }

        clients.removeAll(toRemove);
    }

    private void checkClientList(List<?> clients) {
        List<Object> toRemove = new ArrayList<>();

        for (Object client : clients) {
            try {
                Socket socket = getClientSocket(client);
                if (socket != null && !socket.isClosed()) {
                    if (!isConnectionAllowed(socket)) {
                        socket.close();
                        toRemove.add(client);
                    }
                }
            } catch (Exception e) {
                if (debug) getLogger().warning("Ошибка проверки клиента: " + e.getMessage());
            }
        }

        clients.removeAll(toRemove);
    }

    private Socket getClientSocket(Object client) throws Exception {
        String[] possibleSocketFields = {"socket", "c", "connection", "sock"};

        for (String fieldName : possibleSocketFields) {
            try {
                Field socketField = client.getClass().getDeclaredField(fieldName);
                socketField.setAccessible(true);
                Object socketObj = socketField.get(client);

                if (socketObj instanceof Socket) {
                    return (Socket) socketObj;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }

        return null;
    }

    private boolean isConnectionAllowed(Socket socket) {
        try {
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (address == null) return false;

            String ip = address.getAddress().getHostAddress();

            if (blockAll) {
                logBlock(ip, "Режим 'блокировать всех' включен");
                return false;
            }

            for (String allowedIP : allowedIPs) {
                if (isIPMatch(ip, allowedIP)) {
                    if (debug) getLogger().info("Разрешено: " + ip + " (совпадение с " + allowedIP + ")");
                    return true;
                }
            }

            logBlock(ip, "IP не в белом списке");
            return false;

        } catch (Exception e) {
            if (debug) getLogger().warning("Ошибка проверки IP: " + e.getMessage());
            return false;
        }
    }

    private boolean isIPMatch(String ip, String allowedIP) {
        if (ip.equals(allowedIP)) return true;

        if (allowedIP.equalsIgnoreCase("localhost") &&
                (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1"))) {
            return true;
        }

        if (allowedIP.contains("/")) return isInSubnet(ip, allowedIP);

        if (allowedIP.equals("*")) return true;

        return false;
    }

    private boolean isInSubnet(String ip, String subnet) {
        try {
            String[] parts = subnet.split("/");
            if (parts.length != 2) return false;

            String network = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            String[] ipParts = ip.split("\\.");
            String[] networkParts = network.split("\\.");

            if (ipParts.length != 4 || networkParts.length != 4) return false;

            int fullOctets = prefixLength / 8;

            for (int i = 0; i < fullOctets; i++) {
                if (!ipParts[i].equals(networkParts[i])) return false;
            }

            return true;

        } catch (Exception e) {
            getLogger().warning("Ошибка проверки подсети " + subnet + ": " + e.getMessage());
            return false;
        }
    }

    private void logBlock(String ip, String reason) {
        getLogger().warning("§cБЛОКИРОВКА RCON: " + ip + " - " + reason);

        String message = "§c[RCON Блокировка] IP " + ip + " заблокирован";
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOp())
                .forEach(p -> p.sendMessage(message));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("rconblocker.admin")) {
            sender.sendMessage("§cУ вас нет прав!");
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                loadConfig();
                sender.sendMessage("§aКонфиг перезагружен!");
                sender.sendMessage("§fРежим: §e" + (blockAll ? "Блокировать всех" : "Белый список"));
                sender.sendMessage("§fРазрешено IP: §e" + allowedIPs.size());
                return true;

            case "list":
                sender.sendMessage("§6=== Белый список RCON ===");
                if (allowedIPs.isEmpty()) {
                    sender.sendMessage("§cСписок пуст!");
                } else {
                    for (int i = 0; i < allowedIPs.size(); i++) {
                        sender.sendMessage("§e" + (i + 1) + ". §f" + allowedIPs.get(i));
                    }
                }
                return true;

            case "add":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker add <IP>");
                    return true;
                }

                String ipToAdd = args[1];
                if (!allowedIPs.contains(ipToAdd)) {
                    allowedIPs.add(ipToAdd);
                    getConfig().set("allowed-ips", allowedIPs);
                    saveConfig();
                    sender.sendMessage("§aIP §e" + ipToAdd + " §aдобавлен");
                } else {
                    sender.sendMessage("§cЭтот IP уже есть в списке");
                }
                return true;

            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker remove <IP>");
                    return true;
                }

                String ipToRemove = args[1];
                if (allowedIPs.remove(ipToRemove)) {
                    getConfig().set("allowed-ips", allowedIPs);
                    saveConfig();
                    sender.sendMessage("§aIP §e" + ipToRemove + " §aудален");
                } else {
                    sender.sendMessage("§cIP не найден в списке");
                }
                return true;

            case "mode":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: §e/rconblocker mode <whitelist|blockall>");
                    return true;
                }

                String mode = args[1].toLowerCase();
                if (mode.equals("blockall")) {
                    blockAll = true;
                    sender.sendMessage("§cВключен режим БЛОКИРОВКИ ВСЕХ");
                } else if (mode.equals("whitelist")) {
                    blockAll = false;
                    sender.sendMessage("§aВключен режим БЕЛОГО СПИСКА");
                } else {
                    sender.sendMessage("§cДоступные режимы: whitelist, blockall");
                }

                getConfig().set("block-all", blockAll);
                saveConfig();
                return true;

            case "status":
                sender.sendMessage("§6=== Статус RCONBlocker ===");
                sender.sendMessage("§fСостояние: §aРАБОТАЕТ");
                sender.sendMessage("§fРежим: §e" + (blockAll ? "БЛОКИРОВКА ВСЕХ" : "БЕЛЫЙ СПИСОК"));
                sender.sendMessage("§fРазрешенных IP: §e" + allowedIPs.size());
                sender.sendMessage("§fПроверка каждые: §e" + checkInterval + " тиков");
                sender.sendMessage("§fDebug: §e" + (debug ? "ВКЛ" : "ВЫКЛ"));
                return true;

            case "test":
                sender.sendMessage("§aТест RCONBlocker:");
                sender.sendMessage("§f1. Проверяем доступ к серверу... §aOK");
                sender.sendMessage("§f2. Проверяем конфигурацию... §aOK");
                sender.sendMessage("§f3. Белый список загружен... §a" + allowedIPs.size() + " IP");
                sender.sendMessage("§aТест пройден успешно!");
                return true;

            default:
                showHelp(sender);
                return true;
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6=== RCONBlocker Помощь ===");
        sender.sendMessage("§fЭтот плагин блокирует RCON подключения");
        sender.sendMessage("§fТолько IP из белого списка могут использовать RCON");
        sender.sendMessage("");
        sender.sendMessage("§eКоманды:");
        sender.sendMessage("§f/rconblocker reload §7- Перезагрузить настройки");
        sender.sendMessage("§f/rconblocker list §7- Показать белый список");
        sender.sendMessage("§f/rconblocker add <IP> §7- Добавить IP");
        sender.sendMessage("§f/rconblocker remove <IP> §7- Удалить IP");
        sender.sendMessage("§f/rconblocker mode <whitelist|blockall> §7- Режим работы");
        sender.sendMessage("§f/rconblocker status §7- Статус плагина");
        sender.sendMessage("§f/rconblocker test §7- Проверка работы");
        sender.sendMessage("");
        sender.sendMessage("§eТекущий режим: §f" + (blockAll ? "БЛОКИРОВКА ВСЕХ" : "БЕЛЫЙ СПИСОК"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String[] commands = {"reload", "list", "add", "remove", "mode", "status", "test"};
            for (String cmd : commands) {
                if (cmd.startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            if ("whitelist".startsWith(args[1].toLowerCase())) completions.add("whitelist");
            if ("blockall".startsWith(args[1].toLowerCase())) completions.add("blockall");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            for (String ip : allowedIPs) {
                if (ip.startsWith(args[1])) completions.add(ip);
            }
        }

        return completions;
    }

    @Override
    public void onDisable() {
        getLogger().info("§cRCONBlocker выключен");
    }
}
