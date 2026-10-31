package edu.skope.service;

import edu.skope.repository.Db;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Observed places are retained. Only candidate search scope may expand, never the claimed outage area. */
final class SimilarityPlace {
    private static final Pattern ROOM=Pattern.compile("(?<!\\d)([A-Za-z]?\\d{2,5})\\s*호(?:실)?");
    private static final Pattern BUILDING_WIDE=Pattern.compile("건물\\s*전체");
    private static final Pattern FLOOR_WIDE=Pattern.compile("(?:층|구역)\\s*전체");
    private static final Pattern DENIED_RANGE=Pattern.compile("^(?:가|는|의)?(?:문제|장애|범위)?(?:가|는)?(?:아니|아닌|아닙)");
    private static final Pattern ORDINAL=Pattern.compile("(?<!\\d)([1-9]\\d?)\\s*번(?:째)?");
    private static final Map<String,String> POSITION=Map.ofEntries(
            Map.entry("왼쪽|왼편|좌측","LEFT"),Map.entry("오른쪽|오른편|우측","RIGHT"),
            Map.entry("동쪽|동편|동측","EAST"),Map.entry("서쪽|서편|서측","WEST"),
            Map.entry("남쪽|남편|남측","SOUTH"),Map.entry("북쪽|북편|북측","NORTH"),
            Map.entry("출입문|입구|문\\s*옆|문\\s*위","ENTRY"),Map.entry("창가|창문\\s*(?:옆|쪽)","WINDOW"),
            Map.entry("남자\\s*화장실|남성\\s*화장실","MALE_RESTROOM"),
            Map.entry("여자\\s*화장실|여성\\s*화장실","FEMALE_RESTROOM"));
    record Match(boolean building,boolean floor,boolean room,boolean detail,boolean expanded,String scope) {
        boolean allowed(){return building&&floor&&room&&detail;}
    }
    static Match compare(Map<String,Object> a,Map<String,Object> b,boolean comparableVectors){
        String ba=norm(a.get("building")),bb=norm(b.get("building"));
        boolean building=!ba.isBlank()&&ba.equals(bb);
        String fa=norm(a.get("floor")).replace("층",""),fb=norm(b.get("floor")).replace("층","");
        boolean floor=fa.isBlank()||fb.isBlank()||fa.equals(fb);
        String ra=room(a),rb=room(b);boolean room=ra.isBlank()||rb.isBlank()||ra.equals(rb);
        String scope="LOCAL";boolean expanded=false,detail=!differentSpecificPlaces(a,b);
        // Broad rules without comparable provider vectors never prove that two rooms share an issue.
        if(building&&comparableVectors&&closeInTime(a,b)){
            if(wide(a,BUILDING_WIDE)&&wide(b,BUILDING_WIDE)){
                scope="STATED_BUILDING_RANGE";expanded=!floor||!room||!detail;floor=true;room=true;detail=true;
            }else if(!fa.isBlank()&&fa.equals(fb)&&wide(a,FLOOR_WIDE)&&wide(b,FLOOR_WIDE)){
                scope="STATED_AREA_RANGE";expanded=!room||!detail;room=true;detail=true;
            }else if(floor&&network(a)&&network(b)){
                scope="SHARED_NETWORK_SEARCH";expanded=!room;room=true;detail=true;
            }
        }
        return new Match(building,floor,room,detail,expanded,scope);
    }
    private static boolean wide(Map<String,Object> r,Pattern p){
        // Use the final merged symptom. An initial broad claim may have been corrected in a follow-up.
        // Also require the initial source to support this expansion; model-only broad claims are not evidence.
        return affirmativeRange(norm(Db.str(r,"symptom")),p)&&affirmativeRange(norm(Db.str(r,"originalText")),p);
    }
    private static boolean affirmativeRange(String text,Pattern p){
        var m=p.matcher(text);
        while(m.find())if(!DENIED_RANGE.matcher(text.substring(m.end())).find())return true;
        return false;
    }
    private static boolean network(Map<String,Object> r){
        return "WIFI".equals(r.get("subcategory"))&&norm(r.get("targetText")).matches("와이파이|wi-?fi|무선망|무선인터넷|인터넷");
    }
    private static boolean closeInTime(Map<String,Object> a,Map<String,Object> b){
        // Reported dates, when both supplied, are not silently replaced with submission dates.
        if(a.get("occurredOn")!=null&&b.get("occurredOn")!=null&&!a.get("occurredOn").equals(b.get("occurredOn")))return false;
        try{return Duration.between(SimilarityService.timestamp(a.get("createdAt")),SimilarityService.timestamp(b.get("createdAt"))).abs().compareTo(Duration.ofHours(24))<=0;}
        catch(RuntimeException missing){return false;}
    }
    static boolean differentSpecificPlaces(Map<String,Object> a,Map<String,Object> b){
        Set<String> x=anchors(a),y=anchors(b);
        for(var dimension:List.of(Set.of("LEFT","RIGHT"),Set.of("EAST","WEST"),Set.of("NORTH","SOUTH"),Set.of("ENTRY","WINDOW"),Set.of("MALE_RESTROOM","FEMALE_RESTROOM"))){
            var ax=new HashSet<>(x);ax.retainAll(dimension);var by=new HashSet<>(y);by.retainAll(dimension);
            if(ax.size()==1&&by.size()==1&&!ax.equals(by))return true;
        }
        var ax=new HashSet<>(x);ax.removeIf(v->!v.startsWith("ORDINAL:"));var by=new HashSet<>(y);by.removeIf(v->!v.startsWith("ORDINAL:"));
        return ax.size()==1&&by.size()==1&&!ax.equals(by);
    }
    private static Set<String> anchors(Map<String,Object> r){
        // The extracted main symptom avoids attributing a secondary, explicitly normal object to this fault.
        String text=Db.str(r,"locationText")+" "+Db.str(r,"symptom");var result=new HashSet<String>();
        POSITION.forEach((pattern,anchor)->{if(Pattern.compile(pattern).matcher(text).find())result.add(anchor);});
        var numbers=ORDINAL.matcher(text);while(numbers.find())result.add("ORDINAL:"+Integer.parseInt(numbers.group(1)));
        String[] words={"첫\\s*번째","두\\s*번째","세\\s*번째","네\\s*번째","다섯\\s*번째"};
        for(int i=0;i<words.length;i++)if(Pattern.compile(words[i]).matcher(text).find())result.add("ORDINAL:"+(i+1));
        return result;
    }
    private static String room(Map<String,Object> r){var m=ROOM.matcher(Db.str(r,"locationText"));return m.find()?m.group(1).toUpperCase(Locale.ROOT):"";}
    private static String norm(Object v){return Objects.toString(v,"").replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
}
