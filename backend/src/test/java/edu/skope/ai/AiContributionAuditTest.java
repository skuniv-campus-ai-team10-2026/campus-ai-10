package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline ablation. Protocol fixed before execution; no production changes or network fallback.
 * DEMO = existing keyword extractor + same production policies, not an optimized rule system.
 * Raw AI = original JSON response, assessment.reason != NONE means a proposed question.
 * Compare that proposal only where the frozen label is CONTINUE or COMPLETE. Terminal
 * review/limit states have no raw-AI equivalent. Raw AI was prompted as a component, not
 * a standalone application. Initial inputs are the primary cohort; follow-ups reuse the
 * EXACT original saved prior state for both arms (not independent conversational rollouts).
 * Field checks use only pre-existing explicit report/intent labels. They are sparse checks,
 * not exhaustive extraction accuracy. Development fixtures are not an independent test set.
 */
@EnabledIfEnvironmentVariable(named="SKOPE_RUN_AI_CONTRIBUTION",matches="1")
class AiContributionAuditTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DIR=Path.of("..","docs","evaluation");
    static final Map<String,String> FIXTURES=Map.of(
        "hybrid-v1","f24afd17e6cf2b784ae6cb6bc2cff7d42fb5138220df9b1f6120f7b2d7718c60",
        "hybrid-stress-v1","393ec0142f150a78e5f2a28acd98425283b8720cf95527701cd8d4fe24184112");
    final Map<Path,String> evidenceHashes=new LinkedHashMap<>();
    JsonNode read(Path p)throws Exception {byte[] bytes=Files.readAllBytes(p);evidenceHashes.put(p,AiQualityCache.digest(bytes));return JSON.readTree(bytes);}

    @Test void compareSavedLiveWithExistingRules()throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("protocolVersion","ai-contribution-v1");
        result.put("evaluatedAt",java.time.OffsetDateTime.now().toString());
        result.put("promptVersion",AiService.PROMPT_VERSION);result.put("policyVersion",AiService.POLICY_VERSION);
        result.put("limitation","Development data, Codex-authored labels, no independent human review. Raw AI proposes a question, not final intake state. DEMO is a limited existing baseline. Follow-ups share original saved LIVE history; not autonomous rollouts.");
        var hashes=new TreeMap<String,String>();
        for(String name:List.of("AiService.java","FollowUpPolicy.java","ClarificationPolicy.java","EmergencySignals.java")) {
            Path p=Path.of("src/main/java/edu/skope/ai",name);String h=AiQualityCache.digest(Files.readAllBytes(p));hashes.put(name,h);evidenceHashes.put(p,h);
        }
        result.put("sourceHashes",hashes);
        var rows=new ArrayList<Map<String,Object>>();
        int replayCount=0;
        for(String suite:List.of("hybrid-v1","hybrid-stress-v1")) {
            Path fixturePath=DIR.resolve(suite+".json");JsonNode fixture=read(fixturePath);
            assertEquals(FIXTURES.get(suite),evidenceHashes.get(fixturePath),"Frozen fixture changed");
            JsonNode saved=read(DIR.resolve(suite+"-results.json"));
            assertEquals(AiService.PROMPT_VERSION,saved.path("promptVersion").asText());
            Map<String,JsonNode> original=new HashMap<>();saved.path("rows").forEach(r->original.put(r.path("id").asText(),r));
            for(JsonNode group:fixture.path("groups")) {
                int step=0;
                for(JsonNode expected:group.path("steps")) {
                    String id=group.path("id").asText()+"-"+(++step);
                    var row=new LinkedHashMap<String,Object>();rows.add(row);
                    row.put("suite",suite);row.put("id",id);row.put("initial",step==1);row.put("expected",expected);
                    JsonNode old=original.get(id);
                    if(old==null||!old.has("cacheKey")) {row.put("status","UNVERIFIED");row.put("cause","NO_ORIGINAL_LIVE_RESPONSE");continue;}
                    assertEquals(expected,old.path("expected"));
                    JsonNode cache=read(Path.of("..",".runtime","ai-quality-cache",suite,old.path("cacheKey").asText()+".json"));
                    assertEquals("SUCCESS",cache.path("status").asText());
                    JsonNode payload=cache.path("payload");
                    JsonNode raw=JSON.readTree(payload.at("/choices/0/message/content").asText());
                    var replay=new Replay(payload);var demo=new Rules();
                    JsonNode hybrid=JSON.valueToTree(replay.analyze(input(old.path("input"))));
                    JsonNode rules=JSON.valueToTree(demo.analyze(input(old.path("input"))));
                    assertEquals(1,replay.calls,"Each saved input must replay exactly once");replayCount+=replay.calls;
                    row.put("status","EVALUATED");row.put("input",old.path("input"));row.put("model",payload.path("model"));
                    row.put("rawAi",raw);row.put("rules",rules);row.put("hybrid",hybrid);
                    row.put("rulesFailedChecks",checks(expected,rules,true));row.put("hybridFailedChecks",checks(expected,hybrid,true));
                    var fieldChecks=new LinkedHashMap<String,Object>();
                    for(var arm:Map.of("rawAi",raw,"rules",rules,"hybrid",hybrid).entrySet())fieldChecks.put(arm.getKey(),checks(expected,arm.getValue(),false));
                    row.put("fieldFailedChecks",fieldChecks);row.put("fieldCheckCount",fieldCount(expected));
                    String status=expected.path("expectedStatus").asText();
                    if(Set.of("CONTINUE","COMPLETE").contains(status)) {
                        assertTrue(raw.path("assessment").has("reason"));
                        row.put("expectedQuestion",status.equals("CONTINUE"));
                        row.put("questionPredictions",Map.of("rawAi",!raw.at("/assessment/reason").asText().equals("NONE"),"rules",rules.at("/followUpState/status").asText().equals("CONTINUE"),"hybrid",hybrid.at("/followUpState/status").asText().equals("CONTINUE")));
                    }
                }
            }
        }
        result.put("rows",rows);var summary=new LinkedHashMap<String,Object>();
        for(String cohort:List.of("initial","fixedHistoryFollowUp","all")) {
            var metrics=new LinkedHashMap<String,Object>();
            for(String arm:List.of("rules","rawAi","hybrid")) {
                int tp=0,fp=0,tn=0,fn=0,fields=0,failedFields=0,allChecksPass=0,evaluated=0;
                for(var row:rows) {
                    if(!"EVALUATED".equals(row.get("status")))continue;
                    boolean initial=Boolean.TRUE.equals(row.get("initial"));
                    if(cohort.equals("initial")&&!initial||cohort.equals("fixedHistoryFollowUp")&&initial)continue;
                    evaluated++;
                    JsonNode node=JSON.valueToTree(row);
                    fields+=node.path("fieldCheckCount").asInt();failedFields+=node.path("fieldFailedChecks").path(arm).size();
                    if(!arm.equals("rawAi")&&node.path(arm+"FailedChecks").isEmpty())allChecksPass++;
                    if(!row.containsKey("expectedQuestion"))continue;
                    boolean truth=(boolean)row.get("expectedQuestion"),pred=node.path("questionPredictions").path(arm).asBoolean();
                    if(truth&&pred)tp++;else if(!truth&&pred)fp++;else if(truth)fn++;else tn++;
                }
                var m=new LinkedHashMap<String,Object>();m.put("evaluated",evaluated);m.put("question",Map.of("tp",tp,"fp",fp,"tn",tn,"fn",fn));
                m.put("questionPrecision",tp+fp==0?null:(double)tp/(tp+fp));m.put("questionRecall",tp+fn==0?null:(double)tp/(tp+fn));
                m.put("fieldChecks",fields);m.put("passedFieldChecks",fields-failedFields);
                if(!arm.equals("rawAi"))m.put("rowsPassingAllFrozenChecks",allChecksPass);
                metrics.put(arm,m);
            }
            summary.put(cohort,metrics);
        }
        summary.put("unverified",rows.stream().filter(r->"UNVERIFIED".equals(r.get("status"))).count());result.put("summary",summary);
        result.put("measurement",Map.of("newLlmCalls",0,"embeddingCalls",0,"newInputTokens",0,"newOutputTokens",0,"newCostUsd",0,"savedResponsesReplayed",replayCount,"automaticRetries",0));
        for(var e:evidenceHashes.entrySet())assertEquals(e.getValue(),AiQualityCache.digest(Files.readAllBytes(e.getKey())),"Evidence or production source changed during audit: "+e.getKey());
        result.put("evidenceIntegrity","PASS");result.put("evidenceFilesChecked",evidenceHashes.size());
        JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("ai-contribution-v1-results.json").toFile(),result);
    }
    static Map<String,Object> input(JsonNode input) {return JSON.convertValue(input,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}
    static boolean field(String pointer) {return pointer.equals("/intent")||pointer.startsWith("/report/");}
    static int fieldCount(JsonNode expected) {int n=0;for(String mode:List.of("equals","contains","alsoContains","notContains")){var it=expected.path(mode).fieldNames();while(it.hasNext())if(field(it.next()))n++;}return n;}
    static List<String> checks(JsonNode expected,JsonNode actual,boolean full) {
        var failures=new ArrayList<String>();
        if(full) {
            if(!expected.path("expectedStatus").equals(actual.at("/followUpState/status")))failures.add("status");
            if(expected.has("allowedReasons")){boolean found=false;for(JsonNode r:expected.path("allowedReasons"))found|=r.equals(actual.at("/followUpState/nextReason"));if(!found)failures.add("reason");}
        }
        for(String mode:List.of("equals","contains","alsoContains","notContains"))expected.path(mode).fields().forEachRemaining(e->{
            if(!full&&!field(e.getKey()))return;
            boolean pass=mode.equals("equals")?e.getValue().equals(actual.at(e.getKey())):actual.at(e.getKey()).asText().contains(e.getValue().asText())!=mode.equals("notContains");
            if(!pass)failures.add(mode+":"+e.getKey());
        });return failures;
    }
    static class Replay extends AiService {
        final JsonNode payload;int calls;
        Replay(JsonNode payload){super("LIVE","https://api.openai.com/v1","offline-placeholder","gpt-6-luna","RULE_ONLY","",60,2048,"none");this.payload=payload;}
        @Override protected JsonNode complete(Map<String,Object> body){calls++;assertEquals(1,calls);return payload.deepCopy();}
    }
    static class Rules extends AiService {
        Rules(){super("DEMO","","","","RULE_ONLY","",60);}
        @Override protected JsonNode complete(Map<String,Object> body){throw new AssertionError("Network forbidden in offline rules arm");}
    }
}
