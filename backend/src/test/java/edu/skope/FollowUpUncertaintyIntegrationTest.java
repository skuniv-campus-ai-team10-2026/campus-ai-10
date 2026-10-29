package edu.skope;

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

/** A wrong scripted model SET through real HTTP/session/CSRF/DB (H2, or MySQL via TEST_DB_URL). No provider calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FollowUpUncertaintyIntegrationTest.Config.class)
class FollowUpUncertaintyIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){ApiIntegrationTest.config(p);}
    @TestConfiguration static class Config {@Bean @Primary ScriptedAi uncertaintyScriptedAi(){return new ScriptedAi();}}
    @Autowired Db db; @Autowired Environment env; @Autowired ScriptedAi ai;
    ApiIntegrationTest http;
    @BeforeEach void setup(){http=new ApiIntegrationTest();http.db=db;http.env=env;http.clean();ai.calls=0;}

    @Test void unknownOwnershipIsStoredAsUncertainNotPersonal()throws Exception{
        String answer="제 개인 물건인지 학교 물건인지 모르겠어요";
        ai.on(answer,Map.of("campusRelevance","PERSONAL_ON_CAMPUS","workGroup","GENERAL_REVIEW"),Map.of("CAMPUS_RELEVANCE",new String[]{"SET","제 개인 물건"}));
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text",ai.firstText()),200);
        assertEquals("NEEDS_INPUT",first.path("status").asText());
        var accepted=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",first.path("revision").asInt(),"answer",answer),200);
        assertEquals("ACCEPTED",accepted.path("status").asText());long id=accepted.at("/report/id").asLong();
        var row=db.one("SELECT campus_relevance,work_group,target_text,location_text FROM reports WHERE id=?",id);
        assertEquals("UNCERTAIN",row.get("campusRelevance"));assertEquals("GENERAL_REVIEW",row.get("workGroup"));
        assertEquals("거울",row.get("targetText"));assertEquals("학교 공용 화장실",row.get("locationText"));
        assertNotEquals("QUESTION",accepted.at("/report/requestKind").asText(),"not filed as a personal general inquiry");
        // Filed without another question: the existing supervisor note carries the open item and the student's words.
        String note=Objects.toString(db.one("SELECT review_note FROM reports WHERE id=?",id).get("reviewNote"),"");
        assertTrue(note.contains("학교 물건인지 여부"),note);assertTrue(note.contains("마지막 추가 답변: "+answer),note);
        var admin=http.new Session().login("admin");
        var detail=admin.ok("GET","/admin/reports/"+id,null,200);
        assertEquals(note,detail.path("reviewNote").asText(),"visible on the administrator screen");
        assertEquals("REVIEW_HOLD",detail.path("unassignedReason").asText());
        assertEquals(2,ai.calls);
    }
    @Test void undecidedTargetGoesToReviewWithoutAChosenObject()throws Exception{
        String answer="거울인지 문손잡이인지 모르겠어요";
        ai.on(answer,Map.of("targetText","거울"),Map.of("TARGET",new String[]{"SET","거울"}));
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text",ai.firstText()),200);
        var next=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",first.path("revision").asInt(),"answer",answer),200);
        assertEquals("REVIEW_REQUIRED",next.path("status").asText(),"no repeated question about the object");
        assertTrue(next.at("/analysis/report/targetText").isNull());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) n FROM reports"),"n"));
        var accepted=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",next.path("revision").asInt(),"submitForReview",true),200);
        assertEquals("ACCEPTED",accepted.path("status").asText());long id=accepted.at("/report/id").asLong();
        var row=db.one("SELECT target_text,location_text,symptom FROM reports WHERE id=?",id);
        assertNull(row.get("targetText"));assertEquals("학교 공용 화장실",row.get("locationText"));
        assertEquals("공용 화장실의 거울이 깨졌습니다.",row.get("symptom"));
        assertEquals("NEEDS_REVIEW",accepted.at("/report/routeStatus").asText());
        assertEquals(2,ai.calls);
    }
}
