package com.antitoxicity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

public class DiscordWebhook {

    private final String webhookUrl;
    private final String serverName;
    private final String serverType;
    private final Logger logger;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

    public DiscordWebhook(String webhookUrl, String serverName, String serverType, Logger logger) {
        this.webhookUrl = webhookUrl;
        this.serverName = serverName;
        this.serverType = serverType;
        this.logger = logger;
    }

    /**
     * Sends analysis results to the Discord webhook.
     * Only call this if there were messages analyzed.
     */
    public void sendReport(List<GeminiAnalyzer.Sanction> sanctions, int totalMessages, int totalPlayers) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", "ATOX");
            payload.addProperty("avatar_url", "https://i.imgur.com/4M34hi2.png");

            JsonArray embeds = new JsonArray();
            JsonObject embed = new JsonObject();

            String timestamp = LocalDateTime.now().format(FORMATTER);

            if (sanctions.isEmpty()) {
                embed.addProperty("title", "\u2705 Analysis Complete — No Sanctions");
                embed.addProperty("description",
                        "No violations were found in the analyzed messages.");
                embed.addProperty("color", 3066993); // Green
            } else {
                embed.addProperty("title", "\u26a0\ufe0f Analysis Complete — Sanctions Applied");
                embed.addProperty("color", 15158332); // Red

                StringBuilder desc = new StringBuilder();
                desc.append("**").append(sanctions.size()).append(" sanction(s)** detected.\n\n");

                for (int i = 0; i < sanctions.size(); i++) {
                    GeminiAnalyzer.Sanction s = sanctions.get(i);
                    String emoji = getActionEmoji(s.action);

                    desc.append("**").append(i + 1).append(".** ")
                            .append(emoji).append(" **").append(s.action);
                    if (s.duration != null && !s.duration.isEmpty()) {
                        desc.append(" (").append(s.duration).append(")");
                    }
                    desc.append("** → `").append(s.player).append("`\n");
                    desc.append("   \u2022 **Reason:** ").append(s.reason).append("\n");
                    desc.append("   \u2022 **Trigger message:** `")
                            .append(truncate(s.triggerMessage, 200)).append("`\n\n");
                }

                embed.addProperty("description", desc.toString());
            }

            // Footer with server info
            JsonObject footer = new JsonObject();
            footer.addProperty("text", "\uD83C\uDFAE " + serverName + " | \uD83D\uDCCC " + serverType
                    + " | \uD83D\uDCAC " + totalMessages + " msgs from " + totalPlayers
                    + " player(s) | " + timestamp);
            embed.add("footer", footer);

            // Server info fields
            JsonArray fields = new JsonArray();

            JsonObject serverField = new JsonObject();
            serverField.addProperty("name", "\uD83D\uDDA5\uFE0F Server");
            serverField.addProperty("value", serverName);
            serverField.addProperty("inline", true);

            JsonObject typeField = new JsonObject();
            typeField.addProperty("name", "\uD83C\uDFAE Type");
            typeField.addProperty("value", serverType);
            typeField.addProperty("inline", true);

            JsonObject statsField = new JsonObject();
            statsField.addProperty("name", "\uD83D\uDCCA Stats");
            statsField.addProperty("value", totalMessages + " messages from " + totalPlayers + " player(s)");
            statsField.addProperty("inline", true);

            fields.add(serverField);
            fields.add(typeField);
            fields.add(statsField);
            embed.add("fields", fields);

            embeds.add(embed);
            payload.add("embeds", embeds);

            sendPayload(payload.toString());
        } catch (Exception e) {
            logger.severe("[ATOX] Error sending Discord webhook: " + e.getMessage());
        }
    }

    private void sendPayload(String jsonPayload) throws Exception {
        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 204) {
            logger.warning("[ATOX] Discord webhook returned HTTP " + responseCode);
        }

        conn.disconnect();
    }

    public void sendDailySummary(SanctionTracker tracker) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", "ATOX");
            payload.addProperty("avatar_url", "https://i.imgur.com/4M34hi2.png");

            JsonArray embeds = new JsonArray();
            JsonObject embed = new JsonObject();

            embed.addProperty("title", "\uD83D\uDCCA Daily Summary - " + serverName);
            embed.addProperty("color", 3447003); // Blue

            java.util.List<SanctionTracker.SanctionRecord> last24h = tracker.getLast24hSanctions();
            java.util.Map<String, Integer> byType = tracker.getSanctionsByType();
            java.util.List<java.util.Map.Entry<String, Integer>> top = tracker.getTopSanctionedPlayers(5);

            StringBuilder desc = new StringBuilder();
            desc.append("**\uD83D\uDCEC Messages analyzed (total):** ").append(tracker.getTotalMessagesAnalyzed()).append("\n");
            desc.append("**\u2696\uFE0F Sanctions last 24h:** ").append(last24h.size()).append("\n");
            desc.append("**\uD83D\uDD04 Cycles completed:** ").append(tracker.getTotalCycles()).append("\n");
            desc.append("**\u274C False positives reported:** ").append(tracker.getFalsePositivesReported()).append("\n\n");

            if (!byType.isEmpty()) {
                desc.append("**Sanctions by type:**\n");
                for (java.util.Map.Entry<String, Integer> e : byType.entrySet()) {
                    desc.append("  ").append(getActionEmoji(e.getKey())).append(" ").append(e.getKey())
                            .append(": ").append(e.getValue()).append("\n");
                }
                desc.append("\n");
            }

            if (!top.isEmpty()) {
                desc.append("**\uD83C\uDFC6 Most sanctioned players:**\n");
                for (int i = 0; i < top.size(); i++) {
                    desc.append("  **").append(i + 1).append(".** `")
                            .append(top.get(i).getKey()).append("` \u2014 ")
                            .append(top.get(i).getValue()).append(" sanction(s)\n");
                }
            }

            embed.addProperty("description", desc.toString());

            String timestamp = LocalDateTime.now().format(FORMATTER);
            JsonObject footer = new JsonObject();
            footer.addProperty("text", "\uD83C\uDFAE " + serverName + " | " + serverType + " | " + timestamp);
            embed.add("footer", footer);

            embeds.add(embed);
            payload.add("embeds", embeds);

            sendPayload(payload.toString());
        } catch (Exception e) {
            logger.severe("[ATOX] Error sending daily summary: " + e.getMessage());
        }
    }

    private String getActionEmoji(String action) {
        switch (action) {
            case "WARN":  return "\u26a0\ufe0f";
            case "MUTE":  return "\uD83D\uDD07";
            case "KICK":  return "\uD83D\uDC62";
            case "BAN":   return "\uD83D\uDD28";
            case "IPBAN": return "\uD83D\uDEAB";
            default:      return "\u2753";
        }
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
