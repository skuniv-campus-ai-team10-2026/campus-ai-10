package edu.skope.service;

import edu.skope.repository.Db;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Department scope is read from the database on every request, never accepted from the browser. */
@Service
public class DepartmentAccess {
    private final Db db;
    public DepartmentAccess(Db db) { this.db = db; }

    private Map<String,Object> actor(long actorId) {
        var user = db.one("SELECT u.role,u.department_id,d.active department_active FROM users u "
                + "LEFT JOIN departments d ON d.id=u.department_id WHERE u.id=? AND u.active=TRUE", actorId);
        if (user == null || !("ADMIN".equals(user.get("role")) || "DEPT_ADMIN".equals(user.get("role"))))
            throw forbidden();
        if ("DEPT_ADMIN".equals(user.get("role")) && (user.get("departmentId") == null
                || !truthy(user.get("departmentActive")))) throw forbidden();
        return user;
    }

    public boolean isSupervisor(long actorId) { return "ADMIN".equals(actor(actorId).get("role")); }
    public Long departmentId(long actorId) {
        var user = actor(actorId);
        return "ADMIN".equals(user.get("role")) ? null : Db.id(user, "departmentId");
    }
    public void requireSupervisor(long actorId) { if (!isSupervisor(actorId)) throw forbidden(); }

    public void requireReport(long actorId, long reportId) {
        Long department = departmentId(actorId);
        var report = db.one("SELECT assigned_department_id FROM reports WHERE id=?", reportId);
        if (report == null || (department != null && (report.get("assignedDepartmentId") == null
                || Db.id(report, "assignedDepartmentId") != department)))
            throw new ApiException(404, "REPORT_NOT_FOUND", "접근 가능한 신고를 찾을 수 없습니다.");
    }

    public boolean canReadReport(long actorId, long reportId) {
        try { requireReport(actorId, reportId); return true; }
        catch (ApiException ex) { if (ex.status == 404) return false; throw ex; }
    }

    /** An incident is private if even one of its reports belongs to another department or is unassigned. */
    public void requireIncident(long actorId, long incidentId) {
        Long department = departmentId(actorId);
        if (db.one("SELECT id FROM incidents WHERE id=?", incidentId) == null)
            throw new ApiException(404, "INCIDENT_NOT_FOUND", "접근 가능한 사건을 찾을 수 없습니다.");
        if (department == null) return;
        var scope = db.one("SELECT COUNT(*) total,SUM(CASE WHEN assigned_department_id=? THEN 1 ELSE 0 END) allowed "
                + "FROM reports WHERE incident_id=?", department, incidentId);
        if (Db.id(scope, "total") == 0 || scope.get("allowed") == null
                || Db.id(scope, "total") != Db.id(scope, "allowed"))
            throw new ApiException(404, "INCIDENT_NOT_FOUND", "접근 가능한 사건을 찾을 수 없습니다.");
    }

    public boolean canReadIncident(long actorId, long incidentId) {
        try { requireIncident(actorId, incidentId); return true; }
        catch (ApiException ex) { if (ex.status == 404) return false; throw ex; }
    }

    public void requirePair(long actorId, long pairId) {
        departmentId(actorId);
        var pair = db.one("SELECT left_report_id,right_report_id FROM similar_pairs WHERE id=?", pairId);
        if (pair == null) throw new ApiException(404, "REPORT_NOT_FOUND", "접근 가능한 유사 후보를 찾을 수 없습니다.");
        requireReport(actorId, Db.id(pair, "leftReportId"));
        requireReport(actorId, Db.id(pair, "rightReportId"));
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }
    private static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "활성 관리자 계정과 소속 부서를 확인하세요.");
    }
}
