package edu.skope.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic policy checks, not measurements of LIVE model quality. */
class ClarificationPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "은주2관에서 와이파이가 안돼요,LOCATION_DETAIL",
        "은주2관 403호에서 와이파이가 안돼요,SYMPTOM_DETAIL",
        "은주2관 403호에서 와이파이가 끊겨요,COMPLETE",
        "은주2관 전체에서 와이파이가 끊겨요,COMPLETE",
        "은주2관 403호에서 물이 떨어져요,COMPLETE",
        "강의실이 너무 불편해요,ISSUE_UNCLEAR",
        "휴학 신청 절차가 궁금해요,COMPLETE"
    })
    void typeSpecificMinimums(String input,String expected){
        var result=initial(input);var state=state(result);
        assertEquals(expected,expected.equals("COMPLETE")?state.get("status"):state.get("nextReason"));
        assertFalse(((List<?>)state.get("neededReasons")).contains("OCCURRED_AT"));
    }
    @Test void unsupportedButConcreteFurnitureIssueDoesNotRequireClassification(){
        // DEMO has no furniture extractor; use explicit extracted facts to test only the production policy.
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","OTHER","subcategory","UNKNOWN","locationText","북악관 502호","building","북악관","symptom","의자 다리가 흔들림","missingFields",List.of()));
        var d=ClarificationPolicy.decide("REPORT","북악관 502호 의자 다리가 흔들려요","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","NONE"));
        assertEquals("COMPLETE",d.state().get("status"));
    }
    @Test void oneReplyCanSupplySpaceTimeAndFailureMode(){
        var result=reply("은주2관에서 와이파이가 안돼요",initial("은주2관에서 와이파이가 안돼요"),"은주2관 403호에서 어제부터 와이파이가 계속 끊겨요");
        assertEquals("COMPLETE",state(result).get("status"));
        assertEquals("4",report(result).get("floor"));assertEquals("어제",report(result).get("occurredAtText"));
    }
    @Test void resolvingLocationStillRequiresFailureMode(){
        var result=reply("은주2관에서 와이파이가 안돼요",initial("은주2관에서 와이파이가 안돼요"),"은주2관 403호예요");
        assertEquals("SYMPTOM_DETAIL",state(result).get("nextReason"));
        assertEquals(List.of("LOCATION_DETAIL"),state(result).get("askedReasons"));
    }
    @Test void cannotAnswerStopsWithoutRepeating(){
        var result=reply("은주2관에서 와이파이가 안돼요",initial("은주2관에서 와이파이가 안돼요"),"어느 층인지는 잘 모르겠어요");
        assertEquals("MANUAL_REVIEW",state(result).get("status"));assertNull(report(result).get("followUpQuestion"));
    }
    @Test void buildingCorrectionDropsOldRoomAndScope(){
        var prior=initial("북악관 전체에서 와이파이가 끊겨요");
        var result=reply("북악관 전체에서 와이파이가 끊겨요",prior,"북악관이 아니라 유담관이에요");
        assertEquals("LOCATION_DETAIL",state(result).get("nextReason"));assertNull(report(result).get("floor"));
    }
    @Test void groundedButGenericWifiEvidenceCannotSkipQuestion(){
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","IT","subcategory","WIFI","locationText","은주2관 403호","building","은주2관","floor","4","symptom","와이파이가 안되는 거 같습니다","missingFields",List.of()));
        var d=ClarificationPolicy.decide("REPORT","은주2관 403호 와이파이가 안되는 거 같습니다","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","NONE","known",Map.of("symptomEvidence","와이파이가 안되는 거 같습니다")));
        assertEquals("SYMPTOM_DETAIL",d.state().get("nextReason"));
    }
    @ParameterizedTest
    @CsvSource({
        "와파 connected는 됨 근데 인터넷 먹통임,COMPLETE",
        "WiFi CONNECTED but NO INTERNET,COMPLETE",
        "와파 연결됨 근데 인터넷이 먹통임,COMPLETE",
        "와파 접속됨 인터넷 안됨,COMPLETE",
        "와파 먹통임,SYMPTOM_DETAIL",
        "와파 connected,SYMPTOM_DETAIL",
        "와파 connected인지 모르겠고 인터넷 먹통임,SYMPTOM_DETAIL",
        "와파 maybe connected but no internet,SYMPTOM_DETAIL",
        "와파 not connected but no internet,SYMPTOM_DETAIL",
        "와파 disconnected but no internet,SYMPTOM_DETAIL",
        "와파 connected는 아님 인터넷 먹통임,SYMPTOM_DETAIL",
        "와파 connected 인터넷 먹통은 아님,SYMPTOM_DETAIL"
    })
    void mixedLanguageRequiresBothExplicitObservations(String text,String expected){
        var d=mixedWifi(text,text);
        assertEquals(expected,expected.equals("COMPLETE")?d.state().get("status"):d.state().get("nextReason"));
    }
    @Test void croppedEnglishEvidenceCannotDropNegationOrUncertainty(){
        for(String prefix:List.of("not ","maybe ")) {
            var d=mixedWifi(prefix+"connected but no internet","connected but no internet");
            assertEquals("SYMPTOM_DETAIL",d.state().get("nextReason"));
        }
    }
    @Test void inventedMixedEvidenceCannotSatisfyGenericInput(){
        assertEquals("SYMPTOM_DETAIL",mixedWifi("와파 먹통임","connected but no internet").state().get("nextReason"));
    }
    @Test void mixedEvidenceSurvivesLocationReplyButNotSymptomCorrection(){
        var first=mixedWifi("와파 connected는 됨 근데 인터넷 먹통임","connected는 됨 근데 인터넷 먹통임");
        var retained=ClarificationPolicy.decide("REPORT","북악관 와파 connected는 됨 근데 인터넷 먹통임","410호예요","LOCATION",first.report(),first.report(),base(1),first.state(),Map.of("LOCATION",Map.of("action","SET")),Map.of());
        assertEquals("COMPLETE",retained.state().get("status"));
        var corrected=ClarificationPolicy.decide("REPORT","북악관 와파 connected는 됨 근데 인터넷 먹통임","정정할게요. 그냥 와파 먹통이고 연결 상태는 몰라요","SYMPTOM",first.report(),first.report(),base(1),first.state(),Map.of("SYMPTOM",Map.of("action","SET")),Map.of());
        assertEquals("SYMPTOM_DETAIL",corrected.state().get("nextReason"));
    }
    private ClarificationPolicy.Decision mixedWifi(String text,String evidence){
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","IT","subcategory","WIFI","locationText","북악관 410호","building","북악관","floor","4","symptom","와이파이는 연결되지만 인터넷이 되지 않음","missingFields",List.of()));
        return ClarificationPolicy.decide("REPORT","북악관 410호 "+text,"",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","NONE","known",Map.of("symptomEvidence",evidence)));
    }
    @Test void ungroundedModelEvidenceCannotSatisfyLocationOrOverrideRules(){
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","FACILITY","subcategory","LEAK","locationText","은주2관","building","은주2관","symptom","물이 떨어짐","missingFields",List.of()));
        var d=ClarificationPolicy.decide("REPORT","은주2관 물이 떨어져요","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","NONE","known",Map.of("locationEvidence","403호")));
        assertEquals("LOCATION_DETAIL",d.state().get("nextReason"));
    }
    @Test void aiCanProposeAmbiguityForUnsupportedTypeButNotOptionalTime(){
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","OTHER","subcategory","UNKNOWN","locationText","북악관 502호","building","북악관","symptom","화면 작동 이상","missingFields",List.of()));
        var d=ClarificationPolicy.decide("REPORT","북악관 502호 화면 작동 이상","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","SYMPTOM_DETAIL","evidence","화면 작동 이상"));
        assertEquals("SYMPTOM_DETAIL",d.state().get("nextReason"));assertEquals("AI_AND_RULES",d.explanation().get("source"));
    }
    @Test void capAndEmergencyOverrideQuestioning(){
        for(boolean urgent:List.of(false,true)){
            var r=new LinkedHashMap<>(Map.<String,Object>of("category","IT","subcategory","WIFI","symptom","와이파이가 안됨","missingFields",List.of("LOCATION"),"emergencyHint",urgent));
            var d=ClarificationPolicy.decide("REPORT","와이파이가 안됨","",null,Map.of(),r,base(3),Map.of(),Map.of(),Map.of());
            assertEquals(urgent?"COMPLETE":"LIMIT",d.state().get("status"));assertNull(r.get("followUpQuestion"));
            assertEquals(urgent,d.state().get("urgentReview"));
        }
    }
    @Test void targetedQuestionsForEquipmentAndAcademicStage(){
        for(String type:List.of("ELECTRIC","STUDENT_RECORD")){
            var r=new LinkedHashMap<>(Map.<String,Object>of("category",type.equals("ELECTRIC")?"FACILITY":"ACADEMIC","subcategory",type,"locationText","북악관 502호","building","북악관","symptom",type.equals("ELECTRIC")?"전기 문제":"휴학 신청 문제","missingFields",List.of()));
            var d=ClarificationPolicy.decide("REPORT","휴학 신청 문제","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of());
            assertEquals(type.equals("ELECTRIC")?"TARGET_UNCLEAR":"WORKFLOW_UNCLEAR",d.state().get("nextReason"));
        }
    }
    @Test void specificAcademicQuestionDoesNotRequireAnApplicationStage(){
        var r=new LinkedHashMap<>(Map.<String,Object>of("category","ACADEMIC","subcategory","STUDENT_RECORD","symptom","휴학 가능한 최대 학기 수 문의","missingFields",List.of()));
        var d=ClarificationPolicy.decide("QUESTION","휴학은 최대 몇 학기까지 가능한가요?","",null,Map.of(),r,base(0),Map.of(),Map.of(),Map.of("reason","NONE"));
        assertEquals("COMPLETE",d.state().get("status"));
    }
    private Map<String,Object> base(int turns){return Map.of("turns",turns,"noProgress",0,"reviewFields",List.of());}
    private Map<String,Object> initial(String text){return AiServiceTest.demo().analyze(Map.of("text",text,"hybrid",true));}
    private Map<String,Object> reply(String text,Map<String,Object> prior,String answer){return AiServiceTest.demo().analyze(Map.of("text",text,"hybrid",true,"followUp",Map.of("field",Objects.toString(state(prior).get("nextField"),"LOCATION"),"answer",answer,"previousReport",report(prior),"state",state(prior))));}
    private Map<String,Object> report(Map<String,Object> r){return AiServiceTest.report(r);}
    @SuppressWarnings("unchecked") private Map<String,Object> state(Map<String,Object> r){return (Map<String,Object>)r.get("followUpState");}
}
