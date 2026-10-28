-- docs/DB_SCHEMA.md 전체 스키마 (MySQL 8, utf8mb4)
-- 시각(instant)은 DATETIME(6) UTC, 시간표 시각은 운행일 기준 분(INT)으로 저장한다.

-- ===== 기준 정보 =====

CREATE TABLE city (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(10)  NOT NULL,
    name_ko      VARCHAR(50)  NOT NULL,
    name_en      VARCHAR(50)  NOT NULL,
    country_code CHAR(2)      NOT NULL,
    timezone     VARCHAR(40)  NOT NULL,
    center_lat   DECIMAL(9,6) NOT NULL,
    center_lng   DECIMAL(9,6) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_city_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE airport (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    iata     CHAR(3)      NOT NULL,
    city_id  BIGINT       NOT NULL,
    name_ko  VARCHAR(80)  NOT NULL,
    timezone VARCHAR(40)  NOT NULL,
    lat      DECIMAL(9,6) NOT NULL,
    lng      DECIMAL(9,6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_airport_iata UNIQUE (iata),
    CONSTRAINT fk_airport_city FOREIGN KEY (city_id) REFERENCES city (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE area (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    city_id     BIGINT       NOT NULL,
    name_ko     VARCHAR(50)  NOT NULL,
    description VARCHAR(200) NULL,
    lat         DECIMAL(9,6) NOT NULL,
    lng         DECIMAL(9,6) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_area_city_sort (city_id, sort_order),
    CONSTRAINT fk_area_city FOREIGN KEY (city_id) REFERENCES city (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE transport_route (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    airport_id          BIGINT       NOT NULL,
    area_id             BIGINT       NOT NULL,
    direction           VARCHAR(20)  NOT NULL,
    mode                VARCHAR(20)  NOT NULL,
    name_ko             VARCHAR(80)  NOT NULL,
    board_stop          VARCHAR(80)  NULL,
    alight_stop         VARCHAR(80)  NULL,
    duration_min        INT          NOT NULL,
    walk_min            INT          NOT NULL DEFAULT 0,
    fare_min            INT          NULL,
    fare_max            INT          NULL,
    currency            CHAR(3)      NOT NULL,
    is_24h              BOOLEAN      NOT NULL DEFAULT FALSE,
    schedule_type       VARCHAR(20)  NOT NULL,
    first_departure_min INT          NULL,
    last_departure_min  INT          NULL,
    headway_min         INT          NULL,
    boarding_tip        VARCHAR(300) NULL,
    note                VARCHAR(300) NULL,
    source_url          VARCHAR(300) NOT NULL,
    data_as_of          DATE         NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    INDEX idx_route_airport_area_dir (airport_id, area_id, direction),
    CONSTRAINT fk_route_airport FOREIGN KEY (airport_id) REFERENCES airport (id),
    CONSTRAINT fk_route_area FOREIGN KEY (area_id) REFERENCES area (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE transport_timetable (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    route_id      BIGINT      NOT NULL,
    day_type      VARCHAR(10) NOT NULL,
    departure_min INT         NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_timetable_route_day_dep (route_id, day_type, departure_min),
    CONSTRAINT fk_timetable_route FOREIGN KEY (route_id) REFERENCES transport_route (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE place (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    city_id            BIGINT       NOT NULL,
    google_place_id    VARCHAR(100) NULL,
    name_ko            VARCHAR(100) NOT NULL,
    category           VARCHAR(20)  NOT NULL,
    lat                DECIMAL(9,6) NOT NULL,
    lng                DECIMAL(9,6) NOT NULL,
    summary            VARCHAR(300) NULL,
    duration_min       INT          NOT NULL,
    opening_hours_json JSON         NOT NULL,
    always_open        BOOLEAN      NOT NULL DEFAULT FALSE,
    source             VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_place_google_place_id UNIQUE (google_place_id),
    INDEX idx_place_city_category (city_id, category),
    CONSTRAINT fk_place_city FOREIGN KEY (city_id) REFERENCES city (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ===== 사용자 데이터 =====

CREATE TABLE trip (
    id               BINARY(16)  NOT NULL,
    owner_token_hash CHAR(64)    NOT NULL,
    user_id          BIGINT      NULL,
    city_id          BIGINT      NOT NULL,
    area_id          BIGINT      NOT NULL,
    start_date       DATE        NOT NULL,
    end_date         DATE        NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_trip_owner_token_hash (owner_token_hash),
    CONSTRAINT fk_trip_city FOREIGN KEY (city_id) REFERENCES city (id),
    CONSTRAINT fk_trip_area FOREIGN KEY (area_id) REFERENCES area (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trip_flight (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    trip_id           BINARY(16)  NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    flight_no         VARCHAR(8)  NOT NULL,
    flight_date       DATE        NOT NULL,
    dep_airport_iata  CHAR(3)     NOT NULL,
    arr_airport_iata  CHAR(3)     NOT NULL,
    scheduled_dep_utc DATETIME(6) NOT NULL,
    revised_dep_utc   DATETIME(6) NULL,
    scheduled_arr_utc DATETIME(6) NOT NULL,
    revised_arr_utc   DATETIME(6) NULL,
    dep_terminal      VARCHAR(10) NULL,
    arr_terminal      VARCHAR(10) NULL,
    status            VARCHAR(20) NOT NULL,
    source            VARCHAR(20) NOT NULL,
    fetched_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trip_flight_trip_direction UNIQUE (trip_id, direction),
    CONSTRAINT fk_trip_flight_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE itinerary_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    trip_id      BINARY(16)   NOT NULL,
    place_id     BIGINT       NOT NULL,
    visit_date   DATE         NOT NULL,
    position     INT          NOT NULL,
    planned_time TIME         NULL,
    memo         VARCHAR(500) NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_itinerary_trip_date_pos (trip_id, visit_date, position),
    CONSTRAINT fk_itinerary_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE,
    CONSTRAINT fk_itinerary_place FOREIGN KEY (place_id) REFERENCES place (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ===== 캐시 =====

CREATE TABLE flight_cache (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    flight_no     VARCHAR(8)  NOT NULL,
    flight_date   DATE        NOT NULL,
    response_json JSON        NOT NULL,
    fetched_at    DATETIME(6) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_flight_cache_no_date UNIQUE (flight_no, flight_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
