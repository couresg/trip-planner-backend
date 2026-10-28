# backend/CLAUDE.md — Spring Boot

루트 `CLAUDE.md`와 `docs/`를 먼저 따른다. 이 파일은 백엔드 전용 규칙이다.

## 상위 문서 위치 (이 폴더에서 Claude Code를 실행할 때)

이 폴더를 IDE 프로젝트 루트로 열고 여기서 Claude Code를 실행하는 경우:
- 상위의 `../CLAUDE.md`는 자동으로 함께 로드된다.
- 루트 CLAUDE.md에 적힌 `docs/...` 경로는 여기서 보면 **`../docs/...`** 이다.
- 작업 전에 관련 문서를 직접 읽는다: `../docs/PRD.md`, `../docs/API_SPEC.md`, `../docs/ROADMAP.md` , `../docs/DB_SCHEMA.md`, `../docs/ARCHITECTURE.md`
- API 명세, DB 스키마, 로드맵 체크박스를 바꿀 때도 `../docs/`의 파일을 수정한다.

## 명령어

```bash
./gradlew bootRun --args='--spring.profiles.active=local'   # 실행
./gradlew test                                               # 전체 테스트 (Docker 필요: Testcontainers)
./gradlew test --tests "*ArrivalTransferServiceTest"         # 특정 테스트
./gradlew build                                              # 빌드 + 테스트
```

## 스택

- Java 21, Spring Boot (최신 안정 버전), Gradle Kotlin DSL
- Spring Web(MVC), Spring Data JPA, Bean Validation, Flyway, MySQL Connector/J
- springdoc-openapi (Swagger UI)
- Caffeine(캐시), Bucket4j(rate limit)
- 테스트: JUnit 5, AssertJ, Mockito, Testcontainers(MySQL), MockMvc 또는 RestClient 테스트

## 패키지 구조 (도메인 우선)

```
com.tripplanner
├── TripPlannerApplication.java
├── common/        # exception(ErrorCode, BusinessException, GlobalExceptionHandler), time(TimeUtils), config, web
├── catalog/       # City, Airport, Area — entity / repository / service / controller / dto
├── flight/        # FlightNumber(값 객체), FlightClient(+Mock/AeroDataBox), FlightService, FlightCache
├── trip/          # Trip, TripFlight, TripService, TripController, OwnerTokenService
├── transfer/      # TransportRoute, Timetable, ScheduleExpander, ArrivalTransferService, DepartureTransferService
├── place/         # Place, PlaceService
└── itinerary/     # ItineraryItem, ItineraryService, ItineraryWarningCalculator
```

각 도메인 내부: `domain/`(엔티티·값 객체), `repository/`, `service/`, `api/`(컨트롤러 + DTO), `client/`(외부 연동 시).

## 코드 규칙

- **컨트롤러는 얇게.** 검증(`@Valid`) → 서비스 호출 → DTO 반환. 엔티티를 응답으로 직접 반환하지 않는다.
- DTO는 `record`로 작성한다.
- 엔티티: 기본 생성자 `protected`, 세터 대신 의미 있는 메서드(`trip.changeArea(area)`), 연관관계는 기본 `LAZY`.
- 조회 서비스는 `@Transactional(readOnly = true)`, 변경은 `@Transactional`.
- N+1 주의: 여행 상세·일정 조회는 fetch join 또는 `@EntityGraph`.
- 예외: `BusinessException(ErrorCode)`를 던지고 `GlobalExceptionHandler`가 `ProblemDetail`로 변환한다. 에러 코드는 `docs/API_SPEC.md` 7장과 일치시킨다.
- 설정값은 `@ConfigurationProperties` record로 묶는다 (예: `ExternalApiProperties`, `TransferProperties`).
- 시간
  - `Clock`을 빈으로 주입해서 쓴다 (`LocalDate.now()` 직접 호출 금지 → 테스트 불가).
  - 저장 `Instant`, 계산 `ZonedDateTime`(공항 timezone), 응답은 `OffsetDateTime`.
  - 시간표의 "운행일 기준 분"은 `ServiceMinute` 같은 값 객체로 감싼다.
- 외부 API
  - `RestClient` + 타임아웃 설정, 실패 시 도메인 예외로 변환.
  - API 키는 헤더로만 전달하고 로그에 남기지 않는다.
  - `external.mock=true`이면 Mock 구현이 빈으로 등록되도록 `@ConditionalOnProperty` 사용.

## 테스트 규칙

- **시간 계산 로직(ScheduleExpander, ArrivalTransferService, DepartureTransferService)은 단위 테스트 필수.** 고정 `Clock`과 경계값(자정, 막차 직후, 첫차 직전, 요일 경계)을 포함한다.
- 리포지토리·통합 테스트는 Testcontainers MySQL (H2 사용 금지: MySQL 문법 차이).
- 외부 API는 fixture JSON(`src/test/resources/fixtures/aerodatabox/*.json`)으로 테스트한다. 테스트에서 실제 API를 호출하지 않는다.
- 테스트 이름은 한글 `@DisplayName`으로 시나리오를 적는다. 예: `"23시30분 도착이면 자정 넘어 01시30분까지의 출발편을 조회한다"`

## DB

- 스키마 변경 = Flyway 새 버전 파일 추가. 이미 적용된 마이그레이션 파일은 수정하지 않는다.
- `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`
- 시드 데이터는 `V100` 이후 번호를 쓴다.

## 하지 말 것

- 비밀값 하드코딩, `application.yml`에 실제 키 기입
- 문자열 연결 SQL / JPQL
- `System.out.println` (SLF4J 사용)
- 외부 API 응답 객체를 그대로 프론트에 전달 (반드시 우리 DTO로 변환)
