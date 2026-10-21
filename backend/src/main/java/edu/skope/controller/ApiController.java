package edu.skope.controller;
import edu.skope.ai.AiService;
import edu.skope.repository.Db;
import edu.skope.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api")
public class ApiController {
    private final AuthController auth;private final ReportService reports;private final RoutingService routes;private final AiService ai;private final IncidentService incidents;private final SimilarityService similarity;private final String dbUrl;
    public ApiController(AuthController auth,ReportService reports,RoutingService routes,AiService ai,IncidentService incidents,SimilarityService similarity,@Value("${spring.datasource.url}")String dbUrl){this.auth=auth;this.reports=reports;this.routes=routes;this.ai=ai;this.incidents=incidents;this.similarity=similarity;this.dbUrl=dbUrl;}
    @org.springframework.beans.factory.annotation.Autowired private IntakeService intake;
    private long uid(Authentication a){return Db.id(auth.user(a),"id");}
    @PostMapping("/assistant/intake") public Map<String,Object> intake(Authentication a,jakarta.servlet.http.HttpSession session,@RequestBody Map<String,Object> b){return intake.intake(uid(a),session,b);}
    @GetMapping("/meta") public Map<String,Object> meta(){return Map.of("aiMode",ai.mode(),"embeddingMode",ai.embeddingMode(),"databaseMode",dbUrl.startsWith("jdbc:mysql:")?"MYSQL":"H2_DEMO","officialSubmission",false,"demoRoutingEnabled",routes.demoEnabled());}
    @GetMapping("/report-types") public Map<String,Object> types(){return Map.of("items",routes.types());}
    @PostMapping("/assistant/analyze") public Map<String,Object> analyze(@RequestBody Map<String,Object> b){
        var result=new LinkedHashMap<>(ai.analyze(b));Object report=result.get("report");Map<?,?> r=report instanceof Map<?,?> m?m:Map.of();var rule=routes.find(Objects.toString(r.get("category"),"OTHER"),Objects.toString(r.get("subcategory"),"UNKNOWN"));
        result.put("questionAnswer",null);result.put("routingPreview",null);
        if("QUESTION".equals(result.get("intent"))){result.put("questionAnswer",routes.answer(rule,Objects.toString(b.get("text"),"")));result.put("report",null);}
        else if("REPORT".equals(result.get("intent"))){if(r.get("locationText")==null||r.get("building")==null)rule=null;result.put("routingPreview",routes.preview(rule));}
        return result;
    }
    @PostMapping("/reports") public ResponseEntity<?> create(Authentication a,@RequestBody Map<String,Object> b){var report=reports.create(uid(a),b);try{similarity.index(Db.id(report,"id"));}catch(RuntimeException ignored){/* committed report survives indexing failure */}return ResponseEntity.status(201).body(report);}
    @GetMapping("/reports") public Map<String,Object> list(Authentication a,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status){return reports.list(uid(a),false,page,size,status,null);}
    @GetMapping("/reports/{id}") public Map<String,Object> detail(Authentication a,@PathVariable long id){return reports.detail(id,uid(a),false);}
    @GetMapping("/admin/reports") public Map<String,Object> adminList(Authentication a,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status,@RequestParam(required=false)String routeStatus,@RequestParam(defaultValue="false")boolean transferRequested){return reports.list(uid(a),true,page,size,status,routeStatus,transferRequested);}
    @GetMapping("/admin/reports/{id}") public Map<String,Object> adminDetail(Authentication a,@PathVariable long id){return reports.detail(id,uid(a),true);}
    @PatchMapping("/admin/reports/{id}/status") public Map<String,Object> status(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return reports.status(id,uid(a),b);}
    @PatchMapping("/admin/reports/{id}/routing") public Map<String,Object> routing(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return reports.routing(id,uid(a),number(b,"routingRuleId"),ReportService.field(b,"reason",1000,false));}
    @PatchMapping("/admin/reports/{id}/transfer-request") public Map<String,Object> transfer(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return reports.requestTransfer(id,uid(a),b);}
    @GetMapping("/admin/reports/{id}/similar") public Map<String,Object> similar(Authentication a,@PathVariable long id){return similarity.similar(id,uid(a));}
    @PatchMapping("/admin/similar-pairs/{id}/review") public Map<String,Object> pair(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return incidents.reviewPair(id,ReportService.field(b,"decision",30,true),uid(a));}
    @PatchMapping("/admin/reports/{id}/incident") public Map<String,Object> link(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return incidents.link(id,number(b,"incidentId"),uid(a));}
    @GetMapping("/admin/incidents/{id}") public Map<String,Object> incident(Authentication a,@PathVariable long id){return incidents.detail(id,uid(a));}
    @PatchMapping("/admin/incidents/{id}/recurrence") public Map<String,Object> recurrence(Authentication a,@PathVariable long id,@RequestBody Map<String,Object> b){return incidents.recurrence(id,number(b,"previousIncidentId"),ReportService.field(b,"decision",30,true),uid(a));}
    @GetMapping("/admin/repeated-candidates") public Map<String,Object> repeated(Authentication a){return incidents.repeated(uid(a));}
    private long number(Map<String,Object> b,String k){Object v=b.get(k);if(!(v instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<1)throw new ApiException(400,"VALIDATION_ERROR",k+"를 확인하세요.");return n.longValue();}
}
