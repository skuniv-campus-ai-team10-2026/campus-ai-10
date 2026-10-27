package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.service.ApiException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The model response contract for the optional work-group fields. Stubbed responses only, zero provider calls. */
class WorkGroupContractTest {
    static final ObjectMapper JSON = new ObjectMapper();

    /** A LIVE service whose provider is replaced by a fixed report; the request body is captured. */
    static final class Stub extends AiService {
        final Map<String, Object> report; Map<String, Object> body;
        Stub(Map<String, Object> report) { super("LIVE", "http://127.0.0.1:1", "offline-only", "chat-test", "RULE_ONLY", "", 1); this.report = report; }
        @Override protected JsonNode complete(Map<String, Object> body) {
            this.body = body;
            try {
                String content = JSON.writeValueAsString(Map.of("intent", "REPORT", "report", report));
                return JSON.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
    static Map<String, Object> base(String category, String subcategory) {
        var r = new LinkedHashMap<String, Object>();
        r.put("category", category); r.put("subcategory", subcategory); r.put("locationText", "북악관 502호");
        r.put("building", "북악관"); r.put("floor", "5"); r.put("symptom", "연결이 자꾸 끊겨요"); r.put("occurredAtText", null);
        r.put("missingFields", List.of()); r.put("followUpQuestion", null); r.put("emergencyHint", false);
        return r;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> report(Map<String, Object> result) { return (Map<String, Object>) result.get("report"); }

    @Test void olderResponsesWithoutNewFieldsStayValid() {
        var result = new Stub(base("IT", "WIFI")).analyze(Map.of("text", "북악관 502호 와이파이가 자꾸 끊겨요"));
        assertEquals("IT_SUPPORT", report(result).get("workGroup"));
        assertNull(report(result).get("targetText")); assertNull(report(result).get("campusRelevance"));
    }

    @Test void narrowTypesOverrideAContradictingGroup() {
        var r = base("IT", "WIFI"); r.put("workGroup", "FACILITY_MAINTENANCE"); r.put("targetText", "공유기"); r.put("campusRelevance", "CAMPUS_FACILITY");
        var result = new Stub(r).analyze(Map.of("text", "북악관 502호 와이파이가 자꾸 끊겨요"));
        assertEquals("IT_SUPPORT", report(result).get("workGroup"));
        assertEquals("공유기", report(result).get("targetText"));
    }

    @Test void unknownTypeKeepsTheModelsAllowedGroup() {
        var r = base("OTHER", "UNKNOWN"); r.put("symptom", "거울이 깨졌어요"); r.put("workGroup", "FACILITY_MAINTENANCE");
        r.put("targetText", "거울"); r.put("campusRelevance", "CAMPUS_FACILITY");
        var result = new Stub(r).analyze(Map.of("text", "북악관 502호 거울이 깨졌어요"));
        assertEquals("FACILITY_MAINTENANCE", report(result).get("workGroup"));
    }

    @Test void inventedCodesAndOverlongTargetsAreRejected() {
        for (var change : List.<Map<String, Object>>of(Map.of("workGroup", "총무처"), Map.of("campusRelevance", "SCHOOL"),
                Map.of("targetText", "가".repeat(101)), Map.of("departmentName", "총무처"))) {
            var r = base("OTHER", "UNKNOWN"); r.putAll(change);
            var e = assertThrows(ApiException.class, () -> new Stub(r).analyze(Map.of("text", "북악관 502호 거울이 깨졌어요")), change.toString());
            assertEquals("AI_UNAVAILABLE", e.code);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void strictSchemaRequiresTheNewFieldsWithFixedValues() {
        var stub = new Stub(base("IT", "WIFI"));
        stub.analyze(Map.of("text", "북악관 502호 와이파이가 자꾸 끊겨요", "hybrid", false));
        var format = (Map<String, Object>) stub.body.get("response_format");
        var schema = (Map<String, Object>) ((Map<String, Object>) format.get("json_schema")).get("schema");
        var report = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("report");
        var properties = (Map<String, Object>) report.get("properties");
        assertTrue(((List<String>) report.get("required")).containsAll(List.of("workGroup", "targetText", "campusRelevance")));
        assertEquals(edu.skope.domain.Codes.WORK_GROUPS, ((Map<String, Object>) properties.get("workGroup")).get("enum"));
        assertEquals(Set.copyOf(edu.skope.domain.Codes.CAMPUS_RELEVANCE), Set.copyOf((List<String>) ((Map<String, Object>) properties.get("campusRelevance")).get("enum")));
    }

    @Test void clientDraftsMayCarryTheFieldsButOnlyValidValues() {
        var demo = new AiService("DEMO", "", "", "", "RULE_ONLY", "", 1);
        var first = demo.analyze(Map.of("text", "연필이 부러졌어요", "hybrid", true));
        var previous = new LinkedHashMap<>(report(first)); previous.put("workGroup", "NOT_A_GROUP");
        var e = assertThrows(ApiException.class, () -> demo.analyze(Map.of("text", "연필이 부러졌어요", "hybrid", true,
                "followUp", Map.of("field", "CATEGORY", "answer", "학교 비품이에요", "previousReport", previous, "state", first.get("followUpState")))));
        assertEquals("VALIDATION_ERROR", e.code);
    }
}
