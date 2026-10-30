package edu.skope.service;
import edu.skope.domain.Codes;
import edu.skope.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class RoutingService {
    private final Db db;
    private final boolean demoEnabled;
    public RoutingService(Db db,@Value("${app.routing.demo-enabled:false}") boolean demoEnabled){this.db=db;this.demoEnabled=demoEnabled;}
    public boolean demoEnabled(){return demoEnabled;}
    private static final String SELECT="SELECT rr.*, d.name department_name FROM routing_rules rr JOIN departments d ON d.id=rr.department_id WHERE rr.active=TRUE AND d.active=TRUE AND ((rr.demo_only=FALSE AND rr.verified_at IS NOT NULL AND rr.source_url IS NOT NULL AND TRIM(rr.source_url)<>'') OR (rr.demo_only=TRUE AND ?=TRUE))";
    private static final String DEMO_WARNING="시연용 배정 · 실제 담당 업무 미확인";
    /** Work-group rules: category WORK_GROUP, subcategory = group, target_key '' (default) or a target name (exception). */
    public static final String WORK_GROUP="WORK_GROUP";
    public Map<String,Object> find(String category,String subcategory){return db.one(SELECT+" AND rr.category=? AND rr.subcategory=? AND rr.target_key=''",demoEnabled,category,subcategory);}
    /**
     * Department for an accepted report, from managed DB rules only (never a model-generated name):
     * 1) the report type's own rule, 2) an exception registered for this work group and target name,
     * 3) the work group's default rule. GENERAL_REVIEW has no rule: it stays for the supervisor.
     */
    public Map<String,Object> route(String category,String subcategory,String workGroup,String target){
        var rule=find(category,subcategory);
        if(rule!=null)return rule;
        String group=Codes.workGroup(subcategory,workGroup);
        if("GENERAL_REVIEW".equals(group))return null;
        String key=targetKey(target);
        if(!key.isEmpty()&&(rule=db.one(SELECT+" AND rr.category=? AND rr.subcategory=? AND rr.target_key=?",demoEnabled,WORK_GROUP,group,key))!=null)return rule;
        return db.one(SELECT+" AND rr.category=? AND rr.subcategory=? AND rr.target_key=''",demoEnabled,WORK_GROUP,group);
    }
    /** Exception key: the target name without spaces or case, so "보드 마카" and "보드마카" match one rule. */
    public static String targetKey(String target){return target==null?"":target.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    public Map<String,Object> byId(long id){var r=db.one(SELECT+" AND rr.id=?",demoEnabled,id);if(r==null)throw new ApiException(422,"ROUTING_RULE_INVALID","사용 가능한 활성 배정 규칙을 선택하세요.");return r;}
    /** Report types for the student form and manual reassignment; work-group rules are not report types. */
    public List<Map<String,Object>> types(){var rows=db.all(SELECT+" AND rr.category<>? ORDER BY rr.id",demoEnabled,WORK_GROUP);rows.forEach(this::label);return rows;}
    public Map<String,Object> preview(Map<String,Object> rule){var p=new LinkedHashMap<String,Object>();p.put("routeStatus",rule==null?"NEEDS_REVIEW":"MATCHED");p.put("routingRuleId",rule==null?null:rule.get("id"));p.put("departmentName",rule==null?null:rule.get("departmentName"));p.put("contactPhone",verified(rule)?rule.get("contactPhone"):null);p.put("sourceUrl",verified(rule)?rule.get("sourceUrl"):null);p.put("verified",verified(rule));p.put("demoOnly",demo(rule));p.put("assignmentBasis",basis(rule));p.put("warning",demo(rule)?DEMO_WARNING:null);return p;}
    public Map<String,Object> department(Map<String,Object> rule){if(rule==null)return null;var d=new LinkedHashMap<String,Object>();d.put("id",rule.get("departmentId"));d.put("name",rule.get("departmentName"));d.put("contactPhone",verified(rule)?rule.get("contactPhone"):null);d.put("sourceUrl",verified(rule)?rule.get("sourceUrl"):null);d.put("verified",verified(rule));d.put("demoOnly",demo(rule));d.put("assignmentBasis",basis(rule));d.put("warning",demo(rule)?DEMO_WARNING:null);return d;}
    private void label(Map<String,Object> rule){rule.put("verified",verified(rule));rule.put("assignmentBasis",basis(rule));rule.put("warning",demo(rule)?DEMO_WARNING:null);if(!verified(rule)){rule.put("contactPhone",null);rule.put("contactEmail",null);rule.put("sourceUrl",null);}}
    private static boolean demo(Map<String,Object> rule){return rule!=null&&(Boolean.TRUE.equals(rule.get("demoOnly"))||rule.get("demoOnly") instanceof Number n&&n.intValue()!=0);}
    private static boolean verified(Map<String,Object> rule){return rule!=null&&!demo(rule)&&rule.get("verifiedAt")!=null&&!Objects.toString(rule.get("sourceUrl"),"").isBlank();}
    private static String basis(Map<String,Object> rule){return rule==null?null:demo(rule)?"DEMO":verified(rule)?"VERIFIED":"UNVERIFIED";}
    /**
     * Why a stored report has no department, derived from saved fields (no extra column), checked in the same
     * order ReportService uses when it decides not to assign. Null when a department is assigned.
     * REVIEW_HOLD: saved for supervisor review (student request, AI failure, urgent review).
     * AI_UNCLASSIFIED: no supported type was recognised (the server cannot tell a wrong guess from a truly other topic).
     * LOCATION_MISSING: a physical fault without a building/place, so automatic assignment was withheld.
     * SCOPE_UNCONFIRMED: an OTHER/UNKNOWN request with a known work group whose school relevance is not confirmed
     * (uncertain ownership, or an older row without it); a building name alone does not make it a school object.
     * NO_RULE: the type/work group is known and in scope but no active type, exception or group rule exists.
     * GENERAL_INQUIRY / OUT_OF_SCOPE: an OTHER/UNKNOWN request about a personal item, or one the school does not handle.
     */
    public static String unassignedReason(Map<String,Object> report){
        if(report.get("assignedDepartmentId")!=null)return null;
        if(!Objects.toString(report.get("reviewNote"),"").isBlank())return "REVIEW_HOLD";
        boolean physical=!"QUESTION".equals(report.get("requestKind"))&&!"ACADEMIC".equals(report.get("category"));
        boolean noPlace=report.get("locationText")==null||report.get("building")==null;
        if(Codes.broad(report.get("subcategory"))){
            Object relevance=report.get("campusRelevance");
            if("PERSONAL_ON_CAMPUS".equals(relevance))return "GENERAL_INQUIRY";
            if("OUT_OF_SCOPE".equals(relevance))return "OUT_OF_SCOPE";
            if("GENERAL_REVIEW".equals(Codes.workGroup("UNKNOWN",report.get("workGroup"))))return "AI_UNCLASSIFIED";
            if(!schoolHandled(relevance))return "SCOPE_UNCONFIRMED";
            return physical&&Codes.needsPlace(relevance)&&noPlace?"LOCATION_MISSING":"NO_RULE";
        }
        if(physical&&noPlace)return "LOCATION_MISSING";
        return "NO_RULE";
    }
    /** An OTHER/UNKNOWN request is the school's to route only when its relevance says so explicitly. */
    public static boolean schoolHandled(Object relevance){return "CAMPUS_FACILITY".equals(relevance)||"SCHOOL_SERVICE".equals(relevance);}
    public Map<String,Object> answer(Map<String,Object> rule,String text){
        // Only contact questions are supported by the verified structured directory.
        boolean contact=text.matches("(?s).*(문의|연락|어디|전화|담당).*"), procedure=text.matches("(?s).*(절차|방법|규정|기준|언제|기간|휴학|성적).*" );
        if(!verified(rule)||!contact||procedure)return Map.of("text","확인된 안내 근거가 없어 답변할 수 없습니다. 학교 공식 홈페이지에서 해당 업무 담당 부서를 확인해 주세요.","verified",false);
        var answer=new LinkedHashMap<String,Object>();String phone=Objects.toString(rule.get("contactPhone"),"");
        answer.put("text",rule.get("workName")+"는 "+rule.get("departmentName")+(phone.isBlank()?"":" ("+phone+")")+"에 문의할 수 있습니다. 아래 공식 출처를 확인하세요.");
        answer.put("departmentName",rule.get("departmentName"));answer.put("contactPhone",rule.get("contactPhone"));answer.put("sourceUrl",rule.get("sourceUrl"));answer.put("verified",true);return answer;
    }
}
