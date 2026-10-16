package edu.skope.service;

import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntakeServiceTest {
    @Test void urgentIncompleteIntakeSavesForReviewWithoutAnotherQuestion(){
        var ai=mock(AiService.class);var reports=mock(ReportService.class);var similarity=mock(SimilarityService.class);var db=mock(Db.class);
        when(db.one(anyString(),anyLong(),anyString())).thenReturn(null);when(ai.mode()).thenReturn("LIVE");
        when(ai.analyze(anyMap())).thenReturn(Map.of("intent","REPORT","report",Map.of("category","FACILITY","subcategory","ELECTRIC","symptom","타는 냄새","missingFields",List.of("LOCATION")),"followUpState",Map.of("status","COMPLETE","urgentReview",true,"neededReasons",List.of("LOCATION_REQUIRED"))));
        when(reports.createFromAnalysis(eq(1L),anyMap(),anyBoolean())).thenAnswer(call->{var stored=new LinkedHashMap<String,Object>(call.getArgument(1));stored.put("id",13L);return stored;});
        var result=new IntakeService(ai,reports,similarity,db).intake(1,new MockHttpSession(),Map.of("clientRequestId",UUID.randomUUID().toString(),"text","어딘가에서 타는 냄새가 나요"));
        assertEquals("ACCEPTED",result.get("status"));assertTrue(((Map<?,?>)result.get("report")).get("reviewNote").toString().contains("발생 장소"));
        verify(ai).analyze(argThat(request->Boolean.TRUE.equals(request.get("hybrid"))));
    }
    @Test void committedIntakeSurvivesIndexingFailureAndRetryDoesNotCallAi(){
        var ai=mock(AiService.class);var reports=mock(ReportService.class);var similarity=mock(SimilarityService.class);var db=mock(Db.class);
        when(db.one(anyString(),anyLong(),anyString())).thenReturn(null).thenReturn(Map.of("id",12L));
        when(ai.mode()).thenReturn("LIVE");
        when(ai.analyze(anyMap())).thenReturn(Map.of("intent","REPORT","report",Map.of("category","IT","subcategory","WIFI","symptom","끊김","building","북악관","locationText","북악관 502호"),"followUpState",Map.of("status","COMPLETE")));
        var stored=Map.<String,Object>of("id",12L,"originalText","북악관 502호 와이파이 끊김");
        when(reports.createFromAnalysis(eq(1L),anyMap(),anyBoolean())).thenReturn(stored);when(reports.detail(12,1,false)).thenReturn(stored);
        doThrow(new ApiException(503,"AI_UNAVAILABLE","embedding failure")).when(similarity).index(12);
        var service=new IntakeService(ai,reports,similarity,db);var session=new MockHttpSession();String key=UUID.randomUUID().toString();
        var body=Map.<String,Object>of("clientRequestId",key,"text","북악관 502호 와이파이 끊김");
        assertEquals("ACCEPTED",service.intake(1,session,body).get("status"));
        assertEquals(stored,service.intake(1,session,body).get("report"));
        verify(ai,times(1)).analyze(anyMap());verify(reports,times(1)).createFromAnalysis(eq(1L),anyMap(),anyBoolean());verify(similarity,times(1)).index(12);
    }
    @Test void providerFailureIsNotRetriedAndReviewPreservesOriginalWithoutInventedTime(){
        var ai=mock(AiService.class);var reports=mock(ReportService.class);var similarity=mock(SimilarityService.class);var db=mock(Db.class);
        when(db.one(anyString(),anyLong(),anyString())).thenReturn(null);
        when(ai.mode()).thenReturn("LIVE");when(ai.analyze(anyMap())).thenThrow(new ApiException(503,"AI_UNAVAILABLE","timeout"));
        when(reports.createFromAnalysis(eq(1L),anyMap(),anyBoolean())).thenAnswer(call->{Map<String,Object> saved=new LinkedHashMap<>(call.getArgument(1));saved.put("id",9L);return saved;});
        var service=new IntakeService(ai,reports,similarity,db);var session=new MockHttpSession();String key=UUID.randomUUID().toString();
        var initial=Map.<String,Object>of("clientRequestId",key,"text","북악관 502호의 와이파이가 끊겨요");
        var result=service.intake(1,session,initial);assertEquals("REVIEW_REQUIRED",result.get("status"));assertEquals("LIVE",result.get("aiMode"));
        service.intake(1,session,initial);service.intake(1,session,Map.of("clientRequestId",key,"resume",true));
        verify(ai,times(1)).analyze(anyMap());verifyNoInteractions(reports);
        var saved=service.intake(1,session,Map.of("clientRequestId",key,"revision",1,"submitForReview",true));
        Map<?,?> report=(Map<?,?>)saved.get("report");assertEquals(initial.get("text"),report.get("originalText"));
        assertTrue(report.get("reviewNote").toString().contains("AI 분석 실패"));assertNull(report.get("occurredAtText"));
        verify(ai,times(1)).analyze(anyMap());
    }
    @Test void failedFollowupRetainsPreviouslyKnownFactsAndUnanalyzedAnswer(){
        var ai=mock(AiService.class);var reports=mock(ReportService.class);var similarity=mock(SimilarityService.class);var db=mock(Db.class);
        when(db.one(anyString(),anyLong(),anyString())).thenReturn(null);
        var extracted=new LinkedHashMap<String,Object>(Map.of("category","IT","subcategory","WIFI","symptom","와이파이가 끊겨요","missingFields",List.of("LOCATION")));
        var state=Map.of("status","CONTINUE","nextField","LOCATION","turns",0,"noProgress",0,"askedFields",List.of(),"reviewFields",List.of());
        when(ai.mode()).thenReturn("LIVE");when(ai.analyze(anyMap())).thenReturn(Map.of("intent","REPORT","report",extracted,"followUpState",state)).thenThrow(new ApiException(503,"AI_UNAVAILABLE","timeout"));
        when(reports.createFromAnalysis(eq(1L),anyMap(),anyBoolean())).thenAnswer(call->{Map<String,Object> saved=new LinkedHashMap<>(call.getArgument(1));saved.put("id",10L);return saved;});
        var service=new IntakeService(ai,reports,similarity,db);var session=new MockHttpSession();String key=UUID.randomUUID().toString();
        service.intake(1,session,Map.of("clientRequestId",key,"text","와이파이가 끊겨요"));
        service.intake(1,session,Map.of("clientRequestId",key,"revision",1,"answer","북악관 502호에서 어제부터요"));
        var saved=service.intake(1,session,Map.of("clientRequestId",key,"revision",2,"submitForReview",true));
        Map<?,?> report=(Map<?,?>)saved.get("report");assertEquals("와이파이가 끊겨요",report.get("symptom"));
        assertTrue(report.get("reviewNote").toString().contains("북악관 502호에서 어제부터요"));
        verify(ai,times(2)).analyze(anyMap());
    }
}
