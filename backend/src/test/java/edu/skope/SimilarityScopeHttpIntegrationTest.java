package edu.skope;

import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import edu.skope.service.SimilarityService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/session/DB integration. Deliberately identical controlled vectors test gates, not AI quality. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SimilarityScopeHttpIntegrationTest.Config.class)
@org.springframework.test.annotation.DirtiesContext
class SimilarityScopeHttpIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){ApiIntegrationTest.config(p);p.add("app.routing.demo-enabled",()->true);p.add("spring.datasource.hikari.maximum-pool-size",()->3);}
    static class ControlledAi extends AiService {
        int calls;
        ControlledAi(){super("DEMO","http://127.0.0.1:1","no-real-key","unused","LIVE","controlled-scope-vectors",1);}
        @Override public Embedding embed(String input){calls++;return new Embedding(new double[]{1,0},"controlled-scope-vectors");}
    }
    @TestConfiguration static class Config {@Bean @Primary ControlledAi controlledScopeAi(){return new ControlledAi();}}
    @Autowired Db db;@Autowired Environment env;@Autowired ControlledAi ai;@Autowired SimilarityService similarity;
    ApiIntegrationTest http;
    @BeforeEach void setup(){http=new ApiIntegrationTest();http.db=db;http.env=env;http.clean();db.update("UPDATE users SET role='DEPT_ADMIN' WHERE department_id IS NOT NULL");ai.calls=0;}
    @AfterEach void cleanup(){http.clean();}
    static Map<String,Object> body(String subtype,String target,String floor,String place,String symptom){
        return Map.ofEntries(Map.entry("clientRequestId",UUID.randomUUID().toString()),Map.entry("originalText",place+" "+symptom),
                Map.entry("category",subtype.equals("WIFI")?"IT":"OTHER"),Map.entry("subcategory",subtype),Map.entry("targetText",target),
                Map.entry("workGroup",subtype.equals("WIFI")?"IT_SUPPORT":"FACILITY_MAINTENANCE"),Map.entry("campusRelevance","CAMPUS_FACILITY"),
                Map.entry("building","북악관"),Map.entry("floor",floor),Map.entry("locationText",place),Map.entry("symptom",symptom));
    }
    @Test void crossRoomNetworkCandidateIsPrivateAndRequiresExplicitAdministratorDecision()throws Exception{
        var first=http.new Session().login("student1");var second=http.new Session().login("student2");
        long a=first.ok("POST","/reports",body("WIFI","무선망","6","북악관 608호","무선망 연결이 반복 끊김"),201).path("id").asLong();
        long b=second.ok("POST","/reports",body("WIFI","무선망","6","북악관 609호","무선망 연결이 계속 해제됨"),201).path("id").asLong();
        var admin=http.new Session().login("admin");var items=admin.ok("GET","/admin/reports/"+a+"/similar",null,200).path("items");
        assertEquals(1,items.size());assertEquals("CANDIDATE",items.get(0).path("decision").asText());assertEquals(b,items.get(0).path("relatedReportId").asLong());
        assertFalse(items.get(0).path("reasonText").asText().contains("호실 일치"));assertTrue(items.get(0).path("reasonText").asText().contains("확장 후보"));
        assertNull(db.one("SELECT incident_id FROM reports WHERE id=?",a).get("incidentId"));assertEquals(404,second.call("GET","/reports/"+a,null,false).statusCode());
        assertEquals(404,http.new Session().login("dept5").call("GET","/admin/reports/"+a+"/similar",null,false).statusCode());
        long pair=items.get(0).path("pairId").asLong();admin.ok("PATCH","/admin/similar-pairs/"+pair+"/review",Map.of("decision","RELATED_DISTINCT"),200);
        similarity.index(a);assertEquals(2,ai.calls,"valid vectors reused after review");assertEquals("RELATED_DISTINCT",db.one("SELECT decision FROM similar_pairs WHERE id=?",pair).get("decision"));
        assertNull(db.one("SELECT incident_id FROM reports WHERE id=?",a).get("incidentId"));
    }
    @Test void separateRestroomFixturesStaySeparateEvenAtMaximumControlledCosine()throws Exception{
        var student=http.new Session().login("student1");long a=student.ok("POST","/reports",body("UNKNOWN","변기","6","북악관 6층 남자화장실","첫 번째 칸 변기가 막힘"),201).path("id").asLong();
        student.ok("POST","/reports",body("UNKNOWN","변기","6","북악관 6층 남자화장실","두 번째 칸의 다른 변기가 막힘"),201);
        assertEquals(0,http.new Session().login("admin").ok("GET","/admin/reports/"+a+"/similar",null,200).path("items").size());assertEquals(2,ai.calls);
    }
    @Test void explicitSharedFloorOutageCanCrossRestroomLabelsWithoutChangingObservedPlaces()throws Exception{
        var student=http.new Session().login("student1");long a=student.ok("POST","/reports",body("UNKNOWN","수도","6","북악관 6층 남자화장실","6층 전체 수도가 나오지 않음"),201).path("id").asLong();
        long b=student.ok("POST","/reports",body("UNKNOWN","수도","6","북악관 6층 여자화장실","층 전체 급수가 끊김"),201).path("id").asLong();
        assertEquals(1,http.new Session().login("admin").ok("GET","/admin/reports/"+a+"/similar",null,200).path("items").size());
        assertEquals("북악관 6층 남자화장실",db.one("SELECT location_text FROM reports WHERE id=?",a).get("locationText"));
        assertEquals("북악관 6층 여자화장실",db.one("SELECT location_text FROM reports WHERE id=?",b).get("locationText"));
    }
}
