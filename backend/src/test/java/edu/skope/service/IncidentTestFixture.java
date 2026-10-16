package edu.skope.service;

import edu.skope.repository.Db;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Isolated H2/MySQL compatibility tests. These do not claim validation against a MySQL server. */
final class IncidentTestFixture {
    final Db db;
    final IncidentService incidents;
    final TransactionTemplate transaction;
    final long student;
    final long admin;

    IncidentTestFixture() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:incidents_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"),
                new ClassPathResource("db/migration/V2__incidents.sql"),
                new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
        db = new Db(new JdbcTemplate(source));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        incidents = new IncidentService(db);
        student = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)",
                "fixture-student", "unused-test-hash", "테스트 학생", "STUDENT");
        admin = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)",
                "fixture-admin", "unused-test-hash", "테스트 관리자", "ADMIN");
    }

    long report(String category, String subtype, String building, String floor, String location, LocalDate occurredOn) {
        return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,"
                        + "location_text,symptom,occurred_on) VALUES(?,?,?,?,?,?,?,?,?,?)", student, UUID.randomUUID().toString(),
                location + "에서 문제가 발생했어요", category, subtype, building, floor, location, "연결이 끊겨요", occurredOn);
    }

    long wifi() { return report("IT", "WIFI", "북악관", "5", "북악관 502호", null); }
    long wifi(LocalDate day) { return report("IT", "WIFI", "북악관", "5", "북악관 502호", day); }
    long pair(long first, long second) {
        return db.insert("INSERT INTO similar_pairs(left_report_id,right_report_id,reason_text) VALUES(?,?,?)",
                Math.min(first, second), Math.max(first, second), "테스트용 규칙 후보");
    }
    Map<String, Object> report(long id) { return db.one("SELECT * FROM reports WHERE id=?", id); }
    long incidentId(long reportId) { return Db.id(report(reportId), "incidentId"); }
    <T> T tx(Supplier<T> work) { return transaction.execute(status -> work.get()); }
    void start(long reportId) { tx(() -> { incidents.start(reportId, admin); return null; }); }
    void resolve(long reportId) {
        tx(() -> {
            db.one("SELECT id FROM reports WHERE id=? FOR UPDATE", reportId);
            db.update("UPDATE reports SET status='RESOLVED',resolution_text='테스트 처리 결과' WHERE id=?", reportId);
            incidents.refresh(incidentId(reportId));
            return null;
        });
    }
    long pastIncident() {
        long reportId = wifi(today().minusDays(3));
        start(reportId); resolve(reportId);
        long incidentId = incidentId(reportId);
        db.update("UPDATE incidents SET created_at=?,closed_at=? WHERE id=?",
                Timestamp.valueOf(today().minusDays(3).atTime(10, 0)),
                Timestamp.valueOf(today().minusDays(2).atTime(15, 0)), incidentId);
        db.update("UPDATE reports SET created_at=? WHERE id=?", Timestamp.valueOf(today().minusDays(3).atTime(10, 0)), reportId);
        return incidentId;
    }
    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
}
