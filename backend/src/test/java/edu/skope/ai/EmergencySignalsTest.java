package edu.skope.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencySignalsTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "콘센트에서 타는 냄새가 나요", "감전됐어요", "콘센트에서 불꽃이 튀어요", "화재가 났습니다",
            "가스 냄새가 나요", "가스 누출이 의심돼요", "연기가 나고 있어요", "불이 났어요", "불났어요", "스파크가 튑니다",
            "불꽃은 없지만 타는 냄새가 납니다", "타는 냄새는 없고 콘센트에서 불꽃이 나요",
            "불꽃이 발생했고 타는 냄새는 없습니다", "불꽃이 있고 타는 냄새는 없습니다",
            "가스 누출은 아니지만 콘센트에서 스파크가 보여요", "불꽃은 없습니다. 타는 냄새가 나요",
            "불꽃이 보여요. 지금 타는 냄새는 없어요", "불꽃은 없어요\n타는 냄새가 나요",
            "불꽃이 없지는 않아요", "불꽃이 없다는 건 아닙니다", "불꽃이 없었던 것은 아닙니다", "가스 냄새가 없는 것 같아요",
            "가스 냄새가 없는지 모르겠어요", "가스 냄새가 나지 않는지는 확실하지 않아요",
            "어제 불꽃이 났지만 지금은 없습니다", "불꽃은 없었는데 이제 불꽃이 보여요",
            "화재 경보가 울리지 않아요", "감전된 사람은 없지만 바닥에 전선이 있어요", "가스 냄새",
            "연기가 나고, 불꽃은 없습니다", "불꽃은 없지만 연기가 나요", "불꽃, 연기, 타는 냄새가 납니다",
            "불꽃이나 연기, 타는 냄새가 없는지는 모르겠어요"
    })
    void affirmativeMixedAndUncertainCuesStillWarn(String input) {
        assertTrue(EmergencySignals.detected(input), input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "와이파이 연결 문의", "", "명진관 1층 전등 하나가 안 켜집니다. 불꽃이나 타는 냄새는 전혀 없습니다.",
            "불꽃은 없습니다", "타는 냄새는 전혀 없어요", "가스 냄새가 나지 않아요", "가스 냄새는 안 나요",
            "가스 누출은 없습니다", "스파크가 보이지 않습니다", "연기가 나지 않습니다", "화재가 아닙니다",
            "불꽃과 타는 냄새는 없어요", "불꽃이나 타는 냄새 또는 가스 냄새는 없습니다",
            "불꽃은 없고 타는 냄새도 없습니다", "불꽃이 없습니다. 가스 냄새가 나지 않아요",
            "불꽃이 발생하지 않습니다", "타는 냄새는 더 이상 나지 않습니다",
            "불꽃이나 연기, 타는 냄새는 전혀 없습니다", "불꽃, 연기, 타는 냄새는 없어요",
            "불꽃, 그리고 연기는 없습니다", "연기는 없고, 불꽃도 없습니다", "연기 수업 문의", "연기학과 강의실 문의"
    })
    void onlyExplicitlyNegatedCuesDoNotWarn(String input) {
        assertFalse(EmergencySignals.detected(input), input);
    }
}
