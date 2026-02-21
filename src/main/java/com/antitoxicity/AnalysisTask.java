package com.antitoxicity;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class AnalysisTask extends BukkitRunnable {

    private final AntiToxicity plugin;
    private final GeminiAnalyzer geminiAnalyzer;
    private final DiscordWebhook discordWebhook;
    private final Logger logger;
    private final String defaultMuteDuration;
    private final String defaultBanDuration;

    private int cycleCount = 0;
    private static final int DAILY_SUMMARY_CYCLES = 96; // 96 x 15min = 24h

    public AnalysisTask(AntiToxicity plugin, GeminiAnalyzer geminiAnalyzer,
                        DiscordWebhook discordWebhook,
                        String defaultMuteDuration, String defaultBanDuration) {
        this.plugin = plugin;
        this.geminiAnalyzer = geminiAnalyzer;
        this.discordWebhook = discordWebhook;
        this.logger = plugin.getLogger();
        this.defaultMuteDuration = defaultMuteDuration;
        this.defaultBanDuration = defaultBanDuration;
    }

    @Override
    public void run() {
        // Skip if no players are online
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        // Get messages without consuming them yet
        Map<String, List<String>> recentMessages = plugin.getMessagesForAnalysis();

        if (recentMessages.isEmpty()) {
            return;
        }

        int totalMessages = 0;
        for (List<String> msgs : recentMessages.values()) {
            totalMessages += msgs.size();
        }
        int totalPlayers = recentMessages.size();

        logger.info("[ATOX] Analyzing " + totalMessages + " messages from "
                + totalPlayers + " player(s)...");

        // Build context: last 10 messages per player (before current cycle)
        Map<String, List<String>> contextMessages = plugin.getContextMessages(recentMessages.keySet(), 10);

        List<GeminiAnalyzer.Sanction> sanctions = geminiAnalyzer.analyze(recentMessages, contextMessages);

        // null = API error -> retain messages, they accumulate for next cycle
        if (sanctions == null) {
            logger.warning("[ATOX] API failed. Messages retained (" + totalMessages
                    + " msgs). Will retry next cycle with accumulated messages.");
            return;
        }

        // API succeeded -> mark messages as consumed
        plugin.markAnalysisComplete();

        SanctionTracker tracker = plugin.getSanctionTracker();
        tracker.recordCycle(totalMessages);

        List<GeminiAnalyzer.Sanction> dedupedSanctions = plugin.deduplicateSanctions(sanctions);

        // Pattern escalation
        List<GeminiAnalyzer.Sanction> finalSanctions = new ArrayList<>();
        for (GeminiAnalyzer.Sanction s : dedupedSanctions) {
            tracker.recordSanction(s);
            String escalated = tracker.checkEscalation(s.player);
            if (escalated != null && severityOf(escalated) > severityOf(s.action)) {
                logger.warning("[ATOX] Escalating " + s.player
                        + " from " + s.action + " to " + escalated + " due to history.");
                finalSanctions.add(new GeminiAnalyzer.Sanction(
                        s.player, escalated, "Repeat offender: " + s.reason,
                        s.triggerMessage, escalated.equals("BAN") ? "7d" : "1h"));
            } else {
                finalSanctions.add(s);
            }
        }

        if (!finalSanctions.isEmpty()) {
            logger.info("[ATOX] Gemini returned " + sanctions.size() + " sanction(s), "
                    + finalSanctions.size() + " after dedup+escalation.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (GeminiAnalyzer.Sanction sanction : finalSanctions) {
                    String cmd = plugin.buildCommand(sanction);
                    if (cmd != null) {
                        logger.info("[ATOX] Executing: " + cmd);
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        } catch (Exception e) {
                            logger.severe("[ATOX] Command error: " + e.getMessage());
                        }
                    }
                }
            });
        } else {
            logger.info("[ATOX] No sanctions needed this cycle.");
        }

        discordWebhook.sendReport(finalSanctions, totalMessages, totalPlayers);

        // Daily summary every ~24h
        cycleCount++;
        if (cycleCount >= DAILY_SUMMARY_CYCLES) {
            cycleCount = 0;
            discordWebhook.sendDailySummary(tracker);
        }
    }

    private int severityOf(String action) {
        switch (action) {
            case "IPBAN": return 5;
            case "BAN":   return 4;
            case "KICK":  return 3;
            case "MUTE":  return 2;
            case "WARN":  return 1;
            default:      return 0;
        }
    }
}
