package edu.skope.service;

import edu.skope.ai.AiService;
import edu.skope.domain.Codes;
import edu.skope.repository.Db;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Bounded session draft. Only server analysis decides whether the natural-language flow can save. */
@Service
public class IntakeService {
    private final AiService ai;
    private final ReportService reports;
    private final SimilarityService similarity;
    private final Db db;
    public IntakeService(AiService ai, ReportService reports, SimilarityService similarity, Db db) {
        this.ai=ai; this.reports=reports; this.similarity=similarity; this.db=db;
    }
    private static final class Draft {
        String text, preferred, failure, failedAnswer, latestAnswer;
        int revision;
        Map<String,Object> analysis;
        Draft(String text,String preferred){this.text=text;this.preferred=preferred;}
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> intake(long user, HttpSession session, Map<String,Object> body) {
        String key=ReportService.field(body,"clientRequestId",36,true);
        try { if(!UUID.fromString(key).toString().equalsIgnoreCase(key))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e){throw new ApiException(400,"VALIDATION_ERROR","요청 식별자를 확인하세요.");}
        // Serialize same-session retries before any paid call; the DB also enforces uniqueness across sessions.
        synchronized(session) {
            var existing=existing(user,key);
            if(existing!=null)return accepted(existing);
            var drafts=(LinkedHashMap<String,Draft>)session.getAttribute("skope-intakes");
            if(drafts==null){drafts=new LinkedHashMap<>();session.setAttribute("skope-intakes",drafts);}
            String ownedKey=user+":"+key;
            Draft draft=drafts.get(ownedKey);
            if(draft!=null && body.get("text") instanceof String text && !draft.text.equals(text.strip()))
                throw new ApiException(409,"REQUEST_CONFLICT","내용을 변경한 경우 새 요청으로 보내 주세요.");
            boolean resume=Boolean.TRUE.equals(body.get("resume")), review=Boolean.TRUE.equals(body.get("submitForReview"));
            String answer=ReportService.field(body,"answer",500,false);
            if(draft==null) {
                if(resume||review||answer!=null)throw new ApiException(409,"DRAFT_EXPIRED","작성 세션이 만료되었습니다. 입력을 보존한 채 처음부터 다시 보내 주세요.");
                String text=ReportService.field(body,"text",4000,true);
                if(text.length()<5)throw new ApiException(400,"VALIDATION_ERROR","내용을 다섯 글자 이상 입력하세요.");
                String preferred=Objects.toString(body.getOrDefault("preferredIntent","AUTO"));
                if(!Set.of("AUTO","REPORT","QUESTION").contains(preferred))throw new ApiException(400,"VALIDATION_ERROR","입력 목적을 확인하세요.");
                draft=new Draft(text,preferred);
                if(drafts.size()>=8)drafts.remove(drafts.keySet().iterator().next());
                drafts.put(ownedKey,draft);
                analyze(draft,Map.of("text",text,"preferredIntent",preferred,"hybrid",true));
            } else if(!resume && (answer!=null || review)) {
                Object revision=body.get("revision");
                if(!(revision instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>draft.revision)
                    throw new ApiException(409,"STALE_DRAFT","현재 질문을 다시 확인한 뒤 답변해 주세요.");
                if(((Number)revision).intValue()<draft.revision)return response(draft);
                if(review){
                    // Preserve a typed-but-unsent reply without another paid analysis or claiming it is verified.
                    if(answer!=null)draft.failedAnswer=answer;
                    return save(user,key,draft,true);
                }
                if(draft.failure!=null || !"CONTINUE".equals(state(draft).get("status")))return response(draft);
                var request=new LinkedHashMap<String,Object>();
                request.put("hybrid",true);
                request.put("text",draft.text);
                String intent=Objects.toString(draft.analysis.get("intent"),"UNKNOWN");
                request.put("preferredIntent",intent.equals("UNKNOWN")?draft.preferred:intent);
                request.put("followUp",Map.of("field",state(draft).get("nextField"),"answer",answer,
                        "previousReport",draft.analysis.get("report"),"state",state(draft)));
                draft.latestAnswer=answer;
                analyze(draft,request);
            }
            if(draft.failure==null && "COMPLETE".equals(state(draft).get("status")))return save(user,key,draft,Boolean.TRUE.equals(state(draft).get("urgentReview")));
            return response(draft);
        }
    }
    private void analyze(Draft draft,Map<String,Object> request) {
        try {draft.analysis=ai.analyze(request);draft.failure=null;draft.failedAnswer=null;}
        catch(ApiException e){if(!e.code.equals("AI_UNAVAILABLE"))throw e;draft.failure="AI 분석을 완료하지 못했습니다. 입력한 내용을 그대로 관리자 검토로 접수할 수 있습니다.";
            if(request.get("followUp") instanceof Map<?,?> follow)draft.failedAnswer=Objects.toString(follow.get("answer"),"");}
        draft.revision++;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> state(Draft d){return d.analysis==null?Map.of():(Map<String,Object>)d.analysis.getOrDefault("followUpState",Map.of());}
    private Map<String,Object> response(Draft d) {
        var response=new LinkedHashMap<String,Object>();
        response.put("status",d.failure==null&&"CONTINUE".equals(state(d).get("status"))?"NEEDS_INPUT":"REVIEW_REQUIRED");
        response.put("revision",d.revision);response.put("analysis",d.analysis);
        response.put("latestAnswer",d.latestAnswer);
        response.put("message",d.failure==null?"추가로 확정하기 어려운 정보는 관리자에게 검토를 요청할 수 있습니다.":d.failure);
        response.put("aiMode",ai.mode());
        return response;
    }
    private Map<String,Object> existing(long user,String key){var r=db.one("SELECT id FROM reports WHERE student_id=? AND client_request_id=?",user,key);return r==null?null:reports.detail(Db.id(r,"id"),user,false);}
    static final String SCOPE_NOTICE="SKOPE는 학교 시설·비품·행정 문제를 접수하는 서비스예요. 개인 물건 구매·추천은 도와드리기 어려워 담당 부서로 자동 전달하지 않았어요. 총괄 담당자가 내용을 확인할 수 있어요.";
    private Map<String,Object> accepted(Map<String,Object> report){
        var response=new LinkedHashMap<String,Object>(Map.of("status","ACCEPTED","report",report,"aiMode",ai.mode()));
        // Optional field: present only for an out-of-scope request, so existing clients can ignore it.
        if(Codes.broad(report.get("subcategory"))&&"OUT_OF_SCOPE".equals(report.get("campusRelevance")))response.put("scopeNotice",SCOPE_NOTICE);
        return response;
    }
    private String fieldNames(Object value){
        var labels=new HashMap<>(Map.of("LOCATION","장소","SYMPTOM","문제 내용 또는 문의 내용","OCCURRED_AT","발생 시점","CATEGORY","유형"));
        labels.putAll(Map.of("ISSUE_UNCLEAR","구체적인 문제 내용","LOCATION_REQUIRED","발생 장소","LOCATION_DETAIL","점검할 공간","SYMPTOM_DETAIL","장애가 나타나는 방식","TARGET_UNCLEAR","점검 대상 설비","WORKFLOW_UNCLEAR","업무 단계","CONFLICT","정보 충돌","CAMPUS_RELEVANCE","학교 물건인지 여부"));
        return value instanceof List<?> fields && !fields.isEmpty()?String.join(", ",fields.stream().map(f->labels.getOrDefault(f.toString(),"추가 정보")).toList()):"없음";
    }
    @SuppressWarnings("unchecked") private Map<String,Object> save(long user,String key,Draft draft,boolean review) {
        var payload=new LinkedHashMap<String,Object>();
        if(draft.analysis!=null)payload.putAll((Map<String,Object>)draft.analysis.get("report"));
        payload.put("originalText",draft.text);payload.put("clientRequestId",key);
        payload.putIfAbsent("category","OTHER");payload.putIfAbsent("subcategory","UNKNOWN");
        if(payload.get("symptom")==null)payload.put("symptom",draft.text.substring(0,Math.min(500,draft.text.length())));
        String intent=draft.analysis==null?draft.preferred:Objects.toString(draft.analysis.get("intent"));
        // A personal or out-of-scope OTHER request is an inquiry for general review, never a facility fault.
        boolean general=Codes.broad(payload.get("subcategory"))&&Codes.generalInquiry(payload.get("campusRelevance"));
        payload.put("requestKind","QUESTION".equals(intent)||general?"QUESTION":"REPORT");
        if(review) {
            payload.put("reviewNote",draft.failure!=null?"AI 분석 실패: 원문과 기존 추출 내용을 확인해 주세요."+(draft.failedAnswer==null?"":" / 미분석 추가 답변: "+draft.failedAnswer):
                    (Boolean.TRUE.equals(state(draft).get("urgentReview"))?"추가 질문 없이 접수한 내용의 관리자 확인이 필요합니다. ":"학생이 관리자 검토를 요청했습니다. ")+"부족한 정보: "+fieldNames(payload.get("missingFields"))+". 처리 전 확인 사항: "+fieldNames(state(draft).get("neededReasons"))+". 충돌하거나 미확정인 정보: "+fieldNames(state(draft).get("reviewFields"))+"."+(draft.latestAnswer==null?"":" / 마지막 추가 답변: "+draft.latestAnswer)+(draft.failedAnswer==null?"":" / 미분석 추가 답변: "+draft.failedAnswer));
        }
        Map<String,Object> saved;
        try {saved=reports.createFromAnalysis(user,payload,Boolean.TRUE.equals(payload.get("emergencyHint")));}
        catch(DuplicateKeyException e){saved=existing(user,key);if(saved==null)throw e;return accepted(saved);}
        catch(ApiException e){if(!e.code.equals("DUPLICATE_REQUEST"))throw e;saved=existing(user,key);if(saved==null)throw e;return accepted(saved);}
        try{similarity.index(Db.id(saved,"id"));}catch(RuntimeException ignored){/* Accepted request survives embedding failures. */}
        return accepted(saved);
    }
}
