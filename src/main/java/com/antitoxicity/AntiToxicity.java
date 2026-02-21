package com.antitoxicity;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class AntiToxicity extends JavaPlugin implements Listener {

    // ---- DIRECT MESSAGE STORAGE (never recreated) ----
    private final List<StoredMsg> allMessages = new CopyOnWriteArrayList<>();
    private volatile long lastAnalysisTime = 0;

    private GeminiAnalyzer geminiAnalyzer;
    private DiscordWebhook discordWebhook;
    private BukkitTask analysisTask;
    private long maxAgeMillis;
    private SanctionTracker sanctionTracker;

    // ---- Public API for ChatListener ----
    public void storeMessage(String playerName, String message) {
        allMessages.add(new StoredMsg(playerName, message, System.currentTimeMillis()));
        getLogger().info("STORED from " + playerName
                + " | total=" + allMessages.size() + " | msg=" + message);
    }

    public Map<String, List<String>> getMessagesForAnalysis() {
        long since = lastAnalysisTime;

        getLogger().info("getMessages: since=" + since + " total=" + allMessages.size());

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (StoredMsg sm : allMessages) {
            if (sm.timestamp > since) {
                result.computeIfAbsent(sm.playerName, k -> new ArrayList<>()).add(sm.message);
            }
        }

        int count = 0;
        for (List<String> v : result.values()) count += v.size();
        getLogger().info("Found " + count + " messages from "
                + result.size() + " players (not yet consumed)");

        return result;
    }

    /** Only call this after a SUCCESSFUL API response */
    public void markAnalysisComplete() {
        lastAnalysisTime = System.currentTimeMillis();
        getLogger().info("Analysis timestamp advanced. Messages consumed.");
    }

    public SanctionTracker getSanctionTracker() { return sanctionTracker; }

    /** Returns the last N messages per player that were already consumed (before current cycle) */
    public Map<String, List<String>> getContextMessages(java.util.Set<String> players, int maxPerPlayer) {
        Map<String, List<String>> context = new LinkedHashMap<>();
        for (String player : players) {
            List<String> msgs = new ArrayList<>();
            for (StoredMsg sm : allMessages) {
                if (sm.playerName.equalsIgnoreCase(player) && sm.timestamp <= lastAnalysisTime) {
                    msgs.add(sm.message);
                }
            }
            int from = Math.max(0, msgs.size() - maxPerPlayer);
            context.put(player, new ArrayList<>(msgs.subList(from, msgs.size())));
        }
        return context;
    }

    public int storedMessageCount() {
        return allMessages.size();
    }

    public int storedPlayerCount() {
        Set<String> p = new HashSet<>();
        for (StoredMsg sm : allMessages) p.add(sm.playerName);
        return p.size();
    }

    private void purgeOldMessages() {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        int before = allMessages.size();
        allMessages.removeIf(sm -> sm.timestamp < cutoff);
        int after = allMessages.size();
        if (before != after) {
            getLogger().info("Purged " + (before - after) + " old messages. Remaining: " + after);
        }
    }

    // ---- Inner class ----
    static final class StoredMsg {
        final String playerName;
        final String message;
        final long timestamp;
        StoredMsg(String playerName, String message, long timestamp) {
            this.playerName = playerName;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    // ---- Plugin lifecycle ----
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlugin();

        ChatListener chatListener = new ChatListener(this);
        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("ATOX enabled! Server type: "
                + getConfig().getString("server-type", "UNKNOWN"));
    }

    @Override
    public void onDisable() {
        if (analysisTask != null) {
            analysisTask.cancel();
        }
        getLogger().info("ATOX disabled.");
    }

    private void loadPlugin() {
        if (analysisTask != null) {
            analysisTask.cancel();
        }

        String apiKey = getConfig().getString("gemini.api-key", "");
        String model = getConfig().getString("gemini.model", "gemini-1.5-flash");
        String fallbackModel = getConfig().getString("gemini.fallback-model", "gemini-flash-3-preview");
        String webhookUrl = getConfig().getString("discord-webhook", "");
        String serverName = getConfig().getString("server-name", "Unknown");
        String serverType = getConfig().getString("server-type", "SURVIVAL");
        int intervalMinutes = getConfig().getInt("analysis-interval-minutes", 15);
        int maxAgeHours = getConfig().getInt("message-max-age-hours", 24);
        String muteDuration = getConfig().getString("durations.mute", "1h");
        String banDuration = getConfig().getString("durations.ban", "1d");

        maxAgeMillis = maxAgeHours * 3600L * 1000L;

        if (apiKey.isEmpty()) {
            getLogger().severe("Gemini API key is not configured!");
        }
        if (webhookUrl.isEmpty()) {
            getLogger().warning("Discord webhook URL is not configured!");
        }

        int warnThreshold = getConfig().getInt("escalation.warns-for-mute", 3);
        int muteThreshold = getConfig().getInt("escalation.mutes-for-ban", 2);
        int escalationDays = getConfig().getInt("escalation.window-days", 7);
        if (sanctionTracker == null) {
            sanctionTracker = new SanctionTracker(getLogger(), warnThreshold, muteThreshold, escalationDays);
        }

        geminiAnalyzer = new GeminiAnalyzer(apiKey, model, fallbackModel, serverType, getLogger());
        discordWebhook = new DiscordWebhook(webhookUrl, serverName, serverType, getLogger());

        long intervalTicks = intervalMinutes * 60L * 20L;
        AnalysisTask task = new AnalysisTask(
                this, geminiAnalyzer, discordWebhook,
                muteDuration, banDuration
        );
        analysisTask = task.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks);

        getLogger().info("Analysis scheduled every " + intervalMinutes + " minutes.");
    }

    // ---- Commands ----
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("antitoxicity")) return false;

        if (!sender.hasPermission("antitoxicity.admin")) {
            sender.sendMessage(colorize("&cYou don't have permission to use this command."));
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "stats":
                showStats(sender);
                break;

            case "fp":
            case "falsepositiv":
                sanctionTracker.reportFalsePositive();
                sender.sendMessage(colorize("&a[ATOX] &7False positive recorded. Thank you."));
                break;

            case "reload":
                reloadConfig();
                loadPlugin();
                sender.sendMessage(colorize("&a[ATOX] &7Configuration reloaded."));
                break;

            case "status":
                sender.sendMessage(colorize("&e[ATOX] &7Status:"));
                sender.sendMessage(colorize("  &7Server: &f" + getConfig().getString("server-name")));
                sender.sendMessage(colorize("  &7Type: &f" + getConfig().getString("server-type")));
                sender.sendMessage(colorize("  &7Messages: &f" + storedMessageCount()));
                sender.sendMessage(colorize("  &7Players: &f" + storedPlayerCount()));
                break;

            case "analyze":
                sender.sendMessage(colorize("&e[ATOX] &7Forcing analysis..."));
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    purgeOldMessages();
                    Map<String, List<String>> msgs = getMessagesForAnalysis();

                    if (msgs.isEmpty()) {
                        getServer().getScheduler().runTask(this, () ->
                                sender.sendMessage(colorize("&c[ATOX] &7No messages to analyze.")));
                        return;
                    }

                    int total = 0;
                    for (List<String> l : msgs.values()) total += l.size();
                    int players = msgs.size();

                    List<GeminiAnalyzer.Sanction> sanctions = geminiAnalyzer.analyze(msgs);

                    if (sanctions == null) {
                        getServer().getScheduler().runTask(this, () ->
                                sender.sendMessage(colorize("&c[ATOX] &7API error. Messages will be retained for the next analysis cycle.")));
                        return;
                    }

                    markAnalysisComplete();

                    List<GeminiAnalyzer.Sanction> dedupedSanctions = deduplicateSanctions(sanctions);

                    getServer().getScheduler().runTask(this, () -> {
                        if (!dedupedSanctions.isEmpty()) {
                            for (GeminiAnalyzer.Sanction s : dedupedSanctions) {
                                String cmd = buildCommand(s);
                                if (cmd != null) {
                                    getLogger().info("Executing: " + cmd);
                                    getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
                                }
                            }
                            sender.sendMessage(colorize("&a[ATOX] &7" + dedupedSanctions.size() + " sanction(s) applied."));
                        } else {
                            sender.sendMessage(colorize("&a[ATOX] &7No sanctions needed."));
                        }
                    });

                    discordWebhook.sendReport(dedupedSanctions, total, players);
                });
                break;

            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    String buildCommand(GeminiAnalyzer.Sanction s) {
        String trigger = (s.triggerMessage != null && !s.triggerMessage.isEmpty() && !s.triggerMessage.equals("N/A"))
                ? " | Message: \"" + s.triggerMessage.replace("\"", "'") + "\"" : "";
        String reason = "[ATOX AI] " + s.reason.replace("\"", "'") + trigger;
        String defMute = getConfig().getString("durations.mute", "1h");
        String defBan = getConfig().getString("durations.ban", "1d");
        String dur = (s.duration != null && !s.duration.isEmpty()) ? s.duration : null;
        boolean isPermanent = "permanent".equalsIgnoreCase(dur);

        switch (s.action) {
            case "WARN":
                return "advancedban:warn " + s.player + " " + reason;
            case "MUTE":
                return "advancedban:tempmute " + s.player + " " + (dur != null ? dur : defMute) + " " + reason;
            case "KICK":
                return "advancedban:kick " + s.player + " " + reason;
            case "BAN":
                if (isPermanent) {
                    return "advancedban:ban " + s.player + " " + reason;
                } else {
                    return "advancedban:tempban " + s.player + " " + (dur != null ? dur : defBan) + " " + reason;
                }
            case "IPBAN":
                if (isPermanent || dur == null) {
                    return "advancedban:ipban " + s.player + " " + reason;
                } else {
                    return "advancedban:tempipban " + s.player + " " + dur + " " + reason;
                }
            default:
                return null;
        }
    }

    /** Keeps only the most severe sanction per player. Order: IPBAN > BAN > KICK > MUTE > WARN */
    public List<GeminiAnalyzer.Sanction> deduplicateSanctions(List<GeminiAnalyzer.Sanction> sanctions) {
        Map<String, GeminiAnalyzer.Sanction> best = new LinkedHashMap<>();
        for (GeminiAnalyzer.Sanction s : sanctions) {
            String key = s.player.toLowerCase();
            if (!best.containsKey(key) || severity(s.action) > severity(best.get(key).action)) {
                best.put(key, s);
            }
        }
        return new ArrayList<>(best.values());
    }

    private int severity(String action) {
        switch (action) {
            case "IPBAN": return 5;
            case "BAN":   return 4;
            case "KICK":  return 3;
            case "MUTE":  return 2;
            case "WARN":  return 1;
            default:      return 0;
        }
    }

    private void showStats(CommandSender sender) {
        sender.sendMessage(colorize("&c&l[ATOX] &e--- Statistics ---"));
        sender.sendMessage(colorize("  &7Total sanctions applied: &f" + sanctionTracker.getTotalSanctions()));
        sender.sendMessage(colorize("  &7Messages analyzed: &f" + sanctionTracker.getTotalMessagesAnalyzed()));
        sender.sendMessage(colorize("  &7Cycles completed: &f" + sanctionTracker.getTotalCycles()));
        sender.sendMessage(colorize("  &7False positives reported: &f" + sanctionTracker.getFalsePositivesReported()));

        Map<String, Integer> byType = sanctionTracker.getSanctionsByType();
        if (!byType.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : byType.entrySet()) {
                sb.append(e.getKey()).append(":").append(e.getValue()).append(" ");
            }
            sender.sendMessage(colorize("  &7By type: &f" + sb.toString().trim()));
        }

        List<Map.Entry<String, Integer>> top = sanctionTracker.getTopSanctionedPlayers(5);
        if (!top.isEmpty()) {
            sender.sendMessage(colorize("  &7Top sanctioned players:"));
            for (Map.Entry<String, Integer> e : top) {
                sender.sendMessage(colorize("    &c" + e.getKey() + " &7-> &f" + e.getValue() + " sanction(s)"));
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(colorize("&c&lATOX &7- Commands:"));
        sender.sendMessage(colorize("  &e/atox reload &7- Reload configuration"));
        sender.sendMessage(colorize("  &e/atox status &7- Show plugin status"));
        sender.sendMessage(colorize("  &e/atox analyze &7- Force an analysis now"));
        sender.sendMessage(colorize("  &e/atox stats &7- Show sanction statistics"));
        sender.sendMessage(colorize("  &e/atox fp &7- Report a false positive"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!getConfig().getBoolean("username-check.enabled", true)) return;
        if (geminiAnalyzer == null) return;

        String name = event.getName();
        String offensiveReason = geminiAnalyzer.analyzeUsername(name);

        if (offensiveReason != null && !offensiveReason.isEmpty()) {
            String kickMsg = getConfig().getString("username-check.kick-message",
                    "Your username is not allowed on this server.\nReason: {reason}\n\nChange your name and try again.");
            kickMsg = kickMsg.replace("{reason}", offensiveReason);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            getLogger().warning("[ATOX] Offensive username blocked: " + name + " | Reason: " + offensiveReason);
            discordWebhook.sendUsernameBlock(name, offensiveReason);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> {
            event.getPlayer().sendMessage(colorize("&8&m                                                  "));
            event.getPlayer().sendMessage(colorize("&c&l⚠ PRIVACY NOTICE"));
            event.getPlayer().sendMessage(colorize("&7This server uses &eArtificial Intelligence"));
            event.getPlayer().sendMessage(colorize("&7to automatically moderate the chat."));
            event.getPlayer().sendMessage(colorize("&7Your messages are analyzed by AI."));
            event.getPlayer().sendMessage(colorize(" "));
            event.getPlayer().sendMessage(colorize("&cDo not share personal data or"));
            event.getPlayer().sendMessage(colorize("&cconfidential information in the chat!"));
            event.getPlayer().sendMessage(colorize("&8&m                                                  "));
        }, 20L);
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
