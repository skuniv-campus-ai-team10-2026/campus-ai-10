package edu.skope;

import com.fasterxml.jackson.databind.JsonNode;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The administrator review note is hidden from student responses only. The stored note, the review hold it causes,
 * the similarity exclusion and the administrator views stay as before. Real HTTP/session/CSRF/DB with the scripted
 * offline model (no provider calls, RULE_ONLY embeddings).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkGroupAssignmentIntegrationTest.Config.class)
// Its own context is closed afterwards so the MySQL run stays under its connection limit.
@org.springframework.test.annotation.DirtiesContext
class StudentReviewNoteVisibilityIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){ApiIntegrationTest.config(p);p.add("app.routing.demo-enabled",()->true);p.add("spring.datasource.hikari.maximum-pool-size",()->3);}
    @Autowired Db db; @Autowired Environment env;
    ApiIntegrationTest http;
    static final String NOTE="학생이 자동 접수 대신 직접 수정하여 접수했습니다. 원문과 수정 내용을 확인해 주세요.";
    static final List<String> STUDENT_FIELDS=List.of("id","requestKind","status","originalText","symptom","locationText","building","floor",
            "occurredAtText","occurredOn","assignedDepartment","routeStatus","resolutionText","createdAt");

    @BeforeEach void setup(){
        http=new ApiIntegrationTest();http.db=db;http.env=env;http.clean();
        // clean() resets every non-admin to STUDENT; department accounts keep their seeded role here.
        db.update("UPDATE users SET role='DEPT_ADMIN' WHERE department_id IS NOT NULL");
    }
    @AfterEach void reset(){if(http!=null)http.clean();}

    String storedNote(long id){return Db.str(db.one("SELECT review_note FROM reports WHERE id=?",id),"reviewNote");}
    long departmentId(String name){return Db.id(db.one("SELECT id FROM departments WHERE name=?",name),"id");}
    void studentView(JsonNode report){
        assertFalse(report.has("reviewNote"),"student response must not carry the administrator note: "+report);
        for(String field:STUDENT_FIELDS)assertTrue(report.has(field),"student field kept: "+field);
    }

    @Test void studentListDetailAndDirectReportHideTheNoteWhileAdministratorsAndPoliciesKeepIt()throws Exception{
        var student=http.new Session().login("student1");
        var noted=http.report("502");noted.put("reviewNote",NOTE); // existing manual-submission contract
        var created=student.ok("POST","/reports",noted,201);long id=created.path("id").asLong();
        studentView(created);
        assertEquals(NOTE,storedNote(id),"the note is stored, not deleted");
        assertEquals("NEEDS_REVIEW",created.path("routeStatus").asText(),"the note still holds automatic routing");

        var list=student.ok("GET","/reports",null,200).path("items");
        assertEquals(1,list.size());studentView(list.get(0));
        studentView(student.ok("GET","/reports/"+id,null,200));

        var admin=http.new Session().login("admin");
        var adminDetail=admin.ok("GET","/admin/reports/"+id,null,200);
        assertEquals(NOTE,adminDetail.path("reviewNote").asText());assertEquals("REVIEW_HOLD",adminDetail.path("unassignedReason").asText());
        assertTrue(adminDetail.has("events"),"administrator processing history unchanged");
        assertEquals(NOTE,admin.ok("GET","/admin/reports",null,200).path("items").get(0).path("reviewNote").asText());

        // A department account sees the note once the supervisor assigns the report to that department.
        long it=departmentId("정보전산원");
        long rule=Db.id(db.one("SELECT id FROM routing_rules WHERE department_id=? AND active=TRUE ORDER BY id LIMIT 1",it),"id");
        admin.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",rule,"reason","정보전산원 확인 필요"),200);
        var department=http.new Session().login("dept"+it);
        assertEquals(NOTE,department.ok("GET","/admin/reports/"+id,null,200).path("reviewNote").asText());
        assertEquals(NOTE,department.ok("GET","/admin/reports",null,200).path("items").get(0).path("reviewNote").asText());
        studentView(student.ok("GET","/reports/"+id,null,200));

        // Access boundaries stay as before.
        http.new Session().login("dept"+departmentId("총무처 총무과")).ok("GET","/admin/reports/"+id,null,404);
        var other=http.new Session().login("student2");
        other.ok("GET","/reports/"+id,null,404);
        assertEquals(0,other.ok("GET","/reports",null,200).path("items").size());
        student.ok("GET","/admin/reports/"+id,null,403);
        assertEquals(NOTE,storedNote(id));
    }

    @Test void noteStillExcludesTheReportFromSimilarCandidates()throws Exception{
        var student=http.new Session().login("student1");
        var noted=http.report("502");noted.put("reviewNote",NOTE);
        long held=student.ok("POST","/reports",noted,201).path("id").asLong();
        long first=student.ok("POST","/reports",http.report("502"),201).path("id").asLong();
        long second=student.ok("POST","/reports",http.report("502"),201).path("id").asLong();
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM similar_pairs WHERE left_report_id=? OR right_report_id=?",held,held),"total"));
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM similar_pairs WHERE left_report_id=? AND right_report_id=?",first,second),"total"),
                "control: the same reports without a note still become candidates");
    }

    @Test void acceptedIntakeForReviewHidesTheNoteFromTheStudentOnly()throws Exception{
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        // Unscripted text makes the offline model fail like an unavailable provider; the student files it for review.
        var failed=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","북악관 503호 무언가 이상해요"),200);
        assertEquals("REVIEW_REQUIRED",failed.path("status").asText());
        var body=Map.of("clientRequestId",key,"revision",failed.path("revision").asInt(),"submitForReview",true);
        var accepted=student.ok("POST","/assistant/intake",body,200);
        assertEquals("ACCEPTED",accepted.path("status").asText());
        var report=accepted.path("report");long id=report.path("id").asLong();
        assertFalse(report.has("reviewNote"),"ACCEPTED report for the student has no note");
        assertTrue(storedNote(id).startsWith("AI 분석 실패"),storedNote(id));
        // A repeated submission returns the same accepted report through the existing-report path, also without the note.
        var again=student.ok("POST","/assistant/intake",body,200).path("report");
        assertEquals(id,again.path("id").asLong());assertFalse(again.has("reviewNote"));
        var admin=http.new Session().login("admin").ok("GET","/admin/reports/"+id,null,200);
        assertEquals(storedNote(id),admin.path("reviewNote").asText());assertEquals("REVIEW_HOLD",admin.path("unassignedReason").asText());
    }
}
