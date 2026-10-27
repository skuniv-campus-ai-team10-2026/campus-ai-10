package edu.skope.domain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import edu.skope.service.ApiException;
public final class Codes {
    public enum Role { STUDENT, ADMIN }
    public enum Status { RECEIVED, IN_PROGRESS, RESOLVED }
    public enum RouteStatus { MATCHED, NEEDS_REVIEW, MANUAL }
    public enum PairDecision { CANDIDATE, SAME_INCIDENT, RELATED_DISTINCT, REJECTED }
    public enum RecurrenceDecision { NOT_EVALUATED, CANDIDATE, CONFIRMED, REJECTED }
    public static final Map<String,String> TYPES=Map.of("WIFI","IT","LEAK","FACILITY","ELECTRIC","FACILITY","STUDENT_RECORD","ACADEMIC","UNKNOWN","OTHER",
            "HVAC","FACILITY","WATER_DISPENSER","FACILITY","FURNITURE","FACILITY");
    public static void type(String category,String subcategory) {
        if(!category.equals(TYPES.get(subcategory))) throw new ApiException(400,"VALIDATION_ERROR","유형 조합이 올바르지 않습니다.");
    }

    /**
     * Handling area. A small fixed set: new objects (mirror, lab coat, pencil...) never add codes, they are
     * described by free-text targetText under one of these groups.
     */
    public static final List<String> WORK_GROUPS=List.of("IT_SUPPORT","ELECTRICAL_MAINTENANCE","FACILITY_MAINTENANCE",
            "SHARED_EQUIPMENT","ACADEMIC_ADMIN","GENERAL_REVIEW");
    /** Existing narrow types keep a fixed group; only UNKNOWN takes its group from the analysis. */
    public static final Map<String,String> TYPE_GROUPS=Map.of("WIFI","IT_SUPPORT","ELECTRIC","ELECTRICAL_MAINTENANCE",
            "LEAK","FACILITY_MAINTENANCE","HVAC","FACILITY_MAINTENANCE","WATER_DISPENSER","SHARED_EQUIPMENT",
            "FURNITURE","SHARED_EQUIPMENT","STUDENT_RECORD","ACADEMIC_ADMIN");
    /**
     * How the request relates to the school. CAMPUS_FACILITY: a fixed facility/equipment on campus (a place is needed).
     * SCHOOL_SERVICE: something the school provides or lends (no fault location needed).
     * PERSONAL_ON_CAMPUS: a personal item in a campus situation (general inquiry). OUT_OF_SCOPE: a personal request
     * the school does not handle. UNCERTAIN: the handling direction depends on it and it is not known yet.
     * A missing building name or a personal item alone does not make a request OUT_OF_SCOPE.
     */
    public static final Set<String> CAMPUS_RELEVANCE=Set.of("CAMPUS_FACILITY","SCHOOL_SERVICE","PERSONAL_ON_CAMPUS","OUT_OF_SCOPE","UNCERTAIN");

    /** Narrow types always map to their fixed group (requests cannot contradict them); UNKNOWN accepts a valid request. */
    public static String workGroup(String subcategory,Object requested) {
        String fixed=subcategory==null?null:TYPE_GROUPS.get(subcategory);
        if(fixed!=null)return fixed;
        return requested instanceof String s&&WORK_GROUPS.contains(s)?s:"GENERAL_REVIEW";
    }
    /**
     * Legacy rows, fixed facilities and still-uncertain objects keep the existing place rule;
     * school services, personal and out-of-scope requests do not need a fault location.
     */
    public static boolean needsPlace(Object campusRelevance) {
        return !"SCHOOL_SERVICE".equals(campusRelevance)&&!generalInquiry(campusRelevance);
    }
    /** Only the broad OTHER/UNKNOWN type uses campus relevance; narrow types keep their existing rules unchanged. */
    public static boolean broad(Object subcategory) {
        return "UNKNOWN".equals(subcategory);
    }
    /** Requests the school's facility staff should not receive automatically. */
    public static boolean generalInquiry(Object campusRelevance) {
        return "PERSONAL_ON_CAMPUS".equals(campusRelevance)||"OUT_OF_SCOPE".equals(campusRelevance);
    }
}
