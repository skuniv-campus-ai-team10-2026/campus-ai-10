package edu.skope.service;
import edu.skope.repository.Db;
import edu.skope.domain.Codes;
import edu.skope.ai.AiService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service
public class ReportService {
    private final Db db; private final RoutingService routes; private final IncidentService incidents;
    public ReportService(Db db,RoutingService routes,IncidentService incidents){this.db=db;this.routes=routes;this.incidents=incidents;}
    public static String field(Map<String,Object> b,String key,int max,boolean required){var v=b.get(key);if(v!=null&&!(v instanceof String))throw new ApiException(400,"VALIDATION_ERROR",key+" 형식이 올바르지 않습니다.");String s=v==null?null:((String)v).strip();if(s!=null&&s.isEmpty())s=null;if((required&&s==null)||(s!=null&&s.length()>max))throw new ApiException(400,"VALIDATION_ERROR",key+" 입력을 확인하세요.");return s;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(long studentId,Map<String,Object> b){
        return createReport(studentId,b,false);
    }
    /** Internal intake entry: flag supplied by server-owned analysis, never request JSON. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> createFromAnalysis(long studentId,Map<String,Object> b,boolean emergency){
        return createReport(studentId,b,emergency);
    }
    private Map<String,Object> createReport(long studentId,Map<String,Object> b,boolean emergency){
        String request=field(b,"clientRequestId",36,true);try{if(!UUID.fromString(request).toString().equalsIgnoreCase(request))throw new IllegalArgumentException();}catch(Exception e){throw new ApiException(400,"VALIDATION_ERROR","요청 식별자는 UUID여야 합니다.");}
        String original=field(b,"originalText",5000,true);if(original.length()<5)throw new ApiException(400,"VALIDATION_ERROR","상황을 다섯 글자 이상 입력하세요.");
        String c=field(b,"category",32,true),s=field(b,"subcategory",50,true);Codes.type(c,s);
        String location=field(b,"locationText",200,false),building=field(b,"building",100,false),floor=field(b,"floor",20,false),symptom=field(b,"symptom",500,true),at=field(b,"occurredAtText",120,false),on=field(b,"occurredOn",10,false);
        String kind=field(b,"requestKind",16,false),review=field(b,"reviewNote",1000,false);
        if(kind==null)kind="REPORT";
        if(!Set.of("REPORT","QUESTION").contains(kind))throw new ApiException(400,"VALIDATION_ERROR","접수 종류를 확인하세요.");
        if(on!=null){try{if(LocalDate.parse(on).isAfter(LocalDate.now(ZoneId.of("Asia/Seoul"))))throw new IllegalArgumentException();}catch(Exception e){throw new ApiException(400,"VALIDATION_ERROR","발생일은 유효한 오늘 이전 날짜여야 합니다.");}}
        if(db.one("SELECT id FROM reports WHERE student_id=? AND client_request_id=?",studentId,request)!=null)throw new ApiException(409,"DUPLICATE_REQUEST","이미 접수된 요청입니다.");
        var rule=review!=null || (!(kind.equals("QUESTION")||c.equals("ACADEMIC"))&&(location==null||building==null))?null:routes.find(c,s);
        long id=db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,location_text,building,floor,symptom,occurred_at_text,occurred_on,routing_rule_id,assigned_department_id,route_status,emergency_hint,request_kind,review_note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",studentId,request,original,c,s,location,building,floor,symptom,at,on,rule==null?null:rule.get("id"),rule==null?null:rule.get("departmentId"),rule==null?"NEEDS_REVIEW":"MATCHED",(emergency || AiService.emergency(original+" "+symptom)),kind,review);
        event(id,studentId,"CREATED",null,"RECEIVED","시연용 자체 접수");return detail(id,studentId,false);
    }
    public Map<String,Object> list(long studentId,boolean admin,int page,int size,String status,String routeStatus){
        if(page<0||page>100000||size<1||size>100)throw new ApiException(400,"VALIDATION_ERROR","목록 범위를 확인하세요.");
        String where=" WHERE 1=1";var args=new ArrayList<Object>();
        if(!admin){where+=" AND student_id=?";args.add(studentId);}
        if(status!=null&&!status.isBlank()){try{Codes.Status.valueOf(status);}catch(Exception e){throw new ApiException(400,"VALIDATION_ERROR","상태가 올바르지 않습니다.");}where+=" AND status=?";args.add(status);}
        if(routeStatus!=null&&!routeStatus.isBlank()){try{Codes.RouteStatus.valueOf(routeStatus);}catch(Exception e){throw new ApiException(400,"VALIDATION_ERROR","배정 상태가 올바르지 않습니다.");}where+=" AND route_status=?";args.add(routeStatus);}
        long total=Db.id(db.one("SELECT COUNT(*) total FROM reports"+where,args.toArray()),"total");args.add(size);args.add((long)page*size);
        var rows=db.all("SELECT * FROM reports"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray());rows.forEach(this::decorate);
        return Map.of("items",rows,"page",page,"size",size,"totalElements",total);
    }
    public Map<String,Object> detail(long id,long studentId,boolean admin){var r=db.one("SELECT * FROM reports WHERE id=?"+(admin?"":" AND student_id=?"),admin?new Object[]{id}:new Object[]{id,studentId});if(r==null)throw new ApiException(404,"REPORT_NOT_FOUND","신고를 찾을 수 없습니다.");decorate(r);if(admin)r.put("events",db.all("SELECT e.event_type,e.old_value,e.new_value,e.note,e.created_at changed_at,u.display_name actor_name FROM report_events e JOIN users u ON u.id=e.actor_id WHERE e.report_id=? ORDER BY e.id",id));return r;}
    private void decorate(Map<String,Object> r){Object route=r.get("routingRuleId");Map<String,Object> rule=route==null?null:db.one("SELECT rr.*,d.name department_name FROM routing_rules rr JOIN departments d ON d.id=rr.department_id WHERE rr.id=?",route);r.put("assignedDepartment",routes.department(rule));r.remove("embeddingJson");r.remove("clientRequestId");}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> status(long id,long actor,Map<String,Object> b){
        var r=locked(id);String next=field(b,"status",20,true),old=Db.str(r,"status");
        if(!(old.equals("RECEIVED")&&next.equals("IN_PROGRESS"))&&!(old.equals("IN_PROGRESS")&&next.equals("RESOLVED")))throw new ApiException(409,"INVALID_STATUS_TRANSITION","접수 → 처리 중 → 완료 순서로만 변경할 수 있습니다.");
        String resolution=field(b,"resolutionText",5000,next.equals("RESOLVED"));
        if(next.equals("IN_PROGRESS"))incidents.start(id,actor);
        db.update("UPDATE reports SET status=?,resolution_text=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",next,resolution,id);
        event(id,actor,"STATUS_CHANGED",old,next,resolution);
        r=db.one("SELECT incident_id FROM reports WHERE id=?",id);if(r.get("incidentId")!=null)incidents.refresh(Db.id(r,"incidentId"));return detail(id,actor,true);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> routing(long id,long actor,long ruleId){var r=locked(id);var rule=routes.byId(ruleId);db.update("UPDATE reports SET routing_rule_id=?,assigned_department_id=?,route_status='MANUAL',updated_at=CURRENT_TIMESTAMP WHERE id=?",ruleId,rule.get("departmentId"),id);event(id,actor,"ROUTING_CHANGED",Objects.toString(r.get("routingRuleId"),null),Long.toString(ruleId),"관리자가 검증된 활성 규칙으로 재배정");return detail(id,actor,true);}
    private Map<String,Object> locked(long id){var r=db.one("SELECT * FROM reports WHERE id=? FOR UPDATE",id);if(r==null)throw new ApiException(404,"REPORT_NOT_FOUND","신고를 찾을 수 없습니다.");return r;}
    private void event(long id,long actor,String type,String old,String next,String note){db.insert("INSERT INTO report_events(report_id,actor_id,event_type,old_value,new_value,note) VALUES(?,?,?,?,?,?)",id,actor,type,old,next,note);}
}
