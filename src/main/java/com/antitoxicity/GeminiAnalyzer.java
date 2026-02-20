package com.antitoxicity;

import com.google.gson.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class GeminiAnalyzer {

    private final String apiKey;
    private final String model;
    private final String serverType;
    private final Logger logger;

    public GeminiAnalyzer(String apiKey, String model, String serverType, Logger logger) {
        this.apiKey = apiKey;
        this.model = model;
        this.serverType = serverType;
        this.logger = logger;
    }

    /**
     * Sends messages to Gemini for analysis and returns a list of sanctions.
     * Returns null on API error (caller should NOT mark messages as analyzed).
     * Returns empty list if API succeeded but no sanctions needed.
     */
    public List<Sanction> analyze(Map<String, List<String>> messagesByPlayer) {
        if (messagesByPlayer.isEmpty()) {
            return new ArrayList<>();
        }

        String prompt = buildPrompt(messagesByPlayer);

        try {
            String response = callGemini(prompt);
            return parseSanctions(response);
        } catch (Exception e) {
            logger.severe("[ATOX] API ERROR - messages will be retained for next cycle: " + e.getMessage());
            return null; // null = API error, don't consume messages
        }
    }

    private String buildPrompt(Map<String, List<String>> messagesByPlayer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a moderator for a Minecraft server of type \"").append(serverType).append("\".\n");
        sb.append("Analyze the following player chat messages and determine if any player deserves a sanction.\n\n");
        sb.append("SANCTION CRITERIA (be VERY conservative — most messages do NOT deserve a sanction):\n");
        sb.append("- WARN: Direct insults aimed at another player, clearly offensive language directed at someone.\n");
        sb.append("- MUTE: Severe and repeated insults, direct harassment of a player, explicit sexual content.\n");
        sb.append("- KICK: Severe harassment, mild threats, extremely offensive content.\n");
        sb.append("- BAN: Real threats, serious discrimination, malicious links, illegal content.\n");
        sb.append("- IPBAN: Doxxing (publishing real personal data of someone), credible and serious physical threats.\n\n");
        sb.append("DO NOT SANCTION for:\n");
        sb.append("- Character spam (aaaa, lol, ???, !!!, xd, etc.) — normal Minecraft behavior\n");
        sb.append("- Emotional or surprise expressions (wow, omg, nooo, etc.)\n");
        sb.append("- Normal in-game questions\n");
        sb.append("- Colloquial language or youth slang without offensive intent\n");
        sb.append("- Random or nonsensical messages not directed at anyone\n");
        sb.append("- Jokes between players without clearly offensive content\n\n");

        if (serverType.equalsIgnoreCase("ANARCHY")) {
            sb.append("NOTE: This is an ANARCHY server, so the tolerance threshold is EXTREMELY HIGH. ");
            sb.append("Only sanction doxxing, credible real physical threats, or illegal content.\n\n");
        }

        sb.append("MESSAGES TO ANALYZE:\n");
        sb.append("---\n");

        for (Map.Entry<String, List<String>> entry : messagesByPlayer.entrySet()) {
            sb.append("Player: ").append(entry.getKey()).append("\n");
            for (String msg : entry.getValue()) {
                sb.append("  - ").append(msg).append("\n");
            }
            sb.append("\n");
        }

        sb.append("---\n\n");
        sb.append("RESPOND ONLY with a JSON array. If there are NO sanctions, respond with an empty array: []\n");
        sb.append("If there are sanctions, use this exact format:\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"player\": \"player_name\",\n");
        sb.append("    \"action\": \"WARN|MUTE|KICK|BAN|IPBAN\",\n");
        sb.append("    \"duration\": \"sanction duration\",\n");
        sb.append("    \"reason\": \"reason for the sanction in English\",\n");
        sb.append("    \"trigger_message\": \"the exact message that triggered the sanction\"\n");
        sb.append("  }\n");
        sb.append("]\n\n");
        sb.append("DURATIONS — Choose based on severity:\n");
        sb.append("- MUTE: from 5m (mild) to 7d (very severe). Examples: 5m, 15m, 30m, 1h, 3h, 6h, 12h, 1d, 3d, 7d\n");
        sb.append("- Temporary BAN: 1h, 6h, 12h, 1d, 3d, 7d, 14d, 30d\n");
        sb.append("- Permanent BAN: set \"permanent\" in duration (for very severe cases without doxxing)\n");
        sb.append("- IPBAN: set \"permanent\" in duration (always permanent, only for doxxing/real serious threats)\n");
        sb.append("- WARN and KICK do not need a duration, set \"\" (empty) in duration.\n\n");
        sb.append("IMPORTANT:\n");
        sb.append("- Respond ONLY with the JSON, nothing else. No markdown, no explanations.\n");
        sb.append("- Do not sanction players who are behaving normally.\n");
        sb.append("- The reason must be clear and concise.\n");
        sb.append("- Only include players who truly deserve a sanction.\n");
        sb.append("- Adjust the duration proportionally to the severity of the infraction.\n");

        return sb.toString();
    }

    private String callGemini(String prompt) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        requestBody.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.1);
        generationConfig.addProperty("maxOutputTokens", 2048);
        requestBody.add("generationConfig", generationConfig);

        JsonArray safetySettings = new JsonArray();
        for (String category : new String[]{
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
        }) {
            JsonObject setting = new JsonObject();
            setting.addProperty("category", category);
            setting.addProperty("threshold", "BLOCK_NONE");
            safetySettings.add(setting);
        }
        requestBody.add("safetySettings", safetySettings);

        String jsonBody = requestBody.toString();

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        StringBuilder response = new StringBuilder();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
        } else {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            logger.severe("[ATOX] Gemini API error (HTTP " + responseCode + "): " + response);
            throw new RuntimeException("Gemini API returned HTTP " + responseCode);
        }

        conn.disconnect();
        return response.toString();
    }

    private List<Sanction> parseSanctions(String geminiResponse) {
        List<Sanction> sanctions = new ArrayList<>();

        try {
            JsonObject root = JsonParser.parseString(geminiResponse).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return sanctions;
            }

            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
            JsonObject contentObj = firstCandidate.has("content")
                    ? firstCandidate.getAsJsonObject("content") : null;

            if (contentObj == null) {
                String finishReason = firstCandidate.has("finishReason")
                        ? firstCandidate.get("finishReason").getAsString() : "UNKNOWN";
                logger.warning("[ATOX] Gemini blocked the response (finishReason=" + finishReason
                        + "). Treating as no sanctions.");
                return sanctions;
            }

            JsonArray partsArr = contentObj.getAsJsonArray("parts");
            if (partsArr == null || partsArr.size() == 0) {
                return sanctions;
            }

            String text = partsArr.get(0).getAsJsonObject().get("text").getAsString().trim();

            // Strip markdown code fences if present
            if (text.startsWith("```")) {
                text = text.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            JsonArray sanctionsArray = JsonParser.parseString(text).getAsJsonArray();

            for (JsonElement elem : sanctionsArray) {
                JsonObject obj = elem.getAsJsonObject();
                String player = obj.get("player").getAsString();
                String action = obj.get("action").getAsString().toUpperCase();
                String reason = obj.get("reason").getAsString();
                String triggerMessage = obj.has("trigger_message")
                        ? obj.get("trigger_message").getAsString() : "N/A";

                String duration = obj.has("duration") && !obj.get("duration").isJsonNull()
                        ? obj.get("duration").getAsString() : "";

                if (isValidAction(action)) {
                    sanctions.add(new Sanction(player, action, reason, triggerMessage, duration));
                }
            }
        } catch (Exception e) {
            logger.warning("[ATOX] Error parsing Gemini response: " + e.getMessage());
        }

        return sanctions;
    }

    private boolean isValidAction(String action) {
        return action.equals("WARN") || action.equals("MUTE")
                || action.equals("KICK") || action.equals("BAN") || action.equals("IPBAN");
    }

    public static class Sanction {
        public final String player;
        public final String action;
        public final String reason;
        public final String triggerMessage;
        public final String duration;

        public Sanction(String player, String action, String reason, String triggerMessage, String duration) {
            this.player = player;
            this.action = action;
            this.reason = reason;
            this.triggerMessage = triggerMessage;
            this.duration = duration;
        }
    }
}
