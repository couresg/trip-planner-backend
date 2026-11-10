package com.tripplanner.recommend.domain;

/** 일정 밀도. 하루에 넣을 장소 수는 실측이 아닌 가정값이다 (docs/PRD.md 미결 사항). */
public enum Pace {
    RELAXED(2), NORMAL(3), PACKED(5);

    private final int placesPerDay;

    Pace(int placesPerDay) {
        this.placesPerDay = placesPerDay;
    }

    public int placesPerDay() {
        return placesPerDay;
    }
}
