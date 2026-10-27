package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Provider stubs validate integration of the merge policy; these are not LIVE quality measurements. */
class AiFollowUpTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void cachedRealLanguageResponsesKeepActionableIssuesAndUncertaintyBoundaries() throws Exception {
        // Historical synthetic place names stay in this read-only fixture; no application DB is used.
        var rows=json.readTree(java.nio.file.Path.of("../docs/evaluation/language-variation-v2-results.json").toFile()).path("rows");
        assertEquals(6,rows.size());
        for(var row:rows){
            AiService ai=new AiService("LIVE","http://127.0.0.1:1","offline-replay","cached-model","RULE_ONLY","",1){
                @Override protected JsonNode complete(Map<String,Object> request){
                    return json.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",row.path("rawAi").toString())))));
                }
            };
            String id=row.path("id").asText();
            Map<String,Object> input=json.convertValue(row.path("input"),Map.class);
            var actual=ai.analyze(input);var state=(Map<?,?>)actual.get("followUpState");
            assertEquals(Set.of("H3","H4").contains(id)?"CONTINUE":"COMPLETE",state.get("status"),id);
            if(id.equals("H3"))assertEquals("ISSUE_UNCLEAR",state.get("nextReason"));
            if(id.equals("H4"))assertEquals("SYMPTOM_DETAIL",state.get("nextReason"));
            assertEquals(false,AiServiceTest.report(actual).get("emergencyHint"),id);
        }
    }

    @Test void explicitBrokenDispenserSurvivesMissingModelSymptomAndCompletesWithOneBasementReply(){
        var proposed=prior();proposed.put("category","OTHER");proposed.put("subcategory","UNKNOWN");
        proposed.put("building","은주2관");proposed.put("locationText","은주2관");proposed.put("floor",null);
        proposed.put("symptom",null);proposed.put("occurredAtText",null);proposed.put("missingFields",List.of("SYMPTOM"));
        var updates=new LinkedHashMap<String,Object>();
        var ai=new Stub(proposed,updates);
        var facts=new LinkedHashMap<String,Object>();for(String key:ClarificationPolicy.FACTS)facts.put(key,null);
        ai.assessment=new LinkedHashMap<>(Map.of("reason","ISSUE_UNCLEAR","evidence","정수기가 고장났어요","rationale","증상 부족","question","어떤 점이 불편한가요?","known",facts));
        String text="은주2관에 정수기가 고장났어요.";
        var first=ai.analyze(Map.of("text",text,"hybrid",true));
        assertEquals("LOCATION_DETAIL",((Map<?,?>)first.get("followUpState")).get("nextReason"));
        String retained=AiServiceTest.report(first).get("symptom").toString();assertTrue(retained.contains("정수기"));
        assertNull(AiServiceTest.report(ai.raw).get("symptom"),"This is a simulated provider error, not LIVE quality evidence");
        proposed.put("locationText","은주2관 지하 1층");proposed.put("floor","B1");
        updates.putAll(changes("LOCATION","SET","지하 1층"));
        ai.assessment.put("reason","SYMPTOM_DETAIL");
        var second=ai.analyze(Map.of("text",text,"hybrid",true,"followUp",Map.of("field","LOCATION","answer","지하 1층","previousReport",AiServiceTest.report(first),"state",first.get("followUpState"))));
        assertEquals("COMPLETE",((Map<?,?>)second.get("followUpState")).get("status"));
        assertEquals(retained,AiServiceTest.report(second).get("symptom"));assertEquals("B1",AiServiceTest.report(second).get("floor"));
        assertEquals("은주2관",AiServiceTest.report(second).get("building"));assertNull(AiServiceTest.report(second).get("occurredAtText"));
        assertNull(AiServiceTest.report(second).get("followUpQuestion"));assertEquals(2,ai.calls);
    }

    @Test void hybridStrictSchemaCombinesGroundedProposalAndServerRules(){
        var proposed=prior();proposed.put("symptom","와이파이가 안 돼요");
        var ai=new Stub(proposed,changes("SYMPTOM","SET","와이파이가 안 돼요"));
        var facts=new LinkedHashMap<String,Object>();for(String key:ClarificationPolicy.FACTS)facts.put(key,null);
        ai.assessment=new LinkedHashMap<>(Map.of("reason","SYMPTOM_DETAIL","evidence","와이파이가 안 돼요","rationale","장애 방식 확인","question","비밀번호를 알려주세요","known",facts));
        var request=new LinkedHashMap<>(input(proposed,"와이파이가 안 돼요",null));request.put("hybrid",true);
        var result=ai.analyze(request);
        assertEquals("SYMPTOM_DETAIL",((Map<?,?>)result.get("clarification")).get("reason"));
        assertFalse(AiServiceTest.report(result).get("followUpQuestion").toString().contains("비밀번호"));
        assertTrue(ai.body.at("/response_format/json_schema/schema/properties/assessment/properties/known/required").isArray());
        assertFalse(result.containsKey("assessment"));assertTrue(ai.raw.containsKey("assessment"));
    }

    @Test void schemaAndEvidenceMergePreserveOtherFactsAndExposeOnlyPublicState() {
        var prior = prior();
        var proposed = new LinkedHashMap<>(prior);
        proposed.put("building", null); proposed.put("locationText", null); proposed.put("floor", null);
        proposed.put("symptom", null); proposed.put("occurredAtText", "오늘 오전");
        var ai = new Stub(proposed, changes("OCCURRED_AT", "SET", "오늘 오전"));
        var result = ai.analyze(input(prior, "시간은 오늘 오전으로 정정해요.", null));
        var report = AiServiceTest.report(result);
        for (String key : List.of("building", "locationText", "floor", "symptom", "category", "subcategory"))
            assertEquals(prior.get(key), report.get(key), key);
        assertEquals("오늘 오전", report.get("occurredAtText"));
        assertFalse(result.containsKey("updates"));
        assertEquals("COMPLETE", ((Map<?,?>)result.get("followUpState")).get("status"));
        assertNull(report.get("followUpQuestion"));
        assertTrue(ai.body.at("/response_format/json_schema/schema/properties/updates/additionalProperties").isBoolean());
        assertFalse(ai.body.at("/response_format/json_schema/schema/properties/updates/additionalProperties").asBoolean());
        assertNull(AiServiceTest.report(ai.raw).get("building"));
        assertEquals("북악관", AiServiceTest.report(ai.finalResult).get("building"));
    }

    @Test void exhaustedStateDoesNotMakeAnotherPaidCall() {
        for (Map<String,Object> state : List.of(
                state(3, 0, "LIMIT"), state(2, 2, "NO_PROGRESS"))) {
            var prior = prior(); prior.put("building", null); prior.put("locationText", null); prior.put("floor", null);
            var ai = new Stub(prior, changes("LOCATION", "SET", "유담관"));
            var result = ai.analyze(input(prior, "유담관이에요", state));
            assertEquals(0, ai.calls);
            assertNull(AiServiceTest.report(result).get("building"));
            assertNull(AiServiceTest.report(result).get("followUpQuestion"));
            assertEquals(state.get("status"), ((Map<?,?>)result.get("followUpState")).get("status"));
        }
    }

    @Test void missingStateRemainsBackwardsCompatibleAndManualFieldsArePreserved() {
        var prior = prior(); prior.put("occurredAtText", "직접 확인한 오전");
        var result = AiServiceTest.demo().analyze(input(prior, "북악관 602호", null));
        var report = AiServiceTest.report(result);
        assertEquals("6", report.get("floor"));
        assertEquals("북악관 602호", report.get("locationText"));
        assertEquals("직접 확인한 오전", report.get("occurredAtText"));
        assertEquals(prior.get("symptom"), report.get("symptom"));
    }

    @Test void vagueFollowupsStopWithoutErasingKnownSymptomsOrRequiringTime() {
        var ai = AiServiceTest.demo();
        var initial = ai.analyze(Map.of("text", "와이파이가 계속 끊겨요", "preferredIntent", "REPORT"));
        var first = ai.analyze(input(AiServiceTest.report(initial), "잘 모르겠어요", (Map<String,Object>)initial.get("followUpState")));
        assertNotNull(AiServiceTest.report(first).get("followUpQuestion"));
        var second = ai.analyze(input(AiServiceTest.report(first), "잘 모르겠어요", (Map<String,Object>)first.get("followUpState")));
        assertEquals("NO_PROGRESS", ((Map<?,?>)second.get("followUpState")).get("status"));
        assertNull(AiServiceTest.report(second).get("followUpQuestion"));
        assertEquals(AiServiceTest.report(initial).get("symptom"), AiServiceTest.report(second).get("symptom"));
        assertEquals(List.of("LOCATION"), AiServiceTest.report(second).get("missingFields"));
    }

    private Map<String,Object> prior() { return AiServiceTest.report(AiServiceTest.demo().analyze(Map.of("text", "어제 북악관 502호 와이파이가 끊겨요"))); }
    private Map<String,Object> state(int turns,int noProgress,String status) { return Map.of("turns",turns,"noProgress",noProgress,"askedFields",List.of("LOCATION"),"status",status); }
    private Map<String,Object> input(Map<String,Object> prior,String answer,Map<String,Object> state) {
        var follow = new LinkedHashMap<String,Object>(Map.of("field","LOCATION","answer",answer,"previousReport",prior));
        if(state!=null)follow.put("state",state);
        return Map.of("text","어제 북악관 502호 와이파이가 끊겨요","preferredIntent","REPORT","followUp",follow);
    }
    private Map<String,Object> changes(String group,String action,String evidence) {
        var updates=new LinkedHashMap<String,Object>();
        for(String field:FollowUpPolicy.GROUPS) {
            var change=new LinkedHashMap<String,Object>();change.put("action",field.equals(group)?action:"KEEP");
            change.put("evidence",field.equals(group)?evidence:null);updates.put(field,change);
        }
        return updates;
    }
    private class Stub extends AiService {
        int calls; JsonNode body; Map<String,Object> raw, finalResult, assessment;
        final Map<String,Object> proposed, updates;
        Stub(Map<String,Object> proposed,Map<String,Object> updates) {
            super("LIVE","https://api.openai.com/v1","synthetic-test-key","stub","RULE_ONLY","",5);
            this.proposed=proposed;this.updates=updates;
        }
        @Override protected JsonNode complete(Map<String,Object> request) {
            calls++;body=json.valueToTree(request);
            try {var payload=new LinkedHashMap<String,Object>(Map.of("intent","REPORT","report",proposed));if(!body.at("/response_format/json_schema/schema/properties/updates").isMissingNode())payload.put("updates",updates);if(assessment!=null)payload.put("assessment",assessment);
                return json.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(payload)))))); }
            catch(Exception e){throw new AssertionError(e);}
        }
        @Override protected void onAnalysis(Map<String,Object> input,Map<String,Object> raw,Map<String,Object> merged) {this.raw=raw;this.finalResult=merged;}
    }
}
