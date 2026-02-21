package com.antitoxicity;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Tracks sanction history for stats, pattern escalation, and daily summaries.
 */
public class SanctionTracker {

    public static final class SanctionRecord {
        public final String player;
        public final String action;
        public final String reason;
        public final String triggerMessage;
        public final long timestamp;

        SanctionRecord(String player, String action, String reason, String triggerMessage) {
            this.player = player;
            this.action = action;
            this.reason = reason;
            this.triggerMessage = triggerMessage;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final List<SanctionRecord> history = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalMessagesAnalyzed = new AtomicInteger(0);
    private final AtomicInteger totalCycles = new AtomicInteger(0);
    private final AtomicInteger falsePositivesReported = new AtomicInteger(0);
    private final Logger logger;

    private final int warnThresholdForMute;
    private final int muteThresholdForBan;
    private final long escalationWindowMillis;

    public SanctionTracker(Logger logger, int warnThresholdForMute, int muteThresholdForBan, int escalationWindowDays) {
        this.logger = logger;
        this.warnThresholdForMute = warnThresholdForMute;
        this.muteThresholdForBan = muteThresholdForBan;
        this.escalationWindowMillis = escalationWindowDays * 24L * 3600L * 1000L;
    }

    public void recordSanction(GeminiAnalyzer.Sanction s) {
        history.add(new SanctionRecord(s.player, s.action, s.reason, s.triggerMessage));
    }

    public void recordCycle(int messagesAnalyzed) {
        totalMessagesAnalyzed.addAndGet(messagesAnalyzed);
        totalCycles.incrementAndGet();
    }

    public void reportFalsePositive() {
        falsePositivesReported.incrementAndGet();
    }

    public String checkEscalation(String playerName) {
        long cutoff = System.currentTimeMillis() - escalationWindowMillis;

        int warns = 0;
        int mutes = 0;

        synchronized (history) {
            for (SanctionRecord r : history) {
                if (!r.player.equalsIgnoreCase(playerName)) continue;
                if (r.timestamp < cutoff) continue;
                if ("WARN".equals(r.action)) warns++;
                else if ("MUTE".equals(r.action)) mutes++;
            }
        }

        if (mutes >= muteThresholdForBan) {
            logger.warning("[ATOX] Escalation: " + playerName
                    + " has " + mutes + " mutes in window -> BAN");
            return "BAN";
        }
        if (warns >= warnThresholdForMute) {
            logger.warning("[ATOX] Escalation: " + playerName
                    + " has " + warns + " warns in window -> MUTE");
            return "MUTE";
        }
        return null;
    }

    public int getTotalSanctions() { return history.size(); }
    public int getTotalMessagesAnalyzed() { return totalMessagesAnalyzed.get(); }
    public int getTotalCycles() { return totalCycles.get(); }
    public int getFalsePositivesReported() { return falsePositivesReported.get(); }

    public List<Map.Entry<String, Integer>> getTopSanctionedPlayers(int topN) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        synchronized (history) {
            for (SanctionRecord r : history) counts.merge(r.player, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        return sorted.subList(0, Math.min(topN, sorted.size()));
    }

    public Map<String, Integer> getSanctionsByType() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        synchronized (history) {
            for (SanctionRecord r : history) counts.merge(r.action, 1, Integer::sum);
        }
        return counts;
    }

    public List<SanctionRecord> getLast24hSanctions() {
        long cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L;
        List<SanctionRecord> recent = new ArrayList<>();
        synchronized (history) {
            for (SanctionRecord r : history) {
                if (r.timestamp >= cutoff) recent.add(r);
            }
        }
        return recent;
    }
}
