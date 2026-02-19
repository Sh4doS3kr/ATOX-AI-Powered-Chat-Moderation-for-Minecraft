package com.antitoxicity;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

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

        List<GeminiAnalyzer.Sanction> sanctions = geminiAnalyzer.analyze(recentMessages);

        // null = API error -> retain messages, they accumulate for next cycle
        if (sanctions == null) {
            logger.warning("[ATOX] API failed. Messages retained (" + totalMessages
                    + " msgs). Will retry next cycle with accumulated messages.");
            return;
        }

        // API succeeded -> mark messages as consumed
        plugin.markAnalysisComplete();

        List<GeminiAnalyzer.Sanction> dedupedSanctions = plugin.deduplicateSanctions(sanctions);

        if (!dedupedSanctions.isEmpty()) {
            logger.info("[ATOX] Gemini returned " + sanctions.size() + " sanction(s), "
                    + dedupedSanctions.size() + " after deduplication.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (GeminiAnalyzer.Sanction sanction : dedupedSanctions) {
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

        discordWebhook.sendReport(dedupedSanctions, totalMessages, totalPlayers);
    }
}
