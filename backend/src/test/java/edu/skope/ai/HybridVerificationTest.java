package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in small LIVE regression; immutable raw cache, no retries, no application DB writes. */
@EnabledIfEnvironmentVariable(named="SKOPE_RUN_HYBRID",matches="1")
class HybridVerificationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String SUITE=System.getenv().getOrDefault("HYBRID_SUITE","hybrid-v1");
    static final boolean REPLAY="1".equals(System.getenv("HYBRID_REPLAY"));
    static final int CAP=SUITE.equals("hybrid-stress-v1")?40:SUITE.equals("intake-review-v1")?3:9;
    static final Path DIR=Path.of("..","docs","evaluation"), CACHE=Path.of("..",".runtime","ai-quality-cache",SUITE);
    static final double RESERVATION=.010048; // 2x (40k input * .10/M + 2048 output * .50/M).
    @Test void measureFrozenCases()throws Exception{
        assertTrue(Set.of("hybrid-v1","hybrid-stress-v1","intake-review-v1").contains(SUITE));
        String hash=AiQualityCache.digest(Files.readAllBytes(DIR.resolve(SUITE+".json")));
        assertEquals(System.getenv("HYBRID_FIXTURE_SHA256"),hash);
        Files.createDirectories(CACHE);
        try(var channel=FileChannel.open(CACHE.resolve("evaluation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            assertNotNull(lock);var ai=new Measured();var fixture=JSON.readTree(Files.readAllBytes(DIR.resolve(SUITE+".json")));
            var result=new LinkedHashMap<String,Object>();result.put("fixtureSha256",hash);result.put("suite",SUITE);
            var sourceHashes=new TreeMap<String,String>();for(String source:List.of("AiService.java","FollowUpPolicy.java","ClarificationPolicy.java","EmergencySignals.java")){var path=Path.of("src/main/java/edu/skope/ai",source);sourceHashes.put(source,AiQualityCache.digest(Files.readAllBytes(path)));}result.put("sourceHashes",sourceHashes);result.put("evaluatedAt",java.time.OffsetDateTime.now().toString());
            result.put("promptVersion",AiService.PROMPT_VERSION);result.put("policyVersion",AiService.POLICY_VERSION);result.put("model","gpt-6-luna");
            result.put("labelLimitation",fixture.get("labelLimitation"));result.put("scope",REPLAY?"Saved LIVE output replay by text/answer; prior context may differ under the new policy. Not a fresh LIVE quality measurement. No network fallback.":"Production AiService only; no DEMO, embeddings, browser or DB writes. Local API integration tested separately.");
            result.put("preflight",Map.of("plannedCalls",fixture.path("plannedCalls").asInt(),"maxCalls",CAP,"automaticRetries",0,"conservativeMaximumUsd",CAP*RESERVATION,"budgetUsd",SUITE.equals("intake-review-v1")?.05:SUITE.equals("hybrid-stress-v1")?.45:.10,"pricingSource","https://developers.openai.com/api/docs/models/gpt-6-luna","pricingDate",SUITE.equals("intake-review-v1")?"2026-10-27":"2026-10-16"));
            var rows=new ArrayList<Map<String,Object>>();result.put("rows",rows);
            for(var group:fixture.path("groups")){
                Map<String,Object> prior=null;int step=0;
                for(var sample:group.path("steps")){
                    var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("id",group.path("id").asText()+"-"+(++step));row.put("expected",sample);
                    var input=new LinkedHashMap<String,Object>(Map.of("text",group.path("text").asText(),"preferredIntent","AUTO","hybrid",true));
                    if(sample.has("answer")){
                        if(prior==null){row.put("status","UNVERIFIED");continue;}
                        Map<?,?> state=(Map<?,?>)prior.get("followUpState");
                        if(!"CONTINUE".equals(state.get("status"))){row.put("status","UNVERIFIED");row.put("cause","PREVIOUS_RESULT_TERMINATES_REAL_INTAKE");continue;}
                        input.put("followUp",Map.of("field",Objects.toString(state.get("nextField"),"SYMPTOM"),"answer",sample.path("answer").asText(),"previousReport",prior.get("report"),"state",state));
                    }
                    row.put("input",input);ai.raw=null;
                    try{
                        assertEquals(hash,AiQualityCache.digest(Files.readAllBytes(DIR.resolve(SUITE+".json"))));
                        prior=ai.analyze(input);var actual=JSON.valueToTree(prior);var failed=new ArrayList<String>();
                        if(!sample.path("expectedStatus").asText().equals(actual.at("/followUpState/status").asText()))failed.add("status");
                        if(sample.has("allowedReasons")){boolean found=false;for(var reason:sample.path("allowedReasons"))found|=reason.equals(actual.at("/followUpState/nextReason"));if(!found)failed.add("reason");}
                        sample.path("equals").fields().forEachRemaining(e->{if(!e.getValue().equals(actual.at(e.getKey())))failed.add(e.getKey());});
                        sample.path("contains").fields().forEachRemaining(e->{if(!actual.at(e.getKey()).asText().contains(e.getValue().asText()))failed.add(e.getKey());});
                        for(String mode:List.of("alsoContains","notContains"))sample.path(mode).fields().forEachRemaining(e->{boolean contains=actual.at(e.getKey()).asText().contains(e.getValue().asText());if(mode.equals("alsoContains")!=contains)failed.add(mode+":"+e.getKey());});
                        row.put("rawModelOutput",ai.raw);row.put("actual",prior);row.put("failedChecks",failed);row.put("status",failed.isEmpty()?"PASS":"FAIL");row.put("source",ai.source);row.put("cacheKey",ai.key);
                    }catch(RuntimeException error){ai.stopped=true;prior=null;row.put("status","UNVERIFIED");row.put("cause",error instanceof edu.skope.service.ApiException e?e.code:error.getClass().getSimpleName());}
                    result.put("measurement",ai.measurement());JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve(SUITE+(REPLAY?"-policy-v2-results.json":"-results.json")).toFile(),result);
                }
            }
            for(var entry:sourceHashes.entrySet())assertEquals(entry.getValue(),AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/ai",entry.getKey()))),"Production code changed during audit");
            result.put("summary",Map.of("pass",rows.stream().filter(r->"PASS".equals(r.get("status"))).count(),"fail",rows.stream().filter(r->"FAIL".equals(r.get("status"))).count(),"unverified",rows.stream().filter(r->"UNVERIFIED".equals(r.get("status"))).count()));
            result.put("measurement",ai.measurement());JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve(SUITE+(REPLAY?"-policy-v2-results.json":"-results.json")).toFile(),result);
        }
    }
    static class Measured extends AiService {
        final AiQualityCache cache;int calls,hits;long in,out;boolean stopped;String key,source;Map<String,Object> raw;Map<String,Long> usage=Map.of();
        Measured()throws Exception{super("LIVE","https://api.openai.com/v1",REPLAY?"local-replay-placeholder":System.getenv().getOrDefault("AI_API_KEY",""),"gpt-6-luna","RULE_ONLY","",60,2048,"none");cache=new AiQualityCache(CACHE);}
        @Override protected JsonNode complete(Map<String,Object> body){
            try{
                // Pin Standard pricing for the small review suite; leave production routing unchanged.
                if(SUITE.equals("intake-review-v1")){body=new LinkedHashMap<>(body);body.put("service_tier","default");}
                if(REPLAY){
                    var request=JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText());
                    for(var row:JSON.readTree(DIR.resolve(SUITE+"-results.json").toFile()).path("rows")){
                        var input=row.path("input");
                        if(request.path("text").asText().equals(input.path("text").asText())&&request.at("/followUp/answer").asText().equals(input.at("/followUp/answer").asText())&&row.has("cacheKey")){
                            key=row.path("cacheKey").asText();var saved=cache.read(key);
                            if(saved!=null&&saved.path("status").asText().equals("SUCCESS")){hits++;source="SAVED_LIVE_POLICY_REPLAY";return saved.path("payload");}
                        }
                    }
                    throw new IllegalStateException("NO_SAVED_LIVE_SAMPLE");
                }
                key=AiQualityCache.key("analysis","gpt-6-luna",PROMPT_VERSION,canonical(JSON.valueToTree(body)));
                var cached=cache.read(key);
                if(cached!=null){if(!cached.path("status").asText().equals("SUCCESS"))throw new IllegalStateException("NO_RETRY");hits++;source="CACHED_LIVE";return cached.path("payload");}
                if(!"LIVE".equals(System.getenv("HYBRID_MODE"))||stopped||cache.attempts()>=CAP||JSON.writeValueAsBytes(body).length+1024>40000)throw new IllegalStateException("PAID_GUARD");
                try(var files=Files.list(CACHE)){for(var p:files.filter(p->p.toString().endsWith(".json")).toList())if(!JSON.readTree(p.toFile()).path("status").asText().equals("SUCCESS"))throw new IllegalStateException("PREVIOUS_FAILURE");}
                cache.begin(key,"analysis","gpt-6-luna",PROMPT_VERSION);calls++;usage=Map.of();source="NEW_LIVE";
                try{var response=super.complete(body);if(usage.isEmpty())throw new IllegalStateException("NO_USAGE");cache.complete(key,response,usage);return response;}
                catch(RuntimeException e){stopped=true;cache.fail(key,"PROVIDER_FAILURE",usage);throw e;}
            }catch(java.io.IOException e){stopped=true;throw new IllegalStateException("CACHE_FAILURE");}
        }
        @Override protected void onUsage(String endpoint,long i,long o,long total){in+=i;out+=o;usage=Map.of("inputTokens",i,"outputTokens",o,"totalTokens",total);}
        @Override protected void onAnalysis(Map<String,Object> input,Map<String,Object> raw,Map<String,Object> merged){this.raw=raw;}
        Map<String,Object> measurement()throws Exception{return Map.of("newCalls",calls,"cacheHits",hits,"cumulativeAttempts",cache.attempts(),"embeddingCalls",0,"inputTokens",in,"outputTokens",out,"estimatedNewCostUsd",(in*.10+out*.50)/1_000_000,"automaticRetries",0);}
    }
    static Object canonical(JsonNode n){if(n.isObject()){var m=new TreeMap<String,Object>();n.fields().forEachRemaining(e->m.put(e.getKey(),canonical(e.getValue())));return m;}if(n.isArray()){var l=new ArrayList<>();n.forEach(v->l.add(canonical(v)));return l;}return JSON.convertValue(n,Object.class);}
}
