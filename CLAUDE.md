# 세모점(Semojum) BE 개발 가이드

## 프로젝트 개요

**세모점**은 점역사(전문 점자 번역사)를 위한 AI 기반 점자 변환 플랫폼이다.
회사: EduDot / 팀: 김현주(CEO), 김태민(CTO), 이준혁(CPO), 조하은(PM/BE)

### 변환 모드
| 모드 | 설명 | 입력 파일 |
|---|---|---|
| a | 이미지 → 텍스트 | PDF |
| b | 텍스트 → 점자 | TXT, HWP |
| c | 이미지 → 점자 | PDF |

---

## 기술 스택

- **언어/프레임워크**: Java 21, Spring Boot 3.5.13, Gradle-Groovy
- **DB**: PostgreSQL 18 (Cloud SQL), Redis (로컬 Docker)
- **스토리지**: GCS (`semojum-bucket`, asia-northeast3), Workload Identity 인증
- **통신**: gRPC + TLS (AI 서버), SSE (FE 실시간 스트리밍)
- **인증**: Spring Security, JWT (jjwt), BCrypt, SHA-256
- **배포**: Docker Compose, Envoy Proxy, GitHub Actions CI/CD
- **패키지 베이스**: `com.semojum.backend`

---

## 인프라

| 구성요소 | 상세 |
|---|---|
| BE VM | `semojum-backend`, `34.158.215.55` (asia-northeast3-a) |
| Cloud SQL | PostgreSQL 18, `34.47.68.184`, DB: `postgres` |
| GCS | `semojum-bucket` (asia-northeast3) |
| AI VM A | `136.119.89.254`, gRPC `50051`, TLS, authority `semo-jum.com` |
| 도메인 | `api.semojum.app`, Cloudflare Flexible SSL |
| Redis | `docker run -d -p 6379:6379` (로컬) |
| Docker Hub | `zxhwan/semojum-backend:latest` |

**배포 흐름**: `dev` 브랜치 push → GitHub Actions → Docker Hub → VM SSH 실행

---

## 패키지 구조

```
com.semojum.backend
├── domain
│   ├── auth
│   │   ├── controller   AuthController
│   │   ├── dto          AuthRequestDto, AuthResponseDto
│   │   ├── entity       User, UserSession
│   │   ├── repository   UserRepository, UserSessionRepository
│   │   └── service      AuthService
│   ├── job
│   │   ├── controller   JobController (SSE 엔드포인트 포함)
│   │   ├── dto          JobResponseDto
│   │   ├── entity       Job, Page
│   │   ├── repository   JobRepository, PageRepository
│   │   ├── service      JobService, SseService
│   │   └── worker       PageWorker
│   ├── result
│   │   ├── entity       PageResult, TextElement, BrailleElement,
│   │   │                BoundingBox, RuleTrail,
│   │   │                QualityCriticalError, QualityReviewFlag
│   │   ├── repository   (각 엔티티별 JpaRepository)
│   │   └── service      ResultService
│   └── user
│       ├── controller   UserController
│       └── service      UserService
├── global
│   ├── exception        CustomException, ErrorCode, ApiResponse
│   ├── gcs              GcsService
│   ├── grpc             BrailleGrpcClient
│   ├── jwt              JwtFilter, JwtProvider
│   ├── oauth2           GoogleOAuthService, KakaoOAuthService
│   ├── security         SecurityConfig, UserDetailsServiceImpl
│   └── thumbnail        ThumbnailService
└── grpc                 (proto 생성 클래스: BrailleRequest, BrailleResponse 등)
```

---

## 공통 응답 구조

```json
{
  "isSuccess": true,
  "code": "COMMON2000",
  "message": "성공입니다.",
  "result": {}
}
```

### 에러 코드
| 코드 | HTTP | 설명 |
|---|---|---|
| COMMON4000 | 400 | 잘못된 요청 |
| COMMON4001 | 401 | 인증 필요 |
| COMMON4003 | 403 | 권한 없음 |
| COMMON5000 | 500 | 서버 에러 |
| AUTH4001 | 401 | 이메일/비밀번호 오류 |
| AUTH4002 | 409 | 이미 사용 중인 이메일 |
| AUTH4003 | 401 | 액세스 토큰 만료/유효하지 않음 |
| USER4001 | 404 | 존재하지 않는 회원 |
| JOB4001 | 404 | 존재하지 않는 작업 |
| JOB4002 | 400 | 잘못된 파일 형식 |
| JOB4003 | 400 | 지원하지 않는 모드 |
| JOB4004 | 404 | 존재하지 않는 요소 |
| JOB4005 | 400 | 잘못된 elementType (TEXT/BRAILLE만 허용) |

---

## 구현 완료 목록

### 인증 (Auth)
- `POST /api/auth/signup` — 이메일 회원가입
- `POST /api/auth/login` — 이메일 로그인 (JWT 발급)
- `POST /api/auth/google` — 구글 PKCE 소셜 로그인
- `POST /api/auth/kakao` — 카카오 소셜 로그인
- `POST /api/auth/logout` — 로그아웃 (리프레시 토큰 revoke)
- `POST /api/auth/refresh` — 액세스 토큰 재발급
- DB 세션 관리: `user_sessions` 테이블, SHA-256 해시 저장
- 소셜 로그인 방식: PKCE 기반 (RFC 8252), 클라이언트가 OAuth2 플로우 처리 후 code + code_verifier + redirect_uri를 BE로 전송

### Job
- `POST /api/jobs` — Job 생성 (multipart)
  - 모드 a/c: PDF 페이지별 분리 → GCS 업로드
  - 모드 b: TXT/HWP 30줄 단위 청크 → GCS 업로드
  - Redis `task_queue` LPUSH, `job:{jobId}:pages` Hash PENDING 초기화
- `GET /api/jobs/{jobId}/status` — Redis Hash 폴링, 페이지별 상태 반환
- `GET /api/jobs/{jobId}/events` — SSE 실시간 스트리밍 (JWT 인증, 본인 Job만)
  - `queue_position`: PENDING 페이지 존재 시 전송 (position, estimated_wait_sec)
  - `page_done`: 페이지 완료 시 DB 결과 포함 전송 (모드별 직렬화)
    - mode a: image_resolution + bounding_box_list + text_list + quality_report
    - mode b: text_list (id/contents) + braille_text_list + quality_report
    - mode c: image_resolution + bounding_box_list + braille_text_list + quality_report
  - `job_done`: 전체 완료 시 전송 후 연결 종료

### PageWorker
- 현재 **워커 1개** (AI 서버 병렬처리 지원 시 6으로 복구 예정, `WORKER_COUNT` 상수)
- GCS 파일 다운로드 → gRPC 요청 (AI 서버) → ResultService.save() → Redis 상태 업데이트
- 오류 발생 시 task를 큐에 재등록 후 2초 대기 (자동 재시도, **최대 3회**)
- 최대 재시도(3회) 초과 → `ResultService.markPageBlocked()`로 DB Page=BLOCKED 반영 + Job 종료 판정 후 Redis도 BLOCKED. markPageBlocked 실패해도 Redis put은 항상 실행(SSE 종료 감지 보장)
- 예외 처리 분기는 무로그로 삼키지 않고 `log.error`로 기록(pop된 task 증발 방지)
- `@PreDestroy`로 graceful shutdown 처리

### ResultService
- AI gRPC 응답(BrailleResponse)을 DB에 저장
- 저장 테이블: `page_results`, `text_elements`, `braille_elements`, `bounding_boxes`, `rule_trails`, `quality_critical_errors`, `quality_review_flags`
- `save()`(성공 경로): Page 상태 업데이트 → `touchJob` → 종료 판정
- `markPageBlocked(jobId, pageNo)`(BLOCKED 경로, 별도 `@Transactional` public, PageWorker가 호출): DB Page=BLOCKED 저장 → `touchJob` → 종료 판정
- `evaluateJobTermination()`: 성공/BLOCKED 경로 공유. 모든 페이지가 terminal(COMPLETED/NEEDS_REVIEW/BLOCKED)일 때, 성공(COMPLETED/NEEDS_REVIEW)이 0건이면 **FAILED**, 1건 이상이면 **COMPLETED**(부분 성공=완료)
- **drafts(시각요소 복수 초안) 저장 형태**: `text_elements`/`braille_elements`의 `drafts` 컬럼(jsonb)은 엔티티에서 `List<Map<String,Object>>`로 매핑(`contents`의 `List<String>`와 동일한 패턴). AI가 주는 drafts는 mode c의 `braille_text_list` 시각요소(image/chart_graph)에만 `[{text, contents:[...], label}]` 배열로 옴. `serializeDrafts()`가 proto Draft를 `{text, contents, label}` Map으로 변환해 저장 → 조회 시 `getDrafts()`가 List를 반환해 응답에 **JSON 배열**로 나감. (과거 `String` 매핑이라 응답에서 이중 인코딩(`"drafts":"[{...}]"`)되던 버그 수정. `SseService`/`UserService`의 `buildResult`는 변경 없이 배열로 직렬화됨)

### Job 상태 전이 & stale-job 스케줄러
- Job 상태: `PENDING → IN_PROGRESS → COMPLETED / FAILED` (모두 plain String)
- `JobRepository.touchJob(jobId)`: 페이지 이벤트마다 PENDING→IN_PROGRESS 전이 + `updated_at` 갱신 (네이티브 UPDATE)
- `JobRepository.finishJob(jobId, status, failedPages)`: COMPLETED/FAILED 종료 전이 + `finished_at`/`updated_at`/`failed_pages` 기록
- **두 쿼리 모두 `WHERE id=:jobId AND status IN ('PENDING','IN_PROGRESS')` 가드 → 이미 종료된 Job을 페이지 이벤트가 되살리지 못함 (가드 제거 금지)**
- `StaleJobScheduler` (5분 주기, `SchedulingConfig`의 `@EnableScheduling`): 멈춘 Job 정리 안전망
  - IN_PROGRESS 무진행(`job.stale.in-progress-timeout`, 기본 1h) → FAILED
  - 고아 PENDING(`job.stale.pending-timeout`, 기본 12h) → FAILED
  - cutoff는 `Instant`(절대시각) 기반 → 컨테이너/Cloud SQL 타임존과 무관하게 정확

### 마이페이지 (User)
- `GET /api/users/jobs` — 내 Job 목록 조회 (최신순, thumbnailUrl 포함)
- `GET /api/users/jobs/{jobId}/pages/{pageNo}` — 페이지별 변환 결과 조회 (모드별 직렬화)
  - 응답 **바깥 레벨**에 `original`(원본) 포함: mode a/c는 `{type:"pdf", url:<공개 URL>, lines:null}`, mode b는 `{type:"text", url:null, lines:[...]}` (GCS의 `page-n.txt`를 `split("\n", -1)`로 읽음, **DB 컬럼 추가 없음**)
- 두 엔드포인트 모두 JWT 인증 필요, 타인 Job 접근 시 403
- `getMyJobs`/`getJobPage`는 `@Transactional(readOnly=true)` (OSIV off 대응)

### 점역사 수정 (Edit)
- `PATCH /api/jobs/{jobId}/pages/{pageNo}/elements/{elementId}` — 요소 수정 (`ElementEditService`, `@Transactional`)
  - body: `{ "elementType": "TEXT"|"BRAILLE", "contents": [...] }`
  - `{elementId}`는 응답/SSE에 내려가던 **AI element id(String)** (엔티티 PK 아님) → `findByPageResultAndElementId`로 조회
  - `current`(currentContents/currentContent)만 갱신, **`original`은 절대 보존**. 응답으로 갱신된 contents 반환
  - 본인 Job 검증(타인 403), 없는 요소 404, 잘못된 elementType 400. `is_blocked`/상태 무관 수정 허용
- **edit_logs 스냅샷 기록(RLHF용)**: 수정과 같은 트랜잭션에서 1수정=1행 저장
  - 공통: before/after content + `ai_original_content` + mode/element_type/user/job/page
  - mode a/c: `source_pdf_path` + `image_width/height` + `bounding_box`(해당 요소)
  - mode b: `source_text`(변환에 쓴 원본 한글텍스트, GCS `.txt`에서 읽음)
  - 입력 컨텍스트까지 자기완결 스냅샷(같은 요소 반복 수정 시 컨텍스트 중복은 의도된 트레이드오프). PDF/이미지 바이너리는 저장 안 하고 gs 경로만

### 썸네일 (ThumbnailService)
- Job 생성 시 자동 생성 후 GCS 업로드, 공개 URL을 `jobs.thumbnail_url`에 저장
- mode a/c: PDFBox로 PDF 첫 페이지 → PNG 렌더링
- mode b: 텍스트를 NanumGothic 폰트로 흰 배경에 렌더링 → PNG
- 생성 실패 시 경고 로그만 남기고 Job 생성은 정상 진행

### Spring Security
- `JwtFilter`: PERMIT_URLS = `/api/auth/signup`, `/api/auth/login`, `/api/auth/google`, `/api/auth/kakao`, `/swagger-ui`, `/v3/api-docs`
- 미인증 요청 시 `COMMON4001` JSON 반환

### JPA / 커넥션 관리
- `spring.jpa.open-in-view=false` — SSE(30분) 연결 동안 OSIV가 DB 커넥션을 잡아 풀(10) 고갈되는 누수 방지. 커넥션은 쿼리 종료 즉시 반납
- 읽기 서비스 메서드는 `@Transactional(readOnly=true)` 부여 (OSIV off 대응 + 여러 쿼리 단일 세션)

### gRPC
- AI VM A (`136.119.89.254:50051`) TLS 연결, authority `semo-jum.com`
- proto: `BrailleRequest` / `BrailleResponse`
- BE gRPC 타임아웃: 200s (AI 서버 하드 타임아웃 180s보다 높게)

---

## DB 스키마 요약

### 주요 테이블
| 테이블 | 설명 |
|---|---|
| users | 회원 (EMAIL/KAKAO/GOOGLE) |
| user_sessions | 리프레시 토큰 세션 |
| jobs | 변환 작업 (`updated_at` timestamptz 포함 — touchJob/finishJob/DB default(now())로만 갱신) |
| pages | 페이지별 파일 경로 |
| page_results | AI 변환 결과 |
| text_elements | 텍스트 요소 (text_list) |
| braille_elements | 점자 요소 (braille_text_list) |
| bounding_boxes | 바운딩박스 (mode a, c) |
| rule_trails | 적용된 점역 규정 |
| quality_critical_errors | 품질 오류 |
| quality_review_flags | 검토 필요 항목 |
| edit_logs | 점역사 수정 이력 (RLHF 학습용 스냅샷) |

### Page 상태 값
| 값 | 설명 |
|---|---|
| PENDING | 대기 중 |
| RUNNING | 처리 중 |
| COMPLETED | 완료 |
| NEEDS_REVIEW | 검토 필요 |
| BLOCKED | 처리 불가 |

### Job 상태 값
| 값 | 설명 |
|---|---|
| PENDING | 처리 시작 전 |
| IN_PROGRESS | 처리 중 (첫 페이지 이벤트 시 touchJob으로 전이) |
| COMPLETED | 전체 완료 (부분 실패 포함 — failed_pages에 기록) |
| FAILED | 변환 실패 (전체 페이지 BLOCKED, 또는 stale-job 스케줄러가 정리) |

---

## Redis 키 구조
| 키 | 타입 | 설명 |
|---|---|---|
| `task_queue` | List | 처리 대기 태스크 |
| `job:{jobId}:pages` | Hash | 페이지별 상태 + total_pages |

---

## 코딩 컨벤션

### 커밋 메시지
- `feat:` 새 기능
- `chore:` 설정, 의존성, 기타
- `fix:` 버그 수정

### 브랜치 전략
- `dev`: 메인 개발 브랜치
- `feat/{기능명}`: 기능 브랜치
- 기능 완료 시 PR → dev 머지

### 코드 원칙
- 최소 변경 원칙: 수정 시 타겟된 최소 변경만
- 주석은 기존 것 유지
- 에러 응답은 항상 `ApiResponse.failure(ErrorCode.xxx)` 형태
- 엔티티는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@Builder`

### JWT 설계 원칙
- 로그아웃 후 액세스 토큰은 만료 시까지 유효 (JWT stateless)
- 리프레시 토큰만 revoke → `user_sessions.revoked_at` 설정

---

## 주의사항
- `task.md`는 `.gitignore`에 등록됨 (커밋 제외)
- Cloud SQL 미사용 시 중지 가능 (Spring Boot 재시작 없이 자동 재연결)
- SSE 연결 시 Envoy `timeout: 0s` 필수. `idle_timeout`은 SSE 타임아웃(30분) 이상으로 둘 것 (현재 `envoy.yaml`은 900s라 정합성 점검 필요)
- gRPC 타임아웃은 AI 서버 하드 타임아웃(180s)보다 높은 200s로 설정
- PageWorker `WORKER_COUNT = 1` (임시) — AI 서버 병렬처리 확인 후 6으로 복구
- `jobs.updated_at`(timestamptz)은 `ddl-auto:none`이라 수동 ALTER 필요. 엔티티는 `insertable/updatable=false`이고 DB default(now()) + touchJob/finishJob으로만 갱신 → 코드 배포 전에 DDL(컬럼 추가 → 백필 → NOT NULL → DEFAULT) 먼저 실행
- stale-job 타임아웃은 `application.yaml`의 `job.stale.in-progress-timeout`(1h) / `pending-timeout`(12h)로 조정
- `edit_logs` 테이블은 `ddl-auto:none`이라 자동 생성 안 됨 → 수정 API 배포 전에 DataGrip에서 `CREATE TABLE edit_logs (...)` 먼저 실행
- UserService와 SseService 간 result 직렬화 헬퍼 코드 중복 존재 → 추후 공통 컴포넌트로 리팩토링 예정
