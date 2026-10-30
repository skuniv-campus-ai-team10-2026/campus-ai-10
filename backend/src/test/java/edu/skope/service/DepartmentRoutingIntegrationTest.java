package edu.skope.service;

import edu.skope.config.SeedData;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** New demo routes on a real database (H2, or MySQL when TEST_DB_URL is set). DEMO AI, no paid calls. */
@SpringBootTest
class DepartmentRoutingIntegrationTest {
    static final String OFFICE = "총무처 건설관리과";

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:department_routing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> UUID.randomUUID().toString());
        p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.routing.demo-enabled", () -> true);
    }

    @Autowired Db db; @Autowired SeedData seed; @Autowired ReportService reports; @Autowired DepartmentAccess access;
    long student, supervisor, office, it;

    @BeforeEach void clean() {
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments");
        db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        db.update("UPDATE departments SET active=TRUE");
        // Start every test from the rules a fresh start creates.
        db.update("DELETE FROM routing_rules WHERE subcategory IN ('HVAC','WATER_DISPENSER','FURNITURE')");
        seed.run();
        student = id("SELECT id FROM users WHERE username='student1'");
        supervisor = id("SELECT id FROM users WHERE username='admin'");
        db.update("UPDATE users SET active=TRUE,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END,department_id=NULL WHERE username IN ('student1','admin')");
        office = id("SELECT id FROM departments WHERE name='" + OFFICE + "'");
        it = id("SELECT id FROM departments WHERE name='정보전산원'");
    }

    long id(String sql) { return Db.id(db.one(sql), "id"); }
    long staff(String name, long department) {
        var u = db.one("SELECT id FROM users WHERE username=?", name);
        if (u == null) return db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)", name, "x", name, "DEPT_ADMIN", department);
        db.update("UPDATE users SET role='DEPT_ADMIN',department_id=?,active=TRUE WHERE id=?", department, Db.id(u, "id"));
        return Db.id(u, "id");
    }
    Map<String, Object> payload(String category, String subcategory, String building, String reviewNote) {
        var p = new LinkedHashMap<String, Object>();
        p.put("clientRequestId", UUID.randomUUID().toString());
        p.put("originalText", "합성 신고: 강의실 설비 문제를 확인해 주세요.");
        p.put("category", category); p.put("subcategory", subcategory); p.put("symptom", "합성 증상");
        if (building != null) { p.put("locationText", building + " 302호"); p.put("building", building); p.put("floor", "3"); }
        if (reviewNote != null) p.put("reviewNote", reviewNote);
        return p;
    }
    Map<String, Object> adminView(String category, String subcategory, String building, String reviewNote) {
        long reportId = ((Number) reports.create(student, payload(category, subcategory, building, reviewNote)).get("id")).longValue();
        return reports.detail(reportId, supervisor, true);
    }

    @Test void startupAddsDemoOnlyRulesWithoutContactsAndNoFurnitureRule() {
        for (String type : List.of("HVAC", "WATER_DISPENSER")) {
            var rule = db.one("SELECT * FROM routing_rules WHERE category='FACILITY' AND subcategory=?", type);
            assertNotNull(rule, type);
            assertEquals(office, Db.id(rule, "departmentId"));
            assertTrue(Boolean.TRUE.equals(rule.get("demoOnly")) || rule.get("demoOnly") instanceof Number n && n.intValue() == 1);
            assertNull(rule.get("contactPhone")); assertNull(rule.get("sourceUrl")); assertNull(rule.get("verifiedAt"));
        }
        assertNull(db.one("SELECT id FROM routing_rules WHERE subcategory='FURNITURE'"));
    }

    @Test void laterStartsNeverOverwriteAnAdministratorsChangeButRestoreAMissingRule() {
        db.update("UPDATE routing_rules SET active=FALSE,work_name='관리자가 수정한 냉난방 규칙' WHERE subcategory='HVAC'");
        db.update("DELETE FROM routing_rules WHERE subcategory='WATER_DISPENSER'");
        seed.run();
        seed.run();
        var hvac = db.all("SELECT * FROM routing_rules WHERE subcategory='HVAC'");
        assertEquals(1, hvac.size());
        assertEquals("관리자가 수정한 냉난방 규칙", hvac.getFirst().get("workName"));
        assertFalse(Boolean.TRUE.equals(hvac.getFirst().get("active")) || hvac.getFirst().get("active") instanceof Number n && n.intValue() == 1);
        assertEquals(1, db.all("SELECT id FROM routing_rules WHERE subcategory='WATER_DISPENSER'").size());
    }

    @Test void newTypesAreAssignedAsDemoAndUnassignedReportsSayWhy() {
        var hvac = adminView("FACILITY", "HVAC", "북악관", null);
        @SuppressWarnings("unchecked") var dept = (Map<String, Object>) hvac.get("assignedDepartment");
        assertEquals(OFFICE, dept.get("name"));
        assertEquals("DEMO", dept.get("assignmentBasis"));
        assertNull(dept.get("contactPhone"), "unverified number is never shown");
        assertNull(hvac.get("unassignedReason"));

        assertEquals(OFFICE, ((Map<?, ?>) adminView("FACILITY", "WATER_DISPENSER", "북악관", null).get("assignedDepartment")).get("name"));
        // FURNITURE still has no type rule; since V11 its work group (SHARED_EQUIPMENT) default (총무처 총무과, department-routes.tsv) assigns it, as demo.
        var furniture = adminView("FACILITY", "FURNITURE", "북악관", null);
        assertEquals("총무처 총무과", ((Map<?, ?>) furniture.get("assignedDepartment")).get("name"));
        assertEquals("DEMO", ((Map<?, ?>) furniture.get("assignedDepartment")).get("assignmentBasis"));
        assertNull(furniture.get("unassignedReason"));
        assertEquals("AI_UNCLASSIFIED", adminView("OTHER", "UNKNOWN", "북악관", null).get("unassignedReason"));
        assertEquals("LOCATION_MISSING", adminView("FACILITY", "HVAC", null, null).get("unassignedReason"));
        assertEquals("REVIEW_HOLD", adminView("FACILITY", "HVAC", "북악관", "학생이 관리자 검토를 요청했습니다.").get("unassignedReason"));
    }

    @Test void unassignedReasonIsNotShownToStudents() {
        long reportId = ((Number) reports.create(student, payload("FACILITY", "FURNITURE", "북악관", null)).get("id")).longValue();
        assertFalse(reports.detail(reportId, student, false).containsKey("unassignedReason"));
    }

    @Test void departmentScopeIsUnchangedForNewTypes() {
        long reportId = ((Number) reports.create(student, payload("FACILITY", "HVAC", "북악관", null)).get("id")).longValue();
        long officeStaff = staff("route_office_staff", office), itStaff = staff("route_it_staff", it);
        assertTrue(access.canReadReport(officeStaff, reportId));
        assertFalse(access.canReadReport(itStaff, reportId));
        assertTrue(access.canReadReport(supervisor, reportId));
    }
}
