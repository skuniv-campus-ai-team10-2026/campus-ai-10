package edu.skope.service;

import edu.skope.ai.AiService;
import edu.skope.domain.Codes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** New report types and unassigned reasons. Pure logic: no Spring, no database, no paid calls. */
class RoutingTypesAndReasonsTest {
    final AiService demo = new AiService("DEMO", "https://api.openai.com/v1", "", "", "RULE_ONLY", "", 20, 1536, "");

    @Test void newTypesBelongToFacilityOnly() {
        for (String type : new String[]{"HVAC", "WATER_DISPENSER", "FURNITURE"}) {
            assertDoesNotThrow(() -> Codes.type("FACILITY", type));
            assertThrows(ApiException.class, () -> Codes.type("IT", type));
            assertThrows(ApiException.class, () -> Codes.type("OTHER", type));
        }
    }

    /** The broken object decides the department, so a leaking dispenser or air conditioner is not a LEAK. */
    @ParameterizedTest
    @CsvSource({
        "북악관 3층 정수기에서 물이 새요, WATER_DISPENSER",
        "도서관 2층 냉온수기가 고장났어요, WATER_DISPENSER",
        "북악관 502호 에어컨에서 물이 떨어져요, HVAC",
        "청운관 401호 난방이 안 돼요, HVAC",
        "강의실이 너무 추워요 북악관 301호, HVAC",
        "북악관 302호 의자가 부러졌어요, FURNITURE",
        "청운관 201호 책상이 흔들려요, FURNITURE",
        "북악관 3층 천장에서 물이 새요, LEAK",
        "북악관 3층 콘센트가 고장났어요, ELECTRIC",
        "북악관 502호 와이파이가 끊겨요, WIFI",
        "휴학 신청은 어디서 하나요, STUDENT_RECORD",
        "엘리베이터가 멈췄어요 북악관, UNKNOWN"
    })
    void demoClassifierUsesTheBrokenObject(String text, String expected) {
        @SuppressWarnings("unchecked") var report = (Map<String, Object>) demo.analyze(Map.of("text", text)).get("report");
        assertEquals(expected, report.get("subcategory"), text);
        assertEquals(Codes.TYPES.get(expected), report.get("category"));
    }

    static Map<String, Object> report(String subcategory, String category, Long department, String reviewNote,
                                      String kind, String location, String building) {
        var r = new HashMap<String, Object>();
        r.put("subcategory", subcategory); r.put("category", category); r.put("assignedDepartmentId", department);
        r.put("reviewNote", reviewNote); r.put("requestKind", kind); r.put("locationText", location); r.put("building", building);
        return r;
    }

    @Test void assignedReportsHaveNoReason() {
        assertNull(RoutingService.unassignedReason(report("HVAC", "FACILITY", 3L, null, "REPORT", "북악관 3층", "북악관")));
        assertNull(RoutingService.unassignedReason(report("UNKNOWN", "OTHER", 3L, "검토", "REPORT", null, null)), "manual reassignment wins");
    }

    @Test void reasonsFollowTheSameOrderAsReportCreation() {
        assertEquals("REVIEW_HOLD", RoutingService.unassignedReason(report("HVAC", "FACILITY", null, "학생이 관리자 검토를 요청했습니다.", "REPORT", "북악관 3층", "북악관")));
        assertEquals("REVIEW_HOLD", RoutingService.unassignedReason(report("UNKNOWN", "OTHER", null, "AI 분석 실패", "REPORT", null, null)), "review first");
        assertEquals("AI_UNCLASSIFIED", RoutingService.unassignedReason(report("UNKNOWN", "OTHER", null, null, "REPORT", "북악관 3층", "북악관")));
        assertEquals("AI_UNCLASSIFIED", RoutingService.unassignedReason(report("UNKNOWN", "OTHER", null, " ", "REPORT", "북악관 3층", "북악관")), "blank note is no review");
        assertEquals("LOCATION_MISSING", RoutingService.unassignedReason(report("HVAC", "FACILITY", null, null, "REPORT", null, null)));
        assertEquals("LOCATION_MISSING", RoutingService.unassignedReason(report("HVAC", "FACILITY", null, null, "REPORT", "3층", null)));
        assertEquals("NO_RULE", RoutingService.unassignedReason(report("FURNITURE", "FACILITY", null, null, "REPORT", "북악관 302호", "북악관")));
        assertEquals("NO_RULE", RoutingService.unassignedReason(report("STUDENT_RECORD", "ACADEMIC", null, null, "QUESTION", null, null)), "academic needs no place");
        assertEquals("NO_RULE", RoutingService.unassignedReason(report("HVAC", "FACILITY", null, null, "QUESTION", null, null)), "questions need no place");
    }
}
