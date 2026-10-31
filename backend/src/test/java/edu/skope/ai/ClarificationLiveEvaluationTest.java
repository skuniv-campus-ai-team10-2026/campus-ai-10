package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Paid LIVE check of the clarification-sufficiency policy (docs/evaluation/clarification-sufficiency-v1.json), run once by
 * a person holding the API key. Skipped unless SKOPE_LIVE_EVAL=clarification-sufficiency-v1 and AI_API_KEY are set.
 * At most 10 LLM calls and an estimated $0.01, no embeddings, no retries, no database. A follow-up turn is sent only when
 * the previous turn actually asked (CONTINUE); otherwise it is recorded as not run. For every turn it records the
 * provider's raw message content (modelContent) separately from the server's final decision (serverFinal).
 * Free replay: SKOPE_LIVE_EVAL=clarification-sufficiency-v1-replay and SKOPE_LIVE_REPLAY=<results file> re-score the saved
 * raw responses with the current code (no key, no provider call).
 */
@EnabledIfEnvironmentVariable(named="SKOPE_LIVE_EVAL", matches="clarification-sufficiency-v1(-replay)?")
class ClarificationLiveEvaluationTest {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Path FIXTURE=Path.of("..","docs","evaluation","clarification-sufficiency-v1.json");
    static final boolean REPLAY="clarification-sufficiency-v1-replay".equals(System.getenv("SKOPE_LIVE_EVAL"));
    static final Path RESULT=Path.of("..","docs","evaluation",REPLAY?"clarification-sufficiency-v1-replay-results.json":"clarification-sufficiency-v1-results.json");
    // Fixed before any run (LF line endings); a changed expectation cannot be scored by this runner.
    static final String FIXTURE_SHA256="68ef21a7515a3461dec43a43b822f9501669c54c4cc1fb56c7ce59ef618a41a4";
    static final int MAX_CALLS=10; static final double MAX_USD=0.01, INPUT_PER_MTOK=0.10, OUTPUT_PER_MTOK=0.50;
    // Worst case of one call (generous input estimate + the full completion cap), checked before each call.
    static final double WORST_CALL_USD=(6000*INPUT_PER_MTOK+1536*OUTPUT_PER_MTOK)/1_000_000;

    static class RecordingAi extends AiService {
        final List<JsonNode> responses=new ArrayList<>(); final Map<String,JsonNode> replay; int calls; double spent;
        RecordingAi(String key,Map<String,JsonNode> replay){super("LIVE",replay!=null?"http://127.0.0.1:1":System.getenv().getOrDefault("AI_BASE_URL","https://api.openai.com/v1"),key,"gpt-6-luna","RULE_ONLY","",30,1536,"none");this.replay=replay;}
        @Override protected JsonNode complete(Map<String,Object> body){
            if(calls>=MAX_CALLS)throw new IllegalStateException("LLM call limit reached");
            if(replay==null&&spent+WORST_CALL_USD>MAX_USD)throw new IllegalStateException("Cost cap would be exceeded");
            calls++;
            JsonNode response;
            if(replay==null)response=super.complete(body);
            else try{
                var user=JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText());
                response=replay.get(user.path("text").asText()+"\u0000"+user.at("/followUp/answer").asText(""));
                if(response==null)throw new IllegalStateException("No saved response; provider calls are not allowed in replay");
            }catch(java.io.IOException e){throw new IllegalStateException(e);}
            spent+=(response.at("/usage/prompt_tokens").asLong()*INPUT_PER_MTOK+response.at("/usage/completion_tokens").asLong()*OUTPUT_PER_MTOK)/1_000_000;
            responses.add(response);return response;
        }
        @Override public Embedding embed(String input){throw new IllegalStateException("Embeddings are not part of this evaluation");}
    }
    static JsonNode content(JsonNode response){
        String text=response.at("/choices/0/message/content").asText(null);
        try{return text==null?null:JSON.readTree(text);}catch(Exception e){return JSON.getNodeFactory().textNode(text);}
    }
    static List<String> check(JsonNode expect,Map<String,Object> f){
        var problems=new ArrayList<String>();
        if(expect.has("status")&&!contains(expect.get("status"),f.get("status")))problems.add("status="+f.get("status")+" not in "+expect.get("status"));
        if(expect.has("nextReason")&&!contains(expect.get("nextReason"),f.get("nextReason")))problems.add("nextReason="+f.get("nextReason")+" not in "+expect.get("nextReason"));
        for(String k:List.of("building","floor"))if(expect.has(k)&&!expect.get(k).asText().equals(f.get(k)))problems.add(k+"="+f.get(k)+" expected "+expect.get(k).asText());
        String place=Objects.toString(f.get("locationText"),"");
        if(expect.has("locationContains"))for(var w:expect.get("locationContains"))if(!place.contains(w.asText()))problems.add("locationText="+place+" lacks "+w.asText());
        if(expect.has("locationExcludes"))for(var w:expect.get("locationExcludes"))if(place.contains(w.asText()))problems.add("locationText="+place+" contains "+w.asText());
        if(expect.has("symptomPresent")&&expect.get("symptomPresent").asBoolean()!=(f.get("symptom")!=null))problems.add("symptom="+f.get("symptom"));
        if(expect.has("questionPresent")&&expect.get("questionPresent").asBoolean()!=(f.get("followUpQuestion")!=null))problems.add("followUpQuestion="+f.get("followUpQuestion"));
        return problems;
    }
    static boolean contains(JsonNode allowed,Object actual){for(var a:allowed)if(a.asText().equals(actual))return true;return false;}

    @Test @SuppressWarnings("unchecked") void runOnce()throws Exception{
        String key=REPLAY?"offline-replay-only":System.getenv("AI_API_KEY");
        assertTrue(key!=null&&!key.isBlank(),"AI_API_KEY required");
        byte[] bytes=new String(Files.readAllBytes(FIXTURE),StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(FIXTURE_SHA256,AcademicAdminLiveEvaluationTest.sha256(bytes),"Frozen expectations changed");
        if(!REPLAY)assertFalse(Files.exists(RESULT),"Results exist; a second paid run is not allowed. Move the old file away to rerun deliberately.");
        Map<String,JsonNode> saved=null;
        if(REPLAY){
            saved=new HashMap<>();
            for(var row:JSON.readTree(Path.of(Objects.requireNonNull(System.getenv("SKOPE_LIVE_REPLAY"),"SKOPE_LIVE_REPLAY required")).toFile()).path("rows"))
                if(row.hasNonNull("providerResponse"))saved.put(row.path("text").asText()+"\u0000"+row.path("answer").asText(""),row.get("providerResponse"));
        }
        var ai=new RecordingAi(key,saved);var rows=new ArrayList<Map<String,Object>>();int pass=0,total=0;
        for(var flow:JSON.readTree(bytes).get("flows")){
            Map<String,Object> previous=null,state=null;String text=null;boolean asked=true;
            for(var turn:flow.get("turns")){
                total++;
                var row=new LinkedHashMap<String,Object>();row.put("flow",flow.get("id").asText());
                if(turn.has("text"))text=turn.get("text").asText();
                row.put("text",text);row.put("answer",turn.has("answer")?turn.get("answer").asText():null);row.put("expect",turn.get("expect"));
                if(!asked){row.put("status","NOT_RUN");row.put("problems",List.of("previous turn did not ask a question"));rows.add(row);continue;}
                int before=ai.responses.size();
                try{
                    var request=new LinkedHashMap<String,Object>(Map.of("text",text,"preferredIntent","AUTO","hybrid",true));
                    if(turn.has("answer"))request.put("followUp",Map.of("field",state.get("nextField"),"answer",turn.get("answer").asText(),"previousReport",previous,"state",state));
                    var result=ai.analyze(request);
                    var report=(Map<String,Object>)result.get("report");state=(Map<String,Object>)result.get("followUpState");previous=report;
                    var f=new LinkedHashMap<String,Object>();f.put("intent",result.get("intent"));f.put("status",state.get("status"));f.put("nextReason",state.get("nextReason"));
                    for(String k:List.of("category","subcategory","workGroup","targetText","building","floor","locationText","symptom","followUpQuestion"))f.put(k,report.get(k));
                    f.put("clarification",result.get("clarification"));
                    var problems=check(turn.get("expect"),f);
                    row.put("serverFinal",f);row.put("problems",problems);row.put("status",problems.isEmpty()?"PASS":"FAIL");if(problems.isEmpty())pass++;
                    asked="CONTINUE".equals(state.get("status"));
                }catch(Exception e){row.put("status","ERROR");row.put("problems",List.of(e.getClass().getSimpleName()+": "+e.getMessage()));asked=false;}
                if(ai.responses.size()>before){var response=ai.responses.getLast();row.put("modelContent",content(response));row.put("providerResponse",response);
                    row.put("usage",Map.of("inputTokens",response.at("/usage/prompt_tokens").asLong(),"outputTokens",response.at("/usage/completion_tokens").asLong()));}
                rows.add(row);
            }
        }
        var summary=new LinkedHashMap<String,Object>();
        summary.put("version","clarification-sufficiency-v1");summary.put("mode",REPLAY?"REPLAY (no provider call)":"LIVE");
        summary.put("promptVersion",AiService.PROMPT_VERSION);summary.put("policyVersion",AiService.POLICY_VERSION);summary.put("model","gpt-6-luna");
        summary.put("llmCalls",ai.calls);summary.put("embeddingCalls",0);summary.put("estimatedUsd",ai.spent);summary.put("automaticRetries",0);
        summary.put("pass",pass);summary.put("total",total);
        summary.put("scope","Synthetic sentences with expectations fixed before the run. modelContent is the provider's raw output; serverFinal is the server's decision. Not independent accuracy.");
        summary.put("rows",rows);
        Files.writeString(RESULT,JSON.writeValueAsString(summary)+"\n",StandardCharsets.UTF_8);
        System.out.println("CLARIFICATION_LIVE pass="+pass+"/"+total+" calls="+ai.calls+" usd="+String.format(Locale.ROOT,"%.6f",ai.spent));
    }
}
