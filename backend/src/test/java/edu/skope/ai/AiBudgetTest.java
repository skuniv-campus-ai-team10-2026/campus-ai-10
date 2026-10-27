package edu.skope.ai;

import edu.skope.repository.Db;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AiBudget arithmetic and failure handling with a mocked database. No network, no paid calls. */
class AiBudgetTest {
    final Db db = mock(Db.class);
    AiBudget budget(String period, double maxUsd) { return new AiBudget(db, period, maxUsd, 10, 10, 0.10, 0.50, 0.02); }

    @Test void costIsTokensTimesPricePerMillionInMicroUsd() {
        var b = budget("TOTAL", 2);
        assertEquals(350, b.cost(AiBudget.Kind.LLM, 1000, 500));      // 1000*0.10 + 500*0.50
        assertEquals(20, b.cost(AiBudget.Kind.EMBEDDING, 1000, 999));  // output ignored for embeddings
        assertEquals(1, b.cost(AiBudget.Kind.EMBEDDING, 1, 0), "always rounded up");
    }

    @Test void periodKeyIsTotalOrTheKoreanDate() {
        assertEquals("TOTAL", budget("TOTAL", 1).periodKey());
        assertEquals(LocalDate.now(ZoneId.of("Asia/Seoul")).toString(), budget("daily", 1).periodKey());
    }

    @Test void invalidSettingsStopStartup() {
        assertThrows(IllegalArgumentException.class, () -> budget("WEEKLY", 1));
        assertThrows(IllegalArgumentException.class, () -> budget("TOTAL", -1));
        assertThrows(IllegalArgumentException.class, () -> new AiBudget(db, "TOTAL", 1, -1, 10, 0.1, 0.5, 0.02));
        assertThrows(IllegalArgumentException.class, () -> new AiBudget(db, "TOTAL", Double.NaN, 1, 10, 0.1, 0.5, 0.02));
    }

    @Test void unreachableBudgetStoreRefusesThePaidCall() {
        when(db.one(anyString(), any())).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        assertThrows(AiBudget.Exceeded.class, () -> budget("TOTAL", 2).reserve(AiBudget.Kind.LLM, 100, 100));
    }

    @Test void refusedReservationIsCountedAndThrows() {
        when(db.one(anyString(), any())).thenReturn(Map.of("periodKey", "TOTAL"));
        when(db.update(startsWith("UPDATE ai_usage_budget SET llm_calls"), any(Object[].class))).thenReturn(0);
        assertThrows(AiBudget.Exceeded.class, () -> budget("TOTAL", 2).reserve(AiBudget.Kind.LLM, 100, 100));
        verify(db).update("UPDATE ai_usage_budget SET blocked_calls=blocked_calls+1 WHERE period_key=?", "TOTAL");
    }

    @Test void unknownUsageKeepsTheWholeReservationAsSpent() {
        var reservation = new AiBudget.Reservation("TOTAL", AiBudget.Kind.LLM, 777);
        budget("TOTAL", 2).settle(reservation, null, null);
        verify(db).update(startsWith("UPDATE ai_usage_budget SET reserved_micro_usd=reserved_micro_usd-?,spent_micro_usd=spent_micro_usd+?,llm_input_tokens"),
            eq(777L), eq(777L), eq(0L), eq(0L), eq("TOTAL"));
    }

    @Test void knownUsageReplacesTheReservationWithTheActualCost() {
        var reservation = new AiBudget.Reservation("TOTAL", AiBudget.Kind.LLM, 5000);
        budget("TOTAL", 2).settle(reservation, 1000L, 200L);
        verify(db).update(startsWith("UPDATE ai_usage_budget SET reserved_micro_usd=reserved_micro_usd-?,spent_micro_usd=spent_micro_usd+?,llm_input_tokens"),
            eq(5000L), eq(200L), eq(1000L), eq(200L), eq("TOTAL"));
    }

    @Test void settlementFailureNeverThrows() {
        when(db.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        assertDoesNotThrow(() -> budget("TOTAL", 2).settle(new AiBudget.Reservation("TOTAL", AiBudget.Kind.EMBEDDING, 10), 5L, 0L));
    }
}
