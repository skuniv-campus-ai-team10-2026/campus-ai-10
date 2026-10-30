package edu.skope.service;

import edu.skope.config.SeedData;
import edu.skope.repository.Db;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL against an isolated H2 database; no AI calls or shared development data. */
class RoutingServiceTest {
    private static final String PASSWORD="test-only-routing-password";

    private Db fixture(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:routing_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"),
                new ClassPathResource("db/migration/V2__incidents.sql"),
                new ClassPathResource("db/migration/V3__intake_context.sql"),
                new ClassPathResource("db/migration/V4__department_workflow.sql"),
                new ClassPathResource("db/migration/V11__work_group_routing.sql")).execute(source);
        return new Db(new JdbcTemplate(source));
    }
    private void seed(Db db,boolean enabled,String password){
        var encoder=mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(call->"test-hash:"+call.getArgument(0));
        new SeedData(db,encoder,password,enabled).run();
    }
    private Map<String,Object> leak(Db db){return db.one("SELECT * FROM routing_rules WHERE subcategory='LEAK'");}

    @Test void defaultModeKeepsOnlyThreeVerifiedRoutesAndNoDepartmentAccounts(){
        var db=fixture();seed(db,false,PASSWORD);var routes=new RoutingService(db,false);
        assertEquals(3,routes.types().size());assertNull(routes.find("FACILITY","LEAK"));
        assertTrue(routes.types().stream().allMatch(rule->Boolean.TRUE.equals(rule.get("verified"))&&"VERIFIED".equals(rule.get("assignmentBasis"))));
        assertTrue(db.all("SELECT * FROM users WHERE role='DEPT_ADMIN'").isEmpty());
        assertThrows(ApiException.class,()->routes.byId(Db.id(leak(db),"id")));
    }

    @Test void demoModeAssignsKnownLeakWithoutPretendingItsContactIsVerified(){
        var db=fixture();seed(db,true,PASSWORD);var routes=new RoutingService(db,true);var rule=routes.find("FACILITY","LEAK");
        // 4 original routes + demo-only HVAC and WATER_DISPENSER added by SeedData.addMissingDemoRoutes (FURNITURE has no rule).
        assertNotNull(rule);assertEquals(6,routes.types().size());
        for(var value:new Map[]{routes.preview(rule),routes.department(rule),routes.types().stream().filter(r->"LEAK".equals(r.get("subcategory"))).findFirst().orElseThrow()}){
            assertEquals(false,value.get("verified"));assertEquals("DEMO",value.get("assignmentBasis"));
            assertEquals(true,value.get("demoOnly"));assertNotNull(value.get("warning"));
            assertNull(value.get("contactPhone"));assertNull(value.get("sourceUrl"));
        }
        assertEquals("MATCHED",routes.preview(rule).get("routeStatus"));
        var answer=routes.answer(rule,"누수는 어디로 연락하면 되나요?");assertEquals(false,answer.get("verified"));
        assertFalse(answer.containsKey("departmentName"));assertFalse(answer.get("text").toString().contains("02-940"));
        assertEquals(4,db.all("SELECT * FROM users WHERE role='DEPT_ADMIN'").size());
        assertNull(routes.find("FACILITY","UNKNOWN"));
    }

    @Test void upgradingExistingDirectoryCreatesAccountsAndPreservesUserChanges(){
        var db=fixture();seed(db,false,PASSWORD);
        var originalVerified=db.all("SELECT * FROM routing_rules WHERE subcategory<>'LEAK' ORDER BY id");
        db.update("UPDATE routing_rules SET demo_only=FALSE,active=FALSE WHERE subcategory='LEAK'");
        seed(db,true,PASSWORD);
        assertEquals(originalVerified,db.all("SELECT * FROM routing_rules WHERE subcategory<>'LEAK' ORDER BY id"));
        // 4 original rows (names kept) + 10 core departments from the official chart (3 chart names already existed).
        assertEquals(14,db.all("SELECT * FROM departments").size());
        var deptAccount=db.one("SELECT * FROM users WHERE username='dept1'");assertEquals(1L,Db.id(deptAccount,"departmentId"));
        db.update("UPDATE users SET password_hash='changed-hash',department_id=2,active=FALSE,display_name='보존 이름' WHERE username='dept1'");
        db.update("UPDATE routing_rules SET active=FALSE WHERE subcategory='LEAK'");
        long extra=db.insert("INSERT INTO departments(name) VALUES(?)","추가 등록된 시연 부서");
        long inactive=db.insert("INSERT INTO departments(name,active) VALUES(?,FALSE)","비활성 부서");
        seed(db,true,"different-test-password");
        var preserved=db.one("SELECT * FROM users WHERE username='dept1'");
        assertEquals("changed-hash",preserved.get("passwordHash"));assertEquals(2L,Db.id(preserved,"departmentId"));
        assertEquals(false,preserved.get("active"));assertEquals("보존 이름",preserved.get("displayName"));
        // Since the official department list (10/30), a department gets an account only once a rule routes to it.
        assertNull(db.one("SELECT id FROM users WHERE username=?","dept"+extra));
        db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,target_key,active,demo_only) VALUES(?,?,?,?,?,?,?)","WORK_GROUP","SHARED_EQUIPMENT","추가 부서 예외",extra,"시험대상",true,true);
        seed(db,true,"different-test-password");
        assertNotNull(db.one("SELECT id FROM users WHERE username=?","dept"+extra));
        assertNull(db.one("SELECT id FROM users WHERE username=?","dept"+inactive));
        assertNull(new RoutingService(db,true).find("FACILITY","LEAK"));
    }

    @Test void officialCoreDepartmentsAreAddedOnceWithoutAccountsOrChangesToExistingRows(){
        var db=fixture();seed(db,true,PASSWORD);
        var original=db.all("SELECT * FROM departments WHERE id<=4 ORDER BY id");
        var official=db.all("SELECT * FROM departments WHERE source_url=?","https://www.skuniv.ac.kr/organization-phone");
        assertEquals(10,official.size(),"10 new core departments; 총무처 건설관리과·정보전산원·교무처 교무과 already existed");
        assertTrue(official.stream().allMatch(d->d.get("verifiedAt")!=null&&d.get("description")!=null));
        assertNotNull(db.one("SELECT id FROM departments WHERE name='학생처 장학과' AND description='대학본부 > 학생처'"));
        assertEquals(4,db.all("SELECT * FROM users WHERE role='DEPT_ADMIN'").size(),"no account for a department without a rule");
        seed(db,true,PASSWORD);
        assertEquals(14,db.all("SELECT * FROM departments").size(),"idempotent on every start");
        assertEquals(original,db.all("SELECT * FROM departments WHERE id<=4 ORDER BY id"),"original rows untouched");
    }

    @Test void demoFlagCannotBypassInactiveRuleOrDepartmentOrOrdinaryMissingEvidence(){
        var db=fixture();seed(db,true,PASSWORD);var routes=new RoutingService(db,true);
        db.update("UPDATE routing_rules SET active=FALSE WHERE subcategory='LEAK'");assertNull(routes.find("FACILITY","LEAK"));
        db.update("UPDATE routing_rules SET active=TRUE WHERE subcategory='LEAK'");
        db.update("UPDATE departments SET active=FALSE WHERE id=?",leak(db).get("departmentId"));assertNull(routes.find("FACILITY","LEAK"));
        db.update("UPDATE routing_rules SET source_url=NULL WHERE subcategory='WIFI'");assertNull(routes.find("IT","WIFI"));
        db.update("UPDATE routing_rules SET source_url=' ' WHERE subcategory='ELECTRIC'");assertNull(routes.find("FACILITY","ELECTRIC"));
    }

    @Test void DemoRulesStayDemoEvenIfTheirSourceFieldsArePopulated(){
        var db=fixture();seed(db,true,PASSWORD);
        db.update("UPDATE routing_rules SET source_url='https://example.test/unverified',verified_at=CURRENT_TIMESTAMP WHERE subcategory='LEAK'");
        assertNull(new RoutingService(db,false).find("FACILITY","LEAK"));
        var routes=new RoutingService(db,true);var rule=routes.find("FACILITY","LEAK");
        assertEquals(false,routes.department(rule).get("verified"));assertNull(routes.department(rule).get("sourceUrl"));
        assertEquals(false,routes.answer(rule,"담당 부서가 어디인가요?").get("verified"));
    }

    @Test void noPasswordNeverCreatesAccountsAndCustomLeakMappingIsNotConverted(){
        var db=fixture();seed(db,false,"");assertTrue(db.all("SELECT * FROM users").isEmpty());
        db.update("UPDATE routing_rules SET demo_only=FALSE,active=FALSE,work_name='별도 검토 중인 경로' WHERE subcategory='LEAK'");
        seed(db,true,"");assertTrue(db.all("SELECT * FROM users").isEmpty());
        assertEquals(false,leak(db).get("demoOnly"));assertEquals(false,leak(db).get("active"));
        assertNull(new RoutingService(db,true).find("FACILITY","LEAK"));
    }
}
