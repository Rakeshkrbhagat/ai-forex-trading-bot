package com.forexbot.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory ring buffer of the LLM's live reasoning / decision stream so the
 * mobile dashboard can "watch the AI think" (BUY / SELL / HOLD / REJECTED).
 *
 * <p>Fed by {@link TickPipelineService} on every autonomous cycle and exposed
 * read-only through the monitor API. Bounded so it never leaks memory; oldest
 * entries are evicted first.</p>
 */
@Service
public class ActivityFeedService {

    /** Maximum number of decisions retained for the console. */
    private static final int MAX_ENTRIES = 200;

    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();

    /**
     * Record a decision/analysis event.
     *
     * @param symbol  the currency pair under evaluation.
     * @param action  BUY / SELL / HOLD / REJECTED.
     * @param message the LLM rationale or guardrail reason.
     */
    public synchronized void record(String symbol, String action, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("symbol", symbol);
        entry.put("action", action == null ? "" : action.toUpperCase());
        entry.put("message", message);
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /** @return up to {@code limit} most-recent entries, newest first. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        int cap = Math.max(1, limit);
        List<Map<String, Object>> out = new ArrayList<>(Math.min(cap, entries.size()));
        for (Map<String, Object> e : entries) {
            if (out.size() >= cap) {
                break;
            }
            out.add(e);
        }
        return out;
    }
}

