package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/session/CSRF/SQL workflow. AI is DEMO and embedding is RULE_ONLY: no paid calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepartmentWorkflowIntegrationTest {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final ObjectMapper JSON=new ObjectMapper();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:department_workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
        p.add("DEMO_PASSWORD",()->PASSWORD);p.add("AI_MODE",()->"DEMO");p.add("EMBEDDING_MODE",()->"RULE_ONLY");
        p.add("app.routing.demo-enabled",()->true);
    }
    @Autowired Db db; @Autowired Environment env;
    @BeforeEach void clean(){
        db.update("DELETE FROM report_events");db.update("DELETE FROM similar_pairs");db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL");db.update("DELETE FROM incidents");
        db.update("UPDATE departments SET active=TRUE");
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        db.update("UPDATE users SET password_hash=?,active=TRUE WHERE username IN ('student1','student2','admin')",hash);
        for(var dept:db.all("SELECT id FROM departments")){
            long id=Db.id(dept,"id");String name="dept"+id;
            if(db.one("SELECT id FROM users WHERE username=?",name)==null)
                db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)",name,hash,name,"DEPT_ADMIN",id);
            else db.update("UPDATE users SET role='DEPT_ADMIN',department_id=?,active=TRUE,password_hash=? WHERE username=?",id,hash,name);
        }
    }
    class Session {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token,header;
        HttpResponse<String> call(String method,String path,Object body,boolean csrf)throws Exception{
            if(csrf&&token==null)csrf();
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(csrf)req.header(header,token);
            return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception{var j=ok("GET","/auth/csrf",null,200);token=j.get("token").asText();header=j.get("headerName").asText();}
        Session login(String username)throws Exception{csrf();ok("POST","/auth/login",Map.of("username",username,"password",PASSWORD),200);token=null;csrf();return this;}
        JsonNode ok(String method,String path,Object body,int code)throws Exception{var r=call(method,path,body,!method.equals("GET"));assertEquals(code,r.statusCode(),r.body());return JSON.readTree(r.body());}
    }
    long dept(String subtype){return Db.id(db.one("SELECT department_id FROM routing_rules WHERE subcategory=?",subtype),"departmentId");}
    long rule(String subtype){return Db.id(db.one("SELECT id FROM routing_rules WHERE subcategory=?",subtype),"id");}
    Session staff(String subtype)throws Exception{return new Session().login("dept"+dept(subtype));}
    Map<String,Object> payload(String subtype){
        var p=new LinkedHashMap<String,Object>();
        p.put("clientRequestId",UUID.randomUUID().toString());p.put("originalText","북악관 502호의 테스트 문제를 처리해 주세요.");
        p.put("category",switch(subtype){case "WIFI"->"IT";case "STUDENT_RECORD"->"ACADEMIC";case "UNKNOWN"->"OTHER";default->"FACILITY";});
        p.put("subcategory",subtype);p.put("symptom","테스트 문제 발생");p.put("locationText","북악관 502호");p.put("building","북악관");p.put("floor","5");
        if(subtype.equals("STUDENT_RECORD"))p.put("requestKind","QUESTION");
        return p;
    }
    long create(Session student,String subtype)throws Exception{return student.ok("POST","/reports",payload(subtype),201).get("id").asLong();}
    JsonNode start(Session s,long id)throws Exception{return s.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","IN_PROGRESS"),200);}
    long pair(long a,long b){return db.insert("INSERT INTO similar_pairs(left_report_id,right_report_id,reason_text) VALUES(?,?,?)",Math.min(a,b),Math.max(a,b),"권한 경계 합성 후보");}

    @Test void everyRegisteredDepartmentProcessesOwnReportAndStudentReadsResult()throws Exception{
        var student=new Session().login("student1");var admin=new Session().login("admin");
        assertTrue(student.ok("GET","/meta",null,200).get("demoRoutingEnabled").asBoolean());
        for(String type:List.of("WIFI","ELECTRIC","LEAK","STUDENT_RECORD")){
            long id=create(student,type);var staff=staff(type);
            var me=staff.ok("GET","/auth/me",null,200);assertEquals("DEPT_ADMIN",me.get("role").asText());assertEquals(dept(type),me.get("departmentId").asLong());
            var list=staff.ok("GET","/admin/reports",null,200);assertEquals(1,list.get("totalElements").asInt());assertEquals(id,list.at("/items/0/id").asLong());
            start(staff,id);staff.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","RESOLVED","resolutionText","담당 부서가 시연 조치를 완료했습니다."),200);
            assertEquals("RESOLVED",student.ok("GET","/reports/"+id,null,200).get("status").asText());
        }
        assertEquals(4,admin.ok("GET","/admin/reports",null,200).get("totalElements").asInt());
    }
    @Test void foreignDepartmentCannotReadMutateTransferOrBypassCsrf()throws Exception{
        var student=new Session().login("student1");long id=create(student,"LEAK");var it=staff("WIFI");
        it.ok("GET","/admin/reports/"+id,null,404);
        it.ok("PATCH","/admin/reports/"+id+"/status",Map.of("status","IN_PROGRESS"),404);
        it.ok("PATCH","/admin/reports/"+id+"/transfer-request",Map.of("reason","타 부서 접근 시도"),404);
        it.ok("PATCH","/admin/reports/"+id+"/routing",Map.of("routingRuleId",rule("WIFI")),403);
        student.ok("GET","/admin/reports",null,403);
        var own=staff("LEAK");assertEquals(403,own.call("PATCH","/admin/reports/"+id+"/status",Map.of("status","IN_PROGRESS"),false).statusCode());
        assertEquals("RECEIVED",db.one("SELECT status FROM reports WHERE id=?",id).get("status"));
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM report_events WHERE report_id=?",id),"total"));
    }
    @Test void transferPreservesResponsibilityUntilSupervisorReassigns()throws Exception{
        var student=new Session().login("student1");long id=create(student,"WIFI");var it=staff("WIFI");var admin=new Session().login("admin");
        String endpoint="/admin/reports/"+id;
        it.ok("PATCH",endpoint+"/transfer-request",Map.of("reason"," "),400);
        var pending=it.ok("PATCH",endpoint+"/transfer-request",Map.of("reason","전기 설비 점검이 먼저 필요합니다."),200);
        assertTrue(pending.get("transferRequested").asBoolean());assertEquals("RECEIVED",pending.get("status").asText());
        assertEquals(dept("WIFI"),pending.get("assignedDepartmentId").asLong());
        it.ok("PATCH",endpoint+"/transfer-request",Map.of("reason","중복 요청"),409);
        assertEquals(1,admin.ok("GET","/admin/reports?transferRequested=true",null,200).get("totalElements").asInt());
        it.ok("GET","/admin/reports?transferRequested=true",null,403);
        start(it,id);
        var routed=admin.ok("PATCH",endpoint+"/routing",Map.of("routingRuleId",rule("ELECTRIC"),"reason","전기실 확인 후 재배정합니다."),200);
        assertFalse(routed.get("transferRequested").asBoolean());
        it.ok("GET",endpoint,null,404);staff("ELECTRIC").ok("GET",endpoint,null,200);
        assertEquals(0,admin.ok("GET","/admin/reports?transferRequested=true",null,200).get("totalElements").asInt());
        assertEquals(dept("ELECTRIC"),student.ok("GET","/reports/"+id,null,200).at("/assignedDepartment/id").asLong());
    }
    @Test void demoRouteIsExplicitAndUnknownStaysInSupervisorQueue()throws Exception{
        var student=new Session().login("student1");long leak=create(student,"LEAK"),unknown=create(student,"UNKNOWN");
        var d=student.ok("GET","/reports/"+leak,null,200).get("assignedDepartment");
        assertEquals("DEMO",d.get("assignmentBasis").asText());assertFalse(d.get("verified").asBoolean());assertTrue(d.get("contactPhone").isNull());assertTrue(d.get("sourceUrl").isNull());
        assertEquals("NEEDS_REVIEW",student.ok("GET","/reports/"+unknown,null,200).get("routeStatus").asText());
        for(String type:List.of("WIFI","ELECTRIC","LEAK","STUDENT_RECORD"))staff(type).ok("GET","/admin/reports/"+unknown,null,404);
        assertEquals(1,new Session().login("admin").ok("GET","/admin/reports?routeStatus=NEEDS_REVIEW",null,200).get("totalElements").asInt());
    }
    @Test void similarCandidateAndIncidentEndpointsRespectBothSides()throws Exception{
        var student=new Session().login("student1");long wifi=create(student,"WIFI"),leak=create(student,"LEAK");long pairId=pair(wifi,leak);
        var it=staff("WIFI");var admin=new Session().login("admin");
        assertEquals(0,it.ok("GET","/admin/reports/"+wifi+"/similar",null,200).get("items").size());
        it.ok("GET","/admin/reports/"+leak+"/similar",null,404);
        it.ok("PATCH","/admin/similar-pairs/"+pairId+"/review",Map.of("decision","REJECTED"),404);
        assertEquals(1,admin.ok("GET","/admin/reports/"+wifi+"/similar",null,200).get("items").size());
        long foreignIncident=start(staff("LEAK"),leak).get("incidentId").asLong();
        it.ok("GET","/admin/incidents/"+foreignIncident,null,404);
        it.ok("PATCH","/admin/reports/"+wifi+"/incident",Map.of("incidentId",foreignIncident),404);
        long ownIncident=start(it,wifi).get("incidentId").asLong();
        it.ok("PATCH","/admin/incidents/"+ownIncident+"/recurrence",Map.of("previousIncidentId",foreignIncident,"decision","CONFIRMED"),404);
        for(var candidate:it.ok("GET","/admin/repeated-candidates",null,200).get("items"))
            for(var reportId:candidate.get("reportIds"))assertEquals(wifi,reportId.asLong());
    }
    @Test void currentDepartmentAndDisabledDepartmentApplyToExistingSession()throws Exception{
        var student=new Session().login("student1");long wifi=create(student,"WIFI"),leak=create(student,"LEAK");var it=staff("WIFI");
        db.update("UPDATE users SET department_id=? WHERE username=?",dept("LEAK"),"dept"+dept("WIFI"));
        it.ok("GET","/admin/reports/"+wifi,null,404);it.ok("GET","/admin/reports/"+leak,null,200);
        db.update("UPDATE departments SET active=FALSE WHERE id=?",dept("LEAK"));
        it.ok("GET","/admin/reports",null,403);it.ok("PATCH","/admin/reports/"+leak+"/status",Map.of("status","IN_PROGRESS"),403);
    }

    @Test void foreignCandidateDoesNotHideOwnDepartmentGroupOrExposePreviousIncident()throws Exception{
        var student=new Session().login("student1");var it=staff("WIFI");var admin=new Session().login("admin");
        long a=create(student,"WIFI"),b=create(student,"WIFI"),foreign=create(student,"WIFI");
        admin.ok("PATCH","/admin/reports/"+foreign+"/routing",Map.of("routingRuleId",rule("ELECTRIC")),200);
        var candidates=it.ok("GET","/admin/repeated-candidates",null,200).get("items");
        assertEquals(1,candidates.size());assertEquals(2,candidates.get(0).get("reportCount").asInt());
        for(var id:candidates.get(0).get("reportIds"))assertNotEquals(foreign,id.asLong());
        long previous=start(staff("ELECTRIC"),foreign).get("incidentId").asLong();
        long current=start(it,a).get("incidentId").asLong();
        db.update("UPDATE incidents SET previous_incident_id=?,recurrence_decision='CANDIDATE' WHERE id=?",previous,current);
        var detail=it.ok("GET","/admin/incidents/"+current,null,200);
        assertTrue(detail.get("previousIncidentId").isNull());assertFalse(detail.has("previousIncident"));
        assertEquals("NOT_EVALUATED",detail.get("recurrenceDecision").asText());
        assertTrue(admin.ok("GET","/admin/incidents/"+current,null,200).has("previousIncident"));
    }
    @Test void sameDepartmentIncidentWorksAndCannotBeSplitByReroutingOneMember()throws Exception{
        var student=new Session().login("student1");long a=create(student,"WIFI"),b=create(student,"WIFI");var it=staff("WIFI");
        long pairId=Db.id(db.one("SELECT id FROM similar_pairs WHERE left_report_id=? AND right_report_id=?",Math.min(a,b),Math.max(a,b)),"id");
        long incident=it.ok("PATCH","/admin/similar-pairs/"+pairId+"/review",Map.of("decision","SAME_INCIDENT"),200).get("incidentId").asLong();
        assertEquals(2,it.ok("GET","/admin/incidents/"+incident,null,200).get("reportCount").asInt());
        new Session().login("admin").ok("PATCH","/admin/reports/"+a+"/routing",Map.of("routingRuleId",rule("ELECTRIC")),409);
        assertEquals(dept("WIFI"),db.one("SELECT assigned_department_id FROM reports WHERE id=?",a).get("assignedDepartmentId"));
        // Simulate a historical mixed incident; only the supervisor may inspect it.
        db.update("UPDATE reports SET assigned_department_id=? WHERE id=?",dept("LEAK"),b);
        it.ok("GET","/admin/incidents/"+incident,null,404);
        it.ok("PATCH","/admin/reports/"+a+"/status",Map.of("status","IN_PROGRESS"),404);
        new Session().login("admin").ok("GET","/admin/incidents/"+incident,null,200);
    }
}
