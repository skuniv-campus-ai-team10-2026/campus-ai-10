package edu.skope.service;

import edu.skope.config.SeedData;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed expectations E1–E15 of the work-group design (업무그룹-분류-설계안.md), written before relying on them.
 * DEMO classifier + server policy on a real database (H2, or MySQL when TEST_DB_URL is set). No paid calls:
 * this proves the server contract only, not LIVE model quality.
 */
@SpringBootTest
class WorkGroupRoutingIntegrationTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:work_group;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> UUID.randomUUID().toString());
        p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.routing.demo-enabled", () -> true);
    }

    @Autowired Db db; @Autowired SeedData seed; @Autowired ReportService reports; @Autowired IntakeService intake;
    long student, supervisor;

    @BeforeEach void clean() {
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments");
        db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        seed.run();
        student = Db.id(db.one("SELECT id FROM users WHERE username='student1'"), "id");
        supervisor = Db.id(db.one("SELECT id FROM users WHERE username='admin'"), "id");
        db.update("UPDATE users SET active=TRUE,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END,department_id=NULL WHERE username IN ('student1','admin')");
    }

    /** One natural-language conversation: first text, then each answer to the server's question. */
    final class Talk {
        final MockHttpSession session = new MockHttpSession();
        final String key = UUID.randomUUID().toString();
        Map<String, Object> last;
        Talk(String text) { last = intake.intake(student, session, new LinkedHashMap<>(Map.of("clientRequestId", key, "text", text))); }
        Talk answer(String answer) {
            last = intake.intake(student, session, new LinkedHashMap<>(Map.of("clientRequestId", key, "answer", answer, "revision", last.get("revision"))));
            return this;
        }
        String status() { return (String) last.get("status"); }
        @SuppressWarnings("unchecked") Map<String, Object> state() { return (Map<String, Object>) ((Map<String, Object>) last.get("analysis")).get("followUpState"); }
        @SuppressWarnings("unchecked") Map<String, Object> report() { return (Map<String, Object>) last.get("report"); }
        Map<String, Object> admin() { return reports.detail(Db.id(report(), "id"), supervisor, true); }
    }

    static void scope(Map<String, Object> report, String group, String target, String relevance) {
        assertEquals(group, report.get("workGroup"), "workGroup");
        assertEquals(target, report.get("targetText"), "targetText");
        assertEquals(relevance, report.get("campusRelevance"), "campusRelevance");
    }

    @Test void e1NewFixtureIsAcceptedUnderAWorkGroupWithoutAQuestion() {
        var talk = new Talk("은주2관 3층 화장실 거울이 깨졌어요");
        assertEquals("ACCEPTED", talk.status());
        var report = talk.report();
        assertEquals("OTHER", report.get("category")); assertEquals("UNKNOWN", report.get("subcategory"));
        scope(report, "FACILITY_MAINTENANCE", "거울", "CAMPUS_FACILITY");
        assertTrue(report.get("symptom").toString().contains("깨졌"));
        assertEquals("REPORT", report.get("requestKind"));
        assertNull(report.get("assignedDepartment"), "a work group is never an automatic assignment rule");
        assertEquals("NO_RULE", talk.admin().get("unassignedReason"));
        assertFalse(talk.last.containsKey("scopeNotice"));
    }

    @Test void e2LoanedItemNeedsNoFaultLocation() {
        var talk = new Talk("학교에서 빌린 실습복이 찢어져 있어요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "SHARED_EQUIPMENT", "실습복", "SCHOOL_SERVICE");
        assertNull(talk.report().get("building"));
        assertEquals("REPORT", talk.report().get("requestKind"));
        assertEquals("NO_RULE", talk.admin().get("unassignedReason"));
    }

    @Test void e3LeftPersonalItemIsAGeneralInquiry() {
        var talk = new Talk("강의실에 옷을 두고 왔어요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "GENERAL_REVIEW", "옷", "PERSONAL_ON_CAMPUS");
        assertEquals("QUESTION", talk.report().get("requestKind"));
        assertNull(talk.report().get("assignedDepartment"));
        assertEquals("GENERAL_INQUIRY", talk.admin().get("unassignedReason"));
        assertFalse(talk.last.containsKey("scopeNotice"), "a personal item is not called out of scope");
    }

    @Test void e4OwnershipIsAskedOnceAndASchoolAnswerProceeds() {
        var talk = new Talk("연필이 부러졌어요");
        assertEquals("NEEDS_INPUT", talk.status());
        assertEquals("CAMPUS_RELEVANCE", talk.state().get("nextReason"));
        assertEquals("CATEGORY", talk.state().get("nextField"));
        talk.answer("학교 비품이에요. 북악관 302호예요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "SHARED_EQUIPMENT", "연필", "CAMPUS_FACILITY");
        assertEquals("북악관", talk.report().get("building"));
        assertEquals("연필이 부러졌어요", talk.report().get("symptom"), "an ownership answer is not a new symptom");
        assertEquals("NO_RULE", talk.admin().get("unassignedReason"));
    }

    @Test void e4PersonalAnswerBecomesAGeneralInquiry() {
        var talk = new Talk("연필이 부러졌어요").answer("제 거예요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "GENERAL_REVIEW", "연필", "PERSONAL_ON_CAMPUS");
        assertEquals("QUESTION", talk.report().get("requestKind"));
        assertEquals("GENERAL_INQUIRY", talk.admin().get("unassignedReason"));
    }

    @Test void e4UnknownOwnershipIsNotAskedAgainAndNeedsNoPlace() {
        var talk = new Talk("연필이 부러졌어요").answer("잘 모르겠어요");
        assertEquals("ACCEPTED", talk.status(), "asked once; the supervisor reviews the rest");
        scope(talk.report(), "GENERAL_REVIEW", "연필", "UNCERTAIN");
        assertEquals("AI_UNCLASSIFIED", talk.admin().get("unassignedReason"));
    }

    @Test void e5PurchaseRecommendationIsOutOfScopeWithANotice() {
        var talk = new Talk("제 연필이 부러졌는데 새 연필 추천해 주세요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "GENERAL_REVIEW", "연필", "OUT_OF_SCOPE");
        assertEquals("QUESTION", talk.report().get("requestKind"));
        assertEquals(IntakeService.SCOPE_NOTICE, talk.last.get("scopeNotice"));
        assertEquals("OUT_OF_SCOPE", talk.admin().get("unassignedReason"));
    }

    @Test void e6AndE7ExistingTypesKeepTheirRoutes() {
        var dispenser = new Talk("북악관 3층 정수기가 고장났어요");
        assertEquals("ACCEPTED", dispenser.status());
        assertEquals("WATER_DISPENSER", dispenser.report().get("subcategory"));
        assertEquals("SHARED_EQUIPMENT", dispenser.report().get("workGroup"));
        assertNotNull(dispenser.report().get("assignedDepartment"), "existing demo route unchanged");
        var leak = new Talk("북악관 3층 천장에서 물이 새요");
        assertEquals("ACCEPTED", leak.status());
        assertEquals("LEAK", leak.report().get("subcategory"));
        assertEquals("FACILITY_MAINTENANCE", leak.report().get("workGroup"));
        assertNotNull(leak.report().get("assignedDepartment"));
    }

    @Test void e8SameTargetDifferentSituationsKeepTheirOwnSymptoms() {
        var crack = new Talk("은주2관 3층 화장실 거울이 금 갔어요");
        var loose = new Talk("은주2관 3층 화장실 거울이 흔들려요");
        assertEquals("거울", crack.report().get("targetText")); assertEquals("거울", loose.report().get("targetText"));
        assertTrue(crack.report().get("symptom").toString().contains("금"));
        assertTrue(loose.report().get("symptom").toString().contains("흔들"));
    }

    long pairs() { return Db.id(db.one("SELECT COUNT(*) total FROM similar_pairs"), "total"); }

    @Test void e9DifferentObjectsAtTheSamePlaceAreNotCandidates() {
        new Talk("은주2관 3층 화장실 거울이 깨졌어요");
        new Talk("은주2관 3층 화장실 정수기가 고장났어요");
        new Talk("은주2관 3층 화장실 휴지걸이가 부러졌어요");
        assertEquals(0, pairs(), "same place and same broad group are not evidence without the same object");
    }

    @Test void e10SameObjectBecomesAReviewCandidateOnly() {
        var a = new Talk("은주2관 3층 화장실 거울이 깨졌어요");
        var b = new Talk("은주2관 3층 화장실 거울이 금 갔어요");
        assertEquals(1, pairs());
        var pair = db.one("SELECT * FROM similar_pairs");
        assertEquals("CANDIDATE", pair.get("decision"));
        assertNull(pair.get("similarityScore"), "RULE_ONLY: no embedding score");
        for (var talk : List.of(a, b)) assertNull(db.one("SELECT incident_id FROM reports WHERE id=?", talk.report().get("id")).get("incidentId"));
    }

    @Test void e11WifiCandidatePolicyIsUnchanged() {
        new Talk("북악관 502호 와이파이가 자꾸 끊겨요");
        new Talk("북악관 502호 와이파이가 자꾸 끊겨요");
        assertEquals(1, pairs());
    }

    @Test void e12AVagueFaultStillAsksAsBefore() {
        var talk = new Talk("뭔가 고장났어요");
        assertEquals("NEEDS_INPUT", talk.status());
        assertEquals("LOCATION_REQUIRED", talk.state().get("nextReason"));
        @SuppressWarnings("unchecked") var needed = (List<String>) talk.state().get("neededReasons");
        assertTrue(needed.contains("TARGET_UNCLEAR"));
        assertFalse(needed.contains("CAMPUS_RELEVANCE"), "an unknown object is not an ownership question");
    }

    @Test void e13ALocationAnswerKeepsTargetAndGroup() {
        var talk = new Talk("화장실 거울이 깨졌어요");
        assertEquals("LOCATION_REQUIRED", talk.state().get("nextReason"));
        talk.answer("은주2관 3층이에요");
        assertEquals("ACCEPTED", talk.status());
        scope(talk.report(), "FACILITY_MAINTENANCE", "거울", "CAMPUS_FACILITY");
        assertEquals("은주2관", talk.report().get("building")); assertEquals("3", talk.report().get("floor"));
    }

    Map<String, Object> manual(String category, String subcategory) {
        var p = new LinkedHashMap<String, Object>();
        p.put("clientRequestId", UUID.randomUUID().toString()); p.put("originalText", "수동으로 고친 신고입니다.");
        p.put("category", category); p.put("subcategory", subcategory); p.put("symptom", "연결이 끊겨요");
        p.put("locationText", "북악관 502호"); p.put("building", "북악관"); p.put("floor", "5");
        return p;
    }

    @Test void e14ManualSaveDerivesTheGroupAndDropsOldAnalysis() {
        var p = manual("IT", "WIFI"); p.put("workGroup", "FACILITY_MAINTENANCE");
        var saved = reports.create(student, p);
        assertEquals("IT_SUPPORT", saved.get("workGroup"), "a narrow type always keeps its fixed group");
        assertNull(saved.get("targetText")); assertNull(saved.get("campusRelevance"));
        var stored = db.one("SELECT work_group FROM reports WHERE id=?", saved.get("id"));
        assertEquals("IT_SUPPORT", stored.get("workGroup"));
        var older = reports.create(student, manual("OTHER", "UNKNOWN"));
        assertEquals("GENERAL_REVIEW", older.get("workGroup"), "an old client sending no new fields still saves");
        var bad = manual("OTHER", "UNKNOWN"); bad.put("workGroup", "PLUMBING");
        assertEquals(400, assertThrows(ApiException.class, () -> reports.create(student, bad)).status);
        var badRelevance = manual("OTHER", "UNKNOWN"); badRelevance.put("campusRelevance", "MAYBE");
        assertEquals(400, assertThrows(ApiException.class, () -> reports.create(student, badRelevance)).status);
    }

    @Test void legacyRowsAreDerivedOnReadWithoutReclassification() {
        long leak = db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,location_text,building,floor,symptom,route_status,request_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                student, UUID.randomUUID().toString(), "예전 누수 신고", "FACILITY", "LEAK", "북악관 3층", "북악관", "3", "물이 새요", "NEEDS_REVIEW", "REPORT");
        long other = db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,symptom,route_status,request_kind) VALUES(?,?,?,?,?,?,?,?)",
                student, UUID.randomUUID().toString(), "예전 기타 신고", "OTHER", "UNKNOWN", "엘리베이터가 멈췄어요", "NEEDS_REVIEW", "REPORT");
        assertEquals("FACILITY_MAINTENANCE", reports.detail(leak, supervisor, true).get("workGroup"));
        var unknown = reports.detail(other, supervisor, true);
        assertEquals("GENERAL_REVIEW", unknown.get("workGroup"));
        assertEquals("AI_UNCLASSIFIED", unknown.get("unassignedReason"), "legacy OTHER reports keep their old reason");
        assertNull(db.one("SELECT work_group FROM reports WHERE id=?", other).get("workGroup"), "no bulk write");
    }

    @Test void e15EmergencyKeepsTheImmediateReviewPath() {
        var talk = new Talk("은주2관 화장실 거울이 깨졌는데 타는 냄새가 나요");
        assertEquals("ACCEPTED", talk.status(), "no clarification interview during danger");
        assertEquals(Boolean.TRUE, talk.report().get("emergencyHint"));
        assertEquals("거울", talk.report().get("targetText"));
        assertEquals("REVIEW_HOLD", talk.admin().get("unassignedReason"));
    }
}
