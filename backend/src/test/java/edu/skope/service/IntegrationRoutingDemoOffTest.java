package edu.skope.service;

import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Default deployment (DEMO_ROUTING_ENABLED=false): new demo-only routes must stay inactive. DEMO AI. */
@SpringBootTest
class IntegrationRoutingDemoOffTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:int_routing_off;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> UUID.randomUUID().toString());
        p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.routing.demo-enabled", () -> false);
    }
    @Autowired Db db; @Autowired ReportService reports; @Autowired RoutingService routes;

    @BeforeEach void clean() {
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events");
        db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
    }

    @Test void demoOnlyRoutesAreNotUsedWhenTheSwitchIsOff() {
        assertNotNull(db.one("SELECT id FROM routing_rules WHERE subcategory='HVAC'"), "rule exists in the table");
        assertNull(routes.find("FACILITY", "HVAC")); assertNull(routes.find("FACILITY", "WATER_DISPENSER"));
        assertTrue(routes.types().stream().noneMatch(r -> Set.of("HVAC", "WATER_DISPENSER", "LEAK").contains(r.get("subcategory"))));
        assertTrue(routes.types().stream().allMatch(r -> "VERIFIED".equals(r.get("assignmentBasis"))));

        long student = Db.id(db.one("SELECT id FROM users WHERE username='student1'"), "id");
        long admin = Db.id(db.one("SELECT id FROM users WHERE username='admin'"), "id");
        var p = new LinkedHashMap<String, Object>();
        p.put("clientRequestId", UUID.randomUUID().toString()); p.put("originalText", "북악관 502호 에어컨이 안 나와요");
        p.put("category", "FACILITY"); p.put("subcategory", "HVAC"); p.put("symptom", "에어컨 고장");
        p.put("locationText", "북악관 502호"); p.put("building", "북악관"); p.put("floor", "5");
        long id = ((Number) reports.create(student, p).get("id")).longValue();
        var view = reports.detail(id, admin, true);
        assertNull(view.get("assignedDepartment")); assertEquals("NO_RULE", view.get("unassignedReason"));
    }

    @Test void contactQuestionsNeverExposeAnUnverifiedNumber() {
        var hvacRule = db.one("SELECT rr.*,d.name department_name FROM routing_rules rr JOIN departments d ON d.id=rr.department_id WHERE rr.subcategory='HVAC'");
        var answer = routes.answer(hvacRule, "냉난방 고장은 어디에 문의하나요?");
        assertEquals(false, answer.get("verified")); assertFalse(answer.containsKey("contactPhone"));
    }
}
