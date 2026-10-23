package edu.skope.service;

import edu.skope.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Administrator-confirmed incident membership and conservative recurrence candidates. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IncidentService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Db db;
    private final DepartmentAccess access;

    public IncidentService(Db db) { this.db = db; this.access = new DepartmentAccess(db); }

    public void start(long reportId, long actorId) {
        var report = report(reportId, true);
        access.requireReport(actorId, reportId);
        if ("QUESTION".equals(report.get("requestKind"))) return;
        if (report.get("incidentId") != null) {
            var existing = incident(Db.id(report, "incidentId"), true);
            access.requireIncident(actorId, Db.id(report, "incidentId"));
            if (!"OPEN".equals(existing.get("status"))) conflict("종료된 사건에 속한 신고입니다.");
            return;
        }
        long incidentId = createIncident(report);
        attach(report, incidentId, actorId);
        suggestRecurrence(incidentId, List.of(report));
    }

    /** Call after a report's state update in the same READ_COMMITTED transaction. */
    public void refresh(long incidentId) {
        var incident = incident(incidentId, true);
        var count = db.one("SELECT COUNT(*) AS total,SUM(CASE WHEN status='RESOLVED' "
                + "AND resolution_text IS NOT NULL AND TRIM(resolution_text)<>'' THEN 1 ELSE 0 END) AS finished "
                + "FROM reports WHERE incident_id=?", incidentId);
        long total = Db.id(count, "total");
        long finished = count.get("finished") == null ? 0 : Db.id(count, "finished");
        if (total > 0 && total == finished && "OPEN".equals(incident.get("status")))
            db.update("UPDATE incidents SET status='RESOLVED',closed_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'", incidentId);
    }

    public Map<String, Object> reviewPair(long pairId, String decision, long actorId) {
        if (!Set.of("SAME_INCIDENT", "RELATED_DISTINCT", "REJECTED").contains(Objects.toString(decision, "")))
            throw new ApiException(400, "VALIDATION_ERROR", "유사 후보 검토 결정을 선택해 주세요.");
        var pair = db.one("SELECT * FROM similar_pairs WHERE id=? FOR UPDATE", pairId);
        if (pair == null) throw new ApiException(404, "REPORT_NOT_FOUND", "유사 후보를 찾을 수 없습니다.");
        access.requirePair(actorId, pairId);
        String old = Db.str(pair, "decision");
        if (!"CANDIDATE".equals(old)) {
            if (old.equals(decision)) return pair;
            conflict("이미 검토한 후보의 결정을 바꾸려면 사건 관계를 별도로 조정해야 합니다.");
        }
        // All membership writers lock report rows first, in ascending order, then incident rows.
        long leftId = Db.id(pair, "leftReportId"), rightId = Db.id(pair, "rightReportId");
        var left = report(Math.min(leftId, rightId), true);
        var right = report(Math.max(leftId, rightId), true);
        access.requirePair(actorId, pairId);
        var lockedIncidents = lockIncidentIds(left.get("incidentId"), right.get("incidentId"));
        for (long linked : lockedIncidents.keySet()) access.requireIncident(actorId, linked);
        Long resultingIncident = null;
        if ("SAME_INCIDENT".equals(decision)) {
            requireCompatibleMembership(left, right);
            Object leftIncident = left.get("incidentId"), rightIncident = right.get("incidentId");
            if (leftIncident != null && rightIncident != null
                    && ((Number) leftIncident).longValue() != ((Number) rightIncident).longValue())
                conflict("서로 다른 사건에 연결되어 있어 자동 병합할 수 없습니다.");
            if (lockedIncidents.values().stream().anyMatch(i -> !"OPEN".equals(i.get("status"))))
                conflict("종료된 사건에 신규 신고를 병합할 수 없습니다.");
            if (("RESOLVED".equals(left.get("status")) && leftIncident == null)
                    || ("RESOLVED".equals(right.get("status")) && rightIncident == null))
                conflict("사건이 없는 완료 신고의 관계를 자동 변경할 수 없습니다.");
            if (leftIncident == null && rightIncident == null) {
                resultingIncident = createIncident(left);
            } else {
                resultingIncident = ((Number) (leftIncident != null ? leftIncident : rightIncident)).longValue();
                for (var member : members(resultingIncident)) {
                    requireCompatibleMembership(left, member);
                    requireCompatibleMembership(right, member);
                }
                requireConfirmedRecurrenceCompatible(resultingIncident, List.of(left, right));
            }
            attach(left, resultingIncident, actorId);
            attach(right, resultingIncident, actorId);
            updateCandidateAfterMembership(resultingIncident);
        } else if (left.get("incidentId") != null && right.get("incidentId") != null
                && Db.id(left, "incidentId") == Db.id(right, "incidentId")) {
            conflict("이미 같은 사건에 연결된 신고를 별도 관계로 변경할 수 없습니다.");
        }
        db.update("UPDATE similar_pairs SET decision=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
                decision, actorId, pairId);
        audit(leftId, actorId, "SIMILARITY_REVIEW", old, decision, "유사 후보 #" + pairId + " / 상대 신고 #" + rightId);
        audit(rightId, actorId, "SIMILARITY_REVIEW", old, decision, "유사 후보 #" + pairId + " / 상대 신고 #" + leftId);
        var result = db.one("SELECT * FROM similar_pairs WHERE id=?", pairId);
        result.put("incidentId", resultingIncident);
        return result;
    }

    public Map<String, Object> link(long reportId, long incidentId, long actorId) {
        var report = report(reportId, true);
        access.requireReport(actorId, reportId);
        if ("QUESTION".equals(report.get("requestKind"))) conflict("일반 문의는 시설 사건에 연결할 수 없습니다.");
        var locked = lockIncidentIds(report.get("incidentId"), incidentId);
        for (long linked : locked.keySet()) access.requireIncident(actorId, linked);
        var target = locked.get(incidentId);
        if (!"OPEN".equals(target.get("status"))) conflict("종료된 사건에는 신고를 연결할 수 없습니다.");
        if (report.get("incidentId") != null) {
            if (Db.id(report, "incidentId") != incidentId) conflict("이미 다른 사건에 연결된 신고입니다.");
            return detail(incidentId, actorId);
        }
        if ("RESOLVED".equals(report.get("status"))) conflict("완료된 신고를 새 사건에 연결할 수 없습니다.");
        requireCompatibleMembership(report, target);
        for (var member : members(incidentId)) requireCompatibleMembership(report, member);
        requireConfirmedRecurrenceCompatible(incidentId, List.of(report));
        attach(report, incidentId, actorId);
        updateCandidateAfterMembership(incidentId);
        return detail(incidentId, actorId);
    }

    @Transactional
    public Map<String, Object> detail(long incidentId, long actorId) {
        // Routing/membership writers use report -> incident locks in this same order.
        // Keep ownership stable until all report text and history have been returned.
        for (var member : members(incidentId)) report(Db.id(member, "id"), true);
        incident(incidentId, true);
        access.requireIncident(actorId, incidentId);
        var result = detail(incidentId);
        if (result.get("previousIncidentId") != null
                && !access.canReadIncident(actorId, Db.id(result, "previousIncidentId"))) {
            result.remove("previousIncident");
            result.put("previousIncidentId", null);
            result.put("recurrenceDecision", "NOT_EVALUATED");
            result.put("reasonText", recurrenceReason(members(incidentId), false));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long incidentId) {
        var result = incident(incidentId, false);
        var reports = members(incidentId);
        reports.forEach(IncidentService::removeEmbeddings);
        result.put("reports", reports);
        result.put("reportIds", reports.stream().map(r -> r.get("id")).toList());
        result.put("reportCount", reports.size());
        result.put("timeBasis", timeBasis(reports));
        result.put("reasonText", recurrenceReason(reports, result.get("previousIncidentId") != null));
        result.put("events", db.all("SELECT e.event_type,e.old_value,e.new_value,e.note,e.created_at AS changed_at,"
                + "u.display_name AS actor_name,e.report_id FROM report_events e JOIN users u ON u.id=e.actor_id "
                + "JOIN reports r ON r.id=e.report_id WHERE r.incident_id=? ORDER BY e.created_at,e.id", incidentId));
        if (result.get("previousIncidentId") != null)
            result.put("previousIncident", incident(Db.id(result, "previousIncidentId"), false));
        return result;
    }

    public Map<String, Object> recurrence(long incidentId, long previousId, String decision, long actorId) {
        access.requireIncident(actorId, incidentId);
        access.requireIncident(actorId, previousId);
        if (!Set.of("CONFIRMED", "REJECTED").contains(Objects.toString(decision, "")))
            throw new ApiException(400, "VALIDATION_ERROR", "재발 확인 또는 반려를 선택해 주세요.");
        if (incidentId == previousId) invalidRecurrence("같은 사건을 과거 사건으로 참조할 수 없습니다.");
        // Acquire report locks before incident locks, matching report status and membership writers.
        var initialReports = members(incidentId);
        for (var report : initialReports) report(Db.id(report, "id"), true);
        var locked = lockIncidentIds(incidentId, previousId);
        access.requireIncident(actorId, incidentId);
        access.requireIncident(actorId, previousId);
        var current = locked.get(incidentId);
        var previous = locked.get(previousId);
        var reports = members(incidentId);
        if (!initialReports.stream().map(r -> r.get("id")).toList().equals(reports.stream().map(r -> r.get("id")).toList()))
            conflict("다른 관리자가 사건에 신고를 연결했습니다. 새로고침 후 다시 검토해 주세요.");
        if (!validPrevious(current, reports, previous))
            invalidRecurrence("과거 사건의 종료·유형·장소·발생 시점이 재발 기준에 맞지 않습니다. 같은 날 발생은 선후 확인이 필요합니다.");
        String old = Db.str(current, "recurrenceDecision");
        db.update("UPDATE incidents SET previous_incident_id=?,recurrence_decision=?,recurrence_reviewed_by=?,"
                + "recurrence_reviewed_at=CURRENT_TIMESTAMP WHERE id=?", previousId, decision, actorId, incidentId);
        for (var report : reports)
            audit(Db.id(report, "id"), actorId, "RECURRENCE_REVIEW", old, decision,
                    "과거 사건 #" + previousId + ". " + recurrenceReason(reports, true));
        return detail(incidentId, actorId);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> repeated(long actorId) {
        access.departmentId(actorId);
        var result = new LinkedHashMap<>(repeatedScoped(actorId));
        var visible = new ArrayList<Map<String, Object>>();
        for (var item : (List<Map<String, Object>>) result.get("items")) {
            var ids = (List<Number>) item.get("reportIds");
            if (!ids.stream().allMatch(id -> access.canReadReport(actorId, id.longValue()))) continue;
            if (item.get("previousIncidentId") != null
                    && !access.canReadIncident(actorId, Db.id(item, "previousIncidentId"))) {
                item.put("previousIncidentId", null);
                item.put("recurrenceDecision", "NOT_EVALUATED");
                item.put("reasonText", "소속 부서 신고의 사건 후보입니다. 최종 판단은 담당자가 확인합니다.");
            }
            visible.add(item);
        }
        result.put("items", visible);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> repeated() {
        return repeatedScoped(null);
    }

    private Map<String, Object> repeatedScoped(Long actorId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (var incident : db.all("SELECT * FROM incidents ORDER BY created_at DESC,id DESC")) {
            if (actorId != null && !access.canReadIncident(actorId, Db.id(incident,"id"))) continue;
            var reports = members(Db.id(incident, "id"));
            if (reports.isEmpty()) continue;
            items.add(candidateItem(incident, reports, incident.get("previousIncidentId"), "INCIDENT"));
        }
        Long department = actorId == null ? null : access.departmentId(actorId);
        var pending = department == null
                ? db.all("SELECT * FROM reports WHERE incident_id IS NULL ORDER BY id")
                : db.all("SELECT * FROM reports WHERE incident_id IS NULL AND assigned_department_id=? ORDER BY id", department);
        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var report : pending) groups.put(Db.id(report, "id"), new ArrayList<>(List.of(report)));
        var pairs = db.all("SELECT left_report_id,right_report_id FROM similar_pairs WHERE decision='CANDIDATE' ORDER BY id");
        for (var pair : pairs) {
            Long leftKey = findGroup(groups, Db.id(pair, "leftReportId"));
            Long rightKey = findGroup(groups, Db.id(pair, "rightReportId"));
            if (leftKey == null || rightKey == null || leftKey.equals(rightKey)) continue;
            var left = groups.get(leftKey); var right = groups.get(rightKey);
            if (left.stream().allMatch(a -> right.stream().allMatch(b -> SimilarityService.compatiblePlace(a, b)
                    && !previouslySeparated(a, b)))) {
                left.addAll(right); groups.remove(rightKey);
            }
        }
        for (var group : groups.values()) {
            var previous = findPrevious(group.getFirst(), group);
            if (group.size() > 1 || previous != null)
                items.add(candidateItem(null, group, previous == null ? null : previous.get("id"), "UNCONFIRMED_REPORTS"));
        }
        return Map.of("items", items, "generatedAt", OffsetDateTime.now(SEOUL).toString(), "aiMode", "RULE_ONLY");
    }

    private Map<String, Object> candidateItem(Map<String, Object> incident, List<Map<String, Object>> reports,
                                               Object previousId, String kind) {
        var first = reports.getFirst();
        var item = new LinkedHashMap<String, Object>();
        item.put("kind", kind);
        item.put("incidentId", incident == null ? null : incident.get("id"));
        for (String field : List.of("category", "subcategory", "building", "floor")) item.put(field, first.get(field));
        item.put("reportCount", reports.size());
        item.put("reportIds", reports.stream().map(r -> r.get("id")).toList());
        item.put("incidentStatus", incident == null ? null : incident.get("status"));
        item.put("previousIncidentId", previousId);
        item.put("recurrenceDecision", incident == null ? (previousId == null ? "NOT_EVALUATED" : "CANDIDATE") : incident.get("recurrenceDecision"));
        item.put("timeBasis", timeBasis(reports));
        item.put("reasonText", (incident == null ? "미확정 규칙 후보이며 사건 병합 전 관리자 확인이 필요합니다. " : "관리자가 확인한 사건 묶음입니다. ")
                + recurrenceReason(reports, previousId != null));
        return item;
    }

    private static Long findGroup(Map<Long, List<Map<String, Object>>> groups, long reportId) {
        for (var entry : groups.entrySet())
            if (entry.getValue().stream().anyMatch(r -> Db.id(r, "id") == reportId)) return entry.getKey();
        return null;
    }

    private long createIncident(Map<String, Object> report) {
        return db.insert("INSERT INTO incidents(category,subcategory,building,floor) VALUES(?,?,?,?)",
                report.get("category"), report.get("subcategory"), report.get("building"), report.get("floor"));
    }

    private void attach(Map<String, Object> report, long incidentId, long actorId) {
        if (report.get("incidentId") != null) return;
        if (db.update("UPDATE reports SET incident_id=? WHERE id=? AND incident_id IS NULL", incidentId, report.get("id")) != 1)
            conflict("다른 관리자가 사건 연결을 변경했습니다.");
        report.put("incidentId", incidentId);
        audit(Db.id(report, "id"), actorId, "INCIDENT_LINK", null, Long.toString(incidentId), "관리자 확인으로 사건 연결");
    }

    private Map<String, Object> report(long reportId, boolean lock) {
        var report = db.one("SELECT * FROM reports WHERE id=?" + (lock ? " FOR UPDATE" : ""), reportId);
        if (report == null) throw new ApiException(404, "REPORT_NOT_FOUND", "신고를 찾을 수 없습니다.");
        return report;
    }

    private Map<String, Object> incident(long incidentId, boolean lock) {
        var incident = db.one("SELECT * FROM incidents WHERE id=?" + (lock ? " FOR UPDATE" : ""), incidentId);
        if (incident == null) throw new ApiException(404, "INCIDENT_NOT_FOUND", "사건을 찾을 수 없습니다.");
        return incident;
    }

    private Map<Long, Map<String, Object>> lockIncidentIds(Object... ids) {
        SortedSet<Long> ordered = new TreeSet<>();
        for (Object id : ids) if (id != null) ordered.add(((Number) id).longValue());
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (long id : ordered) result.put(id, incident(id, true));
        return result;
    }

    private List<Map<String, Object>> members(long incidentId) {
        return db.all("SELECT * FROM reports WHERE incident_id=? ORDER BY id", incidentId);
    }

    private void requireCompatibleMembership(Map<String, Object> a, Map<String, Object> b) {
        if ("QUESTION".equals(a.get("requestKind")) || "QUESTION".equals(b.get("requestKind"))) conflict("일반 문의는 동일 사건으로 묶을 수 없습니다.");
        // A third report or a manual link must not silently undo an administrator's earlier pair decision.
        // This method also compares reports with incident rows, whose numeric ids are a different identity space.
        if (previouslySeparated(a, b))
            conflict("이미 별도 사건 또는 부적합 후보로 검토된 신고 관계입니다. 기존 검토 결과를 자동 변경할 수 없습니다.");
        if (!Objects.equals(a.get("category"), b.get("category")) || !Objects.equals(a.get("subcategory"), b.get("subcategory")))
            conflict("유형이 다른 신고는 같은 사건에 연결할 수 없습니다.");
        for (String field : List.of("building", "floor")) {
            String left = SimilarityService.normalized(a.get(field)), right = SimilarityService.normalized(b.get(field));
            if (field.equals("floor")) { left = left.replace("층", ""); right = right.replace("층", ""); }
            if (!left.isBlank() && !right.isBlank() && !left.equals(right)) conflict("장소가 다른 신고입니다. 위치를 확인해 주세요.");
        }
        String leftRoom = SimilarityService.room(a), rightRoom = SimilarityService.room(b);
        if (!leftRoom.isBlank() && !rightRoom.isBlank() && !leftRoom.equals(rightRoom)) conflict("호실이 서로 다릅니다.");
    }

    private boolean previouslySeparated(Map<String, Object> a, Map<String, Object> b) {
        if (!a.containsKey("originalText") || !b.containsKey("originalText")) return false;
        long first = Db.id(a, "id"), second = Db.id(b, "id");
        return first != second && db.one("SELECT id FROM similar_pairs WHERE left_report_id=? AND right_report_id=? "
                        + "AND decision IN ('RELATED_DISTINCT','REJECTED')", Math.min(first, second), Math.max(first, second)) != null;
    }

    private void updateCandidateAfterMembership(long incidentId) {
        var current = incident(incidentId, false);
        String decision = Db.str(current, "recurrenceDecision");
        if ("CONFIRMED".equals(decision) || "REJECTED".equals(decision)) return;
        db.update("UPDATE incidents SET previous_incident_id=NULL,recurrence_decision='NOT_EVALUATED' WHERE id=?", incidentId);
        suggestRecurrence(incidentId, members(incidentId));
    }

    private void requireConfirmedRecurrenceCompatible(long incidentId, List<Map<String, Object>> additions) {
        var current = incident(incidentId, false);
        if (!"CONFIRMED".equals(current.get("recurrenceDecision")) || current.get("previousIncidentId") == null) return;
        var all = new ArrayList<>(members(incidentId)); all.addAll(additions);
        if (!validPrevious(current, all, incident(Db.id(current, "previousIncidentId"), false)))
            conflict("연결하려는 신고의 발생 시점 또는 장소가 확정된 재발 관계와 충돌합니다.");
    }

    private void suggestRecurrence(long incidentId, List<Map<String, Object>> reports) {
        if (reports.isEmpty()) return;
        var previous = findPrevious(incident(incidentId, false), reports);
        if (previous != null)
            db.update("UPDATE incidents SET previous_incident_id=?,recurrence_decision='CANDIDATE' "
                    + "WHERE id=? AND recurrence_decision='NOT_EVALUATED'", previous.get("id"), incidentId);
    }

    private Map<String, Object> findPrevious(Map<String, Object> current, List<Map<String, Object>> reports) {
        for (var previous : db.all("SELECT * FROM incidents WHERE status='RESOLVED' AND category=? AND subcategory=? "
                        + "ORDER BY closed_at DESC,id DESC", current.get("category"), current.get("subcategory")))
            if (validPrevious(current, reports, previous)) return previous;
        return null;
    }

    private boolean validPrevious(Map<String, Object> current, List<Map<String, Object>> reports,
                                  Map<String, Object> previous) {
        if (reports.isEmpty() || !"RESOLVED".equals(previous.get("status")) || previous.get("closedAt") == null
                || !SimilarityService.compatiblePlace(current, previous)) return false;
        // A report id and an incident id use distinct identity spaces; membership determines self-reference.
        if (reports.stream().anyMatch(r -> r.get("incidentId") != null && Db.id(r, "incidentId") == Db.id(previous, "id"))) return false;
        LocalDateTime closed = SimilarityService.timestamp(previous.get("closedAt"));
        LocalDateTime now = LocalDateTime.now(SEOUL);
        if (closed.isAfter(now)) return false;
        var oldReports = members(Db.id(previous, "id"));
        if (oldReports.isEmpty()) return false;
        for (var report : reports) {
            if (oldReports.stream().anyMatch(old -> !SimilarityService.compatiblePlace(report, old))) return false;
            if (report.get("occurredOn") != null) {
                LocalDate date = LocalDate.parse(report.get("occurredOn").toString());
                // DATE precision cannot establish whether an occurrence on the closure day was later.
                if (!date.isAfter(closed.toLocalDate()) || date.isAfter(now.toLocalDate())) return false;
            } else {
                LocalDateTime received = SimilarityService.timestamp(report.get("createdAt"));
                if (!received.isAfter(closed) || received.isAfter(now)) return false;
            }
        }
        return true;
    }

    private void audit(long reportId, long actorId, String type, String oldValue, String newValue, String note) {
        db.insert("INSERT INTO report_events(report_id,actor_id,event_type,old_value,new_value,note) VALUES(?,?,?,?,?,?)",
                reportId, actorId, type, oldValue, newValue, note);
    }

    private static String timeBasis(List<Map<String, Object>> reports) {
        return reports.stream().allMatch(r -> r.get("occurredOn") != null) ? "CONFIRMED_OCCURRENCE" : "REPORTED_AT_ONLY";
    }

    private static String recurrenceReason(List<Map<String, Object>> reports, boolean previous) {
        return (previous ? "같은 유형·장소의 과거 종료 사건과 비교한 관계이며 최종 판단은 관리자 검토 결과를 따릅니다. 미래 고장 예측이 아닙니다. " : "연결된 과거 종료 사건이 없습니다. ")
                + ("REPORTED_AT_ONLY".equals(timeBasis(reports))
                ? "발생일 미확인: 접수 시각만으로 비교했으며 실제 재발 시점은 불확실합니다." : "사용자가 입력한 발생일 기준이며 관리자 확인이 필요합니다.");
    }

    private static void removeEmbeddings(Map<String, Object> report) {
        report.remove("embeddingJson"); report.remove("embeddingModel");
    }

    private static void conflict(String message) { throw new ApiException(409, "INCIDENT_CONFLICT", message); }
    private static void invalidRecurrence(String message) { throw new ApiException(422, "INVALID_RECURRENCE_REFERENCE", message); }
}
