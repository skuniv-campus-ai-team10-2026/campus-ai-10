package edu.skope.service;
import edu.skope.repository.Db;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class RoutingService {
    private final Db db;
    public RoutingService(Db db){this.db=db;}
    private static final String SELECT="SELECT rr.*, d.name department_name FROM routing_rules rr JOIN departments d ON d.id=rr.department_id WHERE rr.active=TRUE AND d.active=TRUE AND rr.verified_at IS NOT NULL AND rr.source_url IS NOT NULL";
    public Map<String,Object> find(String category,String subcategory){return db.one(SELECT+" AND rr.category=? AND rr.subcategory=?",category,subcategory);}
    public Map<String,Object> byId(long id){var r=db.one(SELECT+" AND rr.id=?",id);if(r==null)throw new ApiException(422,"ROUTING_RULE_INVALID","검증된 활성 배정 규칙을 선택하세요.");return r;}
    public List<Map<String,Object>> types(){var rows=db.all(SELECT+" ORDER BY rr.id");rows.forEach(r->r.put("verified",true));return rows;}
    public Map<String,Object> preview(Map<String,Object> rule){var p=new LinkedHashMap<String,Object>();p.put("routeStatus",rule==null?"NEEDS_REVIEW":"MATCHED");p.put("routingRuleId",rule==null?null:rule.get("id"));p.put("departmentName",rule==null?null:rule.get("departmentName"));p.put("contactPhone",rule==null?null:rule.get("contactPhone"));p.put("sourceUrl",rule==null?null:rule.get("sourceUrl"));p.put("verified",rule!=null);return p;}
    public Map<String,Object> department(Map<String,Object> rule){if(rule==null)return null;var d=new LinkedHashMap<String,Object>();d.put("id",rule.get("departmentId"));d.put("name",rule.get("departmentName"));d.put("contactPhone",rule.get("contactPhone"));d.put("sourceUrl",rule.get("sourceUrl"));d.put("verified",rule.get("verifiedAt")!=null&&rule.get("sourceUrl")!=null);return d;}
    public Map<String,Object> answer(Map<String,Object> rule,String text){
        // Only contact questions are supported by the verified structured directory.
        boolean contact=text.matches("(?s).*(문의|연락|어디|전화|담당).*"), procedure=text.matches("(?s).*(절차|방법|규정|기준|언제|기간|휴학|성적).*" );
        if(rule==null||!contact||procedure)return Map.of("text","확인된 안내 근거가 없어 답변할 수 없습니다. 학교 공식 홈페이지에서 해당 업무 담당 부서를 확인해 주세요.","verified",false);
        var answer=new LinkedHashMap<String,Object>();String phone=Objects.toString(rule.get("contactPhone"),"");
        answer.put("text",rule.get("workName")+"는 "+rule.get("departmentName")+(phone.isBlank()?"":" ("+phone+")")+"에 문의할 수 있습니다. 아래 공식 출처를 확인하세요.");
        answer.put("departmentName",rule.get("departmentName"));answer.put("contactPhone",rule.get("contactPhone"));answer.put("sourceUrl",rule.get("sourceUrl"));answer.put("verified",true);return answer;
    }
}
