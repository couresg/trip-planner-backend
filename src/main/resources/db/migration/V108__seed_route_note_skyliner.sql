-- 스카이라이너 메모 보강. V104·V107은 이미 적용되었으므로 고치지 않고 이 버전에서 바꾼다. 값은 그대로다.
UPDATE transport_route SET note = CONCAT(note, '. 2026-11-20까지는 별도 시각표(이 데이터는 11-21 개정 기준)')
WHERE id IN (1104, 1204);
