package edu.skope.ai;

import edu.skope.repository.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Service-wide limit on paid AI calls (LLM and Embedding), stored in ai_usage_budget.
 *
 * Like a prepaid card: before each call the worst-case cost is reserved with one conditional UPDATE
 * ("... WHERE used + reservation <= limit"), so parallel requests cannot overshoot; after the call the
 * reservation is replaced by the provider's actual token counts. If the database cannot be written, or a
 * limit would be passed, the call is refused before any network traffic (fail closed).
 * Costs are estimates from configured prices, not an invoice.
 */
@Component
public class AiBudget {
    private static final Logger log = LoggerFactory.getLogger(AiBudget.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public enum Kind { LLM, EMBEDDING }
    public record Reservation(String periodKey, Kind kind, long reservedMicroUsd) {}

    /** Refused before sending; AiService turns it into the ordinary AI_UNAVAILABLE path. */
    public static final class Exceeded extends RuntimeException {
        Exceeded(String message) { super(message); }
    }

    private final Db db;
    private final String period;
    private final long maxMicroUsd;
    private final long maxLlmCalls, maxEmbeddingCalls;
    private final double inputPerMtok, outputPerMtok, embeddingPerMtok;

    public AiBudget(Db db,
                    @Value("${AI_BUDGET_PERIOD:TOTAL}") String period,
                    @Value("${AI_BUDGET_MAX_USD:2.00}") double maxUsd,
                    @Value("${AI_BUDGET_MAX_LLM_CALLS:500}") long maxLlmCalls,
                    @Value("${AI_BUDGET_MAX_EMBEDDING_CALLS:500}") long maxEmbeddingCalls,
                    @Value("${AI_PRICE_INPUT_PER_MTOK:0.10}") double inputPerMtok,
                    @Value("${AI_PRICE_OUTPUT_PER_MTOK:0.50}") double outputPerMtok,
                    @Value("${EMBEDDING_PRICE_PER_MTOK:0.02}") double embeddingPerMtok) {
        this.db = db;
        this.period = period.trim().toUpperCase(Locale.ROOT);
        if (!this.period.equals("TOTAL") && !this.period.equals("DAILY"))
            throw new IllegalArgumentException("AI_BUDGET_PERIOD must be TOTAL or DAILY");
        if (maxUsd < 0 || maxLlmCalls < 0 || maxEmbeddingCalls < 0 || inputPerMtok < 0 || outputPerMtok < 0 || embeddingPerMtok < 0
                || !Double.isFinite(maxUsd) || !Double.isFinite(inputPerMtok) || !Double.isFinite(outputPerMtok) || !Double.isFinite(embeddingPerMtok))
            throw new IllegalArgumentException("AI budget limits and prices must be finite and not negative");
        this.maxMicroUsd = Math.round(maxUsd * 1_000_000);
        this.maxLlmCalls = maxLlmCalls;
        this.maxEmbeddingCalls = maxEmbeddingCalls;
        this.inputPerMtok = inputPerMtok;
        this.outputPerMtok = outputPerMtok;
        this.embeddingPerMtok = embeddingPerMtok;
    }

    /** Reserves one call at its worst-case cost or throws Exceeded. Never sends anything itself. */
    public Reservation reserve(Kind kind, long estimatedInputTokens, long maxOutputTokens) {
        String key = periodKey();
        long reserved = cost(kind, estimatedInputTokens, maxOutputTokens);
        int changed;
        try {
            ensureRow(key);
            changed = kind == Kind.LLM
                ? db.update("UPDATE ai_usage_budget SET llm_calls=llm_calls+1,reserved_micro_usd=reserved_micro_usd+?,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE period_key=? AND llm_calls<? AND spent_micro_usd+reserved_micro_usd+?<=?", reserved, key, maxLlmCalls, reserved, maxMicroUsd)
                : db.update("UPDATE ai_usage_budget SET embedding_calls=embedding_calls+1,reserved_micro_usd=reserved_micro_usd+?,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE period_key=? AND embedding_calls<? AND spent_micro_usd+reserved_micro_usd+?<=?", reserved, key, maxEmbeddingCalls, reserved, maxMicroUsd);
        } catch (RuntimeException e) {
            log.warn("AI budget could not be checked, paid call refused: {}", e.toString());
            throw new Exceeded("budget store unavailable");
        }
        if (changed != 1) {
            try { db.update("UPDATE ai_usage_budget SET blocked_calls=blocked_calls+1 WHERE period_key=?", key); }
            catch (RuntimeException ignored) { /* counting a refusal is best effort */ }
            log.warn("AI budget limit reached for {} ({}); paid call refused", key, kind);
            throw new Exceeded("budget limit reached");
        }
        return new Reservation(key, kind, reserved);
    }

    /**
     * Replaces the reservation by the provider's token counts. Without usage numbers (failed or unreadable
     * response) the whole reservation is kept as spent, because the provider may still have charged it.
     */
    public void settle(Reservation reservation, Long inputTokens, Long outputTokens) {
        if (reservation == null) return;
        boolean known = inputTokens != null && inputTokens >= 0 && (reservation.kind() == Kind.EMBEDDING || outputTokens != null && outputTokens >= 0);
        long in = known ? inputTokens : 0, out = known && reservation.kind() == Kind.LLM ? outputTokens : 0;
        long spent = known ? cost(reservation.kind(), in, out) : reservation.reservedMicroUsd();
        try {
            if (reservation.kind() == Kind.LLM)
                db.update("UPDATE ai_usage_budget SET reserved_micro_usd=reserved_micro_usd-?,spent_micro_usd=spent_micro_usd+?,"
                    + "llm_input_tokens=llm_input_tokens+?,llm_output_tokens=llm_output_tokens+?,updated_at=CURRENT_TIMESTAMP(6) WHERE period_key=?",
                    reservation.reservedMicroUsd(), spent, in, out, reservation.periodKey());
            else
                db.update("UPDATE ai_usage_budget SET reserved_micro_usd=reserved_micro_usd-?,spent_micro_usd=spent_micro_usd+?,"
                    + "embedding_tokens=embedding_tokens+?,updated_at=CURRENT_TIMESTAMP(6) WHERE period_key=?",
                    reservation.reservedMicroUsd(), spent, in, reservation.periodKey());
        } catch (RuntimeException e) {
            // The reservation stays counted, which only makes the limit stricter.
            log.warn("AI usage could not be settled; reservation kept: {}", e.toString());
        }
    }

    /** Current period usage and limits for the supervisor. Amounts are estimates in USD. */
    public Map<String, Object> status() {
        String key = periodKey();
        var row = db.one("SELECT * FROM ai_usage_budget WHERE period_key=?", key);
        long llmCalls = number(row, "llmCalls"), embeddingCalls = number(row, "embeddingCalls");
        long spent = number(row, "spentMicroUsd"), reserved = number(row, "reservedMicroUsd");
        var limits = new LinkedHashMap<String, Object>();
        limits.put("maxUsd", maxMicroUsd / 1_000_000.0);
        limits.put("maxLlmCalls", maxLlmCalls);
        limits.put("maxEmbeddingCalls", maxEmbeddingCalls);
        var used = new LinkedHashMap<String, Object>();
        used.put("llmCalls", llmCalls);
        used.put("llmInputTokens", number(row, "llmInputTokens"));
        used.put("llmOutputTokens", number(row, "llmOutputTokens"));
        used.put("embeddingCalls", embeddingCalls);
        used.put("embeddingTokens", number(row, "embeddingTokens"));
        used.put("estimatedSpentUsd", spent / 1_000_000.0);
        used.put("reservedUsd", reserved / 1_000_000.0);
        var out = new LinkedHashMap<String, Object>();
        out.put("period", period);
        out.put("periodKey", key);
        out.put("limits", limits);
        out.put("used", used);
        out.put("blockedCalls", number(row, "blockedCalls"));
        out.put("llmBlocked", llmCalls >= maxLlmCalls || spent + reserved >= maxMicroUsd);
        out.put("embeddingBlocked", embeddingCalls >= maxEmbeddingCalls || spent + reserved >= maxMicroUsd);
        out.put("note", "예상 비용은 설정한 요율로 계산한 추정치이며 청구 금액이 아닙니다.");
        return out;
    }

    String periodKey() { return period.equals("TOTAL") ? "TOTAL" : LocalDate.now(KST).toString(); }

    /** micro-USD = tokens x (USD per 1M tokens), rounded up. */
    long cost(Kind kind, long inputTokens, long outputTokens) {
        double micro = kind == Kind.LLM ? inputTokens * inputPerMtok + outputTokens * outputPerMtok : inputTokens * embeddingPerMtok;
        return (long) Math.ceil(micro);
    }

    private void ensureRow(String key) {
        if (db.one("SELECT period_key FROM ai_usage_budget WHERE period_key=?", key) != null) return;
        try { db.update("INSERT INTO ai_usage_budget(period_key) VALUES(?)", key); }
        catch (DuplicateKeyException alreadyCreated) { /* another request created it first */ }
    }

    private static long number(Map<String, Object> row, String key) {
        return row != null && row.get(key) instanceof Number n ? n.longValue() : 0;
    }
}
