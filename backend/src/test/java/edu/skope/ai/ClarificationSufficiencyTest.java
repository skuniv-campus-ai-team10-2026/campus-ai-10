package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.domain.CampusPlaces;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Server policy for "is this actionable?" with expectations fixed before running. Each model output is a scripted
 * stub (including deliberately over-eager missing flags and questions), so this checks what the server does with
 * an output, not LIVE model quality.
 */
class ClarificationSufficiencyTest {
    private final ObjectMapper json = new ObjectMapper();

    private Map<String,Object> model(String category,String subcategory,String group,String target,String building,String floor,
                                     String place,String symptom,List<String> missing) {
        var r=new LinkedHashMap<>(AiServiceTest.report(AiServiceTest.demo().analyze(Map.of("text","어제 북악관 502호 와이파이가 끊겨요"))));
        r.put("category",category);r.put("subcategory",subcategory);r.put("workGroup",group);r.put("targetText",target);
        r.put("campusRelevance","CAMPUS_FACILITY");r.put("building",building);r.put("floor",floor);r.put("locationText",place);
        r.put("symptom",symptom);r.put("missingFields",missing);r.put("occurredAtText",null);r.put("followUpQuestion",null);r.put("emergencyHint",false);
        return r;
    }
    private Map<String,Object> assess(String reason,String evidence,String question) {
        var facts=new LinkedHashMap<String,Object>();for(String key:ClarificationPolicy.FACTS)facts.put(key,null);
        var a=new LinkedHashMap<String,Object>();a.put("reason",reason);a.put("evidence",evidence);a.put("rationale",reason.equals("NONE")?null:"확인");
        a.put("question",question);a.put("known",facts);return a;
    }
    private Map<String,Object> updates(String group,String action,String evidence) {
        var u=new LinkedHashMap<String,Object>();
        for(String g:FollowUpPolicy.GROUPS){var c=new LinkedHashMap<String,Object>();c.put("action",g.equals(group)?action:"KEEP");c.put("evidence",g.equals(group)?evidence:null);u.put(g,c);}
        return u;
    }
    private Map<String,Object> asked(String reason) {
        var s=new LinkedHashMap<String,Object>();s.put("turns",0);s.put("noProgress",0);s.put("askedFields",List.of());s.put("reviewFields",List.of());
        s.put("status","CONTINUE");s.put("nextField",reason.startsWith("LOCATION")?"LOCATION":"SYMPTOM");s.put("nextReason",reason);
        s.put("askedReasons",List.of());s.put("neededReasons",List.of(reason));s.put("knownEvidence",Map.of());return s;
    }
    private Map<String,Object> first(String text,Map<String,Object> proposed,Map<String,Object> assessment) {
        return new Stub(proposed,Map.of(),assessment).analyze(Map.of("text",text,"hybrid",true));
    }
    private Map<String,Object> reply(String text,Map<String,Object> previous,Map<String,Object> state,String field,String answer,
                                     Map<String,Object> proposed,Map<String,Object> updates,Map<String,Object> assessment) {
        return new Stub(proposed,updates,assessment).analyze(Map.of("text",text,"hybrid",true,
                "followUp",Map.of("field",field,"answer",answer,"previousReport",previous,"state",state)));
    }
    private static Map<?,?> state(Map<String,Object> r){return (Map<?,?>)r.get("followUpState");}
    private static Map<String,Object> report(Map<String,Object> r){return AiServiceTest.report(r);}
    private static void accepted(Map<String,Object> r,String id){
        assertEquals("COMPLETE",state(r).get("status"),id+" "+r.get("clarification"));
        assertNull(report(r).get("followUpQuestion"),id);
    }

    @Test void c1DirtyLibraryIsActionableWithoutAFloorEvenWhenTheModelAsksForOne(){
        var r=first("도서관이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"도서관",null,"도서관","도서관이 더러움",List.of("LOCATION")),
                assess("LOCATION_DETAIL","도서관","도서관 몇 층인가요?"));
        accepted(r,"C1");
        assertEquals("유담관",report(r).get("building"));assertEquals("도서관",report(r).get("locationText"),"facility context kept");
        assertNull(report(r).get("floor"));assertTrue(report(r).get("symptom").toString().contains("더러"));
        // The model's missing-symptom flag alone does not override the student's stated condition.
        var flagged=first("도서관이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"도서관",null,"도서관",null,List.of("SYMPTOM")),
                assess("ISSUE_UNCLEAR","도서관이 더러워요","무엇이 불편한가요?"));
        accepted(flagged,"C1 flagged");assertEquals("도서관이 더러워요",report(flagged).get("symptom"));
    }

    @Test void c2FloorReplyKeepsTheCleanlinessIssueAndPlaceAndEnds(){
        var previous=report(first("도서관이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"도서관",null,"도서관","도서관이 더러움",List.of()),assess("NONE",null,null)));
        // Answering an earlier location question; the model now also proposes a new optional diagnosis.
        var r=reply("도서관이 더러워요",previous,asked("LOCATION_DETAIL"),"LOCATION","8층이에요",
                model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"도서관","8","도서관 8층","도서관이 더러움",List.of()),
                updates("LOCATION","SET","8층"),assess("SYMPTOM_DETAIL","더러워요","어떻게 더러운가요?"));
        accepted(r,"C2");
        var rep=report(r);assertEquals("유담관",rep.get("building"));assertEquals("8",rep.get("floor"));
        assertTrue(rep.get("locationText").toString().contains("도서관")&&rep.get("locationText").toString().contains("8층"),rep.get("locationText").toString());
        assertEquals("도서관이 더러움",rep.get("symptom"));
    }

    @Test void c3LibraryFloorIsAcceptedDirectly(){
        var r=first("도서관 8층이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"도서관","8","도서관 8층","도서관 8층이 더러움",List.of()),
                assess("NONE",null,null));
        accepted(r,"C3");assertEquals("유담관",report(r).get("building"));assertEquals("8",report(r).get("floor"));
        assertEquals("도서관 8층",report(r).get("locationText"));
    }

    @Test void c4VagueClassroomComplaintAsksForTheActualProblemWithContext(){
        var proposed=model("OTHER","UNKNOWN","GENERAL_REVIEW",null,null,null,"강의실",null,List.of("SYMPTOM"));
        var withModelQuestion=first("강의실이 불편해요",proposed,assess("ISSUE_UNCLEAR","강의실이 불편해요","강의실에서 어떤 점이 불편했나요?"));
        assertEquals("CONTINUE",state(withModelQuestion).get("status"));assertEquals("ISSUE_UNCLEAR",state(withModelQuestion).get("nextReason"));
        assertEquals("강의실에서 어떤 점이 불편했나요?",report(withModelQuestion).get("followUpQuestion"));
        var clarification=(Map<?,?>)withModelQuestion.get("clarification");
        assertEquals("ISSUE_UNCLEAR",clarification.get("reason"));assertNotNull(clarification.get("message"));
        // A model question that asks for something forbidden falls back to the server's contextual question.
        var fallback=first("강의실이 불편해요",proposed,assess("ISSUE_UNCLEAR","강의실이 불편해요","언제부터 불편했나요?"));
        assertEquals("강의실을 이용할 때 어떤 문제가 있나요?",report(fallback).get("followUpQuestion"));
    }

    @Test void c5ConcreteAirConditionerFaultIsAcceptedWithoutDiagnosis(){
        var r=first("북악관 608호 에어컨이 안 켜져요",model("FACILITY","HVAC","FACILITY_MAINTENANCE","에어컨","북악관","6","북악관 608호","에어컨이 켜지지 않음",List.of()),
                assess("SYMPTOM_DETAIL","에어컨이 안 켜져요","바람이 전혀 나오지 않나요?"));
        accepted(r,"C5");
    }

    @Test void c6UnknownAnswerGoesToReviewWithoutRepeating(){
        var proposed=model("OTHER","UNKNOWN","GENERAL_REVIEW",null,null,null,"강의실",null,List.of("SYMPTOM"));
        var start=first("강의실이 불편해요",proposed,assess("ISSUE_UNCLEAR","강의실이 불편해요","강의실에서 어떤 점이 불편했나요?"));
        var r=reply("강의실이 불편해요",report(start),castState(start),"SYMPTOM","잘 모르겠어요",proposed,updates("SYMPTOM","KEEP",null),
                assess("ISSUE_UNCLEAR","잘 모르겠어요","무엇이 불편한가요?"));
        assertEquals("MANUAL_REVIEW",state(r).get("status"));assertNull(report(r).get("followUpQuestion"));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> castState(Map<String,Object> r){return (Map<String,Object>)r.get("followUpState");}

    @Test void c7LibraryAndYudamgwanWordingDoesNotResetKnownFacts(){
        var previous=report(first("도서관 에어컨이 안 나와요",model("FACILITY","HVAC","FACILITY_MAINTENANCE","에어컨","도서관",null,"도서관","에어컨이 작동하지 않음",List.of()),
                assess("LOCATION_DETAIL","도서관","도서관 몇 층인가요?")));
        assertEquals("유담관",previous.get("building"));
        var r=reply("도서관 에어컨이 안 나와요",previous,asked("LOCATION_DETAIL"),"LOCATION","유담관 8층이에요",
                model("FACILITY","HVAC","FACILITY_MAINTENANCE","에어컨","유담관","8","유담관 8층","에어컨이 작동하지 않음",List.of()),
                updates("LOCATION","SET","유담관 8층"),assess("NONE",null,null));
        accepted(r,"C7");
        var rep=report(r);assertEquals("유담관",rep.get("building"));assertEquals("8",rep.get("floor"));
        assertEquals("에어컨이 작동하지 않음",rep.get("symptom"));assertEquals("에어컨",rep.get("targetText"));
        assertTrue(rep.get("locationText").toString().contains("도서관"),"same building: facility context is not reset");
        assertEquals(CampusPlaces.key("도서관"),CampusPlaces.key("유담관"),"similar-report and incident place comparison use the same key");
    }

    @Test void c8YudamgwanAloneIsNeverTurnedIntoTheLibrary(){
        var r=first("유담관 10층 화장실이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"유담관","10","유담관 10층 화장실","화장실이 더러움",List.of()),
                assess("NONE",null,null));
        accepted(r,"C8");assertEquals("유담관 10층 화장실",report(r).get("locationText"));
        assertFalse(CampusPlaces.housedFacility("유담관"));assertEquals("북악관",CampusPlaces.building("북악관"));
        // An explicitly named other building is not overwritten by a facility word in the place text.
        var other=first("북악관 도서관 앞이 더러워요",model("OTHER","UNKNOWN","FACILITY_MAINTENANCE",null,"북악관",null,"북악관 도서관 앞","바닥이 더러움",List.of()),
                assess("NONE",null,null));
        assertEquals("북악관",report(other).get("building"));
    }

    // Boundary cases requested before the LIVE run.
    @Test void b1StatedWholeBuildingRangeIsActionableEvenForAFixedFacility(){
        var r=first("북악관 전체 와이파이가 안돼요",model("IT","WIFI","IT_SUPPORT","와이파이","북악관",null,"북악관","와이파이 접속 불가",List.of()),
                assess("SYMPTOM_DETAIL","와이파이가 안돼요","연결 자체가 안 되나요?"));
        accepted(r,"B1");
        // Without a stated range the same fixed facility still needs the affected space.
        var local=first("북악관 와이파이가 안돼요",model("IT","WIFI","IT_SUPPORT","와이파이","북악관",null,"북악관","와이파이 접속 불가",List.of()),
                assess("NONE",null,null));
        assertEquals("CONTINUE",state(local).get("status"));assertEquals("LOCATION_DETAIL",state(local).get("nextReason"));
    }

    @Test void b2AnsweredFloorIsNotReaskedAndNoNewOptionalItemIsAdded(){
        var previous=report(first("도서관 정수기가 고장났어요",model("FACILITY","WATER_DISPENSER","SHARED_EQUIPMENT","정수기","도서관",null,"도서관","정수기 고장",List.of()),
                assess("NONE",null,null)));
        for(var proposal:List.of(assess("TARGET_UNCLEAR","정수기가 고장났어요","어떤 정수기인가요?"),assess("SYMPTOM_DETAIL","고장났어요","물이 안 나오나요?"),
                assess("LOCATION_DETAIL","8층","8층 어느 쪽인가요?"))){
            var r=reply("도서관 정수기가 고장났어요",previous,asked("LOCATION_DETAIL"),"LOCATION","8층이에요",
                    model("FACILITY","WATER_DISPENSER","SHARED_EQUIPMENT","정수기","도서관","8","도서관 8층","정수기 고장",List.of()),
                    updates("LOCATION","SET","8층"),proposal);
            accepted(r,"B2 "+proposal.get("reason"));assertEquals("8",report(r).get("floor"));assertEquals("정수기 고장",report(r).get("symptom"));
        }
        // A real contradiction in the answer still goes to the existing conflict path, not silent acceptance.
        var conflict=reply("도서관 정수기가 고장났어요",previous,asked("LOCATION_DETAIL"),"LOCATION","8층인지 9층인지 모르겠어요",
                model("FACILITY","WATER_DISPENSER","SHARED_EQUIPMENT","정수기","도서관",null,"도서관","정수기 고장",List.of()),
                updates("LOCATION","CLARIFY","8층인지 9층인지"),assess("NONE",null,null));
        assertNotEquals("COMPLETE",state(conflict).get("status"));
    }

    @Test void b4TheQuestionAsksForTheActuallyMissingItemWithKnownContext(){
        var r=first("도서관 정수기가 고장났어요",model("FACILITY","WATER_DISPENSER","SHARED_EQUIPMENT","정수기","도서관",null,"도서관","정수기 고장",List.of()),
                assess("LOCATION_DETAIL","도서관","언제 고장났나요?"));
        assertEquals("CONTINUE",state(r).get("status"));assertEquals("LOCATION_DETAIL",state(r).get("nextReason"));
        String question=report(r).get("followUpQuestion").toString();
        assertTrue(question.contains("도서관")&&question.contains("정수기")&&question.contains("층"),question);
        assertFalse(question.contains("언제"),"a forbidden model question is not used");
    }

    private class Stub extends AiService {
        final Map<String,Object> proposed, updates, assessment;
        Stub(Map<String,Object> proposed,Map<String,Object> updates,Map<String,Object> assessment) {
            super("LIVE","https://api.openai.com/v1","synthetic-test-key","stub","RULE_ONLY","",5);
            this.proposed=proposed;this.updates=updates;this.assessment=assessment;
        }
        @Override protected JsonNode complete(Map<String,Object> request) {
            JsonNode body=json.valueToTree(request);
            try {var payload=new LinkedHashMap<String,Object>(Map.of("intent","REPORT","report",proposed,"assessment",assessment));
                if(!body.at("/response_format/json_schema/schema/properties/updates").isMissingNode())payload.put("updates",updates);
                return json.valueToTree(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(payload)))))); }
            catch(Exception e){throw new AssertionError(e);}
        }
    }
}
