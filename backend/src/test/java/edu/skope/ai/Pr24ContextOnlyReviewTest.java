package edu.skope.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Pr24ContextOnlyReviewTest {
    private static Map<String,Object> report(String place){
        var r=new LinkedHashMap<String,Object>();
        r.put("category","FACILITY");r.put("subcategory","WATER_DISPENSER");r.put("workGroup","SHARED_EQUIPMENT");
        r.put("targetText","정수기");r.put("campusRelevance","CAMPUS_FACILITY");r.put("building","유담관");
        r.put("locationText",place);r.put("floor","8");r.put("symptom","정수기가 고장났어요");
        r.put("emergencyHint",false);r.put("missingFields",List.of());return r;
    }
    private static Map<String,Object> reply(String answer,String extractedPlace){
        var updates=Map.<String,Object>of("LOCATION",Map.of("action","SET","evidence",answer));
        return FollowUpPolicy.apply("REPORT",report(extractedPlace),report("유담관 8층 도서관"),updates,
                answer,"LOCATION",FollowUpPolicy.state(null)).report();
    }
    @Test void facilityOnlyCorrectionDoesNotRequireRepeatingKnownBuildingAndFloor(){
        var result=reply("도서관이 아니라 강의실이에요","유담관 8층 강의실");
        String place=Objects.toString(result.get("locationText"));
        assertFalse(place.contains("도서관"),"Denied old facility remains: "+place);
        assertTrue(place.contains("강의실"),"New explicit facility missing: "+place);
        assertEquals("유담관",result.get("building"));assertEquals("8",result.get("floor"));
    }
    @Test void facilityDenialDoesNotRequireRepeatingKnownBuildingAndFloor(){
        var result=reply("도서관은 아니에요","유담관 8층");
        String place=Objects.toString(result.get("locationText"));
        assertFalse(place.contains("도서관"),"Denied facility remains: "+place);
        assertEquals("유담관",result.get("building"));assertEquals("8",result.get("floor"));
    }
}
