package edu.skope.ai;

import edu.skope.service.SimilarityService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent expectations fixed before execution. Server logic only; no provider calls. */
class WorkGroupCorrectionTest {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    Map<String,Object> report(){
        var r=new LinkedHashMap<String,Object>();
        r.put("category","OTHER");r.put("subcategory","UNKNOWN");r.put("symptom","거울이 깨졌어요");
        r.put("workGroup","FACILITY_MAINTENANCE");r.put("targetText","거울");r.put("campusRelevance","CAMPUS_FACILITY");
        r.put("building","은주2관");r.put("floor","3");r.put("locationText","은주2관 3층 화장실");
        r.put("originalText","은주2관 3층 화장실 거울이 깨졌어요");r.put("missingFields",List.of());r.put("emergencyHint",false);
        return r;
    }
    Map<String,Object> keep(){
        var updates=new LinkedHashMap<String,Object>();
        for(String group:List.of("LOCATION","SYMPTOM","OCCURRED_AT","CATEGORY"))updates.put(group,Map.of("action","KEEP"));
        return updates;
    }
    @Test void explicitOwnershipCorrectionMustNotKeepTheOldSchoolScope(){
        var old=report();var proposed=new LinkedHashMap<>(old);
        proposed.put("campusRelevance","PERSONAL_ON_CAMPUS");proposed.put("workGroup","GENERAL_REVIEW");
        String answer="아니요, 학교 물건이 아니라 제 개인 거울이에요";
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,keep(),answer,"CATEGORY",Map.of("turns",1)).report();
        assertEquals("PERSONAL_ON_CAMPUS",merged.get("campusRelevance"));
        assertEquals("GENERAL_REVIEW",merged.get("workGroup"));
        assertEquals(old.get("symptom"),merged.get("symptom"));
    }
    @Test void correctedProblemWithAnUnknownTargetMustNotKeepTheDeniedObject(){
        var old=report();var proposed=new LinkedHashMap<>(old);String answer="거울이 아니고 다른 물건이 고장났어요";
        proposed.put("symptom","다른 물건 고장");proposed.put("targetText",null);
        var updates=keep();updates.put("SYMPTOM",Map.of("action","SET","evidence",answer));
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"SYMPTOM",Map.of("turns",1)).report();
        assertEquals("다른 물건 고장",merged.get("symptom"));assertNull(merged.get("targetText"));
    }
    @Test void sameObjectAliasMustNotBeExcludedByLiteralTextWhenEmbeddingPasses(){
        var a=report();var b=report();b.put("targetText","미러");b.put("originalText","은주2관 3층 화장실 미러가 깨졌어요");
        for(var r:List.of(a,b)){
            r.put("createdAt","2026-10-27T12:00:00+09:00");r.put("embeddingModel","synthetic-fixed-vector");r.put("embeddingJson","[1.0,0.0]");
        }
        var diagnostics=SimilarityService.diagnose(a,b,.82,30);
        assertEquals(1.0,(Double)diagnostics.get("rawCosine"),.00001);
        assertEquals(true,diagnostics.get("candidate"),diagnostics.toString());
    }

    @Test void ownershipOnlyUpdateKeepsSymptomPlaceAndTarget(){
        var old=report();var proposed=new LinkedHashMap<>(old);String answer="제 소지품입니다";
        proposed.put("campusRelevance","PERSONAL_ON_CAMPUS");proposed.put("workGroup","FACILITY_MAINTENANCE");
        var updates=keep();updates.put("CAMPUS_RELEVANCE",Map.of("action","SET","evidence",answer));
        var outcome=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"CATEGORY",Map.of("turns",1));
        assertEquals("PERSONAL_ON_CAMPUS",outcome.report().get("campusRelevance"));
        assertEquals("GENERAL_REVIEW",outcome.report().get("workGroup"));
        for(String key:List.of("targetText","symptom","locationText"))assertEquals(old.get(key),outcome.report().get(key));
        assertEquals(0,outcome.state().get("noProgress"));
    }
    @Test void locationOnlyReplyCannotChangeKnownOwnershipOrTarget(){
        var old=report();var proposed=new LinkedHashMap<>(old);String answer="4층이에요";
        proposed.put("campusRelevance","PERSONAL_ON_CAMPUS");proposed.put("targetText",null);proposed.put("floor","4");
        var updates=keep();updates.put("LOCATION",Map.of("action","SET","evidence",answer));
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"LOCATION",Map.of("turns",1)).report();
        assertEquals("4",merged.get("floor"));
        for(String key:List.of("campusRelevance","targetText","symptom","workGroup"))assertEquals(old.get(key),merged.get(key));
    }
    @Test void ungroundedMetadataUpdatesDoNotChangeFacts(){
        var old=report();var proposed=new LinkedHashMap<>(old);proposed.put("campusRelevance","PERSONAL_ON_CAMPUS");proposed.put("targetText",null);
        var updates=keep();for(String group:List.of("TARGET","CAMPUS_RELEVANCE"))updates.put(group,Map.of("action","SET","evidence","다른 사람이 한 말"));
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,updates,"3층이에요","LOCATION",Map.of("turns",1)).report();
        assertEquals(old.get("targetText"),merged.get("targetText"));assertEquals(old.get("campusRelevance"),merged.get("campusRelevance"));
    }
    @Test void uncertainOwnershipDoesNotKeepAFalseSchoolCertainty(){
        var old=report();var proposed=new LinkedHashMap<>(old);String answer="제 물건인지 학교 물건인지 모르겠어요";
        var updates=keep();updates.put("CAMPUS_RELEVANCE",Map.of("action","CLARIFY","evidence",answer));
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"CATEGORY",Map.of("turns",1)).report();
        assertEquals("UNCERTAIN",merged.get("campusRelevance"));assertEquals("GENERAL_REVIEW",merged.get("workGroup"));
    }
    @Test void reverseOwnershipCorrectionCanRestoreSchoolService(){
        var old=report();old.put("campusRelevance","PERSONAL_ON_CAMPUS");old.put("workGroup","GENERAL_REVIEW");
        var proposed=new LinkedHashMap<>(old);proposed.put("campusRelevance","SCHOOL_SERVICE");proposed.put("workGroup","SHARED_EQUIPMENT");
        String answer="학교에서 빌린 물건입니다";var updates=keep();updates.put("CAMPUS_RELEVANCE",Map.of("action","SET","evidence",answer));
        var merged=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"CATEGORY",Map.of("turns",1)).report();
        assertEquals("SCHOOL_SERVICE",merged.get("campusRelevance"));assertEquals("SHARED_EQUIPMENT",merged.get("workGroup"));
    }
    @Test void unknownReplacementInvalidatesOldTargetEvidenceAndAsksOnce(){
        var old=report();var proposed=new LinkedHashMap<>(old);String answer="거울이 아니고 다른 물건이 고장났어요";
        proposed.put("symptom","다른 물건 고장");proposed.put("targetText",null);
        var updates=keep();updates.put("SYMPTOM",Map.of("action","SET","evidence",answer));
        var oldState=new LinkedHashMap<String,Object>(Map.of("turns",0,"knownEvidence",Map.of("targetEvidence","거울"),"nextReason","LOCATION_DETAIL"));
        var outcome=FollowUpPolicy.apply("REPORT",proposed,old,updates,answer,"LOCATION",oldState);
        var decision=ClarificationPolicy.decide("REPORT",old.get("originalText").toString(),answer,"LOCATION",old,outcome.report(),outcome.state(),oldState,updates,
                Map.of("reason","NONE","known",Map.of("targetEvidence","거울")));
        assertEquals("TARGET_UNCLEAR",decision.state().get("nextReason"));
        assertNull(((Map<?,?>)decision.state().get("knownEvidence")).get("targetEvidence"));
    }
    @Test void exhaustedDraftDoesNotApplyMetadataUpdates(){
        var old=report();var proposed=new LinkedHashMap<>(old);proposed.put("targetText",null);
        var updates=keep();updates.put("TARGET",Map.of("action","SET","evidence","다른 물건"));
        assertEquals("거울",FollowUpPolicy.apply("REPORT",proposed,old,updates,"다른 물건","CATEGORY",Map.of("turns",3)).report().get("targetText"));
    }
    @Test void absentTargetInAnUnrelatedSymptomUpdateDoesNotEraseIt(){
        var old=report();var proposed=new LinkedHashMap<>(old);proposed.put("targetText",null);proposed.put("symptom","금이 더 커짐");
        var updates=keep();updates.put("SYMPTOM",Map.of("action","SET","evidence","금이 더 커졌어요"));
        assertEquals("거울",FollowUpPolicy.apply("REPORT",proposed,old,updates,"금이 더 커졌어요","SYMPTOM",Map.of("turns",1)).report().get("targetText"));
    }
    @Test void highCosineCannotJoinDifferentObjectsOrGroups(){
        var a=report();var b=report();b.put("targetText","문손잡이");
        for(var r:List.of(a,b)){r.put("createdAt","2026-10-27T12:00:00+09:00");r.put("embeddingModel","fixed");r.put("embeddingJson","[1.0,0.0]");}
        assertEquals(false,SimilarityService.diagnose(a,b,.82,30).get("candidate"));
        b.put("targetText","mirror");b.put("workGroup","SHARED_EQUIPMENT");
        assertEquals(false,SimilarityService.diagnose(a,b,.82,30).get("candidate"));
        b.put("workGroup","FACILITY_MAINTENANCE");b.put("embeddingJson","[0.0,1.0]");
        assertEquals(false,SimilarityService.diagnose(a,b,.82,30).get("candidate"));
    }
    @Test void symptomOnlyChangePreservesKnownRelevanceWhenProposalBecomesUncertain(){
        var old=report();var proposed=new LinkedHashMap<>(old);proposed.put("campusRelevance","UNCERTAIN");proposed.put("symptom","금이 커짐");
        var updates=keep();updates.put("SYMPTOM",Map.of("action","SET","evidence","금이 더 커졌어요"));
        assertEquals("CAMPUS_FACILITY",FollowUpPolicy.apply("REPORT",proposed,old,updates,"금이 더 커졌어요","SYMPTOM",Map.of("turns",1)).report().get("campusRelevance"));
    }
    @Test void fourGroupStoredAndSixGroupNewResponsesBothValidate(){
        for(boolean modern:List.of(false,true)){
            var old=report();old.remove("originalText");var proposed=new LinkedHashMap<>(old);
            proposed.put("occurredAtText",null);proposed.put("followUpQuestion",null);proposed.put("campusRelevance","PERSONAL_ON_CAMPUS");
            var updates=keep();String answer="학교 물건이 아니라 제 개인 거울이에요";
            if(modern){updates.put("TARGET",Map.of("action","KEEP"));updates.put("CAMPUS_RELEVANCE",Map.of("action","SET","evidence",answer));}
            // Provider KEEP evidence is explicitly null in the wire format.
            var wire=new LinkedHashMap<String,Object>();updates.forEach((k,v)->{var change=new LinkedHashMap<>((Map<String,Object>)v);change.putIfAbsent("evidence",null);wire.put(k,change);});
            var ai=new AiService("LIVE","http://127.0.0.1:1","offline-only","fixture","RULE_ONLY","",1){
                @Override protected com.fasterxml.jackson.databind.JsonNode complete(Map<String,Object> body){
                    try{return JSON.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",JSON.writeValueAsString(Map.of("intent","REPORT","report",proposed,"updates",wire)))))));}
                    catch(Exception e){throw new IllegalStateException(e);}
                }
            };
            var result=ai.analyze(Map.of("text","은주2관 3층 화장실 거울이 깨졌어요","followUp",Map.of("field","CATEGORY","answer",answer,"previousReport",old,"state",Map.of("turns",1,"noProgress",0,"status","CONTINUE"))));
            assertEquals("PERSONAL_ON_CAMPUS",((Map<?,?>)result.get("report")).get("campusRelevance"));
        }
    }
}
