package edu.skope.service;

import edu.skope.controller.AuthController;
import edu.skope.repository.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated H2 tests for scope and workflow; these do not claim live AI or MySQL verification. */
class DepartmentWorkflowTest {
    private Db db;
    private DepartmentAccess access;
    private ReportService reports;
    private RoutingService routes;
    private IncidentService incidents;
    private TransactionTemplate transaction;
    private long student,supervisor,it,facilities,itAdmin,facilitiesAdmin,itRule,facilitiesRule;

    @BeforeEach void setUp() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:department_"+UUID.randomUUID()
                +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"),
                new ClassPathResource("db/migration/V2__incidents.sql"),
                new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
        db=new Db(new JdbcTemplate(source));
        student=user("student","STUDENT",null);
        supervisor=user("supervisor","ADMIN",null);
        it=db.insert("INSERT INTO departments(name) VALUES(?)","테스트 전산부");
        facilities=db.insert("INSERT INTO departments(name) VALUES(?)","테스트 시설부");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V4__department_workflow.sql")).execute(source);
        itAdmin=user("it","DEPT_ADMIN",it);facilitiesAdmin=user("facilities","DEPT_ADMIN",facilities);
        itRule=rule("IT","WIFI",it);facilitiesRule=rule("FACILITY","LEAK",facilities);
        access=new DepartmentAccess(db);routes=mock(RoutingService.class);incidents=mock(IncidentService.class);
        when(routes.byId(itRule)).thenAnswer(call->db.one("SELECT * FROM routing_rules WHERE id=?",itRule));
        when(routes.byId(facilitiesRule)).thenAnswer(call->db.one("SELECT * FROM routing_rules WHERE id=?",facilitiesRule));
        reports=new ReportService(db,routes,incidents,access);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test void migrationPreservesExistingAccountsAndLeavesSupervisorDepartmentOptional() {
        assertNull(db.one("SELECT department_id FROM users WHERE id=?",supervisor).get("departmentId"));
        assertTrue(access.isSupervisor(supervisor));assertNull(access.departmentId(supervisor));
        assertEquals(it,access.departmentId(itAdmin));
        assertFalse(Boolean.TRUE.equals(db.one("SELECT demo_only FROM routing_rules WHERE id=?",itRule).get("demoOnly")));
    }

    @Test void departmentListAndDetailsExcludeOtherDepartmentsAndUnassignedReports() {
        long own=report(it),other=report(facilities),pending=report(null);
        var list=reports.list(itAdmin,true,0,20,null,null);
        assertEquals(1L,list.get("totalElements"));
        assertEquals(own,((List<Map<String,Object>>)list.get("items")).getFirst().get("id"));
        assertEquals(own,reports.detail(own,itAdmin,true).get("id"));
        assertApi(404,"REPORT_NOT_FOUND",()->reports.detail(other,itAdmin,true));
        assertApi(404,"REPORT_NOT_FOUND",()->reports.detail(pending,itAdmin,true));
        assertEquals(3L,reports.list(supervisor,true,0,20,null,null).get("totalElements"));
        assertEquals(3L,reports.list(student,false,0,20,null,null).get("totalElements"));
    }

    @Test void foreignStatusMutationAndDepartmentReassignmentAreRejectedWithoutChanges() {
        long foreign=report(facilities),own=report(it);
        assertApi(404,"REPORT_NOT_FOUND",()->tx(()->reports.status(foreign,itAdmin,Map.of("status","IN_PROGRESS"))));
        assertApi(403,"FORBIDDEN",()->tx(()->reports.routing(own,itAdmin,facilitiesRule)));
        assertEquals("RECEIVED",db.one("SELECT status FROM reports WHERE id=?",foreign).get("status"));
        assertEquals(it,db.one("SELECT assigned_department_id FROM reports WHERE id=?",own).get("assignedDepartmentId"));
        verifyNoInteractions(incidents);
    }

    @Test void departmentCanProcessOwnedReportAndStudentCannotUseAdminServicePath() {
        long own=report(it);
        assertEquals("IN_PROGRESS",tx(()->reports.status(own,itAdmin,Map.of("status","IN_PROGRESS"))).get("status"));
        assertEquals("RESOLVED",tx(()->reports.status(own,itAdmin,Map.of("status","RESOLVED","resolutionText","장비 점검 완료"))).get("status"));
        assertApi(403,"FORBIDDEN",()->reports.detail(own,student,true));
        assertApi(403,"FORBIDDEN",()->reports.list(student,true,0,20,null,null));
    }

    @Test void changedMembershipAndDisabledDepartmentAreReadFreshFromDatabase() {
        long own=report(it);
        assertTrue(access.canReadReport(itAdmin,own));
        db.update("UPDATE users SET department_id=? WHERE id=?",facilities,itAdmin);
        assertFalse(access.canReadReport(itAdmin,own));
        db.update("UPDATE departments SET active=FALSE WHERE id=?",facilities);
        assertApi(403,"FORBIDDEN",()->access.canReadReport(itAdmin,own));
        assertApi(403,"FORBIDDEN",()->reports.list(itAdmin,true,0,20,null,null));
        db.update("UPDATE users SET active=FALSE WHERE id=?",supervisor);
        assertApi(403,"FORBIDDEN",()->access.isSupervisor(supervisor));
    }

    @Test void missingDepartmentCannotBecomeUnscopedAdministrator() {
        db.update("UPDATE users SET department_id=NULL WHERE id=?",itAdmin);
        assertApi(403,"FORBIDDEN",()->access.departmentId(itAdmin));
        assertApi(403,"FORBIDDEN",()->reports.list(itAdmin,true,0,20,null,null));
    }

    @Test void transferRequestKeepsOwnerAndStatusThenSupervisorReassignmentClosesRequest() {
        long own=report(it);
        var pending=tx(()->reports.requestTransfer(own,itAdmin,Map.of("reason","통신 문제가 아니라 배선 공사 확인이 필요합니다.")));
        assertEquals(true,pending.get("transferRequested"));assertEquals("RECEIVED",pending.get("status"));
        assertEquals(it,pending.get("assignedDepartmentId"));
        assertEquals(1L,reports.list(supervisor,true,0,20,null,null,true).get("totalElements"));
        assertApi(403,"FORBIDDEN",()->reports.list(itAdmin,true,0,20,null,null,true));
        assertApi(409,"TRANSFER_ALREADY_REQUESTED",()->tx(()->reports.requestTransfer(own,itAdmin,Map.of("reason","중복 요청"))));
        assertEquals(1L,Db.id(db.one("SELECT COUNT(*) total FROM report_events WHERE event_type='TRANSFER_REQUESTED'"),"total"));
        assertFalse(reports.detail(own,student,false).containsKey("transferReason"));
        var moved=tx(()->reports.routing(own,supervisor,facilitiesRule,"시설 부서에서 현장 확인하기로 했습니다."));
        assertEquals(facilities,moved.get("assignedDepartmentId"));assertEquals(false,moved.get("transferRequested"));
        assertEquals(0L,reports.list(supervisor,true,0,20,null,null,true).get("totalElements"));
        assertApi(404,"REPORT_NOT_FOUND",()->reports.detail(own,itAdmin,true));
        assertEquals(own,reports.detail(own,facilitiesAdmin,true).get("id"));
        assertEquals("시설 부서에서 현장 확인하기로 했습니다.",db.one("SELECT note FROM report_events WHERE report_id=? AND event_type='ROUTING_CHANGED'",own).get("note"));
    }

    @Test void transferRequiresReasonOwnedUnresolvedReportAndDoesNotFreezeExistingProcessing() {
        long own=report(it),other=report(facilities);
        assertApi(400,"VALIDATION_ERROR",()->tx(()->reports.requestTransfer(own,itAdmin,Map.of("reason","   "))));
        assertApi(404,"REPORT_NOT_FOUND",()->tx(()->reports.requestTransfer(other,itAdmin,Map.of("reason","검토 필요"))));
        tx(()->reports.requestTransfer(own,itAdmin,Map.of("reason","배정 검토 필요")));
        tx(()->reports.status(own,itAdmin,Map.of("status","IN_PROGRESS")));
        var completed=tx(()->reports.status(own,itAdmin,Map.of("status","RESOLVED","resolutionText","자체 확인으로 해결")));
        assertEquals(false,completed.get("transferRequested"));
        assertEquals(0L,reports.list(supervisor,true,0,20,null,null,true).get("totalElements"));
        assertApi(409,"REPORT_ALREADY_RESOLVED",()->tx(()->reports.requestTransfer(own,itAdmin,Map.of("reason","다시 요청"))));
    }

    @Test void pairNeedsAccessToBothReportsAndMixedIncidentIsHidden() {
        long a=report(it),b=report(it),foreign=report(facilities);
        long pair=db.insert("INSERT INTO similar_pairs(left_report_id,right_report_id) VALUES(?,?)",a,foreign);
        assertApi(404,"REPORT_NOT_FOUND",()->access.requirePair(itAdmin,pair));
        access.requirePair(supervisor,pair);
        long incident=incident(a,b);access.requireIncident(itAdmin,incident);
        db.update("UPDATE reports SET incident_id=? WHERE id=?",incident,foreign);
        assertFalse(access.canReadIncident(itAdmin,incident));
        access.requireIncident(supervisor,incident);
        assertApi(404,"INCIDENT_NOT_FOUND",()->tx(()->reports.status(a,itAdmin,Map.of("status","IN_PROGRESS"))));
        assertEquals("RECEIVED",db.one("SELECT status FROM reports WHERE id=?",a).get("status"));
    }

    @Test void supervisorCannotSplitOneIncidentAcrossDepartmentScopes() {
        long a=report(it),b=report(it);incident(a,b);
        assertApi(409,"INCIDENT_DEPARTMENT_CONFLICT",()->tx(()->reports.routing(a,supervisor,facilitiesRule)));
        assertEquals(it,db.one("SELECT assigned_department_id FROM reports WHERE id=?",a).get("assignedDepartmentId"));
        long single=report(it);incident(single);
        assertEquals(facilities,tx(()->reports.routing(single,supervisor,facilitiesRule)).get("assignedDepartmentId"));
    }

    @Test void authViewReturnsDepartmentForStaffAndNullForSupervisorAndStudents() {
        var auth=new AuthController(db,mock(PasswordEncoder.class),new HttpSessionSecurityContextRepository(),new HttpSessionCsrfTokenRepository());
        var staff=auth.me(new UsernamePasswordAuthenticationToken("it",null));
        assertEquals("DEPT_ADMIN",staff.get("role"));assertEquals(it,staff.get("departmentId"));assertEquals("테스트 전산부",staff.get("departmentName"));
        var admin=auth.me(new UsernamePasswordAuthenticationToken("supervisor",null));
        assertTrue(admin.containsKey("departmentId"));assertNull(admin.get("departmentName"));
        assertNull(auth.me(new UsernamePasswordAuthenticationToken("student",null)).get("departmentId"));
    }

    private long user(String username,String role,Long department) {
        long id=db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)",username,"unused-test-hash",username,role);
        if(department!=null)db.update("UPDATE users SET department_id=? WHERE id=?",department,id);
        return id;
    }
    private long rule(String category,String subcategory,long department) {
        return db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,source_url,verified_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",category,subcategory,"테스트 업무",department,"https://example.test/source");
    }
    private long report(Long department) {
        return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,symptom,assigned_department_id) VALUES(?,?,?,?,?,?,?)",student,UUID.randomUUID().toString(),"북악관 502호에서 와이파이가 끊겨요","IT","WIFI","와이파이 끊김",department);
    }
    private long incident(long... members) {
        long id=db.insert("INSERT INTO incidents(category,subcategory) VALUES(?,?)","IT","WIFI");
        for(long report:members)db.update("UPDATE reports SET incident_id=? WHERE id=?",id,report);
        return id;
    }
    private <T> T tx(Supplier<T> work){return transaction.execute(status->work.get());}
    private static void assertApi(int status,String code,org.junit.jupiter.api.function.Executable action){
        var ex=assertThrows(ApiException.class,action);assertEquals(status,ex.status);assertEquals(code,ex.code);
    }
}
