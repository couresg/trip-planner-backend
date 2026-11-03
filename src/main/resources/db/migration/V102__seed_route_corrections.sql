-- 노선 시드 보정. V101은 이미 적용되었으므로 고치지 않고 이 버전에서 바꾼다.

-- 택시: 우메다·신오사카는 같은 도로를 쓰는 버스의 최대 소요로 올린다. 택시 소요·요금은 모두 추정값이다.
UPDATE transport_route SET duration_min = 67, note = '추정: 소요 67분은 같은 도로를 쓰는 리무진버스(203) 시각표 최대값을 적용한 값, 요금도 추정값. 교통 상황·심야 할증에 따라 달라짐' WHERE id IN (209, 210);
UPDATE transport_route SET note = '추정: 소요시간과 요금 모두 추정값. 교통 상황·심야 할증에 따라 달라짐' WHERE id IN (208, 211);

-- 201·202의 기준일은 조회일이 아니라 출처 화면이 밝힌 날짜다.
UPDATE transport_route SET data_as_of = '2024-12-21' WHERE id IN (201, 202);

-- 107은 207과 같은 노선의 반대 방향이다. 운행을 확인할 수 없어 응답에서 빠지도록 비활성화한다.
UPDATE transport_route SET
    active = FALSE,
    note = '운휴: 운영사(KATE)가 아베노하루카스 노선을 전편 운휴로 표시하고, 긴테츠 아베노바시 정류장은 노선표에 없음(2026-11-03 조회). 재개 일정 미확인. 값은 V100 추정 그대로',
    source_url = 'https://www.kate.co.jp/timetable/detail/AB',
    data_as_of = '2026-11-03'
WHERE id = 107;
