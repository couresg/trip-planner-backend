-- 오사카 기준 정보 시드 (docs/DB_SCHEMA.md 시드 데이터 정책)
-- 주의: 요금·시간표·영업시간은 모두 "추정"값이다. 운영사 공식 사이트로 검증 필요 (docs/PRD.md 미결 사항 참고).
-- ID는 API 예시·테스트에서 참조할 수 있도록 고정한다.
--   city 1 / airport 1 / area 11~14 / route 101~ (공항→지역), 201~ (지역→공항) / place 501~
-- 시간표 시각은 운행일 기준 분 (05:45 → 345, 00:30 → 1470).

INSERT INTO city (id, code, name_ko, name_en, country_code, timezone, center_lat, center_lng, active)
VALUES (1, 'OSA', '오사카', 'Osaka', 'JP', 'Asia/Tokyo', 34.693700, 135.502300, TRUE);

INSERT INTO airport (id, iata, city_id, name_ko, timezone, lat, lng)
VALUES (1, 'KIX', 1, '간사이 국제공항', 'Asia/Tokyo', 34.434700, 135.244000);

INSERT INTO area (id, city_id, name_ko, description, lat, lng, sort_order) VALUES
    (11, 1, '난바', '도톤보리·신사이바시 인접, 난카이선으로 공항 직결', 34.666400, 135.501100, 1),
    (12, 1, '우메다', '오사카역 일대, 쇼핑·교통 중심지', 34.702500, 135.495900, 2),
    (13, 1, '신오사카', '신칸센역, JR 하루카로 공항 직결', 34.733400, 135.500100, 3),
    (14, 1, '텐노지', '아베노 하루카스·신세카이 인접, JR로 공항 직결', 34.646500, 135.513500, 4);

-- ===== 공항 → 숙소 지역 (AIRPORT_TO_AREA) =====
INSERT INTO transport_route (id, airport_id, area_id, direction, mode, name_ko, board_stop, alight_stop,
    duration_min, walk_min, fare_min, fare_max, currency, is_24h, schedule_type,
    first_departure_min, last_departure_min, headway_min, boarding_tip, note, source_url, data_as_of, active) VALUES
    (101, 1, 11, 'AIRPORT_TO_AREA', 'RAIL', '난카이 라피트', '간사이공항역', '난바역',
        38, 5, 1490, 1490, 'JPY', FALSE, 'TIMETABLE', NULL, NULL, NULL,
        '제1터미널 2층 연결통로 → 에어로플라자 → 난카이선 개찰구', '추정: 시각표(매시 05·35분)와 요금은 공식 시간표로 검증 필요',
        'https://www.nankai.co.jp/traffic/', '2026-10-01', TRUE),
    (102, 1, 11, 'AIRPORT_TO_AREA', 'RAIL', '난카이 공항급행', '간사이공항역', '난바역',
        45, 5, 970, 970, 'JPY', FALSE, 'HEADWAY', 345, 1415, 30,
        '제1터미널 2층 연결통로 → 난카이선 개찰구', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.nankai.co.jp/traffic/', '2026-10-01', TRUE),
    (103, 1, 12, 'AIRPORT_TO_AREA', 'BUS', '공항 리무진버스 (오사카역·우메다)', '제1터미널 1층 버스 정류장', '오사카역(우메다)',
        60, 5, 1800, 1800, 'JPY', FALSE, 'HEADWAY', 410, 1380, 30,
        '제1터미널 1층 도착 로비 밖 버스 정류장 (승강장 번호는 현장 안내 확인)', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.okkbus.co.jp/', '2026-10-01', TRUE),
    (104, 1, 12, 'AIRPORT_TO_AREA', 'RAIL', 'JR 간쿠쾌속', '간사이공항역', '오사카역',
        70, 5, 1210, 1210, 'JPY', FALSE, 'HEADWAY', 350, 1380, 30,
        '제1터미널 2층 연결통로 → JR선 개찰구', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (105, 1, 13, 'AIRPORT_TO_AREA', 'RAIL', 'JR 특급 하루카', '간사이공항역', '신오사카역',
        50, 3, 2410, 3110, 'JPY', FALSE, 'TIMETABLE', NULL, NULL, NULL,
        '제1터미널 2층 연결통로 → JR선 개찰구 (지정석은 매표소에서 구매)', '추정: 시각표(매시 16·46분)와 요금 범위(자유석~지정석)는 공식 정보로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (106, 1, 14, 'AIRPORT_TO_AREA', 'RAIL', 'JR 간쿠쾌속', '간사이공항역', '텐노지역',
        50, 3, 1080, 1080, 'JPY', FALSE, 'HEADWAY', 350, 1400, 30,
        '제1터미널 2층 연결통로 → JR선 개찰구', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (107, 1, 14, 'AIRPORT_TO_AREA', 'BUS', '공항 리무진버스 (텐노지·아베노)', '제1터미널 1층 버스 정류장', '긴테츠 아베노바시역',
        50, 3, 1800, 1800, 'JPY', FALSE, 'HEADWAY', 420, 1350, 60,
        '제1터미널 1층 도착 로비 밖 버스 정류장 (승강장 번호는 현장 안내 확인)', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.okkbus.co.jp/', '2026-10-01', TRUE),
    (108, 1, 11, 'AIRPORT_TO_AREA', 'TAXI', '택시', '제1터미널 1층 택시 승강장', '난바',
        50, 0, 17000, 20000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '제1터미널 1층 도착 로비 밖 택시 승강장', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (109, 1, 12, 'AIRPORT_TO_AREA', 'TAXI', '택시', '제1터미널 1층 택시 승강장', '우메다',
        55, 0, 19000, 23000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '제1터미널 1층 도착 로비 밖 택시 승강장', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (110, 1, 13, 'AIRPORT_TO_AREA', 'TAXI', '택시', '제1터미널 1층 택시 승강장', '신오사카',
        55, 0, 20000, 25000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '제1터미널 1층 도착 로비 밖 택시 승강장', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (111, 1, 14, 'AIRPORT_TO_AREA', 'TAXI', '택시', '제1터미널 1층 택시 승강장', '텐노지',
        45, 0, 16000, 19000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '제1터미널 1층 도착 로비 밖 택시 승강장', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE);

-- ===== 숙소 지역 → 공항 (AREA_TO_AIRPORT) =====
INSERT INTO transport_route (id, airport_id, area_id, direction, mode, name_ko, board_stop, alight_stop,
    duration_min, walk_min, fare_min, fare_max, currency, is_24h, schedule_type,
    first_departure_min, last_departure_min, headway_min, boarding_tip, note, source_url, data_as_of, active) VALUES
    (201, 1, 11, 'AREA_TO_AIRPORT', 'RAIL', '난카이 라피트', '난바역', '간사이공항역',
        38, 5, 1490, 1490, 'JPY', FALSE, 'TIMETABLE', NULL, NULL, NULL,
        '난카이 난바역 3층 개찰구, 라피트 전용 승강장', '추정: 시각표(매시 00·30분)와 요금은 공식 시간표로 검증 필요',
        'https://www.nankai.co.jp/traffic/', '2026-10-01', TRUE),
    (202, 1, 11, 'AREA_TO_AIRPORT', 'RAIL', '난카이 공항급행', '난바역', '간사이공항역',
        45, 5, 970, 970, 'JPY', FALSE, 'HEADWAY', 300, 1380, 30,
        '난카이 난바역 3층 개찰구', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.nankai.co.jp/traffic/', '2026-10-01', TRUE),
    (203, 1, 12, 'AREA_TO_AIRPORT', 'BUS', '공항 리무진버스 (오사카역·우메다)', '오사카역(우메다) 버스 정류장', '간사이공항 제1터미널',
        60, 5, 1800, 1800, 'JPY', FALSE, 'HEADWAY', 300, 1260, 30,
        '정류장 위치는 운영사 안내 확인', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.okkbus.co.jp/', '2026-10-01', TRUE),
    (204, 1, 12, 'AREA_TO_AIRPORT', 'RAIL', 'JR 간쿠쾌속', '오사카역', '간사이공항역',
        70, 5, 1210, 1210, 'JPY', FALSE, 'HEADWAY', 320, 1320, 30,
        'JR 오사카역 간쿠쾌속 승강장 (앞쪽 차량이 간사이공항행)', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (205, 1, 13, 'AREA_TO_AIRPORT', 'RAIL', 'JR 특급 하루카', '신오사카역', '간사이공항역',
        50, 3, 2410, 3110, 'JPY', FALSE, 'TIMETABLE', NULL, NULL, NULL,
        'JR 신오사카역 재래선 하루카 승강장', '추정: 시각표(매시 00·30분)와 요금 범위(자유석~지정석)는 공식 정보로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (206, 1, 14, 'AREA_TO_AIRPORT', 'RAIL', 'JR 간쿠쾌속', '텐노지역', '간사이공항역',
        50, 3, 1080, 1080, 'JPY', FALSE, 'HEADWAY', 340, 1360, 30,
        'JR 텐노지역 한와선 승강장', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.westjr.co.jp/', '2026-10-01', TRUE),
    (207, 1, 14, 'AREA_TO_AIRPORT', 'BUS', '공항 리무진버스 (텐노지·아베노)', '긴테츠 아베노바시역 버스 정류장', '간사이공항 제1터미널',
        50, 3, 1800, 1800, 'JPY', FALSE, 'HEADWAY', 360, 1200, 60,
        '정류장 위치는 운영사 안내 확인', '추정: 첫차·막차·배차간격·요금은 공식 시간표로 검증 필요',
        'https://www.okkbus.co.jp/', '2026-10-01', TRUE),
    (208, 1, 11, 'AREA_TO_AIRPORT', 'TAXI', '택시', '난바', '간사이공항',
        50, 0, 17000, 20000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '숙소 앞 또는 역 택시 승강장, 배차 앱 호출 가능', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (209, 1, 12, 'AREA_TO_AIRPORT', 'TAXI', '택시', '우메다', '간사이공항',
        55, 0, 19000, 23000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '숙소 앞 또는 역 택시 승강장, 배차 앱 호출 가능', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (210, 1, 13, 'AREA_TO_AIRPORT', 'TAXI', '택시', '신오사카', '간사이공항',
        55, 0, 20000, 25000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '숙소 앞 또는 역 택시 승강장, 배차 앱 호출 가능', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE),
    (211, 1, 14, 'AREA_TO_AIRPORT', 'TAXI', '택시', '텐노지', '간사이공항',
        45, 0, 16000, 19000, 'JPY', TRUE, 'NONE', NULL, NULL, NULL,
        '숙소 앞 또는 역 택시 승강장, 배차 앱 호출 가능', '추정: 요금은 교통 상황·심야 할증에 따라 달라짐',
        'https://www.kansai-airport.or.jp/', '2026-10-01', TRUE);

-- ===== 시각표 (TIMETABLE 노선) =====
-- 추정: 30분 간격 정형 패턴으로 근사했고, 평일·토요일·휴일 모두 같은 시각을 넣었다.
--   101 라피트 간사이공항 06:35~23:05 / 105 하루카 간사이공항 06:46~22:16
--   201 라피트 난바 06:00~22:00       / 205 하루카 신오사카 06:30~20:30
INSERT INTO transport_timetable (route_id, day_type, departure_min)
WITH RECURSIVE
    slot (route_id, departure_min, last_min) AS (
        SELECT 101, 395, 1385
        UNION ALL SELECT 105, 406, 1336
        UNION ALL SELECT 201, 360, 1320
        UNION ALL SELECT 205, 390, 1230
        UNION ALL
        SELECT route_id, departure_min + 30, last_min FROM slot WHERE departure_min + 30 <= last_min
    ),
    day_types (day_type) AS (
        SELECT 'WEEKDAY' UNION ALL SELECT 'SATURDAY' UNION ALL SELECT 'HOLIDAY'
    )
SELECT s.route_id, d.day_type, s.departure_min
FROM slot s CROSS JOIN day_types d
ORDER BY s.route_id, d.day_type, s.departure_min;

-- ===== 추천 장소 =====
-- 추정: 영업시간은 대표값이며 계절 변동·임시 휴관은 반영하지 않았다. 상시 개방은 [] + always_open = TRUE.
INSERT INTO place (id, city_id, google_place_id, name_ko, category, lat, lng, summary, duration_min,
    opening_hours_json, always_open, source) VALUES
    (501, 1, NULL, '오사카성', 'SIGHT', 34.687300, 135.526200, '천수각 전망대와 넓은 공원', 90,
        '[{"dayOfWeek":"MON","open":"09:00","close":"17:00"},{"dayOfWeek":"TUE","open":"09:00","close":"17:00"},{"dayOfWeek":"WED","open":"09:00","close":"17:00"},{"dayOfWeek":"THU","open":"09:00","close":"17:00"},{"dayOfWeek":"FRI","open":"09:00","close":"17:00"},{"dayOfWeek":"SAT","open":"09:00","close":"17:00"},{"dayOfWeek":"SUN","open":"09:00","close":"17:00"}]', FALSE, 'SEED'),
    (502, 1, NULL, '츠텐카쿠', 'SIGHT', 34.652500, 135.506300, '신세카이의 상징 전망탑', 60,
        '[{"dayOfWeek":"MON","open":"10:00","close":"20:00"},{"dayOfWeek":"TUE","open":"10:00","close":"20:00"},{"dayOfWeek":"WED","open":"10:00","close":"20:00"},{"dayOfWeek":"THU","open":"10:00","close":"20:00"},{"dayOfWeek":"FRI","open":"10:00","close":"20:00"},{"dayOfWeek":"SAT","open":"10:00","close":"20:00"},{"dayOfWeek":"SUN","open":"10:00","close":"20:00"}]', FALSE, 'SEED'),
    (503, 1, NULL, '유니버설 스튜디오 재팬', 'SIGHT', 34.665400, 135.432300, '하루 종일 즐기는 테마파크', 480,
        '[{"dayOfWeek":"MON","open":"09:00","close":"21:00"},{"dayOfWeek":"TUE","open":"09:00","close":"21:00"},{"dayOfWeek":"WED","open":"09:00","close":"21:00"},{"dayOfWeek":"THU","open":"09:00","close":"21:00"},{"dayOfWeek":"FRI","open":"09:00","close":"21:00"},{"dayOfWeek":"SAT","open":"09:00","close":"21:00"},{"dayOfWeek":"SUN","open":"09:00","close":"21:00"}]', FALSE, 'SEED'),
    (504, 1, NULL, '구로몬 시장', 'FOOD', 34.665600, 135.506600, '해산물·길거리 음식 시장', 60,
        '[{"dayOfWeek":"MON","open":"09:00","close":"18:00"},{"dayOfWeek":"TUE","open":"09:00","close":"18:00"},{"dayOfWeek":"WED","open":"09:00","close":"18:00"},{"dayOfWeek":"THU","open":"09:00","close":"18:00"},{"dayOfWeek":"FRI","open":"09:00","close":"18:00"},{"dayOfWeek":"SAT","open":"09:00","close":"18:00"},{"dayOfWeek":"SUN","open":"09:00","close":"18:00"}]', FALSE, 'SEED'),
    (505, 1, NULL, '신세카이 쿠시카츠 거리', 'FOOD', 34.651700, 135.506100, '쿠시카츠 노포가 모인 거리', 60,
        '[{"dayOfWeek":"MON","open":"11:00","close":"22:00"},{"dayOfWeek":"TUE","open":"11:00","close":"22:00"},{"dayOfWeek":"WED","open":"11:00","close":"22:00"},{"dayOfWeek":"THU","open":"11:00","close":"22:00"},{"dayOfWeek":"FRI","open":"11:00","close":"22:00"},{"dayOfWeek":"SAT","open":"11:00","close":"22:00"},{"dayOfWeek":"SUN","open":"11:00","close":"22:00"}]', FALSE, 'SEED'),
    (506, 1, NULL, '호젠지 요코초', 'FOOD', 34.668000, 135.502700, '이끼 낀 부동명왕과 골목 식당가', 45,
        '[]', TRUE, 'SEED'),
    (507, 1, NULL, '신사이바시스지 상점가', 'SHOPPING', 34.674500, 135.501400, '약 600m 지붕 덮인 쇼핑 아케이드', 90,
        '[{"dayOfWeek":"MON","open":"10:00","close":"21:00"},{"dayOfWeek":"TUE","open":"10:00","close":"21:00"},{"dayOfWeek":"WED","open":"10:00","close":"21:00"},{"dayOfWeek":"THU","open":"10:00","close":"21:00"},{"dayOfWeek":"FRI","open":"10:00","close":"21:00"},{"dayOfWeek":"SAT","open":"10:00","close":"21:00"},{"dayOfWeek":"SUN","open":"10:00","close":"21:00"}]', FALSE, 'SEED'),
    (508, 1, NULL, '그랜드 프론트 오사카', 'SHOPPING', 34.704000, 135.494900, '오사카역 직결 대형 복합 쇼핑몰', 90,
        '[{"dayOfWeek":"MON","open":"10:00","close":"21:00"},{"dayOfWeek":"TUE","open":"10:00","close":"21:00"},{"dayOfWeek":"WED","open":"10:00","close":"21:00"},{"dayOfWeek":"THU","open":"10:00","close":"21:00"},{"dayOfWeek":"FRI","open":"10:00","close":"21:00"},{"dayOfWeek":"SAT","open":"10:00","close":"21:00"},{"dayOfWeek":"SUN","open":"10:00","close":"21:00"}]', FALSE, 'SEED'),
    (509, 1, NULL, '덴덴타운', 'SHOPPING', 34.659700, 135.505900, '전자제품·애니메이션 상점 거리', 90,
        '[{"dayOfWeek":"MON","open":"10:00","close":"20:00"},{"dayOfWeek":"TUE","open":"10:00","close":"20:00"},{"dayOfWeek":"WED","open":"10:00","close":"20:00"},{"dayOfWeek":"THU","open":"10:00","close":"20:00"},{"dayOfWeek":"FRI","open":"10:00","close":"20:00"},{"dayOfWeek":"SAT","open":"10:00","close":"20:00"},{"dayOfWeek":"SUN","open":"10:00","close":"20:00"}]', FALSE, 'SEED'),
    (510, 1, NULL, '나카노시마 공원', 'NATURE', 34.692300, 135.507900, '강 사이 섬의 장미정원 산책로', 60,
        '[]', TRUE, 'SEED'),
    (511, 1, NULL, '반파쿠 기념공원', 'NATURE', 34.809400, 135.532300, '태양의 탑과 넓은 정원', 120,
        '[{"dayOfWeek":"MON","open":"09:30","close":"17:00"},{"dayOfWeek":"TUE","open":"09:30","close":"17:00"},{"dayOfWeek":"THU","open":"09:30","close":"17:00"},{"dayOfWeek":"FRI","open":"09:30","close":"17:00"},{"dayOfWeek":"SAT","open":"09:30","close":"17:00"},{"dayOfWeek":"SUN","open":"09:30","close":"17:00"}]', FALSE, 'SEED'),
    (512, 1, NULL, '덴포잔 대관람차', 'NATURE', 34.656500, 135.431800, '오사카만을 내려다보는 대관람차', 30,
        '[{"dayOfWeek":"MON","open":"10:00","close":"21:00"},{"dayOfWeek":"TUE","open":"10:00","close":"21:00"},{"dayOfWeek":"WED","open":"10:00","close":"21:00"},{"dayOfWeek":"THU","open":"10:00","close":"21:00"},{"dayOfWeek":"FRI","open":"10:00","close":"21:00"},{"dayOfWeek":"SAT","open":"10:00","close":"21:00"},{"dayOfWeek":"SUN","open":"10:00","close":"21:00"}]', FALSE, 'SEED'),
    (513, 1, NULL, '도톤보리', 'NIGHT', 34.668700, 135.501300, '글리코 간판과 네온 운하 거리', 90,
        '[]', TRUE, 'SEED'),
    (514, 1, NULL, '우메다 스카이빌딩 공중정원', 'NIGHT', 34.705300, 135.490600, '173m 옥상 원형 전망대 야경', 60,
        '[{"dayOfWeek":"MON","open":"09:30","close":"22:30"},{"dayOfWeek":"TUE","open":"09:30","close":"22:30"},{"dayOfWeek":"WED","open":"09:30","close":"22:30"},{"dayOfWeek":"THU","open":"09:30","close":"22:30"},{"dayOfWeek":"FRI","open":"09:30","close":"22:30"},{"dayOfWeek":"SAT","open":"09:30","close":"22:30"},{"dayOfWeek":"SUN","open":"09:30","close":"22:30"}]', FALSE, 'SEED'),
    (515, 1, NULL, '하루카스 300 전망대', 'NIGHT', 34.645900, 135.513500, '300m 높이 초고층 전망대', 60,
        '[{"dayOfWeek":"MON","open":"09:00","close":"22:00"},{"dayOfWeek":"TUE","open":"09:00","close":"22:00"},{"dayOfWeek":"WED","open":"09:00","close":"22:00"},{"dayOfWeek":"THU","open":"09:00","close":"22:00"},{"dayOfWeek":"FRI","open":"09:00","close":"22:00"},{"dayOfWeek":"SAT","open":"09:00","close":"22:00"},{"dayOfWeek":"SUN","open":"09:00","close":"22:00"}]', FALSE, 'SEED'),
    (516, 1, NULL, '시텐노지', 'CULTURE', 34.653600, 135.516400, '일본에서 가장 오래된 사찰 중 하나', 60,
        '[{"dayOfWeek":"MON","open":"08:30","close":"16:30"},{"dayOfWeek":"TUE","open":"08:30","close":"16:30"},{"dayOfWeek":"WED","open":"08:30","close":"16:30"},{"dayOfWeek":"THU","open":"08:30","close":"16:30"},{"dayOfWeek":"FRI","open":"08:30","close":"16:30"},{"dayOfWeek":"SAT","open":"08:30","close":"16:30"},{"dayOfWeek":"SUN","open":"08:30","close":"16:30"}]', FALSE, 'SEED'),
    (517, 1, NULL, '오사카 시립미술관', 'CULTURE', 34.650100, 135.510400, '덴노지 공원 안의 미술관 (월요일 휴관)', 90,
        '[{"dayOfWeek":"TUE","open":"09:30","close":"17:00"},{"dayOfWeek":"WED","open":"09:30","close":"17:00"},{"dayOfWeek":"THU","open":"09:30","close":"17:00"},{"dayOfWeek":"FRI","open":"09:30","close":"17:00"},{"dayOfWeek":"SAT","open":"09:30","close":"17:00"},{"dayOfWeek":"SUN","open":"09:30","close":"17:00"}]', FALSE, 'SEED'),
    (518, 1, NULL, '오사카 역사박물관', 'CULTURE', 34.682600, 135.519700, '나니와궁터부터 근대까지 오사카 역사 (화요일 휴관)', 90,
        '[{"dayOfWeek":"MON","open":"09:30","close":"17:00"},{"dayOfWeek":"WED","open":"09:30","close":"17:00"},{"dayOfWeek":"THU","open":"09:30","close":"17:00"},{"dayOfWeek":"FRI","open":"09:30","close":"17:00"},{"dayOfWeek":"SAT","open":"09:30","close":"17:00"},{"dayOfWeek":"SUN","open":"09:30","close":"17:00"}]', FALSE, 'SEED'),
    (519, 1, NULL, '가이유칸', 'CULTURE', 34.654500, 135.429000, '고래상어가 있는 대형 수족관', 120,
        '[{"dayOfWeek":"MON","open":"10:00","close":"20:00"},{"dayOfWeek":"TUE","open":"10:00","close":"20:00"},{"dayOfWeek":"WED","open":"10:00","close":"20:00"},{"dayOfWeek":"THU","open":"10:00","close":"20:00"},{"dayOfWeek":"FRI","open":"10:00","close":"20:00"},{"dayOfWeek":"SAT","open":"10:00","close":"20:00"},{"dayOfWeek":"SUN","open":"10:00","close":"20:00"}]', FALSE, 'SEED');
