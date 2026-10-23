package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Stored real responses exercise current post-processing, with zero provider calls. */
class LanguageVariationReplayTest {
    @Test void replayAllOriginalResponses() throws Exception {
        var json=new ObjectMapper();
        Path source=Path.of("../docs/evaluation/language-variation-v1-results.json");
        var original=json.readTree(source.toFile());
        assertEquals(12,original.path("rows").size());
        var rows=new ArrayList<Map<String,Object>>();
        for(JsonNode row:original.path("rows")) {
            AiService service=new AiService("LIVE","http://127.0.0.1:1","offline-only","gpt-6-luna","RULE_ONLY","",1) {
                @Override protected JsonNode complete(Map<String,Object> body){return row.path("payload");}
            };
            String id=row.path("id").asText();
            var actual=service.analyze(Map.of("text",row.path("input").path("text").asText(),"hybrid",true));
            var state=(Map<?,?>)actual.get("followUpState");
            // Old P4 contains a model omission: replay cannot demonstrate a new prompt's quality.
            String expected=id.startsWith("P6")||id.equals("P4-variant")?"CONTINUE":"COMPLETE";
            assertEquals(expected,state.get("status"),id);
            if(id.equals("P4-variant")||id.startsWith("P6"))assertEquals("ISSUE_UNCLEAR",state.get("nextReason"),id);
            if(id.startsWith("P5"))assertEquals(true,((Map<?,?>)actual.get("report")).get("emergencyHint"),id);
            rows.add(Map.of("id",id,"actual",actual,"source","CACHED_REAL_RESPONSE_NO_NEW_INFERENCE",
                "semanticStatus",id.equals("P4-variant")?"KNOWN_MODEL_FAILURE_REQUIRES_NEW_PROMPT_LIVE":"PASS"));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("../docs/evaluation/language-variation-v2-replay.json").toFile(),
            Map.of("promptVersion",AiService.PROMPT_VERSION,"policyVersion",AiService.POLICY_VERSION,
                "sourceSha256",AiQualityCache.digest(Files.readAllBytes(source)),"calls",0,"rows",rows));
    }
}
