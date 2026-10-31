package edu.skope.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Pr24ReviewProbeTest {
    private static Map<String,Object> report(String building,String place,String floor){
        var r=new LinkedHashMap<String,Object>();
        r.put("category","FACILITY");r.put("subcategory","WATER_DISPENSER");r.put("workGroup","SHARED_EQUIPMENT");
        r.put("targetText","정수기");r.put("campusRelevance","CAMPUS_FACILITY");r.put("building",building);
        r.put("locationText",place);r.put("floor",floor);r.put("symptom","정수기가 고장났어요");
        r.put("emergencyHint",false);r.put("missingFields",List.of());return r;
    }
    @Test void explicitFacilityCorrectionMustNotRestoreThePreviousFacility(){
        var previous=report("유담관","유담관 8층 도서관","8");
        var extracted=report("유담관","유담관 8층 강의실","8");
        String answer="장소는 도서관이 아니라 유담관 8층 강의실이에요";
        var updates=Map.<String,Object>of("LOCATION",Map.of("action","SET","evidence",answer));
        var result=FollowUpPolicy.apply("REPORT",extracted,previous,updates,answer,"LOCATION",FollowUpPolicy.state(null));
        String place=Objects.toString(result.report().get("locationText"));
        assertFalse(place.contains("도서관"),"Explicitly corrected facility was retained: "+place);
        assertTrue(place.contains("강의실"),"New explicit facility was lost: "+place);
    }
    @Test void unchangedFloorOnlyReplyMustStillKeepTheFacility(){
        var previous=report("유담관","도서관",null);
        var extracted=report("유담관","유담관 8층","8");
        var updates=Map.<String,Object>of("LOCATION",Map.of("action","SET","evidence","8층"));
        var result=FollowUpPolicy.apply("REPORT",extracted,previous,updates,"8층이에요","LOCATION",FollowUpPolicy.state(null));
        assertTrue(Objects.toString(result.report().get("locationText")).contains("도서관"));
    }
    @Test void aModelQuestionMustActuallyAskForTheSelectedMissingField(){
        var r=report("도서관","도서관",null);
        var suggestion=new LinkedHashMap<String,Object>();
        suggestion.put("reason","LOCATION_DETAIL");suggestion.put("evidence","도서관");
        suggestion.put("question","정수기는 어떻게 고장났나요?");suggestion.put("known",Map.of());
        var d=ClarificationPolicy.decide("REPORT","도서관 정수기가 고장났어요","",null,Map.of(),r,
                FollowUpPolicy.state(null),Map.of(),Map.of(),suggestion);
        assertEquals("LOCATION_DETAIL",d.state().get("nextReason"));
        String q=Objects.toString(d.report().get("followUpQuestion"));
        assertTrue(q.contains("층")||q.contains("공간")||q.contains("어디"),"Location selected, but diagnosis asked: "+q);
    }
    @Test void storedWifiWordingHasEnoughSymptomInformationWithoutAnotherQuestion(){
        var r=report("북악관","북악관 503호 강의실","5");
        r.put("category","IT");r.put("subcategory","WIFI");r.put("workGroup","IT_SUPPORT");r.put("targetText","와이파이");
        r.put("campusRelevance","SCHOOL_SERVICE");r.put("symptom","학교 와이파이에 연결해도 인터넷 페이지가 열리지 않음");
        String text="북악관 503호 강의실에서 학교 와이파이에 연결해도 인터넷 페이지가 열리지 않아요.";
        var suggestion=Map.<String,Object>of("reason","NONE","known",Map.of("locationEvidence","북악관 503호 강의실","symptomEvidence","인터넷 페이지가 열리지 않아요"));
        var d=ClarificationPolicy.decide("REPORT",text,"",null,Map.of(),r,FollowUpPolicy.state(null),Map.of(),Map.of(),suggestion);
        assertEquals("COMPLETE",d.state().get("status"),"Still requests: "+d.report().get("followUpQuestion"));
    }
}
