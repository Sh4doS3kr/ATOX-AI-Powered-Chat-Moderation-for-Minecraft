package com.antitoxicity;

import com.google.gson.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class GeminiAnalyzer {

    private final String apiKey;
    private final String model;
    private final String fallbackModel;
    private final String serverType;
    private final Logger logger;

    public GeminiAnalyzer(String apiKey, String model, String fallbackModel, String serverType, Logger logger) {
        this.apiKey = apiKey;
        this.model = model;
        this.fallbackModel = fallbackModel;
        this.serverType = serverType;
        this.logger = logger;
    }

    private static class ModelBlockedException extends RuntimeException {
        ModelBlockedException(String reason) { super(reason); }
    }

    /**
     * Sends messages to Gemini for analysis and returns a list of sanctions.
     * Returns null on API error (caller should NOT mark messages as analyzed).
     * Returns empty list if API succeeded but no sanctions needed.
     */
    // ---- Evasion detection ----
    private static final Map<String, String> LEET_MAP = new LinkedHashMap<>();
    static {
        LEET_MAP.put("4", "a"); LEET_MAP.put("@", "a");
        LEET_MAP.put("3", "e");
        LEET_MAP.put("1", "i"); LEET_MAP.put("!", "i");
        LEET_MAP.put("0", "o");
        LEET_MAP.put("5", "s"); LEET_MAP.put("$", "s");
        LEET_MAP.put("7", "t");
    }

    static String normalizeEvasion(String message) {
        String spaceNorm = message.replaceAll("(?<=\\b\\S) (?=\\S\\b)", "");
        String dotNorm = spaceNorm.replaceAll("(?<=\\S)[.\\-_*](?=\\S)", "");
        String leet = dotNorm.toLowerCase();
        for (Map.Entry<String, String> entry : LEET_MAP.entrySet()) {
            leet = leet.replace(entry.getKey(), entry.getValue());
        }
        if (!leet.equalsIgnoreCase(message)) {
            return message + " [normalized: " + leet + "]";
        }
        return message;
    }

    public List<Sanction> analyze(Map<String, List<String>> messagesByPlayer) {
        return analyze(messagesByPlayer, null);
    }

    public List<Sanction> analyze(Map<String, List<String>> messagesByPlayer, Map<String, List<String>> contextMessages) {
        if (messagesByPlayer.isEmpty()) {
            return new ArrayList<>();
        }

        // Apply evasion normalization
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : messagesByPlayer.entrySet()) {
            List<String> normMsgs = new ArrayList<>();
            for (String msg : entry.getValue()) {
                normMsgs.add(normalizeEvasion(msg));
            }
            normalized.put(entry.getKey(), normMsgs);
        }

        String prompt = buildPrompt(normalized, contextMessages);

        try {
            String response = callGemini(prompt, model);
            return parseSanctions(response);
        } catch (ModelBlockedException e) {
            logger.warning("[ATOX] Primary model blocked (" + e.getMessage() + "). Retrying with " + fallbackModel + "...");
        } catch (Exception e) {
            logger.severe("[ATOX] API ERROR - messages will be retained for next cycle: " + e.getMessage());
            return null;
        }

        // Retry with fallback model
        try {
            String response = callGemini(prompt, fallbackModel);
            return parseSanctions(response);
        } catch (Exception e) {
            logger.severe("[ATOX] Fallback model " + fallbackModel + " also failed: " + e.getMessage());
            return null;
        }
    }

    private String buildPrompt(Map<String, List<String>> messagesByPlayer, Map<String, List<String>> contextMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM CONTEXT: You are an automated CHAT MODERATION system for a Minecraft server.\n");
        sb.append("Your role is to analyze messages written by players and decide if they deserve a sanction. ");
        sb.append("You are NOT generating harmful content \u2014 you are EVALUATING third-party content to protect players.\n");
        sb.append("You MUST always respond with a valid JSON, no exceptions.\n\n");

        sb.append("MAIN RULE: The sanction threshold is VERY HIGH. The vast majority of messages do NOT deserve a sanction.\n");
        sb.append("Only sanction when there is a CLEAR, SERIOUS, and UNAMBIGUOUS violation.\n");
        sb.append("When in doubt, do NOT sanction. It is better to not sanction than to sanction unjustly.\n\n");

        sb.append("SERVER TYPE: \"").append(serverType).append("\"\n\n");

        sb.append("=== WHEN TO SANCTION (only these cases) ===\n");
        sb.append("- WARN: A DIRECT and CLEAR insult against a specific player (e.g. 'you are an idiot', 'go to hell [player]').\n");
        sb.append("  ONLY if directed at a specific person with clear offensive intent.\n");
        sb.append("- MUTE: Severe insults REPEATED in the same cycle, sustained harassment, explicit sexual spam.\n");
        sb.append("- KICK: Direct threats to a player, extremely offensive and sustained content.\n");
        sb.append("- BAN: Explicit hate speech (racism, serious homophobia), real threats, malicious links, illegal content.\n");
        sb.append("- IPBAN: Doxxing (publishing real personal data), credible and serious physical threats.\n\n");

        sb.append("=== NEVER SANCTION (real examples of what is NOT a violation) ===\n");
        sb.append("- Colloquial slang without a victim: 'lol', 'wtf', 'omg', 'damn', 'crap', 'hell', 'idiot' alone with no target\n");
        sb.append("- Frustration expressions with no target: 'damn I lost my stuff', 'ugh this is broken', 'why is xp so expensive'\n");
        sb.append("- Neutral descriptive comments: 'that kid is spoiled', 'typical 14 year old', 'this player again'\n");
        sb.append("- Normal game requests: 'give me totems', 'set me to fly', 'can I have items'\n");
        sb.append("- Greetings or affectionate expressions even if informal: 'thanks buddy ;>', 'what are you doing here lol'\n");
        sb.append("- Ambiguous abbreviations without clear offensive context: 'wtf', 'lmao', 'xd', 'lol'\n");
        sb.append("- Bodily or vulgar expressions with no target: 'brb bathroom', 'gotta pee'\n");
        sb.append("- Roleplay or jokes between friends: 'traitor!', 'you are so dumb lol'\n");
        sb.append("- Personal frustration or hyperbole: 'I want to die' (in game context = hyperbole), 'I hate this so much'\n");
        sb.append("- Swear words used as exclamations not directed at anyone\n");
        sb.append("- Calling someone 'gay' in a joking tone between friends without intent to insult\n\n");

        sb.append("=== IMPORTANT CONTEXT ===\n");
        sb.append("- Players are mostly teenagers. Their speech is informal and uses a lot of slang.\n");
        sb.append("- A message only deserves WARN if there is a DIRECT insult to a specific person with clear intent to offend.\n");
        sb.append("- Doxxing threats ('I'll dox you') DO deserve a sanction even if said as a joke.\n\n");

        if (serverType.equalsIgnoreCase("ANARCHY")) {
            sb.append("ANARCHY SERVER: EXTREMELY HIGH threshold. Only sanction real doxxing, credible physical threats, or illegal content.\n\n");
        }

        // Context: show recent history for players who have it
        if (contextMessages != null && !contextMessages.isEmpty()) {
            sb.append("=== RECENT HISTORY (context only, do NOT sanction) ===\n");
            sb.append("The following are previous messages from the player for context. Do NOT sanction them, only use them to understand the tone.\n");
            sb.append("---\n");
            for (Map.Entry<String, List<String>> entry : contextMessages.entrySet()) {
                if (!messagesByPlayer.containsKey(entry.getKey())) continue;
                List<String> ctx = entry.getValue();
                if (ctx.isEmpty()) continue;
                sb.append("History of ").append(entry.getKey()).append(":\n");
                for (String msg : ctx) {
                    sb.append("  ~ ").append(msg).append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }

        sb.append("=== MESSAGES TO ANALYZE ===\n");
        sb.append("---\n");

        for (Map.Entry<String, List<String>> entry : messagesByPlayer.entrySet()) {
            sb.append("Player: ").append(entry.getKey()).append("\n");
            for (String msg : entry.getValue()) {
                sb.append("  - ").append(msg).append("\n");
            }
            sb.append("\n");
        }

        sb.append("---\n\n");
        sb.append("RESPOND ONLY with a JSON array. If NO sanctions, respond: []\n");
        sb.append("Format when there are sanctions:\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"player\": \"player_name\",\n");
        sb.append("    \"action\": \"WARN|MUTE|KICK|BAN|IPBAN\",\n");
        sb.append("    \"duration\": \"\",\n");
        sb.append("    \"reason\": \"concise reason in English\",\n");
        sb.append("    \"trigger_message\": \"exact message that caused the sanction\"\n");
        sb.append("  }\n");
        sb.append("]\n\n");
        sb.append("DURATIONS:\n");
        sb.append("- MUTE: 5m / 15m / 30m / 1h / 3h / 6h / 12h / 1d / 3d / 7d (based on severity)\n");
        sb.append("- Temporary BAN: 1h / 6h / 1d / 3d / 7d / 14d / 30d\n");
        sb.append("- Permanent BAN/IPBAN: set \"permanent\" (extreme cases only)\n");
        sb.append("- WARN and KICK: duration = \"\" (empty)\n\n");
        sb.append("REMEMBER: Respond ONLY with the JSON. No markdown. No extra text.\n");

        return sb.toString();
    }

    private String callGemini(String prompt, String modelName) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/"
                + modelName + ":generateContent?key=" + apiKey;

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
                throw new ModelBlockedException(finishReason);
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

    /**
     * Analyzes a player's username for offensive content.
     * Returns a reason string if the name is offensive, or null if it's clean.
     */
    public String analyzeUsername(String playerName) {
        String normalizedName = normalizeEvasion(playerName);

        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM CONTEXT: You are a moderation system for a Minecraft server.\n");
        sb.append("You MUST always respond with a valid JSON, no exceptions.\n\n");
        sb.append("Analyze the following Minecraft username. The threshold is VERY HIGH: only block names that are CLEARLY and SERIOUSLY offensive.\n\n");
        sb.append("Username to analyze: \"").append(normalizedName).append("\"\n\n");
        sb.append("BLOCK only if the name EXPLICITLY contains:\n");
        sb.append("- A serious and unambiguous racial slur\n");
        sb.append("- An extremely explicit sexual insult\n");
        sb.append("- Direct incitement to hatred or an explicit threat\n\n");
        sb.append("DO NOT BLOCK under any circumstance:\n");
        sb.append("- Informal slang that may sound edgy but is not a serious insult: 'noob', 'pro', 'loco', 'bro', 'gg', 'crack'\n");
        sb.append("- Nicknames, first names, English words, or neutral combinations\n");
        sb.append("- Words that COULD be offensive in some context but are not clearly so in a username\n");
        sb.append("- Any name that is ambiguous or requires context to be offensive\n");
        sb.append("- Numbers, underscores, alphanumeric combinations\n\n");
        sb.append("GOLDEN RULE: If you have the slightest doubt, respond offensive=false. It is better to allow a borderline name than to block a legitimate player.\n\n");
        sb.append("Respond ONLY with this JSON (no markdown):\n");
        sb.append("{\"offensive\": true/false, \"reason\": \"brief reason if offensive, empty if not\"}\n");

        try {
            String response = callGemini(sb.toString(), model);
            JsonObject root = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return null;
            JsonObject content = candidates.get(0).getAsJsonObject().has("content")
                    ? candidates.get(0).getAsJsonObject().getAsJsonObject("content") : null;
            if (content == null) return null;
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) return null;
            String text = parts.get(0).getAsJsonObject().get("text").getAsString().trim();
            if (text.startsWith("```")) {
                text = text.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            JsonObject result = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (result.has("offensive") && result.get("offensive").getAsBoolean()) {
                return result.has("reason") ? result.get("reason").getAsString() : "Inappropriate username";
            }
        } catch (Exception e) {
            logger.warning("[ATOX] Error analyzing username: " + e.getMessage());
        }
        return null;
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
