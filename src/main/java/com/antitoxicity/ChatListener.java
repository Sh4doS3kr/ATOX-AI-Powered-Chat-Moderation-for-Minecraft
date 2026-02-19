package com.antitoxicity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles chat capture across all Paper versions (1.17.1 - 1.21.8+).
 * Registers BOTH legacy and modern listeners. Uses deduplication to avoid double-capture.
 */
public class ChatListener implements Listener {

    private final AntiToxicity plugin;
    private final Logger logger;

    // Deduplication: "playerName:message" -> timestamp of last capture
    private final Map<String, Long> recentCaptures = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 500;

    public ChatListener(AntiToxicity plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        tryRegisterModernChat();
    }

    /**
     * Legacy chat event — works on all versions.
     * ignoreCancelled = false: captures messages even if cancelled by chat formatting plugins.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        captureMessage(event.getPlayer(), event.getMessage(), "Legacy");
    }

    private void captureMessage(Player player, String message, String source) {
        if (player.hasPermission("antitoxicity.bypass")) {
            return;
        }

        String playerName = player.getName();

        // Deduplication: skip if same player+message was captured in the last 500ms
        String dedupeKey = playerName + ":" + message;
        long now = System.currentTimeMillis();
        Long lastCapture = recentCaptures.get(dedupeKey);
        if (lastCapture != null && (now - lastCapture) < DEDUP_WINDOW_MS) {
            return;
        }
        recentCaptures.put(dedupeKey, now);

        // Clean old dedup entries periodically
        if (recentCaptures.size() > 1000) {
            recentCaptures.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_WINDOW_MS * 2);
        }

        plugin.storeMessage(playerName, message);
        logger.info("[" + source + "] Captured from " + playerName + ": " + message);
    }

    /**
     * Try to register Paper's modern AsyncChatEvent via reflection (1.19+).
     * Does NOT disable the legacy listener — deduplication handles overlap.
     */
    private void tryRegisterModernChat() {
        try {
            Class<?> asyncChatEventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");

            Object proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                    plugin.getClass().getClassLoader(),
                    new Class[]{Listener.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("toString")) return "ATOXModernChatListener";
                        return null;
                    }
            );

            ChatListener self = this;

            plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends org.bukkit.event.Event>) asyncChatEventClass,
                    (Listener) proxyListener,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            Method getPlayer = event.getClass().getMethod("getPlayer");
                            Player player = (Player) getPlayer.invoke(event);

                            Method originalMsgMethod = event.getClass().getMethod("originalMessage");
                            Object component = originalMsgMethod.invoke(event);

                            Class<?> plainSerializerClass = Class.forName(
                                    "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                            Method plainText = plainSerializerClass.getMethod("plainText");
                            Object serializer = plainText.invoke(null);
                            Method serialize = serializer.getClass().getMethod("serialize",
                                    Class.forName("net.kyori.adventure.text.Component"));
                            String messageText = (String) serialize.invoke(serializer, component);

                            self.captureMessage(player, messageText, "Modern");
                        } catch (Exception e) {
                            logger.warning("[ATOX] Modern chat listener error: " + e.getMessage());
                        }
                    },
                    plugin,
                    false // ignoreCancelled = false -> capture even if cancelled
            );

            logger.info("[ATOX] Modern Paper AsyncChatEvent registered (1.19+).");
        } catch (ClassNotFoundException e) {
            logger.info("[ATOX] Using legacy AsyncPlayerChatEvent listener only.");
        } catch (Exception e) {
            logger.warning("[ATOX] Could not register modern chat listener: " + e.getMessage());
        }
    }
}
