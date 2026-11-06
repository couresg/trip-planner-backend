-- 공휴일 (docs/DB_SCHEMA.md). 시각표 요일 유형 판정에 쓴다: 운행일이 해당 나라 공휴일이면 HOLIDAY.
-- 데이터는 시드(V106~)로 넣는다. 데이터가 없는 (나라, 연도)는 요일로만 판정하고 WARN 로그를 남긴다.
CREATE TABLE holiday (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    country_code CHAR(2)      NOT NULL,
    holiday_date DATE         NOT NULL,
    name         VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_holiday_country_date UNIQUE (country_code, holiday_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
