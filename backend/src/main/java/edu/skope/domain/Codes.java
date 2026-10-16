package edu.skope.domain;
import java.util.Map;
import edu.skope.service.ApiException;
public final class Codes {
    public enum Role { STUDENT, ADMIN }
    public enum Status { RECEIVED, IN_PROGRESS, RESOLVED }
    public enum RouteStatus { MATCHED, NEEDS_REVIEW, MANUAL }
    public enum PairDecision { CANDIDATE, SAME_INCIDENT, RELATED_DISTINCT, REJECTED }
    public enum RecurrenceDecision { NOT_EVALUATED, CANDIDATE, CONFIRMED, REJECTED }
    public static final Map<String,String> TYPES=Map.of("WIFI","IT","LEAK","FACILITY","ELECTRIC","FACILITY","STUDENT_RECORD","ACADEMIC","UNKNOWN","OTHER");
    public static void type(String category,String subcategory) {
        if(!category.equals(TYPES.get(subcategory))) throw new ApiException(400,"VALIDATION_ERROR","유형 조합이 올바르지 않습니다.");
    }
}
