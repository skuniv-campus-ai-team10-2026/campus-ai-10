package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Saved LIVE parsed-output replay through real HTTP/session/CSRF/DB, not fresh LIVE or browser testing.
 * No provider network fallback or embeddings. Uses the same MySQL override as existing integration tests. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkGroupReleaseIntegrationTest.Config.class)
class WorkGroupReleaseIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        ApiIntegrationTest.config(p);
        p.add("PUBLIC_AUTH_LIMIT_ENABLED",()->true);
        p.add("PUBLIC_AUTH_LOGIN_PER_MINUTE",()->120);
        p.add("PUBLIC_AUTH_REGISTER_PER_MINUTE",()->10);
    }
    @TestConfiguration static class Config {@Bean @Primary ReplayAi workGroupReplayAi(){return new ReplayAi();}}
    @Autowired Db db; @Autowired Environment env; @Autowired ReplayAi ai;
    ApiIntegrationTest http;
    @BeforeEach void setup(){http=new ApiIntegrationTest();http.db=db;http.env=env;http.clean();ai.calls=0;}
    String text(){return ai.row("W1-1").at("/input/text").asText();}
    String answer(String id){return ai.row(id).at("/input/followUp/answer").asText();}
    @Test void ownershipCorrectionSurvivesSaveAdminProcessingAndStudentQuery()throws Exception{
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text",text()),200);
        assertEquals("NEEDS_INPUT",first.path("status").asText());
        var accepted=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",first.path("revision").asInt(),"answer",answer("W1-2")),200);
        assertEquals("ACCEPTED",accepted.path("status").asText());long id=accepted.at("/report/id").asLong();
        var report=accepted.path("report");assertEquals("PERSONAL_ON_CAMPUS",report.path("campusRelevance").asText());
        assertEquals("GENERAL_REVIEW",report.path("workGroup").asText());assertEquals("QUESTION",report.path("requestKind").asText());
        assertTrue(report.path("assignedDepartment").isNull());assertEquals("거울",report.path("targetText").asText());
        var again=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"resume",true),200);
        assertEquals(id,again.at("/report/id").asLong());assertEquals(2,ai.calls);
        var admin=http.new Session().login("admin");
        assertEquals("GENERAL_INQUIRY",admin.ok("GET","/admin/reports/"+id,null,200).path("unassignedReason").asText());
        http.status(admin,id,"IN_PROGRESS");http.status(admin,id,"RESOLVED");
        assertEquals("RESOLVED",student.ok("GET","/reports/"+id,null,200).path("status").asText());
        http.new Session().login("student2").ok("GET","/reports/"+id,null,404);
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) n FROM reports"),"n"));
    }
    @Test void deniedTargetRemainsUnknownUntilStudentRequestsManualReview()throws Exception{
        var student=http.new Session().login("student1");String key=UUID.randomUUID().toString();
        var first=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text",text()),200);
        var next=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",first.path("revision").asInt(),"answer",answer("W1-3")),200);
        assertEquals("NEEDS_INPUT",next.path("status").asText());
        assertTrue(next.at("/analysis/report/targetText").isNull());
        assertEquals("TARGET_UNCLEAR",next.at("/analysis/followUpState/nextReason").asText());
        assertEquals(0,Db.id(db.one("SELECT COUNT(*) n FROM reports"),"n"));
        var accepted=student.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",next.path("revision").asInt(),"submitForReview",true),200);
        assertEquals("ACCEPTED",accepted.path("status").asText());assertTrue(accepted.at("/report/targetText").isNull());
        assertEquals("은주2관",accepted.at("/report/building").asText());assertEquals("3",accepted.at("/report/floor").asText());
        assertFalse(accepted.at("/report/symptom").asText().contains("거울"));assertEquals("NEEDS_REVIEW",accepted.at("/report/routeStatus").asText());
        assertEquals(2,ai.calls);assertEquals(1,Db.id(db.one("SELECT COUNT(*) n FROM reports"),"n"));
    }
    @Test void loanedItemNeedsNoLocationAndStillRequiresSupervisorRouting()throws Exception{
        var student=http.new Session().login("student1");var row=ai.row("W2-1");
        var accepted=student.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text",row.at("/input/text").asText()),200);
        assertEquals("ACCEPTED",accepted.path("status").asText());assertEquals("SCHOOL_SERVICE",accepted.at("/report/campusRelevance").asText());
        assertEquals("SHARED_EQUIPMENT",accepted.at("/report/workGroup").asText());assertEquals("실습복",accepted.at("/report/targetText").asText());
        assertTrue(accepted.at("/report/building").isNull());assertTrue(accepted.at("/report/assignedDepartment").isNull());
        long id=accepted.at("/report/id").asLong();
        assertEquals("NO_RULE",http.new Session().login("admin").ok("GET","/admin/reports/"+id,null,200).path("unassignedReason").asText());
        assertEquals(1,ai.calls);
    }
    static final String RECORDED_PROMPT_VERSION="hybrid-intake-v6", RECORDED_POLICY_VERSION="hybrid-check-v7";
    static class ReplayAi extends AiService {
        static final ObjectMapper JSON=new ObjectMapper();final JsonNode saved;int calls;
        ReplayAi(){super("LIVE","http://127.0.0.1:1","offline-replay-only","gpt-6-luna","RULE_ONLY","",1,1536,"none");
            try{saved=JSON.readTree(Path.of("..","docs","evaluation","work-group-v1-results.json").toFile());}
            catch(Exception e){throw new IllegalStateException("Saved development output unavailable",e);}
        }
        JsonNode row(String id){for(var row:saved.path("rows"))if(id.equals(row.path("id").asText()))return row;throw new IllegalStateException("No saved case "+id);}
        @Override protected JsonNode complete(Map<String,Object> body){
            try{
                // These are outputs recorded under hybrid-intake-v6; the replay checks today's server policy against them.
                // Prompt v7 (ACADEMIC_ADMIN guidance) is measured separately (docs/evaluation/academic-admin-v1.json).
                assertEquals(RECORDED_PROMPT_VERSION,saved.path("promptVersion").asText());assertEquals(RECORDED_POLICY_VERSION,saved.path("policyVersion").asText());
                var request=JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText());
                for(var row:saved.path("rows")){
                    var input=row.path("input");
                    if(!request.path("text").equals(input.path("text"))||!request.at("/followUp/answer").equals(input.at("/followUp/answer")))continue;
                    if(request.has("followUp")){
                        // Facts must match the recording; the server-worded question text may change with the question policy.
                        var recorded=((com.fasterxml.jackson.databind.node.ObjectNode)input.at("/followUp/previousReport").deepCopy());recorded.remove("followUpQuestion");
                        var sent=((com.fasterxml.jackson.databind.node.ObjectNode)request.at("/followUp/previousReport").deepCopy());sent.remove("followUpQuestion");
                        assertEquals(recorded,sent,"Replay must retain the original saved context");
                        assertEquals(input.at("/followUp/state/knownEvidence"),request.at("/followUp/knownEvidence"));
                    }
                    assertEquals("PASS",row.path("status").asText());calls++;
                    return JSON.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",JSON.writeValueAsString(row.path("rawModelOutput")))))));
                }
                throw new IllegalStateException("No cache match; provider calls forbidden");
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
        @Override public Embedding embed(String input){throw new IllegalStateException("Embeddings forbidden in release replay");}
    }
}
