package edu.skope.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimilarityPlaceTest {
    static Map<String,Object> report(String target,String floor,String location,String symptom){
        var r=new HashMap<String,Object>();r.put("category","OTHER");r.put("subcategory","UNKNOWN");r.put("targetText",target);
        r.put("building","북악관");r.put("floor",floor);r.put("locationText",location);r.put("symptom",symptom);r.put("originalText",symptom);r.put("createdAt","2026-10-31T09:00:00");return r;
    }
    static Map<String,Object> network(String room){var r=report("무선망","6","북악관 "+room+"호","무선망 연결이 반복 끊김");r.put("category","IT");r.put("subcategory","WIFI");return r;}
    @Test void differentRoomsCanBeNetworkReviewCandidatesOnlyWithRealComparableVectors(){
        var a=network("608");var b=network("609");var m=SimilarityPlace.compare(a,b,true);
        assertTrue(m.allowed());assertTrue(m.expanded());assertEquals("SHARED_NETWORK_SEARCH",m.scope());
        assertFalse(SimilarityPlace.compare(a,b,false).allowed(),"RULE_ONLY must not expand search by itself");
        assertEquals("북악관 608호",a.get("locationText"),"search scope does not rewrite observation");
    }
    @Test void floorDifferencesNeedExplicitBuildingRangeInBothReports(){
        var a=network("608");var b=network("708");b.put("floor","7");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
        a.put("symptom","건물 전체 무선망 접속 불가");b.put("symptom","건물 전체 무선망 연결 불가");
        assertFalse(SimilarityPlace.compare(a,b,true).allowed(),"model-only broad symptom must not enlarge the search");
        a.put("originalText",a.get("symptom"));b.put("originalText",b.get("symptom"));
        var m=SimilarityPlace.compare(a,b,true);assertTrue(m.allowed());assertEquals("STATED_BUILDING_RANGE",m.scope());
        b.put("building","유담관");assertFalse(SimilarityPlace.compare(a,b,true).allowed(),"no campus-wide outage is inferred");
    }
    @Test void sharedAreaOutageCanCrossRestroomLabelsButIndividualFixturesCannot(){
        var a=report("수도","6","북악관 6층 남자화장실","6층 전체 수도가 나오지 않음");
        var b=report("수도","6","북악관 6층 여자화장실","층 전체 급수 중단");
        assertTrue(SimilarityPlace.compare(a,b,true).allowed());assertFalse(SimilarityPlace.compare(a,b,false).allowed());
        a.put("symptom","세면대 배수 막힘");b.put("symptom","세면대 배수 막힘");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
    }
    @Test void deniedWholeAreaIsNotPermissionToWidenSearch(){
        var a=network("608");var b=network("708");b.put("floor","7");
        a.put("symptom","건물 전체 문제는 아니고 608호만 끊김");a.put("originalText",a.get("symptom"));
        b.put("symptom","건물 전체 장애는 아니고 708호만 끊김");b.put("originalText",b.get("symptom"));
        assertFalse(SimilarityPlace.compare(a,b,true).allowed());
    }
    @Test void expandedSearchHasATimeBoundaryAndDoesNotInventOccurredDates(){
        var a=network("608");var b=network("609");b.put("createdAt","2026-11-01T09:00:00");assertTrue(SimilarityPlace.compare(a,b,true).allowed());
        b.put("createdAt","2026-11-01T09:00:01");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
        b.put("createdAt",a.get("createdAt"));a.put("occurredOn","2026-10-20");b.put("occurredOn","2026-10-21");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
        b.remove("occurredOn");assertTrue(SimilarityPlace.compare(a,b,true).allowed());assertNull(b.get("occurredOn"));
        b.remove("createdAt");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
    }
    @Test void oppositePositionsAndDifferentFixtureNumbersAreNeverProvedSameByHighCosine(){
        for(var p:List.of(new String[]{"창가 에어컨 고장","출입문 에어컨 고장"},new String[]{"왼쪽 공용 사물함 고장","오른쪽 공용 사물함 고장"},
                new String[]{"동쪽 화장실 세면대 고장","서쪽 화장실 세면대 고장"},new String[]{"첫 번째 칸 변기 막힘","두 번째 칸 변기 막힘"},new String[]{"1번 컴퓨터 키보드 고장","2번 컴퓨터 키보드 고장"})){
            var a=report("설비","6","북악관 608호",p[0]);var b=report("설비","6","북악관 608호",p[1]);assertFalse(SimilarityPlace.compare(a,b,true).allowed(),Arrays.toString(p));
        }
    }
    @Test void equivalentLocalDescriptionsAndUnknownDetailRemainCompatible(){
        var a=report("변기","6","북악관 6층 화장실","첫 번째 칸 변기 막힘");var b=report("변기","6","북악관 6층 화장실","1번 칸 변기 배수 불량");assertTrue(SimilarityPlace.compare(a,b,true).allowed());
        b.put("symptom","변기 배수 불량");assertTrue(SimilarityPlace.compare(a,b,true).allowed());
        a.put("symptom","출입문 오른쪽 거울 금");b.put("symptom","입구 오른편 거울 균열");assertTrue(SimilarityPlace.compare(a,b,true).allowed());
    }
    @Test void localEquipmentStillCannotCrossRoomsAndMissingBuildingsDoNotExpand(){
        var a=report("의자","6","북악관 608호","의자 파손");var b=report("의자","6","북악관 609호","의자 파손");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
        a=network("608");b=network("609");a.remove("building");assertFalse(SimilarityPlace.compare(a,b,true).allowed());
    }
}
