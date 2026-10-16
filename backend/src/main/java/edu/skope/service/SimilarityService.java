package edu.skope.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.ai.AiService;
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
        LocalDateTime created = timestamp(report.get("createdAt"));
        var candidates = db.all("SELECT * FROM reports WHERE request_kind='REPORT' AND review_note IS NULL AND id<>? AND category=? AND subcategory=? "
                        + "AND created_at BETWEEN ? AND ? ORDER BY id", reportId,
                report.get("category"), report.get("subcategory"),
                Timestamp.valueOf(created.minusDays(windowDays)), Timestamp.valueOf(created.plusDays(windowDays)));
        for (var other : candidates) {
            if (!compatiblePlace(report, other)) continue;
            Double score = score(report, other);
            String basis = semanticBasis(report, other, score, threshold);
            if (basis.equals("EXCLUDED")) {
                // Re-indexing can replace an earlier rule-only fallback; reviewed decisions remain intact.
                long otherId = Db.id(other, "id");
                db.update("DELETE FROM similar_pairs WHERE left_report_id=? AND right_report_id=? AND decision='CANDIDATE'",
                        Math.min(reportId, otherId), Math.max(reportId, otherId));
                continue;
            }
            String reason = "유형·건물 일치, 접수 시각 " + windowDays + "일 이내. "
                    + (score == null ? "규칙 기반 후보(RULE_ONLY); 실제 Embedding 점수 없음. "
                    : basis.equals("CONNECTIVITY_SYMPTOM_SUPPORT")
                    ? String.format(Locale.ROOT, "실제 Embedding 코사인 %.4f; 연결 장애 증상 일치 보조 기준 %.2f 적용(기본 %.2f). ",
                    score, CONNECTIVITY_SUPPORT_THRESHOLD, threshold) : "실제 Embedding 코사인 비교. ")
                    + (room(report).isBlank() || room(other).isBlank() ? "호실 일부 미확인. " : "호실 일치. ")
                    + (report.get("occurredOn") == null || other.get("occurredOn") == null
                    ? "발생일 미확인; 접수일은 발생일이 아님. " : "발생일은 별도 관리자 검토 필요. ")
                    + "동일 사건 확정 전 관리자 검토 필요.";
            long otherId = Db.id(other, "id");
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
        String buildingA = normalized(left.get("building")), buildingB = normalized(right.get("building"));
        String floorA = normalized(left.get("floor")).replace("층", "");
        String floorB = normalized(right.get("floor")).replace("층", "");
        String roomA = room(left), roomB = room(right);
        boolean building = !buildingA.isBlank() && !buildingB.isBlank() && buildingA.equals(buildingB);
        boolean floor = floorA.isBlank() || floorB.isBlank() || floorA.equals(floorB);
        boolean room = roomA.isBlank() || roomB.isBlank() || roomA.equals(roomB);
        LocalDateTime createdA = timestamp(left.get("createdAt")), createdB = timestamp(right.get("createdAt"));
        boolean time = !createdB.isBefore(createdA.minusDays(windowDays))
                && !createdB.isAfter(createdA.plusDays(windowDays));
        Double rawScore = score(left, right);
        boolean baselineSemantic = rawScore == null || rawScore >= threshold;
        String basis = semanticBasis(left, right, rawScore, threshold);
        boolean semantic = !basis.equals("EXCLUDED");
        List<String> excluded = new ArrayList<>();
        if (!type) excluded.add("TYPE");
        if (!building) excluded.add("BUILDING");
        if (!floor) excluded.add("FLOOR");
        if (!room) excluded.add("ROOM");
        if (!time) excluded.add("TIME_WINDOW");
        List<String> baselineExcluded = new ArrayList<>(excluded);
        if (!baselineSemantic) baselineExcluded.add("EMBEDDING_THRESHOLD");
        if (!semantic) excluded.add("EMBEDDING_THRESHOLD");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeMatch", type); result.put("buildingMatch", building);
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

    private static String semanticBasis(Map<String, Object> left, Map<String, Object> right,
                                        Double score, double threshold) {
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
        String buildingA = normalized(a.get("building")), buildingB = normalized(b.get("building"));
        if (buildingA.isBlank() || buildingB.isBlank() || !buildingA.equals(buildingB)) return false;
        String floorA = normalized(a.get("floor")).replace("층", ""), floorB = normalized(b.get("floor")).replace("층", "");
        if (!floorA.isBlank() && !floorB.isBlank() && !floorA.equals(floorB)) return false;
        String roomA = room(a), roomB = room(b);
        return roomA.isBlank() || roomB.isBlank() || roomA.equals(roomB);
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
