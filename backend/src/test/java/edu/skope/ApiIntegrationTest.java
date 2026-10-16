package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final ObjectMapper JSON=new ObjectMapper();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:skope_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
        p.add("DEMO_PASSWORD",()->PASSWORD);p.add("AI_MODE",()->"DEMO");p.add("EMBEDDING_MODE",()->"RULE_ONLY");
    }
    @Autowired Db db; @Autowired Environment env;
    @BeforeEach void clean(){db.update("DELETE FROM report_events");db.update("DELETE FROM similar_pairs");db.update("DELETE FROM reports");db.update("UPDATE incidents SET previous_incident_id=NULL");db.update("DELETE FROM incidents");db.update("UPDATE users SET password_hash=?,active=TRUE,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));}
    class Session {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token,header;
        HttpResponse<String> call(String method,String path,Object body,boolean withCsrf)throws Exception{
            if(withCsrf&&token==null)csrf();
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(withCsrf)b.header(header,token);
            return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception{var r=call("GET","/auth/csrf",null,false);assertEquals(200,r.statusCode(),r.body());var j=JSON.readTree(r.body());token=j.get("token").asText();header=j.get("headerName").asText();}
        Session login(String username)throws Exception{csrf();var r=call("POST","/auth/login",Map.of("username",username,"password",PASSWORD),true);assertEquals(200,r.statusCode(),r.body());token=null;csrf();return this;}
        JsonNode ok(String method,String path,Object body,int expected)throws Exception{var r=call(method,path,body,!method.equals("GET"));assertEquals(expected,r.statusCode(),r.body());return JSON.readTree(r.body());}
    }
    Map<String,Object> report(String room){var b=new HashMap<String,Object>();b.put("clientRequestId",UUID.randomUUID().toString());b.put("originalText","북악관 "+room+"호에서 와이파이가 계속 끊겨요.");b.put("category","IT");b.put("subcategory","WIFI");b.put("locationText","북악관 "+room+"호");b.put("building","북악관");b.put("floor","5");b.put("symptom","와이파이 연결 끊김");return b;}
    long create(Session s)throws Exception{return s.ok("POST","/reports",report("502"),201).get("id").asLong();}

    @Test void intakeAcceptsCompleteReportOnceWithoutDateOrReviewForm()throws Exception{
        var student=new Session().login("student1");String key=UUID.randomUUID().toString();
        var input=Map.of("clientRequestId",key,"text","은주2관 403호에서 물이 떨어져요","preferredIntent","AUTO");
        var result=student.ok("POST","/assistant/intake",input,200);
        assertEquals("ACCEPTED",result.get("status").asText());
        var report=result.get("report");assertEquals("LEAK",report.get("subcategory").asText());
        assertTrue(report.get("occurredOn").isNull());assertTrue(report.get("occurredAtText").isNull());
        assertFalse(report.get("createdAt").isNull());
        assertEquals(report.get("id"),student.ok("POST","/assistant/intake",input,200).at("/report/id"));
        assertEquals(report.get("id"),new Session().login("student1").ok("POST","/assistant/intake",Map.of("clientRequestId",key,"resume",true),200).at("/report/id"));
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        var admin=new Session().login("admin");long id=report.get("id").asLong();
        status(admin,id,"IN_PROGRESS");status(admin,id,"RESOLVED");
        assertEquals("RESOLVED",student.ok("GET","/reports/"+id,null,200).get("status").asText());
    }
    @Test void intakeClarifiesProblemFirstAndPreservesPlaceAcrossReplies()throws Exception{
        var student=new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","강의실이 너무 불편해요"),200);
        assertEquals("NEEDS_INPUT",first.get("status").asText());
        assertEquals("SYMPTOM",first.at("/analysis/followUpState/nextField").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        var second=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",1,"answer","에어컨이 안 켜져요"),200);
        assertEquals("NEEDS_INPUT",second.get("status").asText());
        assertEquals("LOCATION",second.at("/analysis/followUpState/nextField").asText());
        // Duplicate old reply and forged client state must not advance the server question counter.
        var repeat=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",1,"answer","에어컨이 안 켜져요","state",Map.of("status","COMPLETE")),200);
        assertEquals(second.get("revision"),repeat.get("revision"));
        var finalResult=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",2,"answer","은주2관 403호에서 어제부터 그랬어요"),200);
        assertEquals("ACCEPTED",finalResult.get("status").asText());
        assertEquals("은주2관",finalResult.at("/report/building").asText());
        assertTrue(finalResult.at("/report/symptom").asText().contains("에어컨"));
        assertEquals("어제",finalResult.at("/report/occurredAtText").asText());
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void intakeQuestionIsAnsweredWithoutPhysicalLocationOrIncident()throws Exception{
        var student=new Session().login("student1");var admin=new Session().login("admin");
        var result=student.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text","휴학 신청 절차를 알려 주세요","preferredIntent","AUTO"),200);
        assertEquals("ACCEPTED",result.get("status").asText());assertEquals("QUESTION",result.at("/report/requestKind").asText());
        long id=result.at("/report/id").asLong();assertTrue(result.at("/report/building").isNull());
        assertTrue(db.one("SELECT embedding_json FROM reports WHERE id=?",id).get("embeddingJson")==null);
        assertTrue(status(admin,id,"IN_PROGRESS").get("incidentId").isNull());
        admin.ok("PATCH","/admin/reports/"+id+"/incident",Map.of("incidentId",1),409);
        status(admin,id,"RESOLVED");
        assertEquals("RESOLVED",student.ok("GET","/reports/"+id,null,200).get("status").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM incidents"),"total"));
    }
    @Test void intakeStopsUnproductiveQuestionsAndPersistsReviewReason()throws Exception{
        var student=new Session().login("student1");String key=UUID.randomUUID().toString();
        student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","강의실이 너무 불편해요"),200);
        var terminal=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",1,"answer","잘 모르겠어요"),200);
        assertEquals("REVIEW_REQUIRED",terminal.get("status").asText());
        assertEquals("MANUAL_REVIEW",terminal.at("/analysis/followUpState/status").asText());
        assertTrue(terminal.at("/analysis/report/followUpQuestion").isNull());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        var saved=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",terminal.get("revision").asInt(),"submitForReview",true),200);
        assertEquals("ACCEPTED",saved.get("status").asText());assertEquals("NEEDS_REVIEW",saved.at("/report/routeStatus").asText());
        assertTrue(saved.at("/report/reviewNote").asText().contains("문제 내용"));
    }
    @Test void intakeConcurrentSubmissionUsesOneReportAndExpiredDraftDoesNotRestartAnalysis()throws Exception{
        var student=new Session().login("student1");var body=Map.of("clientRequestId",UUID.randomUUID().toString(),"text","북악관 502호에서 와이파이가 끊겨요");
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->student.ok("POST","/assistant/intake",body,200));
            var b=pool.submit(()->student.ok("POST","/assistant/intake",body,200));
            assertEquals(a.get().at("/report/id"),b.get().at("/report/id"));
        }
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        student.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"resume",true),409);
    }
    @Test void intakeRequiresStudentCsrfAndDoesNotExposeAnotherStudentsDraft()throws Exception{
        String key=UUID.randomUUID().toString();var body=Map.of("clientRequestId",key,"text","강의실이 너무 불편해요");
        var student=new Session().login("student1");
        assertEquals(403,student.call("POST","/assistant/intake",body,false).statusCode());
        student.ok("POST","/assistant/intake",body,200);
        new Session().login("student2").ok("POST","/assistant/intake",Map.of("clientRequestId",key,"resume",true),409);
        new Session().login("admin").ok("POST","/assistant/intake",body,403);
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void hybridWifiClarifiesSpaceAndModeBeforeSavingOnlyOnce()throws Exception{
        var student=new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","은주2관에서 와이파이가 안돼요"),200);
        assertEquals("NEEDS_INPUT",first.get("status").asText());
        assertEquals("LOCATION_DETAIL",first.at("/analysis/clarification/reason").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        var second=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",1,"answer","은주2관 403호예요"),200);
        assertEquals("SYMPTOM_DETAIL",second.at("/analysis/clarification/reason").asText());
        var saved=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",2,"answer","와이파이 연결은 되는데 인터넷이 안돼요"),200);
        assertEquals("ACCEPTED",saved.get("status").asText());assertEquals("은주2관",saved.at("/report/building").asText());
        assertEquals("4",saved.at("/report/floor").asText());assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void hybridUrgentIncompleteReportIsAcceptedForReviewWithoutInterview()throws Exception{
        var student=new Session().login("student1");
        var saved=student.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text","은주2관 콘센트에서 타는 냄새가 나요"),200);
        assertEquals("ACCEPTED",saved.get("status").asText());assertEquals("NEEDS_REVIEW",saved.at("/report/routeStatus").asText());
        assertTrue(saved.at("/report/reviewNote").asText().contains("점검할 공간"));
        assertTrue(saved.at("/report/emergencyHint").asBoolean());
    }
    JsonNode status(Session a,long id,String state)throws Exception{return a.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status",state,"resolutionText","시연 점검 및 설정 변경 완료"),200);}

    @Test void fullE2ePersistsResultsAndAudit()throws Exception{
        var student=new Session().login("student1");var analysis=student.ok("POST","/assistant/analyze",Map.of("text","북악관 502호에서 와이파이가 계속 끊겨요.","preferredIntent","AUTO"),200);
        assertEquals("REPORT",analysis.get("intent").asText());assertEquals("DEMO",analysis.get("aiMode").asText());assertEquals("WIFI",analysis.at("/report/subcategory").asText());assertTrue(analysis.at("/routingPreview/verified").asBoolean());
        long id=create(student);var admin=new Session().login("admin");assertEquals("MATCHED",admin.ok("GET","/admin/reports/"+id,null,200).get("routeStatus").asText());
        status(admin,id,"IN_PROGRESS");var done=status(admin,id,"RESOLVED");assertTrue(done.get("events").size()>=3);
        assertEquals("RESOLVED",new Session().login("student1").ok("GET","/reports/"+id,null,200).get("status").asText());
        assertEquals("RESOLVED",admin.ok("GET","/admin/incidents/"+done.get("incidentId").asLong(),null,200).get("status").asText());
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void csrfOwnershipRolesAndLogout()throws Exception{
        var anon=new Session();assertEquals(403,anon.call("POST","/auth/login",Map.of("username","student1","password",PASSWORD),false).statusCode());
        anon.ok("GET","/reports",null,401);var a=new Session().login("student1");long id=create(a);a.ok("GET","/admin/reports",null,403);new Session().login("student2").ok("GET","/reports/"+id,null,404);
        assertEquals(403,a.call("POST","/reports",report("502"),false).statusCode());a.ok("POST","/auth/logout",Map.of(),200);a.ok("GET","/auth/me",null,401);
    }
    @Test void disablingOrDemotingAnAccountRevokesExistingSession()throws Exception{
        var admin=new Session().login("admin");db.update("UPDATE users SET active=FALSE WHERE username='admin'");admin.ok("GET","/admin/repeated-candidates",null,401);
        db.update("UPDATE users SET active=TRUE WHERE username='admin'");admin=new Session().login("admin");db.update("UPDATE users SET role='STUDENT' WHERE username='admin'");admin.ok("GET","/admin/reports",null,401);
    }
    @Test void duplicateRequestsAreAtomic()throws Exception{
        var s=new Session().login("student1");var payload=report("502");s.ok("POST","/reports",payload,201);assertEquals("DUPLICATE_REQUEST",s.ok("POST","/reports",payload,409).get("code").asText());assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void concurrentDuplicateHasExactlyOneWinner()throws Exception{
        var a=new Session().login("student1");var b=new Session().login("student1");var payload=report("502");try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var x=pool.submit(()->a.call("POST","/reports",payload,true).statusCode());var y=pool.submit(()->b.call("POST","/reports",payload,true).statusCode());assertEquals(Set.of(201,409),Set.of(x.get(),y.get()));
        }assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void questionsFollowupAndUnsupportedGrounding()throws Exception{
        var s=new Session().login("student1");var q=s.ok("POST","/assistant/analyze",Map.of("text","와이파이 문제 어디 문의해?","preferredIntent","QUESTION"),200);assertTrue(q.at("/questionAnswer/verified").asBoolean());assertTrue(q.get("report").isNull());
        var no=s.ok("POST","/assistant/analyze",Map.of("text","휴학 절차는 무엇인가?","preferredIntent","QUESTION"),200);assertFalse(no.at("/questionAnswer/verified").asBoolean());
        var initial=s.ok("POST","/assistant/analyze",Map.of("text","학교 인터넷이 안 돼요","preferredIntent","REPORT"),200);assertFalse(initial.at("/report/missingFields").isEmpty());
        var follow=Map.of("field","LOCATION","answer","북악관 502호","previousReport",JSON.convertValue(initial.get("report"),Map.class));
        var finalA=s.ok("POST","/assistant/analyze",Map.of("text","학교 인터넷이 안 돼요","preferredIntent","REPORT","followUp",follow),200);assertEquals("북악관",finalA.at("/report/building").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
    }
    @Test void invalidInputUnknownRoutingEmergencyAndManualAssignment()throws Exception{
        var s=new Session().login("student1");s.ok("POST","/reports",Map.of("originalText","짧음"),400);var body=report("502");body.put("category","OTHER");body.put("subcategory","UNKNOWN");body.put("originalText","콘센트에서 타는 냄새가 나요");body.put("assignedDepartmentId",9999);
        var r=s.ok("POST","/reports",body,201);long id=r.get("id").asLong();assertTrue(r.get("assignedDepartment").isNull());assertEquals("NEEDS_REVIEW",r.get("routeStatus").asText());assertTrue(r.get("emergencyHint").asBoolean());
        var a=new Session().login("admin");a.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",9999),422);
        long rule=Db.id(db.one("SELECT id FROM routing_rules WHERE subcategory='ELECTRIC'"),"id");var updated=a.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",rule),200);assertEquals("MANUAL",updated.get("routeStatus").asText());
    }
    @Test void statusAndDateValidation()throws Exception{
        var s=new Session().login("student1");var b=report("502");b.put("occurredOn","2999-01-01");s.ok("POST","/reports",b,400);long id=create(s);var a=new Session().login("admin");
        a.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","RESOLVED","resolutionText","test"),409);status(a,id,"IN_PROGRESS");
        a.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","RESOLVED","resolutionText","  "),400);status(a,id,"RESOLVED");a.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","IN_PROGRESS"),409);
    }
    @Test void threeReportsOneIncidentOnlyAfterReview()throws Exception{
        var s=new Session().login("student1");long x=create(s),y=create(s),z=create(s);var a=new Session().login("admin");
        var candidates=a.ok("GET","/admin/reports/"+x+"/similar",null,200);assertEquals(2,candidates.get("items").size());
        for(var pair:candidates.get("items")){assertTrue(pair.get("similarityScore").isNull());a.ok("PATCH","/admin/similar-pairs/"+pair.get("pairId").asLong()+"/review",Map.of("decision","SAME_INCIDENT"),200);}
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM incidents"),"total"));
        for(long id:new long[]{x,y,z}){status(a,id,"IN_PROGRESS");status(a,id,"RESOLVED");}
        assertEquals("RESOLVED",db.one("SELECT status FROM incidents").get("status"));
    }
    @Test void differentRoomsAndTypesDontAutomaticallyMatch()throws Exception{
        var s=new Session().login("student1");long x=create(s);s.ok("POST","/reports",report("503"),201);var b=report("502");b.put("category","FACILITY");b.put("subcategory","LEAK");s.ok("POST","/reports",b,201);
        var a=new Session().login("admin");assertEquals(0,a.ok("GET","/admin/reports/"+x+"/similar",null,200).get("items").size());assertEquals(0,Db.id(db.one("SELECT COUNT(*) total FROM incidents"),"total"));
    }
    @Test void incidentConflictsAndRecurrenceReview()throws Exception{
        var s=new Session().login("student1");var a=new Session().login("admin");long x=create(s),y=create(s);long ix=status(a,x,"IN_PROGRESS").get("incidentId").asLong();long iy=status(a,y,"IN_PROGRESS").get("incidentId").asLong();
        long pair=a.ok("GET","/admin/reports/"+x+"/similar",null,200).at("/items/0/pairId").asLong();a.ok("PATCH","/admin/similar-pairs/"+pair+"/review",Map.of("decision","SAME_INCIDENT"),409);
        a.ok("PATCH","/admin/incidents/"+iy+"/recurrence",Map.of("previousIncidentId",ix,"decision","CONFIRMED"),422);
        status(a,x,"RESOLVED");db.update("UPDATE incidents SET closed_at=? WHERE id=?",java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(2)),ix);
        a.ok("PATCH","/admin/reports/"+y+"/incident",Map.of("incidentId",ix),409);
        a.ok("PATCH","/admin/incidents/"+iy+"/recurrence",Map.of("previousIncidentId",ix,"decision","CONFIRMED"),200);
        var rec=a.ok("GET","/admin/incidents/"+iy,null,200);assertEquals("CONFIRMED",rec.get("recurrenceDecision").asText());assertEquals(ix,rec.get("previousIncidentId").asLong());
        long z=create(s);var repeated=a.ok("GET","/admin/repeated-candidates",null,200);assertFalse(repeated.get("items").isEmpty());
        long iz=status(a,z,"IN_PROGRESS").get("incidentId").asLong();a.ok("PATCH","/admin/incidents/"+iz+"/recurrence",Map.of("previousIncidentId",ix,"decision","REJECTED"),200);
    }
    @Test void paginationAndInvalidEnums()throws Exception{
        var s=new Session().login("student1");create(s);create(s);var list=s.ok("GET","/reports?page=0&size=1",null,200);assertEquals(2,list.get("totalElements").asInt());assertEquals(1,list.get("items").size());s.ok("GET","/reports?page=-1",null,400);s.ok("GET","/reports?status=BAD",null,400);
    }
}
