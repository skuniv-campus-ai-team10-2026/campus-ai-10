package edu.skope.service;

import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit operator maintenance, never a public endpoint or an automatic paid startup job. */
@Component
@ConditionalOnProperty(name="skope.similarity.reindex.enabled",havingValue="true")
final class SimilarityReindexCommand implements ApplicationRunner {
    private final Db db;private final AiService ai;private final SimilarityService similarity;
    private final ConfigurableApplicationContext context;private final String ids,address;private final int maximum,port;
    SimilarityReindexCommand(Db db,AiService ai,SimilarityService similarity,ConfigurableApplicationContext context,
            @Value("${skope.similarity.reindex.ids:}")String ids,@Value("${skope.similarity.reindex.max-reports:20}")int maximum,
            @Value("${server.address:}")String address,@Value("${server.port:8080}")int port){
        this.db=db;this.ai=ai;this.similarity=similarity;this.context=context;this.ids=ids;this.maximum=maximum;this.address=address;this.port=port;
    }
    @Override public void run(ApplicationArguments args){
        if(port!=0||!Set.of("127.0.0.1","::1").contains(address))throw new IllegalStateException("Reindex must run once on a loopback ephemeral port");
        if(!"LIVE".equals(ai.embeddingMode()))throw new IllegalStateException("Reindex requires explicitly configured LIVE embeddings");
        if(maximum<1||maximum>100||ids.isBlank())throw new IllegalArgumentException("Explicit report IDs and a 1..100 report cap are required");
        var selected=new LinkedHashSet<Long>();
        for(String item:ids.split(",",-1)){
            String value=item.strip();if(!value.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Invalid report ID");
            if(!selected.add(Long.parseLong(value)))throw new IllegalArgumentException("Duplicate report ID");
        }
        if(selected.size()>maximum)throw new IllegalArgumentException("Reindex report cap exceeded before provider calls");
        for(long id:selected){var r=db.one("SELECT id,request_kind,review_note FROM reports WHERE id=?",id);
            if(r==null||"QUESTION".equals(r.get("requestKind"))||r.get("reviewNote")!=null)throw new IllegalArgumentException("Report not eligible for similarity reindex: "+id);
        }
        // The existing AiService budget accounts for every real call. No retry and no usage-ledger reset.
        for(long id:selected)if(!similarity.refreshEmbeddingOnly(id))throw new IllegalStateException("Embedding refresh failed; stopped without retry at report "+id);
        for(long id:selected)similarity.index(id); // all selected vectors are now reusable; no second provider call
        System.out.println("SIMILARITY_REINDEX_COMPLETE reports="+selected.size()+" model="+ai.embeddingModel()+" automaticRetries=0");
    }
    @EventListener(ApplicationReadyEvent.class) public void finish(){context.close();}
}
