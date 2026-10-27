package edu.skope.ai;

import java.util.*;

/** AI supplies grounded observations and a proposed reason; server rules own the actual question/decision. */
final class ClarificationPolicy {
    static final List<String> REASONS=List.of("ISSUE_UNCLEAR","LOCATION_REQUIRED","LOCATION_DETAIL","SYMPTOM_DETAIL","TARGET_UNCLEAR","WORKFLOW_UNCLEAR","CONFLICT");
    static final List<String> FACTS=List.of("locationEvidence","scopeEvidence","symptomEvidence","targetEvidence","workflowEvidence");
    static final Map<String,String> LABELS=Map.of("ISSUE_UNCLEAR","구체적인 문제 내용","LOCATION_REQUIRED","발생 장소","LOCATION_DETAIL","점검할 구체적 공간","SYMPTOM_DETAIL","장애가 나타나는 방식","TARGET_UNCLEAR","점검 대상 설비","WORKFLOW_UNCLEAR","문제가 발생한 업무 단계","CONFLICT","서로 다른 정보 확인");
    record Decision(Map<String,Object> report,Map<String,Object> state,Map<String,Object> explanation){}

    @SuppressWarnings("unchecked")
    static Decision decide(String intent,String original,String answer,String field,Map<String,Object> previous,
                           Map<String,Object> report,Map<String,Object> baseState,Map<String,Object> old,
                           Map<String,Object> updates,Map<String,Object> suggestion) {
        var state=new LinkedHashMap<>(baseState);
        var known=new LinkedHashMap<String,String>((Map<String,String>)old.getOrDefault("knownEvidence",Map.of()));
        var asked=new ArrayList<String>((List<String>)old.getOrDefault("askedReasons",List.of()));
        var unavailable=new LinkedHashSet<String>((List<String>)old.getOrDefault("unavailableReasons",List.of()));
        String last=Objects.toString(old.get("nextReason"),"");
        if(field!=null&&!last.isEmpty()&&!asked.contains(last)&&asked.size()<3)asked.add(last);
        boolean placeChanged=!Objects.equals(previous.get("building"),report.get("building")) || changed(updates,"LOCATION");
        boolean symptomChanged=changed(updates,"SYMPTOM")||changed(updates,"CATEGORY");
        if(placeChanged)for(String k:List.of("locationEvidence","scopeEvidence"))known.remove(k);
        if(symptomChanged)for(String k:List.of("symptomEvidence","targetEvidence","workflowEvidence"))known.remove(k);
        Map<?,?> proposed=suggestion.get("known") instanceof Map<?,?> m?m:Map.of();
        for(String key:FACTS){
            String evidence=proposed.get(key) instanceof String s?s.strip():"";
            String source=field!=null&&((key.startsWith("location")||key.startsWith("scope"))?placeChanged:symptomChanged)?answer:original+"\n"+answer;
            if(!evidence.isBlank()&&evidence.length()<=200&&source.contains(evidence) && contextualEvidence(source,evidence))known.put(key,evidence);
        }
        // A rewritten model summary cannot establish details absent from the student's words.
        String symptomSource=field==null?original:answer;
        String wifiEvidence=boundedWifiEvidence(symptomSource);
        if(wifiEvidence!=null)known.put("symptomEvidence",wifiEvidence);
        String symptom=text(report,"symptom"), place=text(report,"locationText"), building=text(report,"building");
        String current=compact(symptom), type=text(report,"subcategory");
        boolean observedFault=FollowUpPolicy.observedFault(field==null?original:symptomChanged?answer:original)!=null;
        boolean academic="ACADEMIC".equals(report.get("category"));
        boolean physical=!"QUESTION".equals(intent)&&!academic;
        List<?> missing=report.get("missingFields") instanceof List<?> l?l:List.of();
        List<?> reviews=state.get("reviewFields") instanceof List<?> l?l:List.of();
        var needed=new LinkedHashSet<String>();
        if(missing.contains("SYMPTOM"))needed.add("ISSUE_UNCLEAR");
        // Uncertain classification is an administrator task, not a student interview.
        if(reviews.stream().anyMatch(x->!"OCCURRED_AT".equals(x)&&!"CATEGORY".equals(x)))needed.add("CONFLICT");
        if(physical) {
            if(missing.contains("LOCATION"))needed.add("LOCATION_REQUIRED");
            else {
                boolean scope=wide(known.get("scopeEvidence"));
                // Never use an old building-wide phrase after a location correction.
                if(field==null)scope|=wide(original);
                if(scope&&!known.containsKey("scopeEvidence")&&field==null)known.put("scopeEvidence",original.substring(0,Math.min(200,original.length())));
                boolean detailed=text(report,"floor").matches("B?[1-9]\\d{0,2}")||specificPlace(place,building)||specificPlace(known.get("locationEvidence"),building);
                if(!scope&&!detailed)needed.add("LOCATION_DETAIL");
            }
            if(type.equals("WIFI")&&!wifiDetail(compact(known.get("symptomEvidence"))))needed.add("SYMPTOM_DETAIL");
            if(type.equals("ELECTRIC")&&!observedFault&&!target(compact(field!=null&&symptomChanged?answer:original+"\n"+answer))&&!concreteTarget(known.get("targetEvidence")))needed.add("TARGET_UNCLEAR");
            if(type.equals("UNKNOWN")&&current.matches(".*(?:시설|설비|장비|기기|뭔가).*(?:고장|이상|안됨|안돼).*" )&&!target(current)&&known.get("targetEvidence")==null)needed.add("TARGET_UNCLEAR");
        }
        String proposedReason=Objects.toString(suggestion.get("reason"),"NONE");
        String evidence=Objects.toString(suggestion.get("evidence"),"");
        boolean grounded=!evidence.isBlank()&&(original+"\n"+answer).contains(evidence);
        if(academic&&!workflow(current)&&!workflow(compact(known.get("workflowEvidence")))
                &&(current.matches(".*(?:신청|발급|휴학|복학|등록|졸업).*(?:안돼|안됨|안되|어려|문제|불편).*")||grounded&&proposedReason.equals("WORKFLOW_UNCLEAR")))needed.add("WORKFLOW_UNCLEAR");
        // Generic OTHER problems can benefit from a grounded semantic ambiguity, without requiring a known category.
        if(grounded&&type.equals("UNKNOWN")&&!missing.contains("SYMPTOM")) {
            if(proposedReason.equals("SYMPTOM_DETAIL")&&!observedFault&&!usefulSymptom(known.get("symptomEvidence")))needed.add(proposedReason);
            if(proposedReason.equals("TARGET_UNCLEAR")&&physical&&!observedFault&&!target(current)&&known.get("targetEvidence")==null)needed.add(proposedReason);
        }
        if(field!=null&&!last.isBlank()&&cannotAnswer(answer))unavailable.add(last);
        // Once a useful location reply makes the report actionable, further diagnosis is optional.
        // This does not waive unknown targets, unspecified issues or contradictory information.
        if(field!=null && last.startsWith("LOCATION") && ((Number)state.getOrDefault("noProgress",0)).intValue()==0
                && needed.equals(Set.of("SYMPTOM_DETAIL")) && !missing.contains("SYMPTOM"))needed.remove("SYMPTOM_DETAIL");
        // A newly supplied fact can resolve a formerly unavailable item, but asking again cannot.
        unavailable.retainAll(needed);
        var unresolvedAsked=new LinkedHashSet<>(needed);unresolvedAsked.retainAll(asked);
        boolean urgent=Boolean.TRUE.equals(report.get("emergencyHint"));
        boolean blocked=!unavailable.isEmpty()||(!unresolvedAsked.isEmpty()&&((Number)state.getOrDefault("noProgress",0)).intValue()>0);
        boolean capped=((Number)state.getOrDefault("turns",0)).intValue()>=3||((Number)state.getOrDefault("noProgress",0)).intValue()>=2;
        String status=needed.isEmpty()?"COMPLETE":urgent?"COMPLETE":blocked?"MANUAL_REVIEW":capped?"LIMIT":"CONTINUE";
        String reason=null;
        if(status.equals("CONTINUE")){
            reason=needed.contains("ISSUE_UNCLEAR")?"ISSUE_UNCLEAR":needed.contains("CONFLICT")?"CONFLICT":
                    grounded&&needed.contains(proposedReason)?proposedReason:needed.iterator().next();
        }
        state.put("status",status);state.put("nextReason",reason);state.put("askedReasons",asked);
        state.put("unavailableReasons",List.copyOf(unavailable));state.put("knownEvidence",known);
        state.put("neededReasons",List.copyOf(needed));state.put("urgentReview",status.equals("COMPLETE")&&(!needed.isEmpty()||!reviews.isEmpty()));
        String next=reason==null?null:reason.startsWith("LOCATION")?"LOCATION":reason.equals("CONFLICT")&&reviews.contains("LOCATION")?"LOCATION":"SYMPTOM";
        state.put("nextField",next);
        report.put("followUpQuestion",reason==null?null:question(reason,type,academic,intent));
        var explanation=new LinkedHashMap<String,Object>();explanation.put("reason",reason);explanation.put("neededReasons",List.copyOf(needed));
        explanation.put("message",reason==null?null:why(reason));explanation.put("source",grounded&&Objects.equals(reason,proposedReason)?"AI_AND_RULES":"SERVER_RULES");
        return new Decision(report,state,explanation);
    }
    static boolean cannotAnswer(String answer){String s=compact(answer);return !s.matches(".*(?:모르는건아니|모르지는않|모르는것은아니).*" )&&s.matches(".*(?:모르|알수없|확인(?:을)?못|기억(?:이)?안|답변(?:하기)?어려).*" );}
    private static boolean changed(Map<String,Object> updates,String group){return updates.get(group) instanceof Map<?,?> m&&Set.of("SET","CLEAR","CLARIFY").contains(Objects.toString(m.get("action"),""));}
    private static boolean specificPlace(String place,String building){String s=compact(place);return !s.isBlank()&&!s.equals(compact(building))&&s.matches(".*(?:\\d+호|\\d+층|지하|로비|복도|강의실|화장실|정문|후문|운동장|주차장|정류장|농구장|축구장|테니스장|계단|출입구|세미나실|열람실|광장).*" );}
    private static boolean wide(String value){String s=compact(value);return s.matches(".*(?:건물전체|관전체|건물전역|모든층|전층|곳곳).*" )&&!s.matches(".*(?:전체|전역|모든층|전층|곳곳).{0,6}(?:아니|아닌|모르|인지).*" );}
    private static boolean wifiDetail(String value){
        return connectedWithoutInternet(value)||Arrays.stream(value.split("[.!?;\\n]|지만|인데")).filter(s->!s.matches(".*(?:인지|모르|아마|일수도|아니라|아닌).*" )).anyMatch(s->s.matches(".*(?:끊|느리|느려|느림|불안정|신호약|신호없|목록에없|목록안|비밀번호오류|인증실패|로그인실패|접속할수없|접속이안|접속하지못|연결자체|연결실패|접속실패|접속불가|연결불가|연결이안|연결안|연결되지|연결은되|연결되는데|연결됐는데|연결되지만|연결은됐).*" ));
    }
    // Require BOTH observations in the student's words; '먹통' alone is still ambiguous.
    private static boolean connectedWithoutInternet(String value){
        if(uncertainInternetPair(value))return false;
        boolean connected=value.matches(".*(?:connected|연결(?:은|는|이)?(?:됨|됐|되어|돼)|접속(?:은|는|이)?(?:됨|됐|되어|돼)).*");
        boolean noInternet=value.matches(".*(?:(?:인터넷|internet)(?:은|는|이|가)?(?:먹통|안됨|안돼|안되|불가|notworking|unavailable)|nointernet).*");
        return connected&&noInternet;
    }
    private static boolean uncertainInternetPair(String value){
        return value.matches(".*(?:인지|모르|아마|일수도|아니|아님|maybe|perhaps|unsure|unknown|notconnected|disconnected|unconnected|connected(?:는|가|이)?(?:아님|아니|안)|먹통(?:은|이|은건|인건)?(?:아님|아니)).*");
    }
    private static String boundedWifiEvidence(String source){
        // Keep the actual evidence, even when it appears after a long introduction.
        for(String clause:source.split("[.!?;\\n]")){
            if(!wifiDetail(compact(clause)))continue;
            for(int start=0;start<clause.length();start+=100){
                String window=clause.substring(start,Math.min(start+200,clause.length()));
                if(wifiDetail(compact(window)))return window;
            }
        }
        return null;
    }
    // A cropped quote must not drop negation or uncertainty from its containing clause.
    private static boolean contextualEvidence(String source,String evidence){
        return Arrays.stream(source.split("[.!?;\\n]"))
                .anyMatch(clause->clause.contains(evidence)&&!compact(clause).matches(".*(?:인지|모르|아마|아니라|아닌|일수도).*")
                        &&(!connectedWithoutInternet(compact(evidence))||!uncertainInternetPair(compact(clause))));
    }
    private static boolean concreteTarget(String value){
        String s=compact(value);
        return FollowUpPolicy.validText(value)&&!s.matches("(?:시설|설비|장비|기기|전기|뭔가|그것|무언가)(?:문제)?")&&!s.matches(".*(?:인지|모르|아마).*" );
    }
    private static boolean usefulSymptom(String value){String s=compact(value);return !s.isBlank()&&!s.matches(".*(?:와이파이|인터넷|시설|문제)(?:가|이|는|은)?(?:잘)?(?:안됨|안돼요|안됩니다|되지않는것같습니다|되지않아요|이상해요|문제있음)[.!?]*");}
    private static boolean target(String s){return s.matches(".*(?:멀티탭|플러그|분전함|배전반|콘센트|조명|전등|전구|형광등|차단기|스위치|정전|전원|의자|책상|에어컨|난방|창문|문손잡이|엘리베이터|수도꼭지|배관|천장).*" );}
    private static boolean workflow(String s){return s.matches(".*(?:절차|방법|어떻게|신청전|신청중|제출후|신청후|신청했|제출했|제출완료|처리상태|승인|대기|접수상태|오류메시지|버튼|화면|로그인|제출이|제출안|제출되지).*" );}
    private static String text(Map<String,Object> map,String key){return Objects.toString(map.get(key),"");}
    private static String compact(String s){return s==null?"":s.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    static String why(String reason){return switch(reason){
        case "LOCATION_REQUIRED","LOCATION_DETAIL"->"담당자가 확인할 장소를 정하기 위해 필요해요.";
        case "SYMPTOM_DETAIL"->"어떻게 문제가 나타나는지에 따라 확인 방법이 달라져요.";
        case "TARGET_UNCLEAR"->"어떤 설비를 점검해야 하는지 확인하려고 해요.";
        case "WORKFLOW_UNCLEAR"->"진행 중인 단계에 따라 확인할 업무가 달라져요.";
        case "CONFLICT"->"서로 다른 정보 중 확실한 내용만 전달하려고 해요.";
        default->"담당자가 어떤 문제를 처리해야 하는지 알아야 해요.";};}
    private static String question(String reason,String type,boolean academic,String intent){return switch(reason){
        case "LOCATION_REQUIRED"->"어느 건물이나 장소에서 발생했나요?";
        case "LOCATION_DETAIL"->"어느 층이나 어느 쪽에서 발생했나요?";
        case "SYMPTOM_DETAIL"->type.equals("WIFI")?"와이파이 연결 자체가 안 되나요, 연결 후 인터넷이 안 되나요? 끊기거나 느린 경우도 알려주세요.":"어떤 동작을 할 때 어떻게 문제가 나타나나요? 직접 겪은 현상만 알려주세요.";
        case "TARGET_UNCLEAR"->"어떤 설비나 물건에 문제가 있나요? 담당자가 찾을 수 있게 알려주세요.";
        case "WORKFLOW_UNCLEAR"->"신청 방법이 궁금한가요, 신청 중 오류가 나나요, 아니면 제출 후 처리가 진행되지 않나요?";
        case "CONFLICT"->"앞서 알려주신 정보와 다른 부분이 있어요. 확실한 장소나 문제 내용만 다시 알려주세요.";
        default->academic||intent.equals("QUESTION")?"어떤 내용을 문의하고 싶으신가요? 궁금한 점이나 겪고 있는 문제를 조금만 구체적으로 알려주세요.":"어떤 점이 불편한가요? 직접 겪은 문제를 알려주세요.";};}
}
