package edu.skope.ai;

import edu.skope.service.ApiException;
import java.util.*;
import java.util.regex.*;

/** Bounded draft clarification. This is not an authority, routing, or report-saving decision. */
final class FollowUpPolicy {
    static final List<String> GROUPS = List.of("LOCATION", "SYMPTOM", "OCCURRED_AT", "CATEGORY");
    private static final List<String> CONTENT = List.of("category", "subcategory", "locationText", "building", "floor", "symptom", "occurredAtText");
    private static final Pattern ROOM = Pattern.compile("(?<!\\d)([A-Za-z]?\\d{2,5})\\s*호(?:실)?");
    private static final Pattern FLOOR = Pattern.compile("(?i)(?:(지하|B)\\s*)?([1-9]\\d{0,2})\\s*층");
    private static final Pattern BUILDING = Pattern.compile("([가-힣A-Za-z0-9]{1,25}(?:관|동|도서관|체육관))");
    private static final Pattern UNCERTAIN = Pattern.compile("인지|아마|둘\\s*중|확실하지|기억.{0,8}(?:안|않)|모르|일\\s*수도");
    private static final Pattern EMPTY = Pattern.compile("^(?:거기|여기|저기|그곳|어딘가|모름|몰라|모르겠|잘모르겠|글쎄|그냥|문제|불편|이상|학교|강의실)(?:요|어요|습니다|해요|이에요|예요|가있어요|이있어요|같아요|어요|\\p{Punct})*$");
    record Outcome(Map<String,Object> report, Map<String,Object> state) { }

    static Map<String,Object> state(Object raw) {
        if (raw == null) return new LinkedHashMap<>(Map.of("turns",0,"noProgress",0,"askedFields",List.of(),"reviewFields",List.of(),"status","CONTINUE"));
        if (!(raw instanceof Map<?,?> m) || !Set.of("turns","noProgress","askedFields","reviewFields","status","nextField","nextReason","askedReasons","unavailableReasons","neededReasons","knownEvidence","urgentReview").containsAll(m.keySet())) throw invalid();
        int turns = integer(m.get("turns"), 3), stalled = integer(m.get("noProgress"), 2);
        List<String> asked = fields(m.get("askedFields"), 3), review = fields(m.get("reviewFields"), 4);
        Object status = m.get("status");
        if (!(status instanceof String) || !Set.of("CONTINUE","COMPLETE","LIMIT","NO_PROGRESS","MANUAL_REVIEW").contains(status)) throw invalid();
        if (m.get("nextField") != null && !GROUPS.contains(m.get("nextField"))) throw invalid();
        var result = new LinkedHashMap<String,Object>(); result.put("turns", turns); result.put("noProgress", stalled);
        result.put("askedFields", asked); result.put("reviewFields", review); result.put("status", status);
        if(m.get("nextReason")!=null){if(!ClarificationPolicy.REASONS.contains(m.get("nextReason")))throw invalid();result.put("nextReason",m.get("nextReason"));}
        for(String key:List.of("askedReasons","unavailableReasons","neededReasons"))if(m.get(key)!=null){
            if(!(m.get(key) instanceof List<?> values)||values.size()>7||values.stream().anyMatch(v->!ClarificationPolicy.REASONS.contains(v)))throw invalid();
            result.put(key,List.copyOf(values));
        }
        if(m.get("knownEvidence")!=null){
            if(!(m.get("knownEvidence") instanceof Map<?,?> facts)||!Set.copyOf(ClarificationPolicy.FACTS).containsAll(facts.keySet())||facts.values().stream().anyMatch(v->!(v instanceof String s)||s.length()>200))throw invalid();
            result.put("knownEvidence",new LinkedHashMap<>((Map<String,String>)facts));
        }
        return result;
    }
    static boolean exhausted(Map<String,Object> state) { return number(state,"turns") >= 3 || number(state,"noProgress") >= 2; }

    static Outcome apply(String intent, Map<String,Object> extracted, Map<String,Object> previous,
                         Map<String,Object> updates, String answer, String field, Map<String,Object> oldState) {
        boolean follow = field != null;
        var report = new LinkedHashMap<>(follow ? previous : extracted);
        Set<String> review = new LinkedHashSet<>((List<String>)oldState.getOrDefault("reviewFields",List.of()));
        if (follow && !exhausted(oldState)) {
            for (String group : GROUPS) {
                Map<?,?> update = updates.get(group) instanceof Map<?,?> m ? m : Map.of();
                String action = Objects.toString(update.get("action"), "KEEP"), evidence = Objects.toString(update.get("evidence"), "");
                if (action.equals("KEEP")) continue;
                if (evidence.isBlank() || !answer.contains(evidence)) { review.add(group); continue; }
                if (action.equals("CLEAR")) {
                    if (explicitClear(group, answer)) { clear(report, group); review.remove(group); }
                    else review.add(group);
                } else if (action.equals("CLARIFY")) {
                    // A vague answer never destroys known facts. An explicit unresolved alternative is different.
                    if (group.equals("LOCATION") && (BUILDING.matcher(answer).find() || ROOM.matcher(answer).find())) {
                        var mentioned = new LinkedHashSet<String>();
                        var matcher = BUILDING.matcher(answer); while (matcher.find()) mentioned.add(normalize(matcher.group(1)));
                        String knownBuilding = text(report,"building");
                        if (knownBuilding != null && (mentioned.isEmpty() || mentioned.equals(Set.of(normalize(knownBuilding))))) {
                            // Uncertain room/floor does not contradict an unchanged, known building.
                            report.put("floor",null); report.put("locationText",knownBuilding);
                        } else clear(report, group);
                    }
                    review.add(group);
                } else if (action.equals("SET")) {
                    if (group.equals("LOCATION")) mergePlace(report, extracted, answer, evidence, review);
                    else if (group.equals("CATEGORY")) {
                        if (!UNCERTAIN.matcher(evidence).find() && validText(evidence)) {
                            report.put("category",extracted.get("category")); report.put("subcategory",extracted.get("subcategory")); review.remove(group);
                        } else review.add(group);
                    } else {
                        String key = group.equals("SYMPTOM") ? "symptom" : "occurredAtText";
                        if (validText(text(extracted,key)) && (!group.equals("OCCURRED_AT") || !UNCERTAIN.matcher(evidence).find())) {
                            report.put(key,extracted.get(key)); review.remove(group);
                        } else review.add(group);
                    }
                }
            }
            report.put("emergencyHint", Boolean.TRUE.equals(previous.get("emergencyHint")) || Boolean.TRUE.equals(extracted.get("emergencyHint")));
        }
        String normalizedFloor=AiService.normalizeFloor(text(report,"floor"));
        report.put("floor", normalizedFloor!=null && normalizedFloor.matches("B?[1-9]\\d{0,2}") ? normalizedFloor : null);
        List<String> missing = new ArrayList<>();
        // Ask about the actual problem first. A place is not required for an information/academic request.
        boolean newSymptom = !follow || updates.get("SYMPTOM") instanceof Map<?,?> update && "SET".equals(update.get("action"));
        boolean modelNeedsSymptom = newSymptom && extracted.get("missingFields") instanceof List<?> fields && fields.contains("SYMPTOM");
        if (modelNeedsSymptom) review.add("SYMPTOM");
        if (!specificSymptom(text(report,"symptom")) || modelNeedsSymptom || review.contains("SYMPTOM")) missing.add("SYMPTOM");
        boolean needsPlace = !"QUESTION".equals(intent) && !"ACADEMIC".equals(report.get("category"));
        if (needsPlace && (!validPlace(report) || review.contains("LOCATION"))) missing.add("LOCATION");
        report.put("missingFields", missing);
        int turns = number(oldState,"turns") + (follow && !exhausted(oldState) ? 1 : 0);
        boolean progress = CONTENT.stream().anyMatch(k -> !Objects.equals(previous.get(k), report.get(k)) && report.get(k) != null);
        int noProgress = follow ? (progress ? 0 : Math.min(2,number(oldState,"noProgress") + 1)) : 0;
        if (exhausted(oldState)) noProgress = number(oldState,"noProgress");
        var asked = new ArrayList<>((List<String>)oldState.getOrDefault("askedFields",List.of()));
        if (follow && asked.size()<3 && !exhausted(oldState)) asked.add(field);
        String status = missing.isEmpty() ? (review.isEmpty() ? "COMPLETE" : "MANUAL_REVIEW")
                : turns >= 3 ? "LIMIT" : noProgress >= 2 ? "NO_PROGRESS" : "CONTINUE";
        String next = null, question = null;
        if (status.equals("CONTINUE")) {
            next = missing.getFirst();
            question = next.equals("LOCATION") ? (review.contains("LOCATION") ? "장소 정보가 서로 달라 확인이 필요해요. 확실한 건물이나 장소를 알려주세요." : "어느 건물이나 장소에서 발생했나요?")
                    : "QUESTION".equals(intent) || "ACADEMIC".equals(report.get("category")) ? "어떤 내용을 문의하고 싶으신가요? 궁금한 점이나 겪고 있는 문제를 조금만 구체적으로 알려주세요."
                    : "어떤 점이 불편한가요? 실제 문제를 알려주세요.\n예: 냉난방, 책상의자, 소음";
        }
        report.put("followUpQuestion",question);
        var state = new LinkedHashMap<String,Object>(); state.put("turns",turns); state.put("noProgress",noProgress);
        state.put("askedFields",asked); state.put("reviewFields",List.copyOf(review)); state.put("status",status); state.put("nextField",next);
        return new Outcome(report,state);
    }

    private static void mergePlace(Map<String,Object> result, Map<String,Object> extracted, String answer, String evidence, Set<String> review) {
        String building = text(extracted,"building"), floor = null, room = null;
        boolean explicitBuilding = building != null && normalize(answer).contains(normalize(building));
        var buildings=new LinkedHashSet<String>();var bm=BUILDING.matcher(answer);while(bm.find())buildings.add(bm.group(1));
        boolean uncertainBuilding=explicitBuilding&&normalize(answer).matches(".*"+Pattern.quote(normalize(building))+"(?:인지|일수|인지는).*" );
        if (uncertainBuilding || buildings.size()>1&&UNCERTAIN.matcher(evidence).find()) { clear(result,"LOCATION"); review.add("LOCATION"); return; }
        boolean buildingChanged = explicitBuilding && !Objects.equals(normalize(building),normalize(text(result,"building")));
        if (buildingChanged) { result.put("building",building); result.put("floor",null); result.put("locationText",building); }
        var floors=new LinkedHashSet<String>();var fm=FLOOR.matcher(answer);while(fm.find())floors.add((fm.group(1)==null?"":"B")+fm.group(2));
        boolean correction=answer.matches("(?s).*(아니고|아니라|아니에요|말고|정정|수정).*" );
        String chosenFloor=AiService.normalizeFloor(text(extracted,"floor"));
        if(floors.size()>1){
            if(correction&&floors.contains(chosenFloor))floor=chosenFloor;
            else {result.put("floor",null);result.put("locationText",result.get("building"));review.add("LOCATION");return;}
        }else if(!floors.isEmpty())floor=floors.iterator().next();
        var rm=ROOM.matcher(answer); if(rm.find()) room=rm.group(1);
        // Multiple alternatives without a single unambiguous extracted choice must be clarified.
        if (room != null && rm.find() && !answer.matches("(?s).*(아니|말고|정정|수정).*")) { review.add("LOCATION"); clear(result,"LOCATION"); return; }
        String selectedRoom = roomOf(text(extracted,"locationText"));
        if (selectedRoom != null && normalize(answer).contains(normalize(selectedRoom+"호"))) room=selectedRoom;
        String inferred = room != null && room.matches("[1-9]\\d{2,3}") ? Integer.toString(Integer.parseInt(room)/100) : null;
        boolean conflict = floor != null && inferred != null && !floor.startsWith("B") && !floor.equals(inferred);
        if (conflict) {
            if(explicitBuilding) result.put("building",building);
            result.put("floor",null); result.put("locationText",result.get("building")); review.add("LOCATION"); return;
        }
        if (explicitBuilding) result.put("building",building);
        if (floor == null && inferred != null) floor=inferred;
        if (floor != null) result.put("floor",floor);
        if (explicitBuilding || floor != null || room != null) {
            String oldRoom=roomOf(text(result,"locationText"));
            // Floor-only corrections invalidate an old room; a changed building invalidates all old details.
            if(room==null && !buildingChanged && floor==null) room=oldRoom;
            result.put("locationText",place(text(result,"building"),text(result,"floor"),room));
            review.remove("LOCATION");
        } else if (validText(text(extracted,"locationText")) && normalize(answer).contains(normalize(text(extracted,"locationText")))) {
            result.put("locationText",extracted.get("locationText")); result.put("building",null); result.put("floor",null); review.remove("LOCATION");
        } else review.add("LOCATION");
    }
    static boolean validText(String value) { return value != null && normalize(value).length() >= 2 && !EMPTY.matcher(normalize(value)).matches() && !normalize(value).matches("(?:잘)?모르.*|알수없.*|기억안.*"); }
    static boolean specificSymptom(String value) {
        if (!validText(value)) return false;
        // Supplement the model's semantic extraction for common vague complaints; not an exhaustive classifier.
        String n = normalize(value).replaceAll("[.!?。]", "");
        return !n.matches("(?:(?:.*(?:강의실|교실|건물|학교|캠퍼스|시설|[가-힣0-9]+관)(?:에서|에|이|가|은|는)?)|(?:너무|좀|정말|많이|매우))*"
                + "(?:불편(?:해요|합니다|함|하다|해|해졌어요)?|문제(?:가있어요|가있습니다|있음|있어요)?|이상(?:해요|합니다|해|함|이있어요))");
    }
    private static boolean validPlace(Map<String,Object> r) {
        String place=text(r,"locationText"), building=text(r,"building");
        return validText(place) && (validText(building) || place.matches(".*(?:정문|후문|운동장|주차장|기숙사|도서관|체육관|정류장|농구장|축구장|테니스장|광장).*"));
    }
    private static String place(String building,String floor,String room) {
        var parts=new ArrayList<String>(); if(building!=null)parts.add(building); if(floor!=null&&room==null)parts.add(floor+"층"); if(room!=null)parts.add(room+"호"); return parts.isEmpty()?null:String.join(" ",parts);
    }
    private static String roomOf(String text) { if(text==null)return null;var m=ROOM.matcher(text);return m.find()?m.group(1):null; }
    static boolean explicitClear(String group,String answer) {
        String n=normalize(answer); if(!n.matches(".*(?:삭제|지워|비워|제거|취소).*"))return false;
        if(n.matches(".*(?:삭제|제거|취소)하지.*|.*(?:지우|비우)지.*|.*(?:지워|비워).*(?:말|않).*"))return false;
        return switch(group){case "LOCATION"->n.matches(".*(?:장소|위치|건물|호실).*" );case "SYMPTOM"->n.matches(".*(?:증상|문제상황).*" );case "OCCURRED_AT"->n.matches(".*(?:시간|시점|날짜|발생일).*" );default->n.matches(".*(?:유형|분류).*" );};
    }
    private static void clear(Map<String,Object> report,String group) {
        switch(group){case "LOCATION"->{report.put("locationText",null);report.put("building",null);report.put("floor",null);}case "SYMPTOM"->report.put("symptom",null);case "OCCURRED_AT"->report.put("occurredAtText",null);case "CATEGORY"->{report.put("category","OTHER");report.put("subcategory","UNKNOWN");}}
    }
    private static int integer(Object value,int max) { if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>max)throw invalid();return n.intValue(); }
    private static List<String> fields(Object raw,int max) { if(raw==null)return List.of();if(!(raw instanceof List<?> a)||a.size()>max||a.stream().anyMatch(x->!GROUPS.contains(x)))throw invalid();return a.stream().map(Object::toString).toList(); }
    private static int number(Map<String,Object> m,String key) { return ((Number)m.getOrDefault(key,0)).intValue(); }
    private static String text(Map<String,Object> m,String key) { return m.get(key) instanceof String s && !s.isBlank()?s.strip():null; }
    private static String normalize(String value) { return value==null?"":value.replaceAll("\\s+","").toLowerCase(Locale.ROOT); }
    private static ApiException invalid() { return new ApiException(400,"VALIDATION_ERROR","추가 질문 상태를 확인하세요."); }
}
