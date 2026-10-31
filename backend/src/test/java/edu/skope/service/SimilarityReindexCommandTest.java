package edu.skope.service;

import edu.skope.ai.AiService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class SimilarityReindexCommandTest {
    final IncidentTestFixture f=new IncidentTestFixture();final AiService ai=mock(AiService.class);
    final ConfigurableApplicationContext context=mock(ConfigurableApplicationContext.class);
    SimilarityReindexCommand command(String ids,int max,String address,int port){return new SimilarityReindexCommand(f.db,ai,new SimilarityService(f.db,ai,.82,30),context,ids,max,address,port);}
    void live(){when(ai.embeddingMode()).thenReturn("LIVE");when(ai.embeddingModel()).thenReturn("new-model");}
    @Test void boundedTwoPhaseReindexReusesVectorsAndPreservesAdministratorDecision(){
        live();long a=f.wifi(),b=f.wifi();long pair=f.pair(a,b);f.db.update("UPDATE similar_pairs SET decision='REJECTED',reviewed_by=? WHERE id=?",f.admin,pair);
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1,0},"new-model"));
        var command=command(a+","+b,2,"127.0.0.1",0);command.run(new DefaultApplicationArguments());verify(ai,times(2)).embed(anyString());
        command.run(new DefaultApplicationArguments());verify(ai,times(2)).embed(anyString());
        assertEquals("REJECTED",f.db.one("SELECT decision FROM similar_pairs WHERE id=?",pair).get("decision"));assertNull(f.report(a).get("incidentId"));assertEquals("RECEIVED",f.report(a).get("status"));
        command.finish();verify(context).close();
    }
    @Test void reindexRetiresCandidatesThatTheCurrentPlacePolicyExcludesButKeepsDecisions(){
        live();long a=f.wifi(),b=f.wifi(),c=f.wifi();
        f.db.update("UPDATE reports SET symptom='창가 자리 연결 끊김' WHERE id IN (?,?)",a,c);
        f.db.update("UPDATE reports SET symptom='출입문 자리 연결 끊김' WHERE id=?",b);
        long stale=f.pair(a,b),reviewed=f.pair(b,c);f.db.update("UPDATE similar_pairs SET decision='SAME_INCIDENT',reviewed_by=? WHERE id=?",f.admin,reviewed);
        when(ai.embed(anyString())).thenReturn(new AiService.Embedding(new double[]{1,0},"new-model"));
        command(a+","+b+","+c,3,"127.0.0.1",0).run(new DefaultApplicationArguments());
        assertNull(f.db.one("SELECT id FROM similar_pairs WHERE id=?",stale),"old candidate outside the current place policy is removed");
        assertEquals("SAME_INCIDENT",f.db.one("SELECT decision FROM similar_pairs WHERE id=?",reviewed).get("decision"));
        assertNotNull(f.db.one("SELECT id FROM similar_pairs WHERE left_report_id=? AND right_report_id=?",Math.min(a,c),Math.max(a,c)));
    }
    @Test void invalidOrOversizedSelectionStopsBeforeAnyPaidCall(){
        live();long a=f.wifi(),b=f.wifi();
        assertThrows(IllegalArgumentException.class,()->command(a+","+b,1,"127.0.0.1",0).run(new DefaultApplicationArguments()));
        assertThrows(IllegalArgumentException.class,()->command(a+","+a,2,"127.0.0.1",0).run(new DefaultApplicationArguments()));
        assertThrows(IllegalArgumentException.class,()->command("",2,"127.0.0.1",0).run(new DefaultApplicationArguments()));
        verify(ai,never()).embed(anyString());
    }
    @Test void publicPortAndRuleOnlyModeCannotStartReindex(){
        live();long a=f.wifi();assertThrows(IllegalStateException.class,()->command(""+a,1,"0.0.0.0",0).run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class,()->command(""+a,1,"127.0.0.1",8080).run(new DefaultApplicationArguments()));
        when(ai.embeddingMode()).thenReturn("RULE_ONLY");assertThrows(IllegalStateException.class,()->command(""+a,1,"127.0.0.1",0).run(new DefaultApplicationArguments()));verify(ai,never()).embed(anyString());
    }
    @Test void excludedReportsStopBeforeProviderCalls(){
        live();long a=f.wifi();f.db.update("UPDATE reports SET review_note='확인 필요' WHERE id=?",a);
        assertThrows(IllegalArgumentException.class,()->command(""+a,1,"127.0.0.1",0).run(new DefaultApplicationArguments()));
        verify(ai,never()).embed(anyString());
    }
    @Test void failedRefreshPreservesStoredVectorsAndStopsBeforeCandidateRebuild(){
        live();long a=f.wifi(),b=f.wifi();f.db.update("UPDATE reports SET embedding_model='old-model',embedding_json='[1,0]' WHERE id=?",a);
        when(ai.embed(anyString())).thenThrow(new IllegalStateException("controlled failure"));
        assertThrows(IllegalStateException.class,()->command(a+","+b,2,"127.0.0.1",0).run(new DefaultApplicationArguments()));
        verify(ai,times(1)).embed(anyString());assertEquals("old-model",f.report(a).get("embeddingModel"));assertEquals(0,DbCount.pairs(f));
    }
    @Test void realMaintenanceApplicationExitsAndUsesTheExistingBudgetWithoutPublicRequests()throws Exception{
        var provider=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        provider.createContext("/v1/embeddings",exchange->{calls.incrementAndGet();byte[] bytes="{\"model\":\"reindex-fake-model\",\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});provider.start();
        String url="jdbc:h2:mem:reindex_cli_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String[] common={"--spring.datasource.url="+url,"--spring.datasource.username=sa","--spring.datasource.password=","--spring.profiles.active=demo",
                "--server.address=127.0.0.1","--server.port=0","--DEMO_PASSWORD=","--app.routing.demo-enabled=false","--AI_MODE=DEMO","--EMBEDDING_MODE=LIVE",
                "--AI_API_KEY=fake-key-no-provider","--AI_BASE_URL=http://127.0.0.1:"+provider.getAddress().getPort()+"/v1","--EMBEDDING_MODEL=reindex-fake-model",
                "--AI_BUDGET_MAX_LLM_CALLS=0","--AI_BUDGET_MAX_EMBEDDING_CALLS=2","--AI_BUDGET_MAX_USD=0.01",
                "--app.attachments.dir=build/reindex-command-test-"+java.util.UUID.randomUUID(),"--logging.level.root=WARN"};
        ConfigurableApplicationContext initial=null,maintenance=null;
        try{
            initial=new org.springframework.boot.builder.SpringApplicationBuilder(edu.skope.SkopeApplication.class).run(common);
            var db=initial.getBean(edu.skope.repository.Db.class);long user=db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES('cli-synthetic','unused','합성 검증','STUDENT')");
            long a=db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,location_text,symptom) VALUES(?,?,'합성 와이파이 연결 장애','IT','WIFI','북악관','북악관 608호','와이파이 연결 끊김')",user,java.util.UUID.randomUUID().toString());
            long b=db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,location_text,symptom) VALUES(?,?,'합성 무선망 연결 장애','IT','WIFI','북악관','북악관 608호','무선망 연결 끊김')",user,java.util.UUID.randomUUID().toString());
            initial.close();initial=null;var args=new java.util.ArrayList<>(java.util.List.of(common));args.add("--skope.similarity.reindex.enabled=true");args.add("--skope.similarity.reindex.ids="+a+","+b);args.add("--skope.similarity.reindex.max-reports=2");
            maintenance=new org.springframework.boot.builder.SpringApplicationBuilder(edu.skope.SkopeApplication.class).run(args.toArray(String[]::new));
            assertFalse(maintenance.isActive(),"one-shot command must exit after all startup runners");assertEquals(2,calls.get());
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"sa",""));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE embedding_model='reindex-fake-model'",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT embedding_calls FROM ai_usage_budget WHERE period_key='TOTAL'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT llm_calls FROM ai_usage_budget WHERE period_key='TOTAL'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM similar_pairs",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM incidents",Integer.class));
        }finally{if(initial!=null)initial.close();if(maintenance!=null)maintenance.close();provider.stop(0);}
    }
    static class DbCount {static long pairs(IncidentTestFixture f){return ((Number)f.db.one("SELECT COUNT(*) n FROM similar_pairs").get("n")).longValue();}}
}
