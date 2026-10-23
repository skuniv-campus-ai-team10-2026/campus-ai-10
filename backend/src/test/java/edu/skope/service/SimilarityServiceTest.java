package edu.skope.service;

import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 20 hand-labeled candidate fixtures evaluate rules only, not a live embedding model's accuracy. */
class SimilarityServiceTest {
    private IncidentTestFixture f;
    private AiService ai;
    private SimilarityService service;

    @BeforeEach void setUp() {
        f = new IncidentTestFixture();
        ai = mock(AiService.class);
        when(ai.embeddingMode()).thenReturn("RULE_ONLY");
        service = new SimilarityService(f.db, ai, .82, 30);
    }

    record LabeledPair(String label, String category, String subtype, String building,
                       String floor, String location, int dayOffset, boolean candidate) {
        @Override public String toString() { return label; }
    }

    static Stream<LabeledPair> labeledPairs() {
        return Stream.of(
                new LabeledPair("01 같은 장애 다른 표현", "IT", "WIFI", "북악관", "5", "북악관 502호 와이파이 끊김", 0, true),
                new LabeledPair("02 무선 인터넷 접속 불가", "IT", "WIFI", "북악관", "5", "북악관 502호", 0, true),
                new LabeledPair("03 위치 공백 정규화", "IT", "WIFI", "북 악 관", "5", "북악관 502 호", 0, true),
                new LabeledPair("04 같은 건물 호실 미확인", "IT", "WIFI", "북악관", "5", "북악관 5층", 0, true),
                new LabeledPair("05 층 미확인 같은 호실", "IT", "WIFI", "북악관", null, "북악관 502호", 0, true),
                new LabeledPair("06 층 표기 정규화", "IT", "WIFI", "북악관", "5층", "북악관 502호", 0, true),
                new LabeledPair("07 다른 건물", "IT", "WIFI", "형설관", "5", "형설관 502호", 0, false),
                new LabeledPair("08 다른 유형 누수", "FACILITY", "LEAK", "북악관", "5", "북악관 502호", 0, false),
                new LabeledPair("09 다른 유형 전기", "FACILITY", "ELECTRIC", "북악관", "5", "북악관 502호", 0, false),
                new LabeledPair("10 분류 불가", "OTHER", "UNKNOWN", "북악관", "5", "북악관 502호", 0, false),
                new LabeledPair("11 다른 층", "IT", "WIFI", "북악관", "6", "북악관 602호", 0, false),
                new LabeledPair("12 다른 호실", "IT", "WIFI", "북악관", "5", "북악관 503호", 0, false),
                new LabeledPair("13 건물 미확인", "IT", "WIFI", null, "5", "502호", 0, false),
                new LabeledPair("14 건물만 확인", "IT", "WIFI", "북악관", null, "북악관", 0, true),
                new LabeledPair("15 빈 건물 필드", "IT", "WIFI", "", "5", "북악관 502호", 0, false),
                new LabeledPair("16 과거 시간창 밖", "IT", "WIFI", "북악관", "5", "북악관 502호", -31, false),
                new LabeledPair("17 이후 시간창 밖", "IT", "WIFI", "북악관", "5", "북악관 502호", 31, false),
                new LabeledPair("18 과거 시간창 안", "IT", "WIFI", "북악관", "5", "북악관 502호", -29, true),
                new LabeledPair("19 경계 30일", "IT", "WIFI", "북악관", "5", "북악관 502호", 30, true),
                new LabeledPair("20 같은 건물 다른 지하층", "IT", "WIFI", "북악관", "B1", "북악관 지하 1층", 0, false));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("labeledPairs")
    void twentyHandLabeledRulePairsHaveExpectedCandidatesWithoutFabricatedScores(LabeledPair label) {
        long base = f.wifi();
        long other = f.report(label.category, label.subtype, label.building, label.floor, label.location, null);
        var created = SimilarityService.timestamp(f.report(base).get("createdAt"));
        f.db.update("UPDATE reports SET created_at=? WHERE id=?", Timestamp.valueOf(created.plusDays(label.dayOffset)), other);
        service.index(base);
        var rows = items(service.similar(base));
        assertEquals(label.candidate ? 1 : 0, rows.size());
        if (label.candidate) {
            assertNull(rows.getFirst().get("similarityScore"));
            assertEquals("RULE_ONLY", rows.getFirst().get("aiMode"));
            assertTrue(rows.getFirst().get("reasonText").toString().contains("접수일은 발생일이 아님"));
            assertEquals(other, Db.id(rows.getFirst(), "relatedReportId"));
        }
        assertNull(f.report(base).get("incidentId"));
        assertNull(f.report(other).get("incidentId"));
        var diagnostic = SimilarityService.diagnose(f.report(base), f.report(other), .82, 30);
        assertEquals(label.candidate, diagnostic.get("baselineCandidate"));
        assertNull(diagnostic.get("rawCosine"));
    }

    @Test void actualVectorMathPathPersistsModelAndScoreButTestProviderIsMock() {
        when(ai.embeddingMode()).thenReturn("LIVE");
        when(ai.embeddingModel()).thenReturn("mock-fixture-model");
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, .2, 0}, "mock-fixture-model"));
        long a = f.wifi(), b = f.wifi();
        service.index(a); service.index(b);
        var rows = items(service.similar(a));
        assertEquals(1, rows.size());
        assertEquals(1.0, ((Number) rows.getFirst().get("similarityScore")).doubleValue(), .0001);
        assertEquals("LIVE", rows.getFirst().get("aiMode"));
        assertEquals("mock-fixture-model", f.report(a).get("embeddingModel"));
        assertNotNull(f.report(a).get("embeddingJson"));
        var pair = f.db.one("SELECT * FROM similar_pairs");
        assertTrue(Db.id(pair, "leftReportId") < Db.id(pair, "rightReportId"));
        service.index(a);
        assertEquals(1, items(service.similar(a)).size());
        verify(ai, times(2)).embed(anyString());
    }
    @Test void questionsAndUncertainReportsAreNotEmbeddedOrOfferedAsIncidentCandidates(){
        long question=f.wifi(), uncertain=f.wifi(), normal=f.wifi();
        f.db.update("UPDATE reports SET request_kind='QUESTION' WHERE id=?",question);
        f.db.update("UPDATE reports SET review_note='장소 충돌 확인 필요' WHERE id=?",uncertain);
        service.index(question);service.index(uncertain);
        verifyNoInteractions(ai);
        service.index(normal);
        assertTrue(items(service.similar(normal)).isEmpty());
    }

    @Test void modelOrDimensionMismatchFallsBackToNullScore() {
        when(ai.embeddingMode()).thenReturn("LIVE");
        long a = f.wifi(), b = f.wifi();
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "model-a"),
                new AiService.Embedding(new double[]{1, 0}, "model-b"));
        service.index(a); service.index(b);
        assertNull(items(service.similar(a)).getFirst().get("similarityScore"));
        assertNull(SimilarityService.score(Map.of("embeddingModel", "same", "embeddingJson", "[1,0]"),
                Map.of("embeddingModel", "same", "embeddingJson", "[1,0,0]")));
    }

    @Test void vectorBelowThresholdRemovesOnlyUnreviewedFallbackCandidate() {
        when(ai.embeddingMode()).thenReturn("LIVE");
        long a = f.wifi(), b = f.wifi();
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "test"),
                new AiService.Embedding(new double[]{0, 1}, "test"));
        service.index(a);
        assertEquals(1, items(service.similar(a)).size());
        service.index(b);
        assertTrue(items(service.similar(a)).isEmpty());
        long pair = f.pair(a, b);
        f.tx(() -> f.incidents.reviewPair(pair, "RELATED_DISTINCT", f.admin));
        service.index(b);
        assertEquals("RELATED_DISTINCT", items(service.similar(a)).getFirst().get("decision"));
    }

    @Test void embeddingFailureDoesNotRemoveAcceptedReportAndRetainsHonestRuleOnlyFallback() {
        when(ai.embeddingMode()).thenReturn("LIVE");
        long a = f.wifi(), b = f.wifi();
        when(ai.embed(anyString())).thenThrow(new ApiException(503, "AI_UNAVAILABLE", "Simulated provider outage"));
        assertDoesNotThrow(() -> service.index(b));
        assertNotNull(f.report(b));
        assertEquals("RECEIVED", f.report(b).get("status"));
        var row = items(service.similar(b)).getFirst();
        assertNull(row.get("similarityScore")); assertEquals("RULE_ONLY", row.get("aiMode"));
    }

    @Test void vectorValidationRejectsMalformedZeroNonfiniteAndDimensionsAndAvoidsOverflow() {
        assertFalse(SimilarityService.validVector(new double[]{0, 0}));
        assertFalse(SimilarityService.validVector(new double[]{Double.NaN, 1}));
        assertFalse(SimilarityService.validVector(new double[]{Double.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class, () -> SimilarityService.cosine(new double[]{1}, new double[]{1, 2}));
        assertEquals(1, SimilarityService.cosine(new double[]{1e300, 1e300}, new double[]{1e300, 1e300}), 1e-12);
        assertEquals(-1, SimilarityService.cosine(new double[]{1, 0}, new double[]{-1, 0}), 1e-12);
        assertNull(SimilarityService.score(Map.of("embeddingModel", "m", "embeddingJson", "[\"1\",0]"),
                Map.of("embeddingModel", "m", "embeddingJson", "[1,0]")));
        assertNull(SimilarityService.score(Map.of("embeddingModel", "m", "embeddingJson", "not-json"),
                Map.of("embeddingModel", "m", "embeddingJson", "[1,0]")));
    }

    @Test void diagnosticsRetainRawScoreAndEveryBlockingGateEvenWhenNoCandidateExists() {
        var left = diagnosticReport("청운관", "4", "청운관 407호", new double[]{1, 0});
        var right = diagnosticReport("청운관", "4층", "청운관 407 호", new double[]{.8, .6});
        var below = SimilarityService.diagnose(left, right, .82, 30);
        assertEquals(.8, (Double) below.get("rawCosine"), 1e-12);
        assertEquals(List.of("EMBEDDING_THRESHOLD"), below.get("excludedBy"));
        assertEquals(false, below.get("candidate"));
        right.put("category", "FACILITY"); right.put("subcategory", "ELECTRIC");
        right.put("building", "유담관"); right.put("floor", "5"); right.put("locationText", "유담관 507호");
        right.put("createdAt", LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(31));
        var blocked = SimilarityService.diagnose(left, right, .82, 30);
        assertEquals(List.of("TYPE", "BUILDING", "FLOOR", "ROOM", "TIME_WINDOW", "EMBEDDING_THRESHOLD"), blocked.get("excludedBy"));
        assertEquals(.8, (Double) blocked.get("rawCosine"), 1e-12);
    }

    @Test void reindexRetriesInvalidVectorsAndRefreshesOnlyWhenTheConfiguredModelChanges() {
        when(ai.embeddingMode()).thenReturn("LIVE");
        long report = f.wifi();
        when(ai.embeddingModel()).thenReturn("current-model");
        f.db.update("UPDATE reports SET embedding_model=?,embedding_json=? WHERE id=?", "current-model", "[0,0]", report);
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "current-model"),
                new AiService.Embedding(new double[]{0, 1}, "next-model"));
        service.index(report); service.index(report);
        verify(ai, times(1)).embed(anyString());
        when(ai.embeddingModel()).thenReturn("next-model");
        service.index(report); service.index(report);
        verify(ai, times(2)).embed(anyString());
        assertEquals("next-model", f.report(report).get("embeddingModel"));
    }

    @Test void failedModelRefreshRetainsStoredVectorButDowngradesOnlyUnreviewedCandidate() {
        when(ai.embeddingMode()).thenReturn("LIVE"); when(ai.embeddingModel()).thenReturn("old-model");
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "old-model"));
        long a = f.wifi(), b = f.wifi(), c = f.wifi();
        service.index(a); service.index(b); service.index(c);
        var reviewed = f.db.one("SELECT id FROM similar_pairs WHERE left_report_id=? AND right_report_id=?", a, c);
        f.tx(() -> f.incidents.reviewPair(Db.id(reviewed, "id"), "RELATED_DISTINCT", f.admin));
        when(ai.embeddingModel()).thenReturn("new-model");
        when(ai.embed(anyString())).thenThrow(new ApiException(503, "AI_UNAVAILABLE", "Simulated provider outage"));
        service.index(a);
        var unreviewed = f.db.one("SELECT * FROM similar_pairs WHERE left_report_id=? AND right_report_id=?", a, b);
        assertNull(unreviewed.get("similarityScore"));
        assertTrue(unreviewed.get("reasonText").toString().contains("RULE_ONLY"));
        assertEquals("old-model", f.report(a).get("embeddingModel"));
        assertNotNull(f.report(a).get("embeddingJson"));
        var preserved = f.db.one("SELECT * FROM similar_pairs WHERE id=?", reviewed.get("id"));
        assertEquals("RELATED_DISTINCT", preserved.get("decision"));
        assertEquals(1.0, ((Number) preserved.get("similarityScore")).doubleValue(), 1e-12);
    }

    @Test void explicitRuleOnlyReindexDoesNotCallProviderOrPresentOldScoreAsActiveLiveComparison() {
        when(ai.embeddingMode()).thenReturn("LIVE"); when(ai.embeddingModel()).thenReturn("stored-model");
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "stored-model"));
        long a = f.wifi(), b = f.wifi(); service.index(a); service.index(b);
        when(ai.embeddingMode()).thenReturn("RULE_ONLY"); service.index(a);
        verify(ai, times(2)).embed(anyString());
        var row = items(service.similar(a)).getFirst();
        assertNull(row.get("similarityScore")); assertEquals("RULE_ONLY", row.get("aiMode"));
        assertEquals("stored-model", f.report(a).get("embeddingModel"));
    }

    @Test void connectivitySupportRecoversAReviewCandidateWithoutChangingTheGeneralThreshold() {
        when(ai.embeddingMode()).thenReturn("LIVE"); when(ai.embeddingModel()).thenReturn("test-model");
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1, 0}, "test-model"),
                new AiService.Embedding(new double[]{.75, Math.sqrt(1 - .75 * .75)}, "test-model"));
        long a = f.wifi(), b = f.wifi();
        f.db.update("UPDATE reports SET original_text=?,symptom=? WHERE id=?", "북악관 502호 와이파이가 수시로 끊깁니다", "연결 끊김", a);
        f.db.update("UPDATE reports SET original_text=?,symptom=? WHERE id=?", "북악관 502호 무선 인터넷이 단절됩니다", "접속 단절", b);
        service.index(a); service.index(b);
        var row = items(service.similar(a)).getFirst();
        assertEquals(.75, ((Number) row.get("similarityScore")).doubleValue(), 1e-12);
        assertEquals("LIVE", row.get("aiMode"));
        assertTrue(row.get("reasonText").toString().contains("연결 장애 증상 일치 보조 기준"));
        var diagnostic = SimilarityService.diagnose(f.report(a), f.report(b), .82, 30);
        assertEquals(false, diagnostic.get("baselineCandidate")); assertEquals(true, diagnostic.get("candidate"));
        assertEquals("CONNECTIVITY_SYMPTOM_SUPPORT", diagnostic.get("decisionBasis"));
        assertEquals(List.of("EMBEDDING_THRESHOLD"), diagnostic.get("baselineExcludedBy"));
        assertNull(f.report(a).get("incidentId")); assertNull(f.report(b).get("incidentId"));
    }

    @Test void connectivitySupportNeverBypassesItsScoreFloorOrPlaceTypeAndTimeGates() {
        var left = diagnosticReport("은주2관", "6", "은주2관 612호", new double[]{1, 0});
        var right = diagnosticReport("은주2관", "6", "은주2관 612호", new double[]{.69, Math.sqrt(1 - .69 * .69)});
        left.put("originalText", "와이파이 연결이 자꾸 끊겨요"); right.put("originalText", "무선망이 계속 단절됩니다");
        assertEquals(false, SimilarityService.diagnose(left, right, .82, 30).get("candidate"));
        right.put("embeddingJson", java.util.Arrays.toString(new double[]{.71, Math.sqrt(1 - .71 * .71)}));
        assertEquals(true, SimilarityService.diagnose(left, right, .82, 30).get("candidate"));
        right.put("locationText", "은주2관 613호");
        assertEquals(List.of("ROOM"), SimilarityService.diagnose(left, right, .82, 30).get("excludedBy"));
        right.put("locationText", "은주2관 612호"); right.put("createdAt", LocalDateTime.of(2026, 2, 1, 0, 0));
        assertEquals(List.of("TIME_WINDOW"), SimilarityService.diagnose(left, right, .82, 30).get("excludedBy"));
        right.put("createdAt", left.get("createdAt")); right.put("category", "FACILITY"); right.put("subcategory", "ELECTRIC");
        assertEquals(false, SimilarityService.diagnose(left, right, .82, 30).get("candidate"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "은주2관 612호 무선 인터넷 접속이 자꾸 끊깁니다.", "은주2관 와이파이가 불안정합니다.",
            "무선망 재접속을 계속 반복합니다.", "무선망이 수업 중 꺼져요.", "와이파이 연결이 안 됩니다."})
    void explicitConnectivityFailureIncludesSeveralFailureExpressions(String text) {
        var report = diagnosticReport("은주2관", "6", "은주2관 612호", new double[]{1, 0}); report.put("originalText", text);
        assertTrue(SimilarityService.explicitConnectivityFailure(report));
    }

    @ParameterizedTest @ValueSource(strings = {
            "인터넷이 끊기지 않습니다.", "무선망이 단절되지 않았고 정상입니다.", "와이파이가 불안정하지 않습니다.",
            "와이파이 연결을 끊는 방법이 궁금합니다.", "와이파이 비밀번호가 게시판에 노출됐습니다.",
            "와이파이가 끊겨서 비밀번호 변경도 요청합니다.", "와이파이 안내 스티커에 끊김이라고 적혀 있습니다.",
            "모니터 화면만 끊기고 와이파이는 정상입니다.", "와이파이는 아니고 전등이 꺼집니다."})
    void ambiguousDistinctOrNegatedProblemsDoNotUseTheRecallSupportRule(String text) {
        var left = diagnosticReport("은주2관", "6", "은주2관 612호", new double[]{1, 0});
        var right = diagnosticReport("은주2관", "6", "은주2관 612호", new double[]{.75, Math.sqrt(1 - .75 * .75)});
        left.put("originalText", "은주2관 와이파이 연결이 자꾸 끊깁니다."); right.put("originalText", text);
        assertFalse(SimilarityService.explicitConnectivityFailure(right));
        assertEquals(false, SimilarityService.diagnose(left, right, .82, 30).get("candidate"));
    }

    private static Map<String, Object> diagnosticReport(String building, String floor, String location, double[] vector) {
        var result = new HashMap<String, Object>();
        result.put("category", "IT"); result.put("subcategory", "WIFI");
        result.put("building", building); result.put("floor", floor); result.put("locationText", location);
        result.put("embeddingModel", "fixture-model"); result.put("embeddingJson", java.util.Arrays.toString(vector));
        result.put("createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }
}
