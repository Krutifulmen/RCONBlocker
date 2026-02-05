package xyz.imperiumsmp.rcon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.rcon.RconClient;

import java.lang.reflect.Field;
import java.net.Socket;
import java.util.List;

public class RconInjector {

    public static void inject() {
        try {
            MinecraftServer server = MinecraftServer.getServer();

            Field rconThreadField = MinecraftServer.class.getDeclaredField("rconThread");
            rconThreadField.setAccessible(true);
            Object rconThread = rconThreadField.get(server);

            Field clientsField = rconThread.getClass().getDeclaredField("clients");
            clientsField.setAccessible(true);

            List<RconClient> clients = (List<RconClient>) clientsField.get(rconThread);

            new Thread(() -> {
                while (true) {
                    try {
                        for (RconClient client : clients) {
                            Field socketField = RconClient.class.getDeclaredField("socket");
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
}
