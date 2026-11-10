package com.tripplanner.place.domain;

public enum PlaceCategory {
    SIGHT("관광"), FOOD("맛집"), SHOPPING("쇼핑"), NATURE("자연"), NIGHT("야경"), CULTURE("문화");

    private final String labelKo;

    PlaceCategory(String labelKo) {
        this.labelKo = labelKo;
    }

    /** 화면(frontend constants/messages.ts)과 같은 한국어 이름. 추천 이유 문장에 쓴다. */
    public String labelKo() {
        return labelKo;
    }
}
