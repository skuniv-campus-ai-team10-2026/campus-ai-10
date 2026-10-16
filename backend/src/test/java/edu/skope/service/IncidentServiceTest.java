package edu.skope.service;

import edu.skope.repository.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IncidentServiceTest {
    private IncidentTestFixture f;
    @BeforeEach void setUp() { f = new IncidentTestFixture(); }

    @Test void threeReportsBecomeOneIncidentOnlyAfterAdministratorReviewAndCloseTogether() {
        long a = f.wifi(), b = f.wifi(), c = f.wifi();
        assertNull(f.report(a).get("incidentId"));
        long ab = f.pair(a, b), bc = f.pair(b, c);
        f.tx(() -> f.incidents.reviewPair(ab, "SAME_INCIDENT", f.admin));
        f.tx(() -> f.incidents.reviewPair(bc, "SAME_INCIDENT", f.admin));
        long incident = f.incidentId(a);
        assertEquals(incident, f.incidentId(b)); assertEquals(incident, f.incidentId(c));
        assertEquals(1, Db.id(f.db.one("SELECT COUNT(*) AS total FROM incidents"), "total"));
        assertEquals(3, f.incidents.detail(incident).get("reportCount"));
        f.resolve(a); f.resolve(b);
        assertEquals("OPEN", f.incidents.detail(incident).get("status"));
        f.resolve(c);
        assertEquals("RESOLVED", f.incidents.detail(incident).get("status"));
        assertNotNull(f.incidents.detail(incident).get("closedAt"));
        assertEquals(f.admin, Db.id(f.db.one("SELECT * FROM similar_pairs WHERE id=?", ab), "reviewedBy"));
        assertNotNull(f.db.one("SELECT * FROM similar_pairs WHERE id=?", ab).get("reviewedAt"));
        assertEquals(7, Db.id(f.db.one("SELECT COUNT(*) AS total FROM report_events"), "total"));
    }

    @Test void separateOpenIncidentsAndClosedIncidentsCannotBeAutomaticallyMerged() {
        long a = f.wifi(), b = f.wifi(); f.start(a); f.start(b);
        long ab = f.pair(a, b);
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.reviewPair(ab, "SAME_INCIDENT", f.admin)));
        assertNotEquals(f.incidentId(a), f.incidentId(b));
        assertEquals("CANDIDATE", f.db.one("SELECT decision FROM similar_pairs WHERE id=?", ab).get("decision"));
        f.resolve(a);
        long c = f.wifi(), ac = f.pair(a, c);
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.reviewPair(ac, "SAME_INCIDENT", f.admin)));
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.link(c, f.incidentId(a), f.admin)));
        assertNull(f.report(c).get("incidentId"));
    }

    @Test void partialResolutionStillAllowsNewReportToJoinOpenIncidentButNotAnotherRoom() {
        long a = f.wifi(), b = f.wifi();
        f.tx(() -> f.incidents.reviewPair(f.pair(a, b), "SAME_INCIDENT", f.admin));
        f.resolve(a);
        long c = f.wifi();
        f.tx(() -> f.incidents.reviewPair(f.pair(a, c), "SAME_INCIDENT", f.admin));
        assertEquals(f.incidentId(a), f.incidentId(c));
        long otherRoom = f.report("IT", "WIFI", "북악관", "5", "북악관 503호", null);
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.link(otherRoom, f.incidentId(a), f.admin)));
        assertNull(f.report(otherRoom).get("incidentId"));
    }

    @Test void confirmedLaterOccurrenceCreatesSeparateRecurrenceWithAuditedDecision() {
        long previous = f.pastIncident();
        long fresh = f.wifi(IncidentTestFixture.today().minusDays(1)); f.start(fresh);
        long current = f.incidentId(fresh);
        assertNotEquals(previous, current);
        var candidate = f.incidents.detail(current);
        assertEquals(previous, Db.id(candidate, "previousIncidentId"));
        assertEquals("CANDIDATE", candidate.get("recurrenceDecision"));
        var confirmed = f.tx(() -> f.incidents.recurrence(current, previous, "CONFIRMED", f.admin));
        assertEquals("CONFIRMED", confirmed.get("recurrenceDecision"));
        assertEquals("CONFIRMED_OCCURRENCE", confirmed.get("timeBasis"));
        assertEquals(f.admin, Db.id(confirmed, "recurrenceReviewedBy"));
        assertNotNull(confirmed.get("recurrenceReviewedAt"));
        assertEquals(1, Db.id(f.db.one("SELECT COUNT(*) AS total FROM report_events WHERE event_type='RECURRENCE_REVIEW'"), "total"));
        long oldOccurrence = f.wifi(IncidentTestFixture.today().minusDays(4));
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.link(oldOccurrence, current, f.admin)));
        assertNull(f.report(oldOccurrence).get("incidentId"));
        var rejected = f.tx(() -> f.incidents.recurrence(current, previous, "REJECTED", f.admin));
        assertEquals("REJECTED", rejected.get("recurrenceDecision"));
    }

    @Test void unknownOccurrenceRemainsExplicitlyUncertainBeforeAndAfterReview() {
        long previous = f.pastIncident(); long fresh = f.wifi();
        var pending = (List<Map<String, Object>>) f.incidents.repeated().get("items");
        var provisional = pending.stream().filter(i -> i.get("incidentId") == null).findFirst().orElseThrow();
        assertEquals(previous, Db.id(provisional, "previousIncidentId"));
        assertEquals("REPORTED_AT_ONLY", provisional.get("timeBasis"));
        assertTrue(provisional.get("reasonText").toString().contains("발생일 미확인"));
        f.start(fresh);
        var confirmed = f.tx(() -> f.incidents.recurrence(f.incidentId(fresh), previous, "CONFIRMED", f.admin));
        assertEquals("REPORTED_AT_ONLY", confirmed.get("timeBasis"));
        assertNull(f.report(fresh).get("occurredOn"));
    }

    @Test void recurrenceRejectsOpenPreviousSameDayFutureWrongTypeAndWrongPlace() {
        long a = f.wifi(); f.start(a); long b = f.wifi(); f.start(b);
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(f.incidentId(b), f.incidentId(a), "CONFIRMED", f.admin)));
        long previous = f.pastIncident();
        long sameDay = f.wifi(IncidentTestFixture.today().minusDays(2)); f.start(sameDay);
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(f.incidentId(sameDay), previous, "CONFIRMED", f.admin)));
        long future = f.wifi(IncidentTestFixture.today().plusDays(1)); f.start(future);
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(f.incidentId(future), previous, "CONFIRMED", f.admin)));
        long other = f.report("IT", "WIFI", "형설관", "5", "형설관 502호", IncidentTestFixture.today()); f.start(other);
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(f.incidentId(other), previous, "CONFIRMED", f.admin)));
        long leak = f.report("FACILITY", "LEAK", "북악관", "5", "북악관 502호", IncidentTestFixture.today()); f.start(leak);
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(f.incidentId(leak), previous, "CONFIRMED", f.admin)));
        assertApi("INVALID_RECURRENCE_REFERENCE", () -> f.tx(() -> f.incidents.recurrence(previous, previous, "CONFIRMED", f.admin)));
    }

    @Test void separateAndRejectedPairReviewsDoNotAssignAnIncidentAndAreAudited() {
        long a = f.wifi(), b = f.wifi(), c = f.wifi();
        long ab = f.pair(a, b), ac = f.pair(a, c);
        f.tx(() -> f.incidents.reviewPair(ab, "RELATED_DISTINCT", f.admin));
        f.tx(() -> f.incidents.reviewPair(ac, "REJECTED", f.admin));
        assertNull(f.report(a).get("incidentId")); assertNull(f.report(b).get("incidentId"));
        assertEquals(4, Db.id(f.db.one("SELECT COUNT(*) AS total FROM report_events"), "total"));
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.reviewPair(ab, "SAME_INCIDENT", f.admin)));
        assertApi("VALIDATION_ERROR", () -> f.tx(() -> f.incidents.reviewPair(ab, "AUTO_MERGE", f.admin)));
        assertEquals("RELATED_DISTINCT", f.tx(() -> f.incidents.reviewPair(ab, "RELATED_DISTINCT", f.admin)).get("decision"));
    }

    @Test void failedAuditRollsBackIncidentCreationMembershipAndPairReviewTogether() {
        long a = f.wifi(), b = f.wifi(), pair = f.pair(a, b);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> f.tx(() -> f.incidents.reviewPair(pair, "SAME_INCIDENT", 999999)));
        assertNull(f.report(a).get("incidentId")); assertNull(f.report(b).get("incidentId"));
        assertEquals(0, Db.id(f.db.one("SELECT COUNT(*) AS total FROM incidents"), "total"));
        assertEquals("CANDIDATE", f.db.one("SELECT decision FROM similar_pairs WHERE id=?", pair).get("decision"));
        assertEquals(0, Db.id(f.db.one("SELECT COUNT(*) AS total FROM report_events"), "total"));
    }

    @Test void manualOrTransitiveMembershipCannotBypassReviewedDistinctPair() {
        long a = f.wifi(), b = f.wifi(), c = f.wifi();
        long ab = f.pair(a, b);
        f.tx(() -> f.incidents.reviewPair(ab, "RELATED_DISTINCT", f.admin));
        long ac = f.pair(a, c), bc = f.pair(b, c);
        var provisional = (List<Map<String, Object>>) f.incidents.repeated().get("items");
        assertTrue(provisional.stream().noneMatch(group -> {
            var ids = (List<?>) group.get("reportIds"); return ids.contains(a) && ids.contains(b);
        }));
        f.start(a);
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.link(b, f.incidentId(a), f.admin)));
        f.tx(() -> f.incidents.reviewPair(ac, "SAME_INCIDENT", f.admin));
        assertApi("INCIDENT_CONFLICT", () -> f.tx(() -> f.incidents.reviewPair(bc, "SAME_INCIDENT", f.admin)));
        assertNull(f.report(b).get("incidentId"));
        assertEquals("RELATED_DISTINCT", f.db.one("SELECT decision FROM similar_pairs WHERE id=?", ab).get("decision"));
        assertEquals("CANDIDATE", f.db.one("SELECT decision FROM similar_pairs WHERE id=?", bc).get("decision"));
    }

    @Test void concurrentFinalResolutionsCloseTheIncidentWithoutLostAggregateUpdate() throws Exception {
        long a = f.wifi(), b = f.wifi();
        f.tx(() -> f.incidents.reviewPair(f.pair(a, b), "SAME_INCIDENT", f.admin));
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(a, b).stream().map(id -> executor.submit(() -> f.tx(() -> {
                f.db.one("SELECT id FROM reports WHERE id=? FOR UPDATE", id);
                f.db.update("UPDATE reports SET status='RESOLVED',resolution_text='동시 처리 테스트' WHERE id=?", id);
                ready.countDown();
                try { if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent update did not start"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                f.incidents.refresh(f.incidentId(id));
                return null;
            }))).toList();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertEquals("RESOLVED", f.incidents.detail(f.incidentId(a)).get("status"));
    }

    private static void assertApi(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).code);
    }
}
