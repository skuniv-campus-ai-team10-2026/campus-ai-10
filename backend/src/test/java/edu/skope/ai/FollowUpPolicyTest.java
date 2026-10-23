package edu.skope.ai;

import edu.skope.service.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic policy tests. These do not measure LLM extraction accuracy. */
class FollowUpPolicyTest {
    @ParameterizedTest @ValueSource(strings={"강의실이 너무 불편해요","은주2관에 문제가 있어요","불편해요","시설이 이상해요"})
    void vagueComplaintNeedsActualProblemBeforePlace(String symptom){
        var outcome=FollowUpPolicy.apply("UNKNOWN",report("symptom",symptom,"building",null,"locationText",null),Map.of(),Map.of(),"",null,FollowUpPolicy.state(null));
        assertEquals("SYMPTOM",outcome.state().get("nextField"));
    }
    @Test void academicQuestionAndConcreteAcademicProblemDoNotNeedPlace(){
        for(String intent:List.of("QUESTION","REPORT")){
            var outcome=FollowUpPolicy.apply(intent,report("category","ACADEMIC","subcategory","STUDENT_RECORD","symptom","휴학 신청 처리 상태가 바뀌지 않음","building",null,"locationText",null,"occurredAtText",null),Map.of(),Map.of(),"",null,FollowUpPolicy.state(null));
            assertEquals("COMPLETE",outcome.state().get("status"));
        }
    }
    @Test void semanticMissingProblemSurvivesUnrelatedLocationAnswer(){
        var first=FollowUpPolicy.apply("REPORT",report("symptom","강의실 이용 불편","missingFields",List.of("SYMPTOM")),Map.of(),Map.of(),"",null,FollowUpPolicy.state(null));
        assertEquals("SYMPTOM",first.state().get("nextField"));
        var next=FollowUpPolicy.apply("REPORT",report(),first.report(),updates("LOCATION","북악관 502호"),"북악관 502호","SYMPTOM",first.state());
        assertEquals("SYMPTOM",next.state().get("nextField"));
    }
    @Test void correctingOnlySymptomPreservesManuallyConfirmedOtherFields() {
        var previous = report();
        var extracted = report("building", null, "floor", null, "locationText", null,
                "occurredAtText", null, "category", "OTHER", "subcategory", "UNKNOWN", "symptom", "화면이 깜빡임");
        var outcome = follow(previous, extracted, update("SYMPTOM", "SET", "화면이 깜빡여요"), "화면이 깜빡여요");
        assertEquals("화면이 깜빡임", outcome.report().get("symptom"));
        for (String field : List.of("building", "floor", "locationText", "occurredAtText", "category", "subcategory"))
            assertEquals(previous.get(field), outcome.report().get(field), field);
        assertEquals("COMPLETE", outcome.state().get("status"));
    }

    @Test void keepIgnoresReextractedChangesWithoutAnExplicitUpdate() {
        var previous = report();
        var outcome = follow(previous, report("building", "다른관", "symptom", "다른 증상", "occurredAtText", null),
                Map.of(), "앞에서 말한 내용 그대로예요");
        for (String field : List.of("building", "floor", "locationText", "occurredAtText", "category", "subcategory", "symptom"))
            assertEquals(previous.get(field), outcome.report().get(field), field);
    }

    @Test void oneAnswerCanFillPlaceTimeAndSymptomDespiteBeingAskedForLocation() {
        var previous = report("building", null, "floor", null, "locationText", null, "symptom", null, "occurredAtText", null);
        var extracted = report("building", "유담관", "floor", "4", "locationText", "유담관 401호",
                "symptom", "화면이 10분마다 깜빡임", "occurredAtText", "어제부터");
        String answer = "유담관 401호에서 어제부터 화면이 10분마다 깜빡여요";
        var updates = updates("LOCATION", "유담관 401호", "OCCURRED_AT", "어제부터", "SYMPTOM", "화면이 10분마다 깜빡여요");
        var outcome = follow(previous, extracted, updates, answer);
        assertEquals("유담관", outcome.report().get("building"));
        assertEquals("4", outcome.report().get("floor"));
        assertTrue(outcome.report().get("locationText").toString().contains("401호"));
        assertEquals("어제부터", outcome.report().get("occurredAtText"));
        assertEquals("화면이 10분마다 깜빡임", outcome.report().get("symptom"));
        assertEquals(List.of(), outcome.report().get("missingFields"));
        assertNull(outcome.report().get("followUpQuestion"));
        assertEquals("COMPLETE", outcome.state().get("status"));
    }

    @Test void changingBuildingAloneClearsStaleFloorAndRoom() {
        var outcome = follow(report(), report("building", "유담관"), update("LOCATION", "SET", "유담관"),
                "북악관이 아니라 유담관이에요");
        assertEquals("유담관", outcome.report().get("building"));
        assertEquals("유담관", outcome.report().get("locationText"));
        assertNull(outcome.report().get("floor"));
        assertEquals("어제부터", outcome.report().get("occurredAtText"));
        assertEquals("와이파이가 끊김", outcome.report().get("symptom"));
    }

    @Test void definiteBuildingCorrectionIsNotLostBecauseOtherPlaceDetailsAreUnknown() {
        var outcome = follow(report(), report("building", "유담관", "floor", null, "locationText", "유담관"),
                update("LOCATION", "SET", "북악관이 아니라 유담관이에요."),
                "북악관이 아니라 유담관이에요. 층과 호실은 잘 모르겠어요.");
        assertEquals("유담관", outcome.report().get("building"));
        assertEquals("유담관", outcome.report().get("locationText"));
        assertNull(outcome.report().get("floor"));
        assertFalse(review(outcome).contains("LOCATION"));
    }

    @Test void modelClarifyingConflictingRoomKeepsThePreviouslyConfirmedBuilding() {
        var outcome = follow(report(), report("floor", null), update("LOCATION", "CLARIFY", "북악관 2층 507호"),
                "북악관 2층 507호였던 것 같아요");
        assertEquals("북악관", outcome.report().get("building"));
        assertNull(outcome.report().get("floor"));
        assertEquals("북악관", outcome.report().get("locationText"));
        assertTrue(review(outcome).contains("LOCATION"));
        assertNotNull(outcome.report().get("followUpQuestion"));
    }

    @Test void explicitFloorCorrectionUsesCorrectedFloorAndInvalidatesOldRoom() {
        var outcome = follow(report(), report("floor", "3"), update("LOCATION", "SET", "3층"),
                "5층이 아니라 3층으로 정정할게요");
        assertEquals("북악관", outcome.report().get("building"));
        assertEquals("3", outcome.report().get("floor"));
        assertEquals("북악관 3층", outcome.report().get("locationText"));
        assertFalse(review(outcome).contains("LOCATION"));
    }

    @Test void explicitRoomCorrectionUsesSelectedRoomAndConsistentFloor() {
        var outcome = follow(report(), report("floor", "6", "locationText", "북악관 601호"),
                update("LOCATION", "SET", "601호"), "502호가 아니라 601호입니다");
        assertEquals("6", outcome.report().get("floor"));
        assertTrue(outcome.report().get("locationText").toString().contains("601호"));
        assertFalse(outcome.report().get("locationText").toString().contains("502호"));
    }

    @Test void conflictingExplicitFloorAndRoomAreNotSilentlyConfirmed() {
        var outcome = follow(report(), report("floor", "2", "locationText", "북악관 2층 502호"),
                update("LOCATION", "SET", "북악관 2층 502호"), "북악관 2층 502호예요");
        assertEquals("북악관", outcome.report().get("building"));
        assertNull(outcome.report().get("floor"));
        assertEquals("북악관", outcome.report().get("locationText"));
        assertTrue(review(outcome).contains("LOCATION"));
        assertNotNull(outcome.report().get("followUpQuestion"));
    }

    @Test void definiteNewBuildingSurvivesAmbiguousFloorsWithoutRetainingOldBuilding() {
        var outcome = follow(report(), report("building", "유담관", "floor", "2", "locationText", "유담관 2층"),
                update("LOCATION", "SET", "유담관 2층인지 3층인지"), "유담관 2층인지 3층인지 모르겠어요");
        assertEquals("유담관", outcome.report().get("building"));
        assertEquals("유담관", outcome.report().get("locationText"));
        assertNull(outcome.report().get("floor"));
        assertTrue(review(outcome).contains("LOCATION"));
    }

    @Test void ambiguousBuildingAlternativesRequireReviewInsteadOfInventingChoice() {
        var outcome = follow(report(), report("building", "유담관", "locationText", "유담관"),
                update("LOCATION", "SET", "북악관인지 유담관인지"), "북악관인지 유담관인지 모르겠어요");
        assertNull(outcome.report().get("building"));
        assertNull(outcome.report().get("floor"));
        assertNull(outcome.report().get("locationText"));
        assertTrue(review(outcome).contains("LOCATION"));
        assertEquals("어제부터", outcome.report().get("occurredAtText"));
    }

    @Test void vagueClarificationDoesNotErasePreviouslyKnownPlace() {
        var outcome = follow(report(), report("locationText", "거기요", "building", null, "floor", null),
                update("LOCATION", "CLARIFY", "거기요"), "거기요");
        assertEquals("북악관 5층 502호", outcome.report().get("locationText"));
        assertEquals("북악관", outcome.report().get("building"));
        assertEquals("5", outcome.report().get("floor"));
        assertTrue(review(outcome).contains("LOCATION"));
    }

    @Test void negatedDeletionCannotErasePlaceEvenIfModelMislabelsAction() {
        var outcome = follow(report(), report("locationText", null, "building", null, "floor", null),
                update("LOCATION", "CLEAR", "장소를 삭제하지 마세요"), "장소를 삭제하지 마세요");
        assertEquals("북악관 5층 502호", outcome.report().get("locationText"));
        assertEquals("북악관", outcome.report().get("building"));
        assertEquals("5", outcome.report().get("floor"));
        assertTrue(review(outcome).contains("LOCATION"));
    }

    @Test void explicitDeletionClearsOnlyRequestedOptionalTimeAndDoesNotRequireItAgain() {
        var outcome = follow(report(), report(), update("OCCURRED_AT", "CLEAR", "발생 시점은 삭제해주세요"),
                "발생 시점은 삭제해주세요");
        assertNull(outcome.report().get("occurredAtText"));
        assertEquals("북악관 5층 502호", outcome.report().get("locationText"));
        assertEquals("와이파이가 끊김", outcome.report().get("symptom"));
        assertEquals(List.of(), outcome.report().get("missingFields"));
        assertNull(outcome.report().get("followUpQuestion"));
        assertEquals("COMPLETE", outcome.state().get("status"));
    }

    @Test void ungroundedModelEvidenceCannotChangeInformation() {
        var outcome = follow(report(), report("symptom", "누수 발생"), update("SYMPTOM", "SET", "물이 떨어져요"),
                "와이파이가 끊기는 것은 맞아요");
        assertEquals("와이파이가 끊김", outcome.report().get("symptom"));
        assertTrue(review(outcome).contains("SYMPTOM"));
    }

    @Test void uncertainTimeDoesNotEraseCertainPlaceInSameAnswer() {
        var outcome = follow(report("locationText", null, "building", null, "floor", null),
                report("locationText", "유담관 401호", "building", "유담관", "floor", "4"),
                update("LOCATION", "SET", "유담관 401호"), "유담관 401호이고 시작 시간은 모르겠어요");
        assertEquals("유담관", outcome.report().get("building"));
        assertEquals("4", outcome.report().get("floor"));
        assertTrue(outcome.report().get("locationText").toString().contains("401호"));
        assertFalse(review(outcome).contains("LOCATION"));
    }

    @Test void uncertainLocationDoesNotPreventExplicitTimeCorrection() {
        var outcome = follow(report(), report("occurredAtText", "오늘 오전부터"),
                update("OCCURRED_AT", "SET", "오늘 오전부터"), "시작은 오늘 오전부터이고 정확한 층은 모르겠어요");
        assertEquals("오늘 오전부터", outcome.report().get("occurredAtText"));
        assertFalse(review(outcome).contains("OCCURRED_AT"));
    }

    @Test void uncertainTimeDoesNotPreventExplicitCategoryCorrection() {
        var outcome = follow(report(), report("category", "FACILITY", "subcategory", "LEAK"),
                update("CATEGORY", "SET", "누수 문제"), "와이파이가 아니라 누수 문제인데 시작 시간은 모르겠어요");
        assertEquals("FACILITY", outcome.report().get("category"));
        assertEquals("LEAK", outcome.report().get("subcategory"));
        assertFalse(review(outcome).contains("CATEGORY"));
    }

    @Test void explicitUnsupportedTopicCanReplaceAnIncorrectSupportedCategory() {
        var outcome = follow(report(), report("category", "OTHER", "subcategory", "UNKNOWN"),
                update("CATEGORY", "SET", "책상이 부러졌어요"), "인터넷 문제가 아니라 책상이 부러졌어요");
        assertEquals("OTHER", outcome.report().get("category"));
        assertEquals("UNKNOWN", outcome.report().get("subcategory"));
        assertFalse(review(outcome).contains("CATEGORY"));
        assertEquals("북악관", outcome.report().get("building"));
    }

    @Test void uncertainTypeCorrectionDoesNotChooseNewCategory() {
        var outcome = follow(report(), report("category", "FACILITY", "subcategory", "LEAK"),
                update("CATEGORY", "CLARIFY", "누수인지 모르겠어요"), "누수인지 모르겠어요");
        assertEquals("IT", outcome.report().get("category"));
        assertEquals("WIFI", outcome.report().get("subcategory"));
        assertTrue(review(outcome).contains("CATEGORY"));
        assertEquals("MANUAL_REVIEW", outcome.state().get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"거기요", "여기요", "그곳", "모르겠어요", "문제", "불편해요", "학교"})
    void nonSpecificWordsDoNotCountAsUsefulLocationOrSymptom(String word) {
        var initial = initial(report("locationText", word, "building", null, "floor", null, "symptom", word));
        assertEquals(List.of("SYMPTOM", "LOCATION"), initial.report().get("missingFields"));
        assertNotNull(initial.report().get("followUpQuestion"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"본관", "A관"})
    void shortButSpecificBuildingNamesAreValid(String building) {
        var initial = initial(report("building", building, "locationText", building + " 1층", "floor", "1"));
        assertEquals(List.of(), initial.report().get("missingFields"));
        assertEquals("COMPLETE", initial.state().get("status"));
    }

    @Test void missingOptionalTimeDoesNotTriggerAnotherQuestion() {
        var initial = initial(report("occurredAtText", null));
        assertEquals(List.of(), initial.report().get("missingFields"));
        assertEquals("COMPLETE", initial.state().get("status"));
        assertNull(initial.report().get("followUpQuestion"));
    }

    @Test void knownLocationIsNotAskedAgainWhenOnlySymptomIsMissing() {
        var initial = initial(report("symptom", null));
        assertEquals(List.of("SYMPTOM"), initial.report().get("missingFields"));
        assertEquals("SYMPTOM", initial.state().get("nextField"));
        assertNotNull(initial.report().get("followUpQuestion"));
    }

    @Test void twoRepliesWithoutProgressStopQuestionsButPreserveDraftForManualReview() {
        var initial = initial(report("locationText", null, "building", null, "floor", null));
        var once = follow(initial.report(), initial.report(), Map.of(), "모르겠어요", "LOCATION", initial.state());
        assertEquals("CONTINUE", once.state().get("status"));
        assertEquals(1, once.state().get("noProgress"));
        assertNotNull(once.report().get("followUpQuestion"));
        var twice = follow(once.report(), once.report(), Map.of(), "잘 모르겠어요", "LOCATION", once.state());
        assertEquals("NO_PROGRESS", twice.state().get("status"));
        assertEquals(2, twice.state().get("turns"));
        assertEquals(List.of("LOCATION"), twice.report().get("missingFields"));
        assertNull(twice.report().get("followUpQuestion"));
        assertEquals("와이파이가 끊김", twice.report().get("symptom"));
        assertTrue(FollowUpPolicy.exhausted(twice.state()));
    }

    @Test void threeRepliesAreMaximumEvenWhenUnrelatedInformationKeepsChanging() {
        var outcome = initial(report("symptom", null, "locationText", null, "building", null, "floor", null));
        for (int turn = 1; turn <= 3; turn++) {
            String place = "유담관 " + turn + "층";
            var extracted = report("symptom", null, "building", "유담관", "floor", Integer.toString(turn), "locationText", place);
            outcome = follow(outcome.report(), extracted, update("LOCATION", "SET", place), place,
                    "SYMPTOM", outcome.state());
        }
        assertEquals("LIMIT", outcome.state().get("status"));
        assertEquals(3, outcome.state().get("turns"));
        assertEquals(0, outcome.state().get("noProgress"));
        assertEquals(List.of("SYMPTOM"), outcome.report().get("missingFields"));
        assertNull(outcome.report().get("followUpQuestion"));
        assertTrue(FollowUpPolicy.exhausted(outcome.state()));
    }

    @Test void exhaustedStateDoesNotApplyAnotherAnalysisOrGrowTheCounter() {
        var previous = report("locationText", null, "building", null, "floor", null);
        var state = new LinkedHashMap<>(FollowUpPolicy.state(null));
        state.put("turns", 3); state.put("status", "LIMIT");
        var outcome = follow(previous, report(), update("LOCATION", "SET", "북악관 502호"),
                "북악관 502호", "LOCATION", state);
        assertNull(outcome.report().get("locationText"));
        assertEquals(3, outcome.state().get("turns"));
        assertNull(outcome.report().get("followUpQuestion"));
        assertEquals("LIMIT", outcome.state().get("status"));
    }

    @Test void stateValidationRejectsUnboundedOrInventedClientState() {
        var excessive = new LinkedHashMap<>(FollowUpPolicy.state(null)); excessive.put("turns", 4);
        assertThrows(ApiException.class, () -> FollowUpPolicy.state(excessive));
        var injected = new LinkedHashMap<>(FollowUpPolicy.state(null)); injected.put("conversation", List.of("pretend history"));
        assertThrows(ApiException.class, () -> FollowUpPolicy.state(injected));
        var wrongField = new LinkedHashMap<>(FollowUpPolicy.state(null)); wrongField.put("reviewFields", List.of("ADMIN"));
        assertThrows(ApiException.class, () -> FollowUpPolicy.state(wrongField));
    }

    private static FollowUpPolicy.Outcome initial(Map<String, Object> extracted) {
        return FollowUpPolicy.apply("REPORT", extracted, Map.of(), Map.of(), "", null, FollowUpPolicy.state(null));
    }

    private static FollowUpPolicy.Outcome follow(Map<String, Object> previous, Map<String, Object> extracted,
                                                Map<String, Object> updates, String answer) {
        return follow(previous, extracted, updates, answer, "LOCATION", FollowUpPolicy.state(null));
    }

    private static FollowUpPolicy.Outcome follow(Map<String, Object> previous, Map<String, Object> extracted,
                                                Map<String, Object> updates, String answer, String field,
                                                Map<String, Object> state) {
        return FollowUpPolicy.apply("REPORT", extracted, previous, updates, answer, field, state);
    }

    private static Map<String, Object> update(String group, String action, String evidence) {
        return Map.of(group, Map.of("action", action, "evidence", evidence));
    }

    private static Map<String, Object> updates(String... groupEvidencePairs) {
        var updates = new LinkedHashMap<String, Object>();
        for (int i = 0; i < groupEvidencePairs.length; i += 2)
            updates.put(groupEvidencePairs[i], Map.of("action", "SET", "evidence", groupEvidencePairs[i + 1]));
        return updates;
    }

    private static Map<String, Object> report(Object... replacements) {
        var report = new LinkedHashMap<String, Object>();
        report.put("category", "IT"); report.put("subcategory", "WIFI");
        report.put("building", "북악관"); report.put("floor", "5"); report.put("locationText", "북악관 5층 502호");
        report.put("symptom", "와이파이가 끊김"); report.put("occurredAtText", "어제부터");
        report.put("emergencyHint", false); report.put("missingFields", List.of()); report.put("followUpQuestion", null);
        for (int i = 0; i < replacements.length; i += 2) report.put((String) replacements[i], replacements[i + 1]);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static List<String> review(FollowUpPolicy.Outcome outcome) {
        return (List<String>) outcome.state().get("reviewFields");
    }
}
