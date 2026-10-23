package edu.skope.ai;

import edu.skope.repository.Db;
import edu.skope.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression probes promoted from the boundary audit. Provider facts are synthetic stubs. */

class HybridBoundaryAuditTest {
    @ParameterizedTest @ValueSource(strings={"와이파이에 접속할 수 없음","와이파이 접속이 안 됨","무선망에 접속하지 못함"})
    void explicitConnectionFailureDoesNotNeedSameQuestion(String symptom){assertEquals("COMPLETE",decide(report("WIFI",symptom,"은주2관 501호","5"),Map.of()).state().get("status"));}
    @ParameterizedTest @ValueSource(strings={"멀티탭에 플러그가 고정되지 않음","분전함 덮개가 닫히지 않음","배전반 덮개가 파손됨"})
    void namedElectricalObjectDoesNotNeedTargetQuestion(String symptom){assertEquals("COMPLETE",decide(report("ELECTRIC",symptom,"은주2관 501호","5"),Map.of()).state().get("status"));}
    @Test void vagueWifiPasswordReferenceDoesNotCountAsFailureMode(){
        assertEquals("CONTINUE",decide(report("WIFI","와이파이가 안됨; 비밀번호 문제인지 모름","은주2관 501호","5"),Map.of()).state().get("status"));
    }
    @Test void invalidFloorStringCannotMakeBuildingOnlyLocationSufficient(){
        assertEquals("CONTINUE",decide(report("LEAK","천장 물 떨어짐","은주2관","미확인"),Map.of()).state().get("status"));
    }
    @Test void groundedSubstringMustNotReverseExplicitScopeNegation(){
        var r=report("WIFI","와이파이가 끊김","은주2관",null);
        var d=ClarificationPolicy.decide("REPORT","은주2관 건물 전체가 아니라 특정 방에서만 와이파이가 끊겨요. 어느 방인지는 모르겠어요.","",null,Map.of(),r,base(),Map.of(),Map.of(),Map.of("known",Map.of("scopeEvidence","건물 전체")));
        assertEquals("CONTINUE",d.state().get("status"));
    }
    @Test void usefulBuildingCorrectionDoesNotPrematurelyEndSymptomInterview(){
        var prior=report("WIFI","와이파이가 안됨","북악관 501호","5");
        var next=report("WIFI","와이파이가 안됨","유담관",null);next.put("building","유담관");
        var old=Map.<String,Object>of("nextReason","SYMPTOM_DETAIL","askedReasons",List.of());
        var d=ClarificationPolicy.decide("REPORT","북악관 501호 와이파이가 안돼요","건물을 잘못 썼어요. 유담관이에요","SYMPTOM",prior,next,Map.of("turns",1,"noProgress",0,"reviewFields",List.of()),old,Map.of("LOCATION",Map.of("action","SET")),Map.of());
        assertEquals("CONTINUE",d.state().get("status"));
    }
    @Test void modelRecognizedDangerSurvivesReportSaving(){
        var db=mock(Db.class);var routing=mock(RoutingService.class);var incidents=mock(IncidentService.class);
        when(db.one(anyString(),any(Object[].class))).thenAnswer(call->call.getArgument(0,String.class).startsWith("SELECT * FROM reports")?new LinkedHashMap<>(Map.of("id",1L)):null);
        final Object[] savedFlag={null};
        when(db.insert(anyString(),any(Object[].class))).thenAnswer(call->{if(call.getArgument(0,String.class).startsWith("INSERT INTO reports"))savedFlag[0]=call.getArguments()[15];return 1L;});
        var body=report("UNKNOWN","배터리가 부풀어 올라 터질 것 같음","은주2관 501호","5");body.put("category","OTHER");body.put("emergencyHint",true);body.put("clientRequestId",UUID.randomUUID().toString());body.put("originalText","은주2관 501호 배터리가 부풀어 올라 터질 것 같아요");
        new ReportService(db,routing,incidents).createFromAnalysis(1,body,true);
        assertEquals(true,savedFlag[0],"Trusted intake model warning should not disappear at storage boundary");
        new ReportService(db,routing,incidents).create(1,body);
        assertEquals(false,savedFlag[0],"Public manual-save JSON cannot supply a trusted model flag");
    }
    @Test void modelRewritingCannotCreateAConnectionFailureFact(){
        var r=report("WIFI","와이파이 연결이 안됨","은주2관 501호","5");
        var d=ClarificationPolicy.decide("REPORT","은주2관 501호 와이파이가 안돼요","",null,Map.of(),r,base(),Map.of(),Map.of(),Map.of("known",Map.of("symptomEvidence","연결이 안됨")));
        assertEquals("SYMPTOM_DETAIL",d.state().get("nextReason"));
    }
    @Test void invalidFloorIsRemovedBeforeClarification(){
        var r=report("LEAK","천장 물 떨어짐","은주2관","미확인");
        assertNull(FollowUpPolicy.apply("REPORT",r,Map.of(),Map.of(),"",null,FollowUpPolicy.state(null)).report().get("floor"));
    }
    @Test void explicitOutdoorPlaceDoesNotRequireABuilding(){
        var r=report("UNKNOWN","바닥 깨져 발 걸림","교내 농구장 중앙",null);r.put("building",null);
        var merged=FollowUpPolicy.apply("REPORT",r,Map.of(),Map.of(),"",null,FollowUpPolicy.state(null));
        assertEquals(List.of(),merged.report().get("missingFields"));
        assertEquals("COMPLETE",decide(merged.report(),Map.of()).state().get("status"));
    }
    @Test void repeatedNoProgressStopsButUsefulCorrectionsRemainBounded(){
        for(int turns:List.of(1,3)){
            var r=report("WIFI","와이파이가 안됨","은주2관 501호","5");
            var d=ClarificationPolicy.decide("REPORT","은주2관 501호 와이파이가 안돼요","그냥 안돼요","SYMPTOM",r,r,Map.of("turns",turns,"noProgress",turns==1?1:0,"reviewFields",List.of()),Map.of("nextReason","SYMPTOM_DETAIL"),Map.of(),Map.of());
            assertEquals(turns==1?"MANUAL_REVIEW":"LIMIT",d.state().get("status"));assertNull(d.report().get("followUpQuestion"));
        }
    }
    @Test void locationOnlyReplyPreservesPreviouslyNamedEquipment(){
        var prior=report("ELECTRIC","멀티탭에 플러그가 고정되지 않음","은주2관",null);
        var next=report("ELECTRIC","멀티탭에 플러그가 고정되지 않음","은주2관 501호","5");
        var d=ClarificationPolicy.decide("REPORT","은주2관 멀티탭에 플러그를 꽂아도 빠져요","501호예요","LOCATION",prior,next,Map.of("turns",1,"noProgress",0,"reviewFields",List.of()),Map.of("nextReason","LOCATION_DETAIL"),Map.of("LOCATION",Map.of("action","SET")),Map.of());
        assertEquals("COMPLETE",d.state().get("status"));
    }
    @Test void lateFailureEvidenceInLongInputIsKeptAcrossLocationReply(){
        String original="상황을 설명하겠습니다. ".repeat(25)+"은주2관에서 와이파이에 접속할 수 없어요";
        var first=report("WIFI","와이파이 접속 불가","은주2관",null);
        var d=ClarificationPolicy.decide("REPORT",original,"",null,Map.of(),first,base(),Map.of(),Map.of(),Map.of());
        assertFalse(((List<?>)d.state().get("neededReasons")).contains("SYMPTOM_DETAIL"));
        var next=report("WIFI","와이파이 접속 불가","은주2관 501호","5");
        var done=ClarificationPolicy.decide("REPORT",original,"501호예요","LOCATION",first,next,Map.of("turns",1,"noProgress",0,"reviewFields",List.of()),d.state(),Map.of("LOCATION",Map.of("action","SET")),Map.of());
        assertEquals("COMPLETE",done.state().get("status"));
    }
    private Map<String,Object> report(String type,String symptom,String location,String floor){
        var r=new LinkedHashMap<String,Object>();r.put("category",type.equals("WIFI")?"IT":"FACILITY");r.put("subcategory",type);r.put("symptom",symptom);r.put("building","은주2관");r.put("locationText",location);r.put("floor",floor);r.put("missingFields",List.of());return r;
    }
    private Map<String,Object> base(){return Map.of("turns",0,"noProgress",0,"reviewFields",List.of());}
    private ClarificationPolicy.Decision decide(Map<String,Object> r,Map<String,Object> suggestion){return ClarificationPolicy.decide("REPORT",r.get("locationText")+" "+r.get("symptom"),"",null,Map.of(),r,base(),Map.of(),Map.of(),suggestion);}
}
