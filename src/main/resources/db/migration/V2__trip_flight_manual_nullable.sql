-- 수동 입력(manualOverride) 항공편은 한쪽 지점만 안다.
-- 가는 편은 도착(공항·시각)만, 오는 편은 출발(공항·시각)만 입력하므로 반대쪽 지점 컬럼을 NULL 허용으로 바꾼다.
ALTER TABLE trip_flight
    MODIFY dep_airport_iata  CHAR(3)     NULL,
    MODIFY arr_airport_iata  CHAR(3)     NULL,
    MODIFY scheduled_dep_utc DATETIME(6) NULL,
    MODIFY scheduled_arr_utc DATETIME(6) NULL;
