package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.config.SeedData;
import edu.skope.repository.Db;
import edu.skope.service.RoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Paid LIVE evaluation of the ACADEMIC_ADMIN prompt guidance (docs/evaluation/academic-admin-v1.json), run once by a
 * person holding the API key. Skipped unless SKOPE_LIVE_EVAL=academic-admin-v1 and AI_API_KEY are set.
 * One first-turn analysis per case (LLM <= 6), no embeddings, no retries, no shared server or database.
 * Records, separately: the provider's raw message content (modelContent), the server's parsed and normalized
 * output (parsedOutput), which fields the server normalized, the post-processed result, the routed department from
 * the real RoutingService.assign on a freshly seeded H2 directory, token usage, and PASS/FAIL.
 * Free replay: SKOPE_LIVE_EVAL=academic-admin-v1-replay and SKOPE_LIVE_REPLAY=<a saved results file> re-score the
 * saved raw responses with the current code (no key, no provider call).
 */
@EnabledIfEnvironmentVariable(named="SKOPE_LIVE_EVAL", matches="academic-admin-v1(-replay)?")
class AcademicAdminLiveEvaluationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path FIXTURE=Path.of("..","docs","evaluation","academic-admin-v1.json");
    static final boolean REPLAY="academic-admin-v1-replay".equals(System.getenv("SKOPE_LIVE_EVAL"));
    static final Path RESULT=Path.of("..","docs","evaluation",REPLAY?"academic-admin-v1-replay-results.json":"academic-admin-v1-results.json");
    // Fixed before any run (LF line endings); a changed expectation cannot be scored by this runner.
    static final String FIXTURE_SHA256="f6d10357e1b8a6ba39f319bdb67f732c52370c94a65855c28eb2a7126acb644f";
    static final double INPUT_PER_MTOK=0.10, OUTPUT_PER_MTOK=0.50;

    /** LIVE service that records each provider response; it never retries (AiService has no retry). */
    static class RecordingAi extends AiService {
        final List<JsonNode> responses=new ArrayList<>(); final List<Map<String,Object>> raw=new ArrayList<>();
        final Map<String,JsonNode> replay; int calls;
        RecordingAi(String key,Map<String,JsonNode> replay){super("LIVE",replay!=null?"http://127.0.0.1:1":System.getenv().getOrDefault("AI_BASE_URL","https://api.openai.com/v1"),key,"gpt-6-luna","RULE_ONLY","",30,1536,"none");this.replay=replay;}
        @Override protected JsonNode complete(Map<String,Object> body){
            if(++calls>6)throw new IllegalStateException("LLM call limit (6) reached");
            JsonNode response;
            if(replay==null)response=super.complete(body);
            else try{
                String text=JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText()).path("text").asText();
                JsonNode saved=replay.get(text);if(saved==null)throw new IllegalStateException("No saved response for this case; provider calls are not allowed in replay");
                response=saved;
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
            responses.add(response);return response;
        }
        /** The provider's message content exactly as returned, before any server parsing or normalization. */
        static JsonNode content(JsonNode response){
            String text=response.at("/choices/0/message/content").asText(null);
            try{return text==null?null:JSON.readTree(text);}catch(Exception e){return JSON.getNodeFactory().textNode(text);}
        }
        @Override protected void onAnalysis(Map<String,Object> input,Map<String,Object> rawResult,Map<String,Object> merged){raw.add(rawResult);}
        @Override public Embedding embed(String input){throw new IllegalStateException("Embeddings are not part of this evaluation");}
    }

    static String sha256(byte[] bytes)throws Exception{
        var hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",b));return hex.toString();
    }
    static RoutingService routes(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:academic_eval_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var populator=new ResourceDatabasePopulator();
        for(String v:List.of("V1__core.sql","V2__incidents.sql","V3__intake_context.sql","V4__department_workflow.sql","V11__work_group_routing.sql"))
            populator.addScript(new ClassPathResource("db/migration/"+v));
        populator.execute(source);
        var db=new Db(new JdbcTemplate(source));
        new SeedData(db,mock(PasswordEncoder.class),"",true).run();
        return new RoutingService(db,true);
    }
    static boolean in(JsonNode allowed,Object actual){
        for(var a:allowed)if(a.isNull()?actual==null:a.asText().equals(actual))return true;return false;
    }
    static List<String> check(JsonNode expect,Map<String,Object> f){
        var problems=new ArrayList<String>();
        for(String key:List.of("workGroup","targetText","campusRelevance","state","department"))
            if(expect.has(key)&&!in(expect.get(key),f.get(key)))problems.add(key+"="+f.get(key)+" not in "+expect.get(key));
        if(expect.has("targetContains")&&!Objects.toString(f.get("targetText"),"").contains(expect.get("targetContains").asText()))
            problems.add("targetText="+f.get("targetText")+" lacks "+expect.get("targetContains").asText());
        if(expect.has("workGroupNot")&&in(expect.get("workGroupNot"),f.get("workGroup")))problems.add("workGroup="+f.get("workGroup")+" is excluded");
        if(expect.has("anyOf")){boolean any=false;for(var alt:expect.get("anyOf"))any|=check(alt,f).isEmpty();if(!any)problems.add("none of "+expect.get("anyOf"));}
        return problems;
    }

    @Test @SuppressWarnings("unchecked") void runOnce()throws Exception{
        String key=REPLAY?"offline-replay-only":System.getenv("AI_API_KEY");
        assertTrue(key!=null&&!key.isBlank(),"AI_API_KEY required");
        byte[] fixtureBytes=new String(Files.readAllBytes(FIXTURE),StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(FIXTURE_SHA256,sha256(fixtureBytes),"Frozen expectations changed");
        if(!REPLAY)assertFalse(Files.exists(RESULT),"Results exist; a second paid run is not allowed. Move the old file away to rerun deliberately.");
        var fixture=JSON.readTree(fixtureBytes);
        Map<String,JsonNode> saved=null;
        if(REPLAY){
            // Saved raw content per case: modelContent (this runner) or rawModelOutput (an earlier results file).
            saved=new HashMap<>();
            var source=JSON.readTree(Path.of(Objects.requireNonNull(System.getenv("SKOPE_LIVE_REPLAY"),"SKOPE_LIVE_REPLAY required")).toFile());
            for(var row:source.path("rows")){
                JsonNode content=row.hasNonNull("modelContent")?row.get("modelContent"):row.get("rawModelOutput");
                if(content==null||content.isNull())continue;
                saved.put(row.path("text").asText(),JSON.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",
                        Map.of("content",content.isTextual()?content.asText():JSON.writeValueAsString(content)))),"usage",row.path("usage").isObject()?
                        Map.of("prompt_tokens",row.at("/usage/inputTokens").asLong(),"completion_tokens",row.at("/usage/outputTokens").asLong()):Map.of())));
            }
        }
        var ai=new RecordingAi(key,saved);var routes=routes();
        var rows=new ArrayList<Map<String,Object>>();long in=0,out=0;int pass=0;
        for(var c:fixture.get("cases")){
            var row=new LinkedHashMap<String,Object>();row.put("id",c.get("id").asText());row.put("set",c.get("set").asText());row.put("text",c.get("text").asText());
            int before=ai.responses.size();
            try{
                var result=ai.analyze(new LinkedHashMap<>(Map.of("text",c.get("text").asText(),"preferredIntent","AUTO","hybrid",true)));
                var report=(Map<String,Object>)result.get("report");var state=(Map<String,Object>)result.get("followUpState");
                var f=new LinkedHashMap<String,Object>();
                f.put("intent",result.get("intent"));
                for(String k:List.of("category","subcategory","workGroup","targetText","campusRelevance","locationText","building","symptom"))f.put(k,report.get(k));
                f.put("state",state.get("status"));f.put("nextReason",state.get("nextReason"));
                Map<String,Object> rule=!"COMPLETE".equals(state.get("status"))?null:routes.assign(
                        Objects.toString(report.get("category"),"OTHER"),Objects.toString(report.get("subcategory"),"UNKNOWN"),
                        (String)report.get("workGroup"),(String)report.get("targetText"),(String)report.get("campusRelevance"),
                        (String)report.get("locationText"),(String)report.get("building"),
                        "QUESTION".equals(result.get("intent"))||edu.skope.domain.Codes.broad(report.get("subcategory"))&&edu.skope.domain.Codes.generalInquiry(report.get("campusRelevance")),
                        Boolean.TRUE.equals(state.get("urgentReview")));
                f.put("department",rule==null?null:rule.get("departmentName"));
                var parsed=ai.raw.isEmpty()?null:(Map<String,Object>)ai.raw.getLast().get("report");
                row.put("parsedOutput",ai.raw.isEmpty()?null:ai.raw.getLast());
                var normalized=new ArrayList<String>();
                JsonNode original=ai.responses.size()>before?RecordingAi.content(ai.responses.getLast()).path("report"):null;
                if(original!=null&&parsed!=null)for(String k:List.of("category","subcategory","workGroup","targetText","campusRelevance"))
                    if(!Objects.equals(original.hasNonNull(k)?original.get(k).asText():null,parsed.get(k)==null?null:parsed.get(k).toString()))
                        normalized.add(k+": "+(original.hasNonNull(k)?original.get(k).asText():null)+" -> "+parsed.get(k));
                row.put("normalizedByServer",normalized);
                row.put("serverResult",f);
                var problems=check(c.get("expect"),f);
                row.put("status",problems.isEmpty()?"PASS":"FAIL");row.put("problems",problems);
                if(problems.isEmpty())pass++;
            }catch(Exception e){row.put("status","ERROR");row.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
            if(ai.responses.size()>before){var usage=ai.responses.getLast().path("usage");
                row.put("modelContent",RecordingAi.content(ai.responses.getLast()));
                row.put("usage",Map.of("inputTokens",usage.path("prompt_tokens").asLong(),"outputTokens",usage.path("completion_tokens").asLong()));
                in+=usage.path("prompt_tokens").asLong();out+=usage.path("completion_tokens").asLong();}
            rows.add(row);
        }
        var summary=new LinkedHashMap<String,Object>();
        summary.put("fixture",fixture.get("id").asText());summary.put("fixtureSha256",FIXTURE_SHA256);
        summary.put("mode",REPLAY?"REPLAY of saved raw responses (no provider call): "+System.getenv("SKOPE_LIVE_REPLAY"):"LIVE");
        summary.put("promptVersion",AiService.PROMPT_VERSION);summary.put("policyVersion",AiService.POLICY_VERSION);summary.put("model","gpt-6-luna");
        summary.put("llmCalls",ai.calls);summary.put("embeddingCalls",0);summary.put("automaticRetries",0);
        summary.put("inputTokens",in);summary.put("outputTokens",out);
        summary.put("estimatedUsd",Math.round((in*INPUT_PER_MTOK+out*OUTPUT_PER_MTOK)/1e6*1e6)/1e6);
        summary.put("pass",pass);summary.put("total",rows.size());summary.put("rows",rows);
        summary.put("labels","Expectations by Claude Code, fixed before the run; R1 is a development regression case, E*/N1/P1/A1 are evaluation cases.");
        Files.writeString(RESULT,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println((REPLAY?"ACADEMIC_ADMIN_REPLAY ":"ACADEMIC_ADMIN_LIVE ")+pass+"/"+rows.size()+" PASS, LLM "+ai.calls+", tokens "+in+"/"+out+", est $"+summary.get("estimatedUsd")+" -> "+RESULT.toAbsolutePath().normalize());
        assertTrue(ai.calls<=6);
    }
}
