package edu.skope.config;
import edu.skope.domain.Codes;
import edu.skope.repository.Db;
import edu.skope.service.RoutingService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
@Component
public class SeedData implements CommandLineRunner {
    @Value("${app.demo.reset-password:false}") private boolean resetPassword;
    private final Db db; private final PasswordEncoder encoder; private final String password; private final boolean demoRouting;
    public SeedData(Db db,PasswordEncoder encoder,@Value("${DEMO_PASSWORD:}") String password,@Value("${app.routing.demo-enabled:false}") boolean demoRouting){this.db=db;this.encoder=encoder;this.password=password;this.demoRouting=demoRouting;}
    @Override public void run(String...args){
        if(!password.isBlank()){
            if(password.length()<12)throw new IllegalStateException("DEMO_PASSWORD must have at least 12 characters");
            for(String username:new String[]{"student1","student2","admin"}){
                if(db.one("SELECT id FROM users WHERE username=?",username)==null)
                    db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)",username,encoder.encode(password),username.equals("admin")?"시연 관리자":"시연 학생 "+username.substring(7),username.equals("admin")?"ADMIN":"STUDENT");
            }
        }
        if(resetPassword&&!password.isBlank()) {
            for(String username:new String[]{"student1","student2","admin"})
                db.update("UPDATE users SET password_hash=? WHERE username=?",encoder.encode(password),username);
        }
        if(db.one("SELECT id FROM departments LIMIT 1")==null)seedDirectory();
        addMissingOfficialDepartments();
        addMissingDemoRoutes();
        addMissingWorkGroupDefaults();
        // Upgrade only the original unverified sample. Never relabel a verified/custom route
        // or re-enable a demo rule that an administrator has subsequently disabled.
        db.update("UPDATE routing_rules SET demo_only=TRUE,active=TRUE WHERE category='FACILITY' AND subcategory='LEAK' AND work_name='누수 담당 업무 미검증' AND source_url IS NULL AND verified_at IS NULL AND demo_only=FALSE AND department_id IN (SELECT id FROM departments WHERE name='총무처 건설관리과')");
        if(demoRouting&&!password.isBlank()){
            // Accounts only for departments that can actually receive reports (an active routing rule points to them).
            for(var department:db.all("SELECT d.id,d.name FROM departments d WHERE d.active=TRUE AND EXISTS (SELECT 1 FROM routing_rules rr WHERE rr.department_id=d.id AND rr.active=TRUE) ORDER BY d.id")){
                long departmentId=Db.id(department,"id");String username="dept"+departmentId;
                if(db.one("SELECT id FROM users WHERE username=?",username)==null)
                    db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)",username,encoder.encode(password),"시연 "+department.get("name")+" 담당자","DEPT_ADMIN",departmentId);
            }
        }
    }
    private void seedDirectory(){
        long it=dept("정보전산원","https://www.skuniv.ac.kr/campus-network",true);
        long electric=dept("건설관리과 / 전기실","https://www.skuniv.ac.kr/notice/58965",true);
        long general=dept("총무처 건설관리과",null,false);
        long academic=dept("교무처 교무과","https://www.skuniv.ac.kr/notice/54522",true);
        rule("IT","WIFI","유·무선 인터넷 문의",it,"02-940-7043","https://www.skuniv.ac.kr/campus-network",true);
        rule("FACILITY","ELECTRIC","교내 전기시설 문의",electric,"02-940-7049","https://www.skuniv.ac.kr/notice/58965",true);
        rule("FACILITY","LEAK","누수 담당 업무 미검증",general,"02-940-7028",null,false);
        rule("ACADEMIC","STUDENT_RECORD","학적 문의 (절차 답변 제외)",academic,"02-940-7020","https://www.skuniv.ac.kr/notice/54522",true);
    }
    /**
     * Routes added after the first release. Runs on every start but only inserts a type that has no rule yet,
     * so existing databases receive it and an administrator's later changes are never overwritten.
     * No official source names the handling office for these tasks, so they are demo-only (assigned only when
     * DEMO_ROUTING_ENABLED=true) and carry no contact number. FURNITURE deliberately has no rule until confirmed.
     */
    private void addMissingDemoRoutes(){
        String office="총무처 건설관리과";
        var department=db.one("SELECT id FROM departments WHERE name=?",office);
        long departmentId=department!=null&&department.get("id")!=null?Db.id(department,"id"):db.insert("INSERT INTO departments(name,source_url,verified_at) VALUES(?,?,?)",office,null,null);
        demoRule("FACILITY","HVAC","냉난방 담당 업무 미검증",departmentId);
        demoRule("FACILITY","WATER_DISPENSER","정수기 담당 업무 미검증",departmentId);
    }
    /**
     * Core administrative departments from the university's official organization chart (seed/official-departments.tsv).
     * Inserted only when the name is missing; existing rows, including the original directory, are never changed.
     * A department alone receives no reports and gets no account until a routing rule points to it.
     */
    private void addMissingOfficialDepartments(){
        try(var in=SeedData.class.getResourceAsStream("/seed/official-departments.tsv")){
            if(in==null)return;
            String source=null;
            for(String line:new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\\R")){
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] cell=line.split("\t",2);
                if(cell[0].equals("SOURCE")){source=cell[1].strip();continue;}
                if(db.one("SELECT id FROM departments WHERE name=?",cell[0].strip())!=null)continue;
                db.insert("INSERT INTO departments(name,description,source_url,verified_at) VALUES(?,?,?,?)",cell[0].strip(),cell.length>1?cell[1].strip():null,source,"2026-10-30 00:00:00");
            }
        }catch(java.io.IOException e){throw new IllegalStateException("Official department list unreadable",e);}
    }
    private static final Map<String,String> GROUP_NAMES=Map.of("IT_SUPPORT","IT 지원","ELECTRICAL_MAINTENANCE","전기","FACILITY_MAINTENANCE","시설 보수","SHARED_EQUIPMENT","공용 비품","ACADEMIC_ADMIN","학사 행정");
    /**
     * Work-group default rules, derived from the existing type rules instead of per-object code: when every routed
     * type of a group points to one department, that department becomes the group's default. It is demo-only
     * (no official source says the office handles the whole group) and carries no contact. Inserted once per group;
     * administrator changes, disabled rules and explicit exceptions are never overwritten. GENERAL_REVIEW has none.
     */
    private void addMissingWorkGroupDefaults(){
        for(var group:GROUP_NAMES.entrySet()){
            if(db.one("SELECT id FROM routing_rules WHERE category=? AND subcategory=? AND target_key=''",RoutingService.WORK_GROUP,group.getKey())!=null)continue;
            var departments=new LinkedHashSet<Long>();
            for(var type:Codes.TYPE_GROUPS.entrySet()){
                if(!type.getValue().equals(group.getKey()))continue;
                var rule=db.one("SELECT department_id FROM routing_rules WHERE category=? AND subcategory=? AND target_key='' AND active=TRUE",Codes.TYPES.get(type.getKey()),type.getKey());
                if(rule!=null)departments.add(Db.id(rule,"departmentId"));
            }
            if(departments.size()!=1)continue;
            db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,contact_phone,source_url,verified_at,active,demo_only) VALUES(?,?,?,?,?,?,?,?,?)",
                    RoutingService.WORK_GROUP,group.getKey(),group.getValue()+" 기본 배정 (담당 업무 미검증)",departments.iterator().next(),null,null,null,true,true);
        }
    }
    private void demoRule(String c,String s,String work,long dep){
        if(db.one("SELECT id FROM routing_rules WHERE category=? AND subcategory=? AND target_key=''",c,s)!=null)return;
        db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,contact_phone,source_url,verified_at,active,demo_only) VALUES(?,?,?,?,?,?,?,?,?)",c,s,work,dep,null,null,null,true,true);
    }
    private long dept(String name,String source,boolean verified){return db.insert("INSERT INTO departments(name,source_url,verified_at) VALUES(?,?,?)",name,source,verified?"2026-10-10 17:00:00":null);}
    private void rule(String c,String s,String work,long dep,String phone,String source,boolean verified){db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,contact_phone,source_url,verified_at,active) VALUES(?,?,?,?,?,?,?,?)",c,s,work,dep,phone,source,verified?"2026-10-10 17:00:00":null,verified);}
}
