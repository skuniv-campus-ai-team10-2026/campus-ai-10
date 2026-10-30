package edu.skope;

import com.fasterxml.jackson.databind.JsonNode;
import edu.skope.ai.ScriptedAi;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Work-group routing (V11) through real HTTP/session/CSRF/DB (H2, or MySQL via TEST_DB_URL). The model's first-turn
 * understanding is scripted (no provider calls): this checks what the server does with it, not model quality.
 * Development sentences (D*) shaped the change; evaluation sentences (E*) were written separately, with objects and
 * wording not used during development, and fixed before the run.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkGroupAssignmentIntegrationTest.Config.class)
// Its own context (scripted model + demo routing) is closed afterwards so the MySQL run stays under its connection limit.
@org.springframework.test.annotation.DirtiesContext
class WorkGroupAssignmentIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){ApiIntegrationTest.config(p);p.add("app.routing.demo-enabled",()->true);p.add("spring.datasource.hikari.maximum-pool-size",()->3);}
    @TestConfiguration static class Config {@Bean @Primary ScriptedAi workGroupScriptedAi(){return new ScriptedAi();}}
    @Autowired Db db; @Autowired Environment env; @Autowired ScriptedAi ai;
    ApiIntegrationTest http;
    static final String GENERAL="총무처 건설관리과", SUPPLY="총무처 총무과", SCHOLARSHIP="학생처 장학과", CLINIC="보건진료소", IT="정보전산원", ELECTRIC="건설관리과 / 전기실", ACADEMIC="교무처 교무과";

    @BeforeEach void setup(){
        http=new ApiIntegrationTest();http.db=db;http.env=env;http.clean();cleanRules();ai.calls=0;
        // clean() resets every non-admin to STUDENT; department accounts keep their seeded role here.
        db.update("UPDATE users SET role='DEPT_ADMIN' WHERE department_id IS NOT NULL");
    }
    @AfterEach void cleanRules(){
        if(http!=null)http.clean();
        db.update("DELETE FROM routing_rules WHERE category='WORK_GROUP' AND work_name LIKE '%(시험용)'"); // seeded exceptions stay
        db.update("UPDATE routing_rules SET active=TRUE WHERE category='WORK_GROUP' AND target_key=''");
    }

    static Map<String,Object> facility(String place,String building,String symptom,String group,String target,String relevance){
        var r=new LinkedHashMap<String,Object>();
        r.put("category","OTHER");r.put("subcategory","UNKNOWN");r.put("locationText",place);r.put("building",building);
        r.put("symptom",symptom);r.put("workGroup",group);r.put("targetText",target);r.put("campusRelevance",relevance);
        return r;
    }
    JsonNode file(String text)throws Exception{
        var student=http.new Session().login("student1");
        return student.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text",text),200);
    }
    JsonNode accepted(String text)throws Exception{
        var r=file(text);
        assertEquals("ACCEPTED",r.path("status").asText(),text+" is clear: no follow-up question ("+r.at("/analysis/report/followUpQuestion")+")");
        assertTrue(r.at("/report/reviewNote").isNull(),"routing alone never adds a review note (similar-report comparison stays on)");
        return r.path("report");
    }
    JsonNode admin(JsonNode report)throws Exception{return http.new Session().login("admin").ok("GET","/admin/reports/"+report.path("id").asLong(),null,200);}
    void assigned(JsonNode report,String department)throws Exception{
        assertEquals(department,report.at("/assignedDepartment/name").asText(),report.path("originalText").asText());
        assertEquals("MATCHED",report.path("routeStatus").asText());
        assertTrue(admin(report).path("unassignedReason").isNull());
    }
    void unassigned(JsonNode report,String reason)throws Exception{
        assertTrue(report.path("assignedDepartment").isNull(),report.path("originalText").asText());
        assertEquals("NEEDS_REVIEW",report.path("routeStatus").asText());
        assertEquals(reason,admin(report).path("unassignedReason").asText());
    }
    long departmentId(String name){return Db.id(db.one("SELECT id FROM departments WHERE name=?",name),"id");}
    void exception(String group,String target,String department){
        db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,target_key,active,demo_only) VALUES(?,?,?,?,?,?,?)",
                "WORK_GROUP",group,target+" 예외 배정 (시험용)",departmentId(department),target,true,true);
    }

    // 1. Development sentences.

    @Test void d1ClearSupplyShortageIsAcceptedWithoutAQuestionAndRoutedByItsWorkGroup()throws Exception{
        String text="북악관 503호 보드마카 없음";
        ai.first(text,facility("북악관 503호","북악관","보드마카가 없습니다","SHARED_EQUIPMENT","보드마카","CAMPUS_FACILITY"),"NONE",null);
        var report=accepted(text);
        assigned(report,SUPPLY);
        assertEquals("DEMO",report.at("/assignedDepartment/assignmentBasis").asText(),"a group default is never shown as verified");
        assertTrue(report.at("/assignedDepartment/contactPhone").isNull());
        assertEquals("보드마카",report.path("targetText").asText());
    }
    @Test void d2AnUnclearObjectIsAskedOnlyWhenTheModelGroundsTheNeed()throws Exception{
        String text="북악관 608호 영상이 안돼요";
        ai.first(text,facility("북악관 608호","북악관","영상이 안 나옵니다","GENERAL_REVIEW",null,"CAMPUS_FACILITY"),"TARGET_UNCLEAR","영상이 안돼요");
        var r=file(text);
        assertEquals("NEEDS_INPUT",r.path("status").asText());
        assertEquals("TARGET_UNCLEAR",r.at("/analysis/followUpState/nextReason").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) n FROM reports"),"n"),"nothing is filed before the answer");
    }
    @Test void d3TypeRuleFirstThenGroupExceptionThenGroupDefault()throws Exception{
        exception("SHARED_EQUIPMENT","빔프로젝터",IT);
        exception("IT_SUPPORT","와이파이",ACADEMIC);
        String projector="북악관 608호 빔 프로젝터가 안 켜져요", chair="북악관 608호 의자 다리가 흔들려요", wifi="북악관 608호 와이파이가 끊겨요";
        ai.first(projector,facility("북악관 608호","북악관","빔프로젝터 전원이 켜지지 않습니다","SHARED_EQUIPMENT","빔 프로젝터","CAMPUS_FACILITY"),"NONE",null);
        ai.first(chair,facility("북악관 608호","북악관","의자 다리가 흔들립니다","SHARED_EQUIPMENT","의자","CAMPUS_FACILITY"),"NONE",null);
        var w=facility("북악관 608호","북악관","무선망 연결이 끊어지고 다시 연결되지 않습니다","IT_SUPPORT","와이파이",null);w.put("category","IT");w.put("subcategory","WIFI");
        ai.first(wifi,w,"NONE",null);
        assigned(accepted(projector),IT);   // registered exception for this target, spaces ignored
        assigned(accepted(chair),SUPPLY);   // same group, no exception: group default
        assigned(accepted(wifi),IT);        // its type rule wins over the IT_SUPPORT exception
    }
    @Test void d4PersonalItemsAndUncertainOwnershipAreNotRoutedToFacilityStaff()throws Exception{
        String personal="북악관 503호에 둔 제 노트북이 고장났어요", unsure="북악관 503호 의자가 부러졌는데 누구 건지 모르겠어요";
        ai.first(personal,facility("북악관 503호","북악관","개인 노트북이 고장났습니다","GENERAL_REVIEW","노트북","PERSONAL_ON_CAMPUS"),"NONE",null);
        ai.first(unsure,facility("북악관 503호","북악관","의자가 부러졌습니다","SHARED_EQUIPMENT","의자","UNCERTAIN"),"NONE",null);
        var p=accepted(personal);
        assertEquals("QUESTION",p.path("requestKind").asText());
        unassigned(p,"GENERAL_INQUIRY");
        unassigned(accepted(unsure),"SCOPE_UNCONFIRMED"); // a building name alone does not make it a school object
    }
    @Test void d5NoRuleAndAiFailureAreDifferentReasons()throws Exception{
        db.update("UPDATE routing_rules SET active=FALSE WHERE category='WORK_GROUP' AND subcategory='FACILITY_MAINTENANCE' AND target_key=''");
        String door="북악관 503호 출입문이 안 닫혀요";
        ai.first(door,facility("북악관 503호","북악관","출입문이 닫히지 않습니다","FACILITY_MAINTENANCE","출입문","CAMPUS_FACILITY"),"NONE",null);
        unassigned(accepted(door),"NO_RULE");
        // An unscripted text makes the stub fail like an unavailable model; the student files it for review.
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        var failed=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","북악관 503호 무언가 이상해요"),200);
        assertEquals("REVIEW_REQUIRED",failed.path("status").asText());
        var saved=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",failed.path("revision").asInt(),"submitForReview",true),200).path("report");
        var view=admin(saved);
        assertEquals("REVIEW_HOLD",view.path("unassignedReason").asText());
        assertTrue(view.path("reviewNote").asText().startsWith("AI 분석 실패"));
    }
    @Test void d6DepartmentScopeManualReassignmentAndTypeListStayAsBefore()throws Exception{
        String text="북악관 503호 보드마카가 다 떨어졌어요";
        ai.first(text,facility("북악관 503호","북악관","보드마카가 모두 소진됐습니다","SHARED_EQUIPMENT","보드마카","CAMPUS_FACILITY"),"NONE",null);
        var report=accepted(text);long id=report.path("id").asLong();
        http.new Session().login("dept"+departmentId(SUPPLY)).ok("GET","/admin/reports/"+id,null,200);
        http.new Session().login("dept"+departmentId(IT)).ok("GET","/admin/reports/"+id,null,404);
        var admin=http.new Session().login("admin");
        var types=admin.ok("GET","/report-types",null,200).path("items");
        for(var rule:types)assertNotEquals("WORK_GROUP",rule.path("category").asText(),"group rules are not report types");
        assertEquals(1,java.util.stream.StreamSupport.stream(types.spliterator(),false).filter(r->r.path("subcategory").asText().equals("WIFI")).count());
        long wifiRule=Db.id(db.one("SELECT id FROM routing_rules WHERE category='IT' AND subcategory='WIFI'"),"id");
        var moved=admin.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",wifiRule,"reason","정보전산원 확인 필요"),200);
        assertEquals("MANUAL",moved.path("routeStatus").asText());assertEquals(IT,moved.at("/assignedDepartment/name").asText());
    }
    // Lead review of e349274 (10/31).

    @Test void d9ANarrowTypeWithAnExplicitPersonalOrUncertainOwnerNeverUsesItsGroupFallback()throws Exception{
        record Case(String text,String relevance,String reason){}
        for(var c:List.of(new Case("북악관 503호 제 의자 바퀴가 빠졌어요","PERSONAL_ON_CAMPUS","GENERAL_INQUIRY"),
                new Case("북악관 503호 책상이 부서졌는데 학교 건지 모르겠어요","UNCERTAIN","SCOPE_UNCONFIRMED"))){
            var model=facility("북악관 503호","북악관","가구가 파손됐습니다","SHARED_EQUIPMENT","의자",c.relevance());
            model.put("category","FACILITY");model.put("subcategory","FURNITURE");
            ai.first(c.text(),model,"NONE",null);
            unassigned(accepted(c.text()),c.reason());
        }
        // The same type with confirmed school relevance (or an older row without it) still uses the group default.
        String school="북악관 503호 강의실 의자 바퀴가 빠졌어요";
        var model=facility("북악관 503호","북악관","의자 바퀴가 빠졌습니다","SHARED_EQUIPMENT","의자","CAMPUS_FACILITY");
        model.put("category","FACILITY");model.put("subcategory","FURNITURE");
        ai.first(school,model,"NONE",null);
        assigned(accepted(school),SUPPLY);
    }
    @Test void d10TheAnalysisPreviewAndTheSavedReportAgree()throws Exception{
        var student=http.new Session().login("student1");
        record Case(String text,Map<String,Object> model,String department){}
        var furniture=facility("북악관 503호","북악관","가구가 파손됐습니다","SHARED_EQUIPMENT","의자","PERSONAL_ON_CAMPUS");
        furniture.put("category","FACILITY");furniture.put("subcategory","FURNITURE");
        for(var c:List.of(new Case("미리보기 북악관 503호 보드마카 없음",facility("북악관 503호","북악관","보드마카가 없습니다","SHARED_EQUIPMENT","보드마카","CAMPUS_FACILITY"),SUPPLY),
                new Case("미리보기 장학금 결과가 안 나와요",facility(null,null,"장학금 결과가 나오지 않았습니다","ACADEMIC_ADMIN","장학금","SCHOOL_SERVICE"),SCHOLARSHIP),
                new Case("미리보기 북악관 503호 제 의자가 부서졌어요",furniture,null))){
            ai.first(c.text(),c.model(),"NONE",null);
            var preview=student.ok("POST","/assistant/analyze",Map.of("text",c.text(),"preferredIntent","AUTO","hybrid",true),200).path("routingPreview");
            var saved=accepted(c.text());
            assertEquals(saved.path("routeStatus").asText(),preview.path("routeStatus").asText(),c.text());
            assertEquals(c.department()==null?"NEEDS_REVIEW":"MATCHED",preview.path("routeStatus").asText(),c.text());
            if(c.department()!=null){assertEquals(c.department(),preview.path("departmentName").asText());assertEquals(saved.at("/assignedDepartment/name").asText(),preview.path("departmentName").asText());}
            else assertTrue(preview.path("departmentName").isNull());
        }
    }
    @Test void d11StudentsNeverReceiveManualOnlyRulesAsReportTypes()throws Exception{
        var student=http.new Session().login("student1").ok("GET","/report-types",null,200).path("items");
        for(var rule:student){
            assertNotEquals("MANUAL",rule.path("category").asText(),"manual-only rules are not report types");
            assertNotEquals("WORK_GROUP",rule.path("category").asText());
            assertFalse(rule.path("manualOnly").asBoolean());
        }
        var admin=http.new Session().login("admin").ok("GET","/report-types",null,200).path("items");
        long manual=java.util.stream.StreamSupport.stream(admin.spliterator(),false).filter(r->r.path("manualOnly").asBoolean()).count();
        assertEquals(8,manual,"the supervisor's reassignment list keeps them");
        assertEquals(student.size()+8,admin.size());
        // A student cannot file one as a report type either.
        var s=http.new Session().login("student1");
        assertEquals(400,s.call("POST","/reports",Map.of("clientRequestId",UUID.randomUUID().toString(),"originalText","총무 업무 수동 배정으로 접수","category","MANUAL","subcategory","DEPARTMENT","symptom","시험"),true).statusCode());
    }
    @Test void d8OfficialDepartmentsAreReachedBySeededRulesAndSensitiveOnesAreNot()throws Exception{
        // A: the seeded ACADEMIC_ADMIN exception for 장학금 (department-routes.tsv), no place needed for a school service.
        String scholarship="장학금 신청했는데 결과가 아직 안 나왔어요";
        ai.first(scholarship,facility(null,null,"장학금 신청 결과가 나오지 않았습니다","ACADEMIC_ADMIN","장학금","SCHOOL_SERVICE"),"NONE",null);
        assigned(accepted(scholarship),SCHOLARSHIP);
        // C: a department without an automatic rule is in the reassignment list as a manual-only rule.
        var admin=http.new Session().login("admin");
        var types=admin.ok("GET","/report-types",null,200).path("items");
        var manual=new HashMap<String,Long>();
        for(var rule:types)if("MANUAL".equals(rule.path("category").asText()))manual.put(rule.path("departmentName").asText(),rule.path("id").asLong());
        assertEquals(Set.of(SUPPLY,"총무처 산업안전관리센터","대학원 교학과","학생처 학생과",SCHOLARSHIP,"학술정보관","생활관",CLINIC),manual.keySet());
        assertFalse(manual.containsKey("학생처 학생상담센터")||manual.containsKey("서경인권센터"),"counselling and human-rights matters are never routed");
        String fall="북악관 계단에서 넘어져서 다쳤어요";
        ai.first(fall,facility("북악관 계단","북악관","계단에서 넘어져 다쳤습니다","FACILITY_MAINTENANCE","계단","CAMPUS_FACILITY"),"NONE",null);
        long id=accepted(fall).path("id").asLong();
        var moved=admin.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",manual.get(CLINIC),"reason","부상 확인 필요"),200);
        assertEquals("MANUAL",moved.path("routeStatus").asText());assertEquals(CLINIC,moved.at("/assignedDepartment/name").asText());
        assertEquals("DEMO",moved.at("/assignedDepartment/assignmentBasis").asText());
        http.new Session().login("dept"+departmentId(CLINIC)).ok("GET","/admin/reports/"+id,null,200);
        // A manual-only rule never assigns automatically, even for a matching department name.
        assertNull(db.one("SELECT id FROM routing_rules WHERE category='MANUAL' AND subcategory<>'DEPARTMENT'"));
    }
    @Test void d7RoutedAndUnroutedReportsStayInSimilarReportComparison()throws Exception{
        String a="북악관 503호 보드마카가 없어요", b="북악관 503호 보드마카 다 썼어요";
        ai.first(a,facility("북악관 503호","북악관","보드마카가 없습니다","SHARED_EQUIPMENT","보드마카","CAMPUS_FACILITY"),"NONE",null);
        ai.first(b,facility("북악관 503호","북악관","보드마카를 모두 사용했습니다","SHARED_EQUIPMENT","보드마카","CAMPUS_FACILITY"),"NONE",null);
        long first=accepted(a).path("id").asLong(), second=accepted(b).path("id").asLong();
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) n FROM similar_pairs WHERE left_report_id=? AND right_report_id=? AND decision='CANDIDATE'",first,second),"n"));
        assertTrue(db.one("SELECT incident_id FROM reports WHERE id=?",first).get("incidentId")==null,"a candidate is never merged automatically");
    }


    // 2. Evaluation sentences (separate from development; new objects, wording and request kinds).

    @Test void evaluationSentencesAcrossWorkGroupsAndRequestKinds()throws Exception{
        record Case(String id,String text,Map<String,Object> model,String department,String reason){}
        var cases=new ArrayList<Case>();
        cases.add(new Case("E1 부족","은주2관 201호 지우개가 하나도 없어요",facility("은주2관 201호","은주2관","칠판 지우개가 없습니다","SHARED_EQUIPMENT","칠판 지우개","CAMPUS_FACILITY"),SUPPLY,null));
        cases.add(new Case("E2 보충","유담관 4층 화장실 휴지 좀 채워주세요",facility("유담관 4층 화장실","유담관","화장지 보충이 필요합니다","FACILITY_MAINTENANCE","화장지","CAMPUS_FACILITY"),GENERAL,null));
        cases.add(new Case("E3 고장","유담관 4층 복도 형광등 하나가 계속 깜빡여요",facility("유담관 4층 복도","유담관","복도 형광등 하나가 깜빡입니다","ELECTRICAL_MAINTENANCE","형광등","CAMPUS_FACILITY"),ELECTRIC,null));
        cases.add(new Case("E4 수리","북악관 302호 블라인드 줄이 끊어졌어요",facility("북악관 302호","북악관","블라인드 줄이 끊어졌습니다","FACILITY_MAINTENANCE","블라인드","CAMPUS_FACILITY"),GENERAL,null));
        cases.add(new Case("E5 이용 오류","학교 LMS에 로그인하면 계속 오류 페이지가 떠요",facility(null,null,"LMS 로그인 시 오류 페이지가 표시됩니다","IT_SUPPORT","LMS","SCHOOL_SERVICE"),IT,null));
        cases.add(new Case("E6 처리 지연","재학증명서 발급 신청했는데 일주일째 처리가 안 돼요",facility(null,null,"재학증명서 발급 신청이 일주일째 처리되지 않습니다","ACADEMIC_ADMIN","재학증명서","SCHOOL_SERVICE"),ACADEMIC,null));
        cases.add(new Case("E7 개인 물건","도서관 열람실에서 제 충전기가 망가졌어요",facility("도서관 열람실","도서관","개인 충전기가 고장났습니다","GENERAL_REVIEW","충전기","PERSONAL_ON_CAMPUS"),null,"GENERAL_INQUIRY"));
        cases.add(new Case("E8 범위 불확실","은주2관 201호 스탠드가 안 켜지는데 학교 건지 모르겠어요",facility("은주2관 201호","은주2관","스탠드가 켜지지 않습니다","ELECTRICAL_MAINTENANCE","스탠드","UNCERTAIN"),null,"SCOPE_UNCONFIRMED"));
        for(var c:cases)ai.first(c.text(),c.model(),"NONE",null);
        for(var c:cases){
            var report=accepted(c.text());
            if(c.department()!=null)assigned(report,c.department());else unassigned(report,c.reason());
        }
        // An unclear object in new wording is asked once, when the model grounds it.
        String vague="은주2관 201호 화면이 이상해요";
        ai.first(vague,facility("은주2관 201호","은주2관","화면이 이상합니다","GENERAL_REVIEW",null,"CAMPUS_FACILITY"),"TARGET_UNCLEAR","화면이 이상해요");
        assertEquals("NEEDS_INPUT",file(vague).path("status").asText());
        assertEquals(cases.size()+1,ai.calls,"one scripted call per sentence; no provider calls");
    }
}
