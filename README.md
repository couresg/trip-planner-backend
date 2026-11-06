# Trip Planner Backend

여행 도착/출발 플래너의 Spring Boot API 서버. 프론트엔드는 별도 저장소(`frontend/`)다.
요구사항·API·DB 설계 문서는 모노레포 루트의 `docs/`에 있다 (이 폴더에서는 `../docs/`).

## 스택

Java 21, Spring Boot, Gradle (Kotlin DSL), Spring Web, Spring Data JPA, Bean Validation, Flyway, MySQL Connector/J, springdoc-openapi, Caffeine, Bucket4j.
테스트: JUnit 5, AssertJ, Mockito, Testcontainers(MySQL).

## 사전 준비

- JDK 21
- Docker: 로컬 MySQL과 테스트(Testcontainers)에 필요
- 루트의 `.env` (`../.env.example`을 복사해 값 입력)

## 실행

```bash
# 루트에서 DB 먼저 기동
docker compose up -d mysql

# backend/ 에서 실행 (http://localhost:8080, Swagger UI: /swagger-ui.html)
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로파일은 `../.env`를 properties 형식으로 읽는다 (OS 환경변수가 있으면 우선). 로그 레벨은 `com.tripplanner=DEBUG`, CORS 허용 origin은 `http://localhost:5173`.

주의: Windows에서는 DB 주소에 `localhost` 대신 `127.0.0.1`을 쓴다. bash에서 `source .env`는 하지 않는다 (`DB_URL`의 `&` 때문).

## 명령어

```bash
./gradlew test                                          # 전체 테스트 (Docker 필요)
./gradlew test --tests "*ArrivalTransferServiceTest"    # 특정 테스트
./gradlew build                                         # 빌드 + 테스트
```

## 환경변수

| 변수 | 용도 | 기본값 |
|---|---|---|
| `DB_URL` | JDBC URL | `jdbc:mysql://localhost:3306/trip_planner?...` |
| `DB_USERNAME` / `DB_PASSWORD` | DB 계정 | 없음 (필수) |
| `EXTERNAL_MOCK` | `true`면 항공편 목업(fixture) 사용 | `true` |
| `AERODATABOX_API_KEY` | AeroDataBox 키 | 빈 값 |
| `AERODATABOX_API_HOST` | AeroDataBox 호스트 | `aerodatabox.p.rapidapi.com` |
| `GOOGLE_MAPS_API_KEY` | Google 서버 측 키 | 빈 값 |

실제 키는 코드와 `application.yml`에 넣지 않는다.

## 설정 속성 (`application.yml`)

| 속성 | 설명 |
|---|---|
| `app.transfer.*` | 도착/출발 시간 계산 옵션 (도착 조회 창 120분, 입국 소요 30/60/90분, 공항 도착 여유 120/150/180분 등) |
| `app.itinerary.*` | 일정 규칙 (`return-margin-min`, `max-items-per-day`) |
| `app.cors.allowed-origins` | CORS 허용 origin (`local` 프로파일) |
| `app.rate-limit.*` | 요청 횟수 제한 (아래) |

### 요청 횟수 제한

클라이언트 IP별 버킷(Bucket4j + Caffeine). `/api/v1/**` 대상, `/api/v1/health`와 CORS preflight(OPTIONS)는 제외.

| 속성 (`app.rate-limit.*`) | 기본값 | 대상 |
|---|---|---|
| `enabled` | `true` | 전체 on/off |
| `general-per-minute` | 120 | 모든 `/api/v1` 호출 |
| `external-per-minute` | 20 | `GET /api/v1/flights/lookup`, `POST /api/v1/trips` (일반 한도도 함께 소모) |
| `cache-max-size` | 100000 | IP별 버킷을 보관하는 최대 개수 |

초과 시 `429` + 코드 `RATE_LIMITED`(ProblemDetail) + `Retry-After`(초) 헤더. `X-Forwarded-For`는 신뢰하지 않고 `getRemoteAddr()`를 쓴다.

## DB

- 스키마 변경은 Flyway 새 버전 파일 추가 (`V2`~`V99`). 적용된 마이그레이션은 수정하지 않는다.
- 시드 데이터는 `V100` 이후. `spring.flyway.out-of-order=true`.
- `ddl-auto=validate`, `open-in-view=false`.

## 구조

`com.tripplanner` 아래 `common`, `catalog`, `flight`, `trip`, `transfer`, `place`, `itinerary` 패키지 (도메인 우선). 자세한 규칙은 `CLAUDE.md`.
