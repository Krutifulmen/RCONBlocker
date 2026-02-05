package xyz.imperiumsmp.rcon;

import net.minecraft.server.MinecraftServer;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.List;

public class RconInjector {

    public static void inject() {
        try {
            MinecraftServer server = MinecraftServer.getServer();

            Object rconThread = findRconThread(server);
            if (rconThread == null) {
                RconBlockerPlugin.getInstance().getLogger().severe("Failed to find RCON thread on server");
                return;
            }

            Field clientsField = rconThread.getClass().getDeclaredField("clients");
            clientsField.setAccessible(true);

            List<?> clients = (List<?>) clientsField.get(rconThread);

            new Thread(() -> {
                while (true) {
                    try {
                        for (Object client : clients) {
                            Field socketField = client.getClass().getDeclaredField("socket");
                            socketField.setAccessible(true);

                            Socket socket = (Socket) socketField.get(client);
                            String ip = socket.getInetAddress().getHostAddress();

                            if (!RconBlockerPlugin.getInstance().getAllowedIps().contains(ip)) {
                                RconBlockerPlugin.getInstance().getLogger()
                                        .warning("Blocked RCON connection from " + ip);
                                socket.close();
                            }
                        }
                        Thread.sleep(1000);
                    } catch (Exception ignored) {}
                }
            }, "RCON-IP-Checker").start();

        } catch (Exception e) {
            RconBlockerPlugin.getInstance().getLogger().severe("Failed to hook RCON");
            e.printStackTrace();
        }
    }

    private static Object findRconThread(MinecraftServer server) {
        for (Field field : MinecraftServer.class.getDeclaredFields()) {
            String fieldName = field.getName().toLowerCase();
            String typeName = field.getType().getName().toLowerCase();
            if (!fieldName.contains("rcon") && !typeName.contains("rcon")) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(server);
                if (value == null) {
                    continue;
                }
                Field clientsField = value.getClass().getDeclaredField("clients");
                clientsField.setAccessible(true);
                return value;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
