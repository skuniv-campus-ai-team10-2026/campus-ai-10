package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP/DB replay of saved LIVE envelopes, not new paid calls or a LIVE browser test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HybridReplayIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named="SKOPE_RUN_BOUNDARY_AUDIT",matches="1")
class HybridReplayIntegrationTest {
    static final Map<String,Object> actualResponses=new LinkedHashMap<>();
    @AfterAll static void preserveUtf8Evidence()throws Exception{
        var path=Path.of("..","docs","evaluation","hybrid-replay-policy-v2-results.json");
        Map<String,Object> saved=Files.exists(path)?ApiIntegrationTest.JSON.readValue(path.toFile(),LinkedHashMap.class):new LinkedHashMap<>();
        var combined=new LinkedHashMap<String,Object>((Map<String,Object>)saved.getOrDefault("actualResponses",Map.of()));combined.putAll(actualResponses);saved.put("actualResponses",combined);
        ApiIntegrationTest.JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),saved);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){ApiIntegrationTest.config(p);}
    @TestConfiguration static class Config {@Bean @Primary ReplayAi replayAi(){return new ReplayAi();}}
    @Autowired Db db;@Autowired Environment env;@Autowired ReplayAi ai;
    ApiIntegrationTest transport;
    @BeforeEach void setup(){transport=new ApiIntegrationTest();transport.db=db;transport.env=env;transport.clean();ai.calls=0;ai.syntheticDanger=false;}
    @Test void cachedLeakCompletesStudentAdminStudentFlowAndDeduplicates()throws Exception{
        var s=transport.new Session().login("student1");String key=UUID.randomUUID().toString();
        var body=Map.of("clientRequestId",key,"text","다솜관 501호 천장에 물방울이 맺혀 바닥으로 계속 떨어집니다.");
        var accepted=s.ok("POST","/assistant/intake",body,200);assertEquals("ACCEPTED",accepted.path("status").asText());long id=accepted.at("/report/id").asLong();
        assertEquals(id,s.ok("POST","/assistant/intake",body,200).at("/report/id").asLong());assertEquals(1,ai.calls);
        var admin=transport.new Session().login("admin");transport.status(admin,id,"IN_PROGRESS");transport.status(admin,id,"RESOLVED");
        assertEquals("RESOLVED",s.ok("GET","/reports/"+id,null,200).path("status").asText());
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM reports"),"total"));
        actualResponses.put("leakCompleted",s.ok("GET","/reports/"+id,null,200));
    }
    @Test void cachedWifiCompletesAfterLocationReplyAndStoresOnce()throws Exception{
        var s=transport.new Session().login("student1");String key=UUID.randomUUID().toString();
        var initial=s.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","늘봄관 와이파이가 안 돼요."),200);assertEquals("NEEDS_INPUT",initial.path("status").asText());
        var second=s.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",1,"answer","늘봄관 316호예요."),200);assertEquals("ACCEPTED",second.path("status").asText());
        var saved=s.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"revision",2,"answer","연결은 되는데 인터넷 페이지가 열리지 않아요."),200);
        actualResponses.put("multiTurnWifi",saved);
        assertEquals("ACCEPTED",saved.path("status").asText());assertEquals(second.at("/report/id"),saved.at("/report/id"));assertEquals("늘봄관",saved.at("/report/building").asText());assertEquals("3",saved.at("/report/floor").asText());assertEquals(2,ai.calls);
    }
    @Test void cachedVagueWifiMustNotBeAcceptedWithoutClarification()throws Exception{
        var s=transport.new Session().login("student1");String key=UUID.randomUUID().toString();
        var result=s.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","한빛관 512호 와이파이가 안 돼요."),200);
        actualResponses.put("vagueWifi",result);
        assertEquals("NEEDS_INPUT",result.path("status").asText(),result.toString());
    }
    @Test void syntheticModelOnlyWarningMustSurviveRealDatabaseInsert()throws Exception{
        ai.syntheticDanger=true;var s=transport.new Session().login("student1");
        var saved=s.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text","누리관 101호 배터리가 부풀어 올라 터질 것 같아요"),200);
        actualResponses.put("syntheticModelWarning",saved);
        assertEquals("ACCEPTED",saved.path("status").asText());assertTrue(saved.at("/report/emergencyHint").asBoolean(),"Model warning was true, but stored response: "+saved);
    }
    @Test void cachedMixedProblemsRemainVisibleAndUseCurrentReviewRouting()throws Exception{
        var s=transport.new Session().login("student1");
        var saved=s.ok("POST","/assistant/intake",Map.of("clientRequestId",UUID.randomUUID().toString(),"text","솔빛관 306호에서 와이파이가 계속 끊기고 천장에서는 물이 떨어져요."),200);
        actualResponses.put("mixedProblems",saved);
        assertEquals("ACCEPTED",saved.path("status").asText());
        assertTrue(saved.at("/report/symptom").asText().contains("물"));assertTrue(saved.at("/report/symptom").asText().contains("끊"));
        assertEquals("NEEDS_REVIEW",saved.at("/report/routeStatus").asText());
    }
    static class ReplayAi extends AiService {
        static final ObjectMapper JSON=new ObjectMapper();int calls;boolean syntheticDanger;
        ReplayAi(){super("LIVE","https://api.openai.com/v1","local-replay-placeholder","gpt-6-luna","RULE_ONLY","",5,2048,"none");}
        @Override protected JsonNode complete(Map<String,Object> body){
            calls++;
            try{
                if(syntheticDanger){
                    var r=new LinkedHashMap<String,Object>(Map.of("category","OTHER","subcategory","UNKNOWN","locationText","누리관 101호","building","누리관","floor","1","symptom","배터리가 부풀어 올라 터질 것 같음","missingFields",List.of(),"emergencyHint",true));r.put("occurredAtText",null);r.put("followUpQuestion",null);
                    var known=new LinkedHashMap<String,Object>();for(String k:List.of("locationEvidence","scopeEvidence","symptomEvidence","targetEvidence","workflowEvidence"))known.put(k,null);
                    var a=new LinkedHashMap<String,Object>();a.put("reason","NONE");a.put("evidence",null);a.put("rationale",null);a.put("question",null);a.put("known",known);
                    return JSON.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",JSON.writeValueAsString(Map.of("intent","REPORT","report",r,"assessment",a)))))));
                }
                var request=JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText());
                for(var row:JSON.readTree(Path.of("..","docs","evaluation","hybrid-stress-v1-results.json").toFile()).path("rows")){
                    var input=row.path("input");
                    if(request.path("text").asText().equals(input.path("text").asText())&&request.at("/followUp/answer").asText().equals(input.at("/followUp/answer").asText())&&row.has("cacheKey"))
                        return JSON.readTree(Path.of("..",".runtime","ai-quality-cache","hybrid-stress-v1",row.path("cacheKey").asText()+".json").toFile()).path("payload");
                }
                throw new IllegalStateException("No stored LIVE sample; network fallback forbidden");
            }catch(Exception e){throw new IllegalStateException("Replay fixture unavailable",e);}
        }
    }
}
