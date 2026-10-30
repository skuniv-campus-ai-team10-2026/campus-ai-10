package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;

/**
 * Offline model stub for server-defense tests. The first turn replays the saved LIVE output for W1-1
 * ("학교 공용 화장실 미러가 깨졌어요"); each follow-up answer returns a scripted, possibly wrong, model output
 * built from the saved W1-2 shape. No provider network, no embeddings, no paid calls.
 */
public class ScriptedAi extends AiService {
    static final ObjectMapper JSON = new ObjectMapper();
    final JsonNode saved;
    final Map<String, JsonNode> answers = new HashMap<>();
    final Map<String, JsonNode> firsts = new HashMap<>();
    public int calls;

    public ScriptedAi() {
        super("LIVE", "http://127.0.0.1:1", "offline-scripted-only", "gpt-6-luna", "RULE_ONLY", "", 1, 1536, "none");
        try { saved = JSON.readTree(Path.of("..", "docs", "evaluation", "work-group-v1-results.json").toFile()); }
        catch (Exception e) { throw new IllegalStateException("Saved development output unavailable", e); }
    }
    JsonNode row(String id) {
        for (var row : saved.path("rows")) if (id.equals(row.path("id").asText())) return row;
        throw new IllegalStateException("No saved case " + id);
    }
    public String firstText() { return row("W1-1").at("/input/text").asText(); }

    /**
     * Scripts the model output for one follow-up answer. report: fields the model claims; updates: group -> {action, evidence}.
     * Groups not given are KEEP, so only the scripted claim can change anything.
     */
    public ScriptedAi on(String answer, Map<String, Object> report, Map<String, String[]> updates) {
        var raw = (ObjectNode) row("W1-2").path("rawModelOutput").deepCopy();
        var r = (ObjectNode) raw.path("report");
        r.setAll((ObjectNode) JSON.valueToTree(row("W1-1").at("/rawModelOutput/report")));
        r.put("followUpQuestion", (String) null); r.set("missingFields", JSON.createArrayNode());
        r.setAll((ObjectNode) JSON.valueToTree(report));
        var u = JSON.createObjectNode();
        for (String group : FollowUpPolicy.UPDATE_GROUPS) {
            String[] change = updates.getOrDefault(group, new String[]{"KEEP", null});
            var node = JSON.createObjectNode(); node.put("action", change[0]); node.put("evidence", change[1]);
            u.set(group, node);
        }
        raw.set("updates", u);
        answers.put(answer, raw);
        return this;
    }

    /**
     * Scripts the model output for a first turn, built on the saved W1-1 shape: report fields the model claims, and
     * its proposed clarification reason with evidence ("NONE" for none). Every scripted text is fixed before the run.
     */
    public ScriptedAi first(String text, Map<String, Object> report, String reason, String evidence) {
        var raw = (ObjectNode) row("W1-1").path("rawModelOutput").deepCopy();
        var r = (ObjectNode) raw.path("report");
        r.put("followUpQuestion", (String) null); r.set("missingFields", JSON.createArrayNode());
        for (String key : List.of("locationText", "building", "floor", "occurredAtText", "targetText")) r.putNull(key);
        r.setAll((ObjectNode) JSON.valueToTree(report));
        var a = (ObjectNode) raw.path("assessment");
        a.put("reason", reason); a.put("evidence", evidence); a.putNull("question"); a.put("rationale", "scripted");
        var known = (ObjectNode) a.path("known");
        for (String k : List.of("locationEvidence", "scopeEvidence", "symptomEvidence", "targetEvidence", "workflowEvidence")) known.putNull(k);
        firsts.put(text, raw);
        return this;
    }

    @Override protected JsonNode complete(Map<String, Object> body) {
        try {
            var request = JSON.readTree(JSON.valueToTree(body).at("/messages/1/content").asText());
            JsonNode raw = request.has("followUp") ? answers.get(request.at("/followUp/answer").asText())
                    : firsts.containsKey(request.path("text").asText()) ? firsts.get(request.path("text").asText())
                    : request.path("text").asText().equals(firstText()) ? row("W1-1").path("rawModelOutput") : null;
            if (raw == null) throw new IllegalStateException("No scripted output; provider calls forbidden");
            calls++;
            return JSON.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", JSON.writeValueAsString(raw))))));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @Override public Embedding embed(String input) { throw new IllegalStateException("Embeddings forbidden in scripted tests"); }
}
