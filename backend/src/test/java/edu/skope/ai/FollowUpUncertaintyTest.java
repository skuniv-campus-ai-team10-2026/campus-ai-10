package edu.skope.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Server defense against a wrong model SET for TARGET / CAMPUS_RELEVANCE. Expectations fixed before execution.
 * Every case runs the full analyze() path (FollowUpPolicy + ClarificationPolicy) on the saved first turn
 * "학교 공용 화장실 미러가 깨졌어요" (target 거울, CAMPUS_FACILITY, location question pending), with a scripted second turn.
 * This checks the server contract only, not how often a real model makes the mistake.
 */
class FollowUpUncertaintyTest {
    static final Map<String,Object> PERSONAL=Map.of("campusRelevance","PERSONAL_ON_CAMPUS","workGroup","GENERAL_REVIEW");

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(ScriptedAi ai,String answer){
        var first=ai.analyze(new LinkedHashMap<>(Map.of("hybrid",true,"preferredIntent","AUTO","text",ai.firstText())));
        var state=(Map<String,Object>)first.get("followUpState");
        assertEquals("LOCATION",state.get("nextField"),"fixture: the location question is pending");
        var follow=new LinkedHashMap<String,Object>();
        follow.put("field",state.get("nextField"));follow.put("answer",answer);follow.put("previousReport",first.get("report"));follow.put("state",state);
        return ai.analyze(new LinkedHashMap<>(Map.of("hybrid",true,"preferredIntent","REPORT","text",ai.firstText(),"followUp",follow)));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> report(Map<String,Object> r){return (Map<String,Object>)r.get("report");}
    @SuppressWarnings("unchecked") static Map<String,Object> state(Map<String,Object> r){return (Map<String,Object>)r.get("followUpState");}
    static Map<String,String[]> set(String group,String evidence){return Map.of(group,new String[]{"SET",evidence});}
    static void keptFacts(Map<String,Object> report){
        assertEquals("학교 공용 화장실",report.get("locationText"),"known place survives");
        assertEquals("공용 화장실의 거울이 깨졌습니다.",report.get("symptom"),"known symptom survives");
    }

    // 1. Reproduction cases from the lead's report.

    @Test void unknownOwnershipIsNotConfirmedAsPersonalEvenIfTheModelSetsIt(){
        String answer="제 개인 물건인지 학교 물건인지 모르겠어요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 개인 물건")),answer);
        assertEquals("UNCERTAIN",report(r).get("campusRelevance"));
        assertEquals("GENERAL_REVIEW",report(r).get("workGroup"));
        assertEquals("거울",report(r).get("targetText"));keptFacts(report(r));
        assertEquals("COMPLETE",state(r).get("status"),"the volunteered doubt counts as the ownership answer; supervisor reviews");
        assertTrue(((List<?>)state(r).get("askedReasons")).contains("CAMPUS_RELEVANCE"),"ownership is not asked again");
    }
    @Test void unknownOwnershipWithoutAnyUpdateGroupIsNotSettledByTheWordSchool(){
        // Old four-group responses: the "settled" path used to accept any answer containing "학교".
        String answer="제 개인 물건인지 학교 물건인지 모르겠어요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,Map.of()),answer);
        assertNotEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
    }
    @Test void anUndecidedTargetIsNotChosenByTheModel(){
        String answer="거울인지 문손잡이인지 모르겠어요";
        var r=run(new ScriptedAi().on(answer,Map.of("targetText","거울"),set("TARGET","거울")),answer);
        assertNull(report(r).get("targetText"),"neither alternative is confirmed");
        assertEquals("CAMPUS_FACILITY",report(r).get("campusRelevance"));keptFacts(report(r));
        assertEquals("MANUAL_REVIEW",state(r).get("status"),"the student cannot name it; review instead of asking again");
        assertTrue(((List<?>)state(r).get("unavailableReasons")).contains("TARGET_UNCLEAR"));
        assertNull(report(r).get("followUpQuestion"));
    }
    @Test void anUndecidedTargetIsNotConfirmedWhenTheModelPicksTheOtherAlternative(){
        String answer="거울인지 문손잡이인지 모르겠어요";
        var r=run(new ScriptedAi().on(answer,Map.of("targetText","문손잡이"),set("TARGET",answer)),answer);
        assertNull(report(r).get("targetText"));
    }
    @Test void aDoubtStatedInTheNextSentenceStillAppliesToOwnership(){
        String answer="제 개인 물건 같은데, 확실하지 않아요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 개인 물건 같은데")),answer);
        assertEquals("UNCERTAIN",report(r).get("campusRelevance"));
    }
    @Test void anUndecidedAlternativeWithoutTheWordDontKnowIsStillUndecided(){
        String answer="제 거 아니면 학교 거예요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 거")),answer);
        assertEquals("UNCERTAIN",report(r).get("campusRelevance"));
    }

    // 2. Valid answers the check must not block (no blanket "모르" filter).

    @Test void negatedDoubtIsAClearOwnershipAnswer(){
        String answer="모르지는 않아요. 제 물건 맞아요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 물건 맞아요")),answer);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
        assertEquals("GENERAL_REVIEW",report(r).get("workGroup"));
    }
    @Test void anUnknownPlaceDoesNotMakeOwnershipUnknown(){
        String answer="장소는 모르겠지만 제 개인 물건이에요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE",answer)),answer);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"),"only the place is unclear");
        assertEquals("거울",report(r).get("targetText"));keptFacts(report(r));
    }
    @Test void anOwnershipCorrectionIsApplied(){
        String answer="학교 물건이 아니라 제 개인 물건이에요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE",answer)),answer);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
        assertEquals("COMPLETE",state(r).get("status"));keptFacts(report(r));
    }
    @Test void anOwnershipCorrectionWithAnUnknownPlaceIsApplied(){
        String answer="학교 물건이 아니라 제 개인 물건이에요. 어느 건물인지는 모르겠어요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","학교 물건이 아니라 제 개인 물건이에요.")),answer);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
    }
    @Test void aClearTargetCorrectionIsApplied(){
        String answer="거울 말고 문손잡이가 고장났어요";
        var r=run(new ScriptedAi().on(answer,Map.of("targetText","문손잡이"),set("TARGET",answer)),answer);
        assertEquals("문손잡이",report(r).get("targetText"));
    }
    @Test void aTargetIsKeptWhenOnlyThePlaceIsUnknown(){
        String answer="문손잡이가 고장났어요. 몇 층인지는 모르겠어요";
        var r=run(new ScriptedAi().on(answer,Map.of("targetText","문손잡이"),set("TARGET","문손잡이가 고장났어요")),answer);
        assertEquals("문손잡이",report(r).get("targetText"));
    }
    @Test void aWordContainingInjiIsNotDoubt(){
        String answer="제 개인지갑이에요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE",answer)),answer);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
    }

    // 3. Lead review (10/29): one field's doubt must not skip or erase another field's handling.

    @Test void anOwnershipDoubtStillAppliesATargetClearInTheSameAnswer(){
        String answer="학교 물건인지 모르겠어요. 대상 정보는 삭제해 주세요";
        var updates=new LinkedHashMap<String,String[]>();
        updates.put("CAMPUS_RELEVANCE",new String[]{"SET","학교 물건인지 모르겠어요"});updates.put("TARGET",new String[]{"CLEAR","대상 정보는 삭제해 주세요"});
        var r=run(new ScriptedAi().on(answer,PERSONAL,updates),answer);
        assertEquals("UNCERTAIN",report(r).get("campusRelevance"));
        assertNull(report(r).get("targetText"),"the explicit target clear is not skipped");keptFacts(report(r));
    }
    @Test void anOwnershipDoubtStillAppliesATargetClarifyInTheSameAnswer(){
        String answer="학교 물건인지 모르겠어요. 거울인지 다른 건지도 헷갈려요";
        var updates=new LinkedHashMap<String,String[]>();
        updates.put("CAMPUS_RELEVANCE",new String[]{"SET","학교 물건인지 모르겠어요"});updates.put("TARGET",new String[]{"CLARIFY","거울인지 다른 건지도 헷갈려요"});
        var r=run(new ScriptedAi().on(answer,PERSONAL,updates),answer);
        assertEquals("UNCERTAIN",report(r).get("campusRelevance"));assertNull(report(r).get("targetText"));
    }
    @Test void anUnknownPlaceInTheSameClauseDoesNotMakeOwnershipUnknown(){
        for(String answer:List.of("장소는 모르겠고 제 개인 물건이에요","장소는 모르겠어요. 제 개인 물건이에요")){
            var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 개인 물건이에요")),answer);
            assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"),answer);
        }
    }
    @Test void anOwnershipDoubtDoesNotEraseAClearTarget(){
        for(String answer:List.of("학교 물건인지는 모르겠어요. 문손잡이가 고장났어요","학교 물건인지는 모르겠지만 문손잡이가 고장났어요")){
            var updates=new LinkedHashMap<String,String[]>();
            updates.put("CAMPUS_RELEVANCE",new String[]{"SET",answer.substring(0,answer.indexOf("모르"))});updates.put("TARGET",new String[]{"SET","문손잡이가 고장났어요"});
            var r=run(new ScriptedAi().on(answer,Map.of("campusRelevance","PERSONAL_ON_CAMPUS","workGroup","GENERAL_REVIEW","targetText","문손잡이"),updates),answer);
            assertEquals("문손잡이",report(r).get("targetText"),answer);
            assertEquals("UNCERTAIN",report(r).get("campusRelevance"),answer);
        }
    }
    @Test void aClearFactBeforeAnotherFieldsDoubtSurvivesTheReversedOrder(){
        // Lead review (10/29 #2): the same answers with the clear fact first, joined by "~고".
        String personal="제 개인 물건이고 장소는 모르겠어요";
        var r=run(new ScriptedAi().on(personal,PERSONAL,set("CAMPUS_RELEVANCE","제 개인 물건이고")),personal);
        assertEquals("PERSONAL_ON_CAMPUS",report(r).get("campusRelevance"));
        String target="문손잡이가 고장났고 학교 물건인지는 모르겠어요";
        var updates=new LinkedHashMap<String,String[]>();
        updates.put("CAMPUS_RELEVANCE",new String[]{"SET","학교 물건인지는"});updates.put("TARGET",new String[]{"SET","문손잡이가 고장났고"});
        r=run(new ScriptedAi().on(target,Map.of("campusRelevance","PERSONAL_ON_CAMPUS","workGroup","GENERAL_REVIEW","targetText","문손잡이"),updates),target);
        assertEquals("문손잡이",report(r).get("targetText"));assertEquals("UNCERTAIN",report(r).get("campusRelevance"));
    }
    @Test void anOwnershipDoubtLeavesAnAdministratorNote(){
        String answer="제 개인 물건인지 학교 물건인지 모르겠어요";
        var r=run(new ScriptedAi().on(answer,PERSONAL,set("CAMPUS_RELEVANCE","제 개인 물건")),answer);
        assertEquals("COMPLETE",state(r).get("status"));
        assertEquals(true,state(r).get("urgentReview"),"filed without another question, so the supervisor sees why");
    }

    // 4. The shared clause rule itself, independent of any object name.

    @Test void clauseRuleIsObjectIndependent(){
        for(String object:List.of("실험복","프로젝터","사물함 열쇠","연필")){
            assertTrue(FollowUpPolicy.targetDoubt(object+"인지 다른 건지 모르겠어요",object,""),object);
            assertFalse(FollowUpPolicy.targetDoubt("장소는 모르겠지만 "+object+"가 고장났어요",object,""),object);
            assertFalse(FollowUpPolicy.targetDoubt(object+"가 고장났어요",object,""),object);
            // Doubt belongs to the word it is attached to, not to every subject in the clause or answer.
            assertFalse(FollowUpPolicy.targetDoubt("학교 물건인지는 모르겠어요. "+object+"가 고장났어요",object,""),object);
            assertTrue(FollowUpPolicy.targetDoubt("어떤 물건인지 모르겠어요",object,""),object);
            assertFalse(FollowUpPolicy.targetDoubt(object+"가 고장났고 학교 물건인지는 모르겠어요",object,""),object);
            assertTrue(FollowUpPolicy.targetDoubt("장소는 알고 "+object+"인지는 모르겠어요",object,""),object);
        }
        assertFalse(FollowUpPolicy.ownershipDoubt("장소는 모르겠고 제 개인 물건이에요",""));
        assertFalse(FollowUpPolicy.ownershipDoubt("제 개인 물건이고 장소는 모르겠어요",""));
        assertFalse(FollowUpPolicy.ownershipDoubt("제 개인 물건이며 몇 층인지는 모르겠어요",""));
        assertTrue(FollowUpPolicy.ownershipDoubt("제 개인 물건 같고 확실하지 않아요",""));
        assertTrue(FollowUpPolicy.ownershipDoubt("아마 제 개인 물건이에요",""));
        assertTrue(FollowUpPolicy.ownershipDoubt("제 거 아니면 학교 거예요",""));
    }
}
