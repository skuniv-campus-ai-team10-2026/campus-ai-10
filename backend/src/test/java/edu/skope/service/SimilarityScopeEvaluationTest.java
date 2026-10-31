package edu.skope.service;

import com.fasterxml.jackson.databind.*;
import edu.skope.ai.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in replay of frozen synthetic labels and actual saved embeddings. Never contacts a provider. */
@EnabledIfEnvironmentVariable(named="SKOPE_SIMILARITY_SCOPE_EVAL", matches="1")
class SimilarityScopeEvaluationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String OLD_HASH="5a04482391b08283f457abd97166b1743cb173235f8dfd27d850453c68b76577";
    static final String NEW_HASH="abfee64728799010b8663438f29e975f927fd163da61056e90d1bbac208900d3";
    static final String HOLDOUT_HASH="a8040c1858d1e6080417ca302466bc9f02918138615d571649c8a124a0558e51";
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static JsonNode frozen(String file,String expected)throws Exception{
        byte[] b=Files.readAllBytes(Path.of("..","docs","evaluation",file));assertEquals(expected,hash(b));return JSON.readTree(b);
    }
    record Variant(String name,String model,String revision){}
    static final List<Variant> VARIANTS=List.of(new Variant("small-current","text-embedding-3-small","embedding-input-v1"),
            new Variant("small-semantic","text-embedding-3-small","issue-semantic-v2"),
            new Variant("small-evidence","text-embedding-3-small","issue-evidence-v2"),
            new Variant("large-current","text-embedding-3-large","embedding-input-v1"));
    static class SavedAi extends AiService {
        final Map<String,Embedding> saved;int calls;
        SavedAi(String model,Map<String,Embedding> saved){super("LIVE","http://127.0.0.1:1","no-provider-call","unused","LIVE",model,1);this.saved=saved;}
        @Override public Embedding embed(String input){calls++;var e=saved.get(input);if(e==null)throw new AssertionError("Missing saved vector, paid fallback forbidden");return e;}
        @Override protected JsonNode complete(Map<String,Object> request){throw new AssertionError("LLM calls forbidden");}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> report(JsonNode fixture,String id){for(var r:fixture.path("reports"))if(id.equals(r.path("id").asText()))return JSON.convertValue(r,LinkedHashMap.class);throw new IllegalArgumentException(id);}
    static String input(Map<String,Object> r){return r.get("category")+"/"+r.get("subcategory")+" | "+r.get("locationText")+" | "+r.get("symptom")+" | "+r.get("originalText");}
    static Map<String,AiService.Embedding> vectors(JsonNode fixture,String set,Variant v,JsonNode manifest,Path cache)throws Exception{
        var result=new HashMap<String,AiService.Embedding>();
        for(var r:fixture.path("reports")){
            JsonNode row=null;for(var m:manifest.path("rows"))if(set.equals(m.path("set").asText())&&r.path("id").asText().equals(m.path("reportId").asText())&&v.revision.equals(m.path("version").asText()))row=m;
            assertNotNull(row);String text=row.path("input").asText();String key=hash(JSON.writeValueAsBytes(List.of("embedding",v.model,v.revision,text)));JsonNode entry=null;
            try(var dirs=Files.list(cache)){for(var dir:dirs.filter(Files::isDirectory).toList()){Path file=dir.resolve(key+".json");if(Files.exists(file)){entry=JSON.readTree(file.toFile());break;}}}
            assertNotNull(entry,"Cached real response required; missing is not a quality PASS");assertEquals("SUCCESS",entry.path("status").asText());assertEquals(v.model,entry.at("/payload/model").asText());
            double[] vector=JSON.convertValue(entry.at("/payload/vector"),double[].class);assertTrue(SimilarityService.validVector(vector));
            result.put(input(JSON.convertValue(r,LinkedHashMap.class)),new AiService.Embedding(vector,v.model));
        }return result;
    }
    static long insert(IncidentTestFixture f,Map<String,Object> r){
        long id=f.report(r.get("category").toString(),r.get("subcategory").toString(),(String)r.get("building"),(String)r.get("floor"),(String)r.get("locationText"),null);
        f.db.update("UPDATE reports SET original_text=?,symptom=?,work_group=?,target_text=?,campus_relevance=?,created_at=? WHERE id=?",r.get("originalText"),r.get("symptom"),r.get("workGroup"),r.get("targetText"),r.get("campusRelevance"),Timestamp.valueOf(LocalDateTime.parse(r.get("createdAt").toString())),id);return id;
    }
    static boolean beforeScopePolicy(Map<String,Object>a,Map<String,Object>b,Map<String,Object>d){
        String fa=SimilarityService.normalized(a.get("floor")).replace("층",""),fb=SimilarityService.normalized(b.get("floor")).replace("층","");
        String ra=SimilarityService.room(a),rb=SimilarityService.room(b);var score=(Double)d.get("rawCosine");
        boolean semantic=score==null||score>=.82||score>=.70&&SimilarityService.explicitConnectivityFailure(a)&&SimilarityService.explicitConnectivityFailure(b);
        return Boolean.TRUE.equals(d.get("typeMatch"))&&Boolean.TRUE.equals(d.get("buildingMatch"))&&(fa.isBlank()||fb.isBlank()||fa.equals(fb))
                &&(ra.isBlank()||rb.isBlank()||ra.equals(rb))&&Boolean.TRUE.equals(d.get("timeMatch"))&&SimilarityService.broadScope(a,b,score)&&semantic;
    }
    @Test void compareFrozenPairsUsingActualIndexAndCachedVectors()throws Exception{
        Path manifestPath=Path.of(Objects.requireNonNull(System.getenv("SKOPE_SCOPE_MANIFEST")));var manifest=JSON.readTree(manifestPath.toFile());Path cache=Path.of(Objects.requireNonNull(System.getenv("SKOPE_SCOPE_CACHE")));
        Path output=Path.of(Objects.requireNonNull(System.getenv("SKOPE_SCOPE_OUTPUT")));Files.createDirectories(output);Path file=output.resolve("comparison.json");assertFalse(Files.exists(file),"Preserve prior results; select another output directory");
        var summaries=new ArrayList<Map<String,Object>>();
        for(var v:VARIANTS)for(String set:List.of("DEV","NEW","HOLDOUT")){
            if(set.equals("HOLDOUT")&&!v.revision.equals("embedding-input-v1"))continue;
            var fixture=switch(set){case "DEV"->frozen("similarity-review-v2.json",OLD_HASH);case "NEW"->frozen("similarity-scope-v1.json",NEW_HASH);default->frozen("similarity-scope-holdout-v1.json",HOLDOUT_HASH);};
            if(set.equals("HOLDOUT"))for(var names=fixture.path("productionSourceHashes").fields();names.hasNext();){var item=names.next();assertEquals(item.getValue().asText(),hash(Files.readAllBytes(Path.of("src/main/java/edu/skope/service",item.getKey()))),"Implementation changed after holdout freeze");}
            var saved=vectors(fixture,set,v,manifest,cache);var rows=new ArrayList<Map<String,Object>>();int tp=0,fp=0,tn=0,fn=0;
            int btp=0,bfp=0,btn=0,bfn=0;
            for(var pair:fixture.path("pairs")){
                var f=new IncidentTestFixture();
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V10__work_group_target.sql"))
                        .execute(Objects.requireNonNull(f.db.jdbc.getDataSource()));
                var ai=new SavedAi(v.model,saved);var service=new SimilarityService(f.db,ai,.82,30);
                long left=insert(f,report(fixture,pair.path("leftReportId").asText())),right=insert(f,report(fixture,pair.path("rightReportId").asText()));service.index(left);service.index(right);
                boolean actual=!((List<?>)service.similar(left).get("items")).isEmpty(),expected=pair.path("expectedCandidate").asBoolean();
                if(actual&&expected)tp++;else if(actual)fp++;else if(expected)fn++;else tn++;
                assertNotNull(f.report(left).get("embeddingJson"));assertNotNull(f.report(right).get("embeddingJson"));assertNull(f.report(left).get("incidentId"));assertNull(f.report(right).get("incidentId"));assertEquals(2,ai.calls);
                var diagnostic=SimilarityService.diagnose(f.report(left),f.report(right),.82,30);boolean before=beforeScopePolicy(f.report(left),f.report(right),diagnostic);
                if(before&&expected)btp++;else if(before)bfp++;else if(expected)bfn++;else btn++;
                rows.add(Map.of("id",pair.path("id").asText(),"expected",expected,"actual",actual,"baselineActual",before,"status",expected==actual?"PASS":"FAIL","diagnostic",diagnostic));
            }
            var metrics=Map.of("tp",tp,"fp",fp,"tn",tn,"fn",fn,"precision",tp/(double)Math.max(1,tp+fp),"recall",tp/(double)Math.max(1,tp+fn));
            var beforeMetrics=Map.of("tp",btp,"fp",bfp,"tn",btn,"fn",bfn,"precision",btp/(double)Math.max(1,btp+bfp),"recall",btp/(double)Math.max(1,btp+bfn));
            summaries.add(Map.of("variant",v.name,"set",set,"metrics",metrics,"baselineCounterfactual",beforeMetrics,"rows",rows));System.out.println(v.name+" "+set+" "+metrics);
        }
        var result=Map.of("threshold",.82,"newPaidCalls",0,"newLlmCalls",0,"scope","Saved actual embeddings replayed through production index + isolated H2. AI-authored fixed labels; DEV is development regression. Not independently human-reviewed general accuracy.","policySourceSha256",hash(Files.readAllBytes(Path.of("src/main/java/edu/skope/service/SimilarityService.java"))),"comparisons",summaries);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),result);
    }
}
