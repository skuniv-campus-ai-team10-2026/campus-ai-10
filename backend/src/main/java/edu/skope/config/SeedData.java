package edu.skope.config;
import edu.skope.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
@Component
public class SeedData implements CommandLineRunner {
    private final Db db; private final PasswordEncoder encoder; private final String password;
    public SeedData(Db db,PasswordEncoder encoder,@Value("${DEMO_PASSWORD:}") String password){this.db=db;this.encoder=encoder;this.password=password;}
    @Override public void run(String...args){
        if(!password.isBlank()){
            if(password.length()<12)throw new IllegalStateException("DEMO_PASSWORD must have at least 12 characters");
            for(String username:new String[]{"student1","student2","admin"}){
                if(db.one("SELECT id FROM users WHERE username=?",username)==null)
                    db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)",username,encoder.encode(password),username.equals("admin")?"시연 관리자":"시연 학생 "+username.substring(7),username.equals("admin")?"ADMIN":"STUDENT");
            }
        }
        if(db.one("SELECT id FROM departments LIMIT 1")!=null)return;
        long it=dept("정보전산원","https://www.skuniv.ac.kr/campus-network",true);
        long electric=dept("건설관리과 / 전기실","https://www.skuniv.ac.kr/notice/58965",true);
        long general=dept("총무처 건설관리과",null,false);
        long academic=dept("교무처 교무과","https://www.skuniv.ac.kr/notice/54522",true);
        rule("IT","WIFI","유·무선 인터넷 문의",it,"02-940-7043","https://www.skuniv.ac.kr/campus-network",true);
        rule("FACILITY","ELECTRIC","교내 전기시설 문의",electric,"02-940-7049","https://www.skuniv.ac.kr/notice/58965",true);
        rule("FACILITY","LEAK","누수 담당 업무 미검증",general,"02-940-7028",null,false);
        rule("ACADEMIC","STUDENT_RECORD","학적 문의 (절차 답변 제외)",academic,"02-940-7020","https://www.skuniv.ac.kr/notice/54522",true);
    }
    private long dept(String name,String source,boolean verified){return db.insert("INSERT INTO departments(name,source_url,verified_at) VALUES(?,?,?)",name,source,verified?"2026-10-10 17:00:00":null);}
    private void rule(String c,String s,String work,long dep,String phone,String source,boolean verified){db.insert("INSERT INTO routing_rules(category,subcategory,work_name,department_id,contact_phone,source_url,verified_at,active) VALUES(?,?,?,?,?,?,?,?)",c,s,work,dep,phone,source,verified?"2026-10-10 17:00:00":null,verified);}
}
