package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Collect production outputs, NOT an automated semantic grader. Frozen rubric is graded separately.
 * PREPARE builds exact requests without network; LIVE is opt-in, capped, persistent no-retry cache.
 */
@EnabledIfEnvironmentVariable(named="SKOPE_RUN_LANGUAGE",matches="1")
class LanguageVariationVerificationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DIR=Path.of("..","docs","evaluation"), FIXTURE=DIR.resolve("language-variation-v3.json");
    static final String FROZEN="271ff76a6f5715ad07b33e3e38feecedb407b1bc6e3f6ffca7635c0349cdfe35";
    static final Path CACHE=Path.of("..",".runtime","ai-quality-cache","language-variation-v3");
    static final boolean LIVE="LIVE".equals(System.getenv("LANGUAGE_MODE"));
    static final double RESERVE=.008298;
    static final List<String> SOURCES=List.of("AiService.java","FollowUpPolicy.java","ClarificationPolicy.java","EmergencySignals.java");
    @Test void collectFrozenGroundingCases()throws Exception {
        assertEquals(FROZEN,AiQualityCache.digest(Files.readAllBytes(FIXTURE)));
        JsonNode fixture=JSON.readTree(FIXTURE.toFile());assertEquals(6,fixture.path("cases").size());assertTrue(6*RESERVE<.05);
        var sourceHashes=new TreeMap<String,String>();
        for(String s:SOURCES)sourceHashes.put(s,AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/ai",s))));
        var results=new LinkedHashMap<String,Object>();results.put("fixtureSha256",FROZEN);results.put("sourceHashes",sourceHashes);
        results.put("promptVersion",AiService.PROMPT_VERSION);results.put("policyVersion",AiService.POLICY_VERSION);
        results.put("mode",LIVE?"LIVE":"PREPARE_NO_NETWORK");results.put("preflight",fixture.path("preflight"));results.put("labelLimitation",fixture.path("labelLimitation"));
        results.put("evaluatedAt",java.time.OffsetDateTime.now().toString());
        var rows=new ArrayList<Map<String,Object>>();results.put("rows",rows);
        Files.createDirectories(CACHE);
        try(var channel=FileChannel.open(CACHE.resolve("evaluation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            assertNotNull(lock);var ai=new Metered();
            // Do not turn a previously failed/unknown paid attempt into another paid attempt.
            if(LIVE)try(var files=Files.list(CACHE)){for(Path p:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList())assertEquals("SUCCESS",JSON.readTree(p.toFile()).path("status").asText(),"Prior paid failure: stop, do not retry");}
            for(JsonNode item:fixture.path("cases")) {
                var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("id",item.path("id"));row.put("expected",item.path("expected"));row.put("split",item.path("split"));
                var input=Map.<String,Object>of("text",item.path("text").asText(),"preferredIntent","AUTO","hybrid",true);row.put("input",input);
                if(ai.stopped){row.put("status","UNVERIFIED_STOPPED_AFTER_FAILURE");continue;}
                ai.payload=null;ai.usage=Map.of();
                try {row.put("actual",ai.analyze(input));row.put("rawAi",JSON.readTree(ai.payload.at("/choices/0/message/content").asText()));row.put("source",ai.source);row.put("status","OUTPUT_AVAILABLE_PENDING_SEMANTIC_REVIEW");}
                catch(RuntimeException e){row.put("status",LIVE?"UNVERIFIED_FAILURE":"UNVERIFIED_PREFLIGHT_ONLY");row.put("cause",e instanceof edu.skope.service.ApiException x?x.code:e.getClass().getSimpleName());if(LIVE)ai.stopped=true;}
                row.put("cacheKey",ai.key);row.put("requestBytes",ai.requestBytes);if(ai.payload!=null){row.put("providerUsage",ai.payload.path("usage"));row.put("returnedModel",ai.payload.path("model"));row.put("returnedServiceTier",ai.payload.path("service_tier"));}
                results.put("measurement",ai.measurement());save(results);
                System.out.println(item.path("id").asText()+": "+row.get("status")+"; new calls="+ai.calls);
            }
            assertEquals(FROZEN,AiQualityCache.digest(Files.readAllBytes(FIXTURE)));

            for(String s:SOURCES)assertEquals(sourceHashes.get(s),AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/ai",s))));
            results.put("evidenceIntegrity","PASS");results.put("measurement",ai.measurement());save(results);
        }
    }
    static void save(Object result)throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("language-variation-v3"+(LIVE?"-results.json":"-preflight.json")).toFile(),result);}
    static class Metered extends AiService {
        final AiQualityCache cache;int calls,hits,requestBytes;long in,out;String key,source;boolean stopped;JsonNode payload;Map<String,Long> usage=Map.of();
        Metered()throws Exception{super("LIVE","https://api.openai.com/v1",LIVE?System.getenv().getOrDefault("AI_API_KEY",""):"offline-placeholder","gpt-6-luna","RULE_ONLY","",60,2048,"none");cache=new AiQualityCache(CACHE);}
        @Override protected JsonNode complete(Map<String,Object> body){
            try {
                assertEquals(FROZEN,AiQualityCache.digest(Files.readAllBytes(FIXTURE)));
                body.put("service_tier","default");requestBytes=JSON.writeValueAsBytes(body).length;
                assertTrue(requestBytes+1024<=25000,"Input bound exceeded; NO paid call");
                key=AiQualityCache.key("analysis","gpt-6-luna",PROMPT_VERSION,HybridVerificationTest.canonical(JSON.valueToTree(body)));
                JsonNode cached=cache.read(key);
                if(cached!=null){if(!cached.path("status").asText().equals("SUCCESS"))throw new IllegalStateException("NO_RETRY");hits++;source="CACHED_LIVE";payload=cached.path("payload");return payload;}
                if(!LIVE)throw new IllegalStateException("PREFLIGHT_ONLY");
                if(stopped||cache.attempts()>=6||(cache.attempts()+1)*RESERVE>.05)throw new IllegalStateException("PAID_GUARD");
                cache.begin(key,"analysis","gpt-6-luna",PROMPT_VERSION);calls++;source="NEW_LIVE";
                try{payload=super.complete(body);if(usage.isEmpty())throw new IllegalStateException("USAGE_MISSING");cache.complete(key,payload,usage);
                    if(!payload.path("model").asText().startsWith("gpt-6-luna")||!Set.of("default","").contains(payload.path("service_tier").asText())){stopped=true;throw new IllegalStateException("PROVIDER_CONFIGURATION_CHANGED");}
                    return payload;
                }catch(RuntimeException e){stopped=true;cache.fail(key,"PROVIDER_OR_USAGE_FAILURE",usage);throw e;}
            }catch(java.io.IOException e){stopped=true;throw new IllegalStateException("CACHE_FAILURE");}
        }
        @Override protected void onUsage(String endpoint,long i,long o,long total){in+=i;out+=o;usage=Map.of("inputTokens",i,"outputTokens",o,"totalTokens",total);}
        Map<String,Object> measurement()throws Exception{return Map.of("newLlmCalls",calls,"cacheHits",hits,"cumulativeAttempts",cache.attempts(),"embeddingCalls",0,"inputTokens",in,"outputTokens",out,"conservativeEstimatedNewCostUsd",(in*.125+out*.50)/1_000_000,"automaticRetries",0);}
    }
}
