package edu.skope.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.ai.AiService;
import edu.skope.domain.Codes;
import edu.skope.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Small-data candidate retrieval. A candidate is never an automatic incident merge. */
@Service
public class SimilarityService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ROOM = Pattern.compile("(?<!\\d)([A-Za-z]?\\d{2,5})\\s*호(?:실)?");
    // Development-set calibration for a narrowly defined additional review candidate. This is
    // not a universal embedding threshold and never bypasses type, place, or time checks.
    private static final double CONNECTIVITY_SUPPORT_THRESHOLD = .70;
    private static final Pattern NETWORK = Pattern.compile("와이파이|wi-?fi|무선망|무선인터넷|인터넷");
    private static final Pattern CONNECTIVITY_FAILURE = Pattern.compile(
            "끊|단절|(?:재접속|재연결).{0,12}(?:반복|계속|자꾸)|(?:반복|계속|자꾸).{0,12}(?:재접속|재연결)"
                    + "|(?:연결|접속|와이파이|무선망|무선인터넷|인터넷).{0,20}불안정"
                    + "|(?:무선망|와이파이|무선인터넷).{0,32}꺼(?:집|져|졌|지)"
                    + "|(?:접속|연결).{0,10}(?:불가|실패|안(?:되|돼|됩|됐))");
    private static final Pattern DISTINCT_NETWORK_ISSUE = Pattern.compile(
            "비밀번호|패스워드|암호|보안|노출|스티커|표지|안내문|게시판|안내판|ssid");
    private static final Pattern NEGATED_CONNECTIVITY_FAILURE = Pattern.compile(
            "(?:끊|단절).{0,10}(?:않|아니|없)|불안정.{0,8}(?:않|아니)"
                    + "|(?:접속|연결|와이파이|무선망|인터넷).{0,20}(?:정상|문제없)"
                    + "|(?:와이파이|무선망|무선인터넷|인터넷).{0,8}아니"
                    + "|꺼지지|꺼진적없|(?:접속|연결).{0,8}(?:실패하지|불가하지)|끊는(?:법|방법)");
    private final Db db;
    private final AiService ai;
    private final double threshold;
    private final int windowDays;

    public SimilarityService(Db db, AiService ai,
                             @Value("${skope.similarity.threshold:0.82}") double threshold,
                             @Value("${skope.similarity.window-days:30}") int windowDays) {
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1 || windowDays < 1)
            throw new IllegalArgumentException("Invalid similarity settings");
        this.db = db;
        this.ai = ai;
        this.threshold = threshold;
        this.windowDays = windowDays;
    }

    /** Called after the report transaction commits. External failures leave the report intact. */
    public void index(long reportId) {
        Map<String, Object> report = db.one("SELECT * FROM reports WHERE id=?", reportId);
        if (report == null || "QUESTION".equals(report.get("requestKind")) || report.get("reviewNote")!=null) return;
        refreshEmbedding(report, reportId);
        indexCandidates(report,reportId);
    }

    /** Maintenance only: fill vectors first, without changing candidates until the whole bounded batch succeeds. */
    boolean refreshEmbeddingOnly(long reportId) {
        Map<String,Object> report=db.one("SELECT * FROM reports WHERE id=?",reportId);
        if(report==null||"QUESTION".equals(report.get("requestKind"))||report.get("reviewNote")!=null)return false;
        refreshEmbedding(report,reportId);
        return Db.str(report,"embeddingModel").equals(ai.embeddingModel())&&validVector(vector(report.get("embeddingJson")));
    }

    private void refreshEmbedding(Map<String,Object> report,long reportId) {
        if ("RULE_ONLY".equals(ai.embeddingMode())) clearActiveEmbedding(report);
        else try {
            // Report text and extraction fields are immutable after submission. Keep a valid vector
            // for the current model when rebuilding candidates, rather than paying to embed it again.
            boolean reusable = !Db.str(report, "embeddingModel").isBlank()
                    && Db.str(report, "embeddingModel").equals(ai.embeddingModel())
                    && validVector(vector(report.get("embeddingJson")));
            // Keep stored vectors for future reuse, but do not use a prior model as though a
            // refresh for the currently configured model succeeded.
            if (!reusable) clearActiveEmbedding(report);
            var embedding = reusable ? null : ai.embed(embeddingText(report));
            if (embedding != null && validVector(embedding.vector()) && embedding.model() != null
                    && !embedding.model().isBlank()) {
                String vector = JSON.writeValueAsString(embedding.vector());
                db.update("UPDATE reports SET embedding_json=?,embedding_model=? WHERE id=?",
                        vector, embedding.model(), reportId);
                report.put("embeddingJson", vector);
                report.put("embeddingModel", embedding.model());
            }
        } catch (Exception unavailable) {
            // Provider failure is deliberately isolated from accepted reports. No input/key logging.
        }
    }

    private void indexCandidates(Map<String,Object> report,long reportId) {
        LocalDateTime created = timestamp(report.get("createdAt"));
        var candidates = db.all("SELECT * FROM reports WHERE request_kind='REPORT' AND review_note IS NULL AND id<>? AND category=? AND subcategory=? "
                        + "AND created_at BETWEEN ? AND ? ORDER BY id", reportId,
                report.get("category"), report.get("subcategory"),
                Timestamp.valueOf(created.minusDays(windowDays)), Timestamp.valueOf(created.plusDays(windowDays)));
        for (var other : candidates) {
            long otherId = Db.id(other, "id");
            Double score = score(report, other);
            String basis = semanticBasis(report, other, score, threshold);
            if (!compatiblePlace(report, other) || basis.equals("EXCLUDED")) {
                // Re-indexing can replace an earlier rule-only fallback or a candidate from an older place
                // policy; reviewed decisions remain intact.
                db.update("DELETE FROM similar_pairs WHERE left_report_id=? AND right_report_id=? AND decision='CANDIDATE'",
                        Math.min(reportId, otherId), Math.max(reportId, otherId));
                continue;
            }
            var place=SimilarityPlace.compare(report,other,score!=null);
            String reason = "유형·건물 일치, 접수 시각 " + windowDays + "일 이내. "
                    + (score == null ? "규칙 기반 후보(RULE_ONLY); 실제 Embedding 점수 없음. "
                    : basis.equals("CONNECTIVITY_SYMPTOM_SUPPORT")
                    ? String.format(Locale.ROOT, "실제 Embedding 코사인 %.4f; 연결 장애 증상 일치 보조 기준 %.2f 적용(기본 %.2f). ",
                    score, CONNECTIVITY_SUPPORT_THRESHOLD, threshold) : "실제 Embedding 코사인 비교. ")
                    + (place.expanded()?"공통망·명시 범위에 따른 장소 확장 후보; 동일 장애 범위는 미확정. ":
                        room(report).isBlank() || room(other).isBlank() ? "호실 일부 미확인. " : "호실 일치. ")
                    + (report.get("occurredOn") == null || other.get("occurredOn") == null
                    ? "발생일 미확인; 접수일은 발생일이 아님. " : "발생일은 별도 관리자 검토 필요. ")
                    + "동일 사건 확정 전 관리자 검토 필요.";
            long left = Math.min(reportId, otherId), right = Math.max(reportId, otherId);
            try {
                db.insert("INSERT INTO similar_pairs(left_report_id,right_report_id,similarity_score,reason_text) VALUES(?,?,?,?)",
                        left, right, score, reason);
            } catch (DuplicateKeyException alreadyIndexed) {
                // A concurrent report may have indexed this normalized pair first. Preserve review decisions.
                db.update("UPDATE similar_pairs SET similarity_score=?,reason_text=? "
                        + "WHERE left_report_id=? AND right_report_id=? AND decision='CANDIDATE'", score, reason, left, right);
            }
        }
    }

    private static void clearActiveEmbedding(Map<String, Object> report) {
        report.remove("embeddingJson");
        report.remove("embeddingModel");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> similar(long reportId, long actorId) {
        var access = new DepartmentAccess(db);
        access.requireReport(actorId, reportId);
        var result = new LinkedHashMap<>(similar(reportId));
        var rows = (List<Map<String, Object>>) result.get("items");
        result.put("items", rows.stream().filter(row -> access.canReadReport(actorId, Db.id(row, "relatedReportId"))).toList());
        return result;
    }

    public Map<String, Object> similar(long reportId) {
        if (db.one("SELECT id FROM reports WHERE id=?", reportId) == null)
            throw new ApiException(404, "REPORT_NOT_FOUND", "신고를 찾을 수 없습니다.");
        var rows = db.all("SELECT p.id AS pair_id, CASE WHEN p.left_report_id=? THEN p.right_report_id "
                        + "ELSE p.left_report_id END AS related_report_id,p.similarity_score,p.reason_text,p.decision,"
                        + "p.reviewed_by,p.reviewed_at FROM similar_pairs p WHERE p.left_report_id=? OR p.right_report_id=? "
                        + "ORDER BY p.created_at DESC,p.id DESC", reportId, reportId, reportId);
        for (var row : rows) {
            row.put("aiMode", row.get("similarityScore") == null ? "RULE_ONLY" : "LIVE");
            var related = db.one("SELECT id,original_text,location_text,status,incident_id,occurred_on FROM reports WHERE id=?",
                    row.get("relatedReportId"));
            row.put("relatedReport", related);
        }
        return Map.of("reportId", reportId, "items", rows, "embeddingMode", ai.embeddingMode(),
                "threshold", threshold, "windowDays", windowDays, "thresholdValidated", false,
                "connectivitySupportThreshold", CONNECTIVITY_SUPPORT_THRESHOLD);
    }

    public static String embeddingText(Map<String, Object> r) {
        return Db.str(r, "category") + "/" + Db.str(r, "subcategory") + " | "
                + Db.str(r, "locationText") + " | " + Db.str(r, "symptom") + " | " + Db.str(r, "originalText");
    }

    /** Offline evaluation diagnostics, including scores of pairs that retrieval would exclude.
     * Does not persist report text, call a provider, or override administrator decisions. */
    public static Map<String, Object> diagnose(Map<String, Object> left, Map<String, Object> right,
                                               double threshold, int windowDays) {
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1 || windowDays < 1)
            throw new IllegalArgumentException("Invalid similarity settings");
        boolean type = Objects.equals(left.get("category"), right.get("category"))
                && Objects.equals(left.get("subcategory"), right.get("subcategory"));
        String buildingA = edu.skope.domain.CampusPlaces.key(left.get("building")), buildingB = edu.skope.domain.CampusPlaces.key(right.get("building"));
        String floorA = normalized(left.get("floor")).replace("층", "");
        String floorB = normalized(right.get("floor")).replace("층", "");
        String roomA = room(left), roomB = room(right);
        boolean building = !buildingA.isBlank() && !buildingB.isBlank() && buildingA.equals(buildingB);
        Double rawScore = score(left, right);
        var place=SimilarityPlace.compare(left,right,rawScore!=null);
        boolean floor = place.floor(), room = place.room();
        LocalDateTime createdA = timestamp(left.get("createdAt")), createdB = timestamp(right.get("createdAt"));
        boolean time = !createdB.isBefore(createdA.minusDays(windowDays))
                && !createdB.isAfter(createdA.plusDays(windowDays));
        boolean baselineSemantic = rawScore == null || rawScore >= threshold;
        String basis = semanticBasis(left, right, rawScore, threshold);
        boolean scope = broadScope(left, right, rawScore);
        boolean semantic = !scope || !basis.equals("EXCLUDED");
        List<String> excluded = new ArrayList<>();
        if (!type) excluded.add("TYPE");
        if (!building) excluded.add("BUILDING");
        if (!floor) excluded.add("FLOOR");
        if (!room) excluded.add("ROOM");
        if (!place.detail()) excluded.add("SPECIFIC_LOCATION");
        if (!time) excluded.add("TIME_WINDOW");
        if (!scope) excluded.add("WORK_GROUP_TARGET");
        List<String> baselineExcluded = new ArrayList<>(excluded);
        if (!baselineSemantic) baselineExcluded.add("EMBEDDING_THRESHOLD");
        if (!semantic) excluded.add("EMBEDDING_THRESHOLD");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeMatch", type); result.put("workGroupTargetMatch", scope); result.put("buildingMatch", building);
        result.put("specificLocationMatch",place.detail());result.put("comparisonScope",place.scope());result.put("scopeExpanded",place.expanded());
        result.put("floorMatch", floor); result.put("roomMatch", room); result.put("timeMatch", time);
        result.put("normalizedPlace", Map.of("leftBuilding", buildingA, "rightBuilding", buildingB,
                "leftFloor", floorA, "rightFloor", floorB, "leftRoom", roomA, "rightRoom", roomB));
        result.put("rawCosine", rawScore); result.put("threshold", threshold);
        result.put("thresholdPass", baselineSemantic); result.put("windowDays", windowDays);
        result.put("connectivitySupportThreshold", CONNECTIVITY_SUPPORT_THRESHOLD);
        result.put("leftConnectivityFailure", explicitConnectivityFailure(left));
        result.put("rightConnectivityFailure", explicitConnectivityFailure(right));
        result.put("baselineCandidate", baselineExcluded.isEmpty()); result.put("candidate", excluded.isEmpty());
        result.put("baselineExcludedBy", List.copyOf(baselineExcluded));
        result.put("excludedBy", List.copyOf(excluded));
        result.put("decisionBasis", excluded.isEmpty() ? basis : "EXCLUDED");
        return result;
    }

    /**
     * A candidate is the same observed problem. OTHER/UNKNOWN spans many objects, so both reports must have the
     * same work group and, when both state an object, the same object; without an embedding they also need a
     * stated object and a symptom each. A narrow type is one object family where names vary (조명/전등,
     * 와이파이/무선망), so a different word alone proves nothing: two objects are separated only when a report
     * itself calls the other one fine or separate ("천장 배관은 마른 상태"), and then no score can join them.
     */
    static boolean broadScope(Map<String, Object> a, Map<String, Object> b, Double score) {
        boolean broad = Codes.broad(a.get("subcategory")) && Codes.broad(b.get("subcategory"));
        if (broad && !Codes.workGroup("UNKNOWN", a.get("workGroup")).equals(Codes.workGroup("UNKNOWN", b.get("workGroup")))) return false;
        String targetA = targetKey(a.get("targetText")), targetB = targetKey(b.get("targetText"));
        boolean bothTargets = !targetA.isBlank() && !targetB.isBlank();
        if (bothTargets && !sameTarget(a, b) && (broad || statedApart(a, targetB) || statedApart(b, targetA))) return false;
        if (!broad || score != null) return true;
        return bothTargets && !normalized(a.get("symptom")).isBlank() && !normalized(b.get("symptom")).isBlank();
    }

    /**
     * Same object name, or a shorter name for it backed by the report's own words: "손잡이" matches "문손잡이"
     * when that report says "출입문 손잡이", while a "서랍 손잡이" report stays a different object, and so does
     * "문손잡이는 정상이고 서랍 손잡이가…". The name must end the longer one, so "무선망" never matches "무선망 안내문".
     */
    static boolean sameTarget(Map<String, Object> a, Map<String, Object> b) {
        String targetA = targetKey(a.get("targetText")), targetB = targetKey(b.get("targetText"));
        if (targetA.equals(targetB)) return true;
        boolean aLonger = targetA.length() > targetB.length();
        String longName = aLonger ? targetA : targetB, shortName = aLonger ? targetB : targetA;
        Map<String, Object> shortReport = aLonger ? b : a;
        return shortName.length() >= 2 && longName.endsWith(shortName)
                && clauses(shortReport).anyMatch(c -> c.contains(longName) && !apart(c, longName));
    }

    // "Not the broken one" wording. State words only: 않/아니 alone also describe faults ("열리지 않아요").
    private static final Pattern FINE = Pattern.compile("정상|괜찮|멀쩡|문제없|이상없|마른|말랐|별개");
    // "A가 아니라 B": the contrast denies the words before it (A), not the broken object after it (B).
    private static final Pattern CONTRAST = Pattern.compile("아니라|아니고|말고");
    // A fine word that is itself denied describes a fault: "정상은 아니고", "괜찮지 않아요", "정상이 아니라".
    private static final Pattern FINE_DENIED_BY_CONTRAST = Pattern.compile("(?:정상|괜찮|멀쩡|문제없|이상없|별개)(?:은|는|이|가|도|인|인건|인게|이진|이지는|하진|하지는|하지|한건|한게)?$");
    // After a fine word, any past tense or denial up to the clause end makes it a fault description:
    // "정상이었는데"(was fine), "정상적으로 작동하지 않습니다", "정상적으로 연결이 안 돼요". Only a present affirmative remains.
    private static final Pattern NOT_PRESENT_FINE = Pattern.compile("었|였|았|했|됐|졌|던|않|아니|못|안되|안돼|안됨|안됩");
    // A fine word right before the object as its present-tense modifier: "정상인 X", "멀쩡한 X", "정상 사용 중인 X".
    // "정상이던 X가 끊겨요" (was fine, now broken) does not match.
    private static final Pattern FINE_MODIFIER = Pattern.compile("인|한|적인|(?:사용|작동|동작)(?:중인|하는)");

    /** The report names this object only to say it is fine or a different one. */
    private static boolean statedApart(Map<String, Object> report, String object) {
        return clauses(report).anyMatch(c -> apart(c, object));
    }

    /**
     * In one normalized clause, the object is set apart when a contrast denies the part naming it ("문손잡이가
     * 아니라…") or a fine word follows it in its part as a present affirmative state ("천장배관은마른상태"),
     * or modifies it directly ("정상사용중인무선망"). A past or denied fine state is a fault: it never sets apart.
     * A contrast that denies a fine word ("정상은 아니고") states a fault, so it sets nothing apart.
     */
    static boolean apart(String clause, String object) {
        var m = CONTRAST.matcher(clause);
        int from = 0;
        while (true) {
            boolean contrast = m.find();
            String part = clause.substring(from, contrast ? m.start() : clause.length());
            boolean fineDenied = contrast && FINE_DENIED_BY_CONTRAST.matcher(part).find();
            int at = part.indexOf(object);
            if (at >= 0) {
                if (contrast && !fineDenied) return true;
                var fine = FINE.matcher(part);
                while (!fineDenied && fine.find()) {
                    if (fine.start() > at && !NOT_PRESENT_FINE.matcher(part.substring(fine.end())).find()) return true;
                    if (fine.end() <= at && FINE_MODIFIER.matcher(part.substring(fine.end(), at)).matches()) return true;
                }
            }
            if (!contrast) return false;
            from = m.end();
        }
    }

    /** The report's own words, normalized, split at sentence ends and clause connectors (…고, …지만, …는데). */
    private static java.util.stream.Stream<String> clauses(Map<String, Object> report) {
        return Arrays.stream((Db.str(report, "symptom") + "\n" + Db.str(report, "originalText"))
                        .split("[.!?;,\\n]|(?<=지만)|(?<=[는은인]데)|(?<=[이았었했났됐졌있없같않되맞]고)(?=\\s)"))
                .map(SimilarityService::normalized).filter(c -> !c.isBlank());
    }

    /** Limited compatibility for older literal labels. New extraction uses common Korean object names.
     * Unknown aliases remain conservative; a high whole-report cosine cannot prove object equivalence. */
    private static String targetKey(Object value) {
        String key = normalized(java.text.Normalizer.normalize(Objects.toString(value,""), java.text.Normalizer.Form.NFKC));
        return switch (key) { case "미러", "mirror" -> "거울"; default -> key; };
    }

    private static String semanticBasis(Map<String, Object> left, Map<String, Object> right,
                                        Double score, double threshold) {
        if (!broadScope(left, right, score)) return "EXCLUDED";
        if (score == null) return "RULE_ONLY";
        if (score >= threshold) return "EMBEDDING_THRESHOLD";
        if (score >= CONNECTIVITY_SUPPORT_THRESHOLD
                && explicitConnectivityFailure(left) && explicitConnectivityFailure(right))
            return "CONNECTIVITY_SYMPTOM_SUPPORT";
        return "EXCLUDED";
    }

    static boolean explicitConnectivityFailure(Map<String, Object> report) {
        if (!"IT".equals(report.get("category")) || !"WIFI".equals(report.get("subcategory"))) return false;
        String text = normalized(Db.str(report, "symptom") + " " + Db.str(report, "originalText"));
        return NETWORK.matcher(text).find() && CONNECTIVITY_FAILURE.matcher(text).find()
                && !DISTINCT_NETWORK_ISSUE.matcher(text).find() && !NEGATED_CONNECTIVITY_FAILURE.matcher(text).find();
    }

    static Double score(Map<String, Object> left, Map<String, Object> right) {
        String model = Db.str(left, "embeddingModel");
        if (model.isBlank() || !model.equals(Db.str(right, "embeddingModel"))) return null;
        double[] a = vector(left.get("embeddingJson")), b = vector(right.get("embeddingJson"));
        if (!validVector(a) || !validVector(b) || a.length != b.length) return null;
        return cosine(a, b);
    }

    static double cosine(double[] a, double[] b) {
        if (!validVector(a) || !validVector(b) || a.length != b.length)
            throw new IllegalArgumentException("Incompatible embedding vectors");
        // Scale first so even very large finite provider values cannot overflow the dot product.
        double scaleA = Arrays.stream(a).map(Math::abs).max().orElseThrow();
        double scaleB = Arrays.stream(b).map(Math::abs).max().orElseThrow();
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] / scaleA, y = b[i] / scaleB;
            dot += x * y; aa += x * x; bb += y * y;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(aa * bb)));
    }

    private static double[] vector(Object raw) {
        if (raw == null) return null;
        try {
            JsonNode node = JSON.readTree(raw.toString());
            // H2's MySQL-compatible JSON type can return a JSON-encoded string.
            if (node.isTextual()) node = JSON.readTree(node.asText());
            if (!node.isArray() || node.isEmpty()) return null;
            double[] result = new double[node.size()];
            for (int i = 0; i < result.length; i++) {
                if (!node.get(i).isNumber()) return null;
                result[i] = node.get(i).asDouble();
            }
            return result;
        } catch (Exception invalidJson) { return null; }
    }

    static boolean validVector(double[] vector) {
        if (vector == null || vector.length == 0) return false;
        boolean nonzero = false;
        for (double value : vector) {
            if (!Double.isFinite(value)) return false;
            nonzero |= value != 0;
        }
        return nonzero;
    }

    static boolean compatiblePlace(Map<String, Object> a, Map<String, Object> b) {
        if (!Objects.equals(a.get("category"), b.get("category"))
                || !Objects.equals(a.get("subcategory"), b.get("subcategory"))) return false;
        return SimilarityPlace.compare(a,b,score(a,b)!=null).allowed();
    }

    static String room(Map<String, Object> report) {
        var match = ROOM.matcher(Db.str(report, "locationText"));
        return match.find() ? match.group(1).toUpperCase(Locale.ROOT) : "";
    }

    static String normalized(Object value) {
        return Objects.toString(value, "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static LocalDateTime timestamp(Object value) {
        if (value instanceof Timestamp t) return t.toLocalDateTime();
        if (value instanceof LocalDateTime t) return t;
        String text = value.toString();
        try { return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime(); }
        catch (RuntimeException noOffset) { return LocalDateTime.parse(text.replace(' ', 'T')); }
    }
}
