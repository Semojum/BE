# 세모점(Semojum) BE 개발 가이드

## 프로젝트 개요

**세모점**은 점역사(전문 점자 번역사)를 위한 AI 기반 점자 변환 플랫폼이다.

### 변환 모드
| 모드 | 설명 | 입력 파일 |
|---|---|---|
| a | 이미지 → 텍스트 | PDF |
| b | 텍스트 → 점자 | TXT, HWP |
| c | 이미지 → 점자 | PDF |

---

## 기술 스택

- **언어/프레임워크**: Java 21, Spring Boot 3.5.13, Gradle-Groovy
- **DB**: PostgreSQL 18.4 (AWS RDS), Redis (Docker 컨테이너)
- **스토리지**: AWS S3 (`semojum-bucket`, ap-northeast-2), EC2 IAM Role 인증(키리스)
- **통신**: gRPC + TLS (AI 서버), SSE (FE 실시간 스트리밍)
- **인증**: Spring Security, JWT (jjwt), BCrypt, SHA-256
- **배포**: Docker Compose, Envoy Proxy, GitHub Actions CI/CD
- **패키지 베이스**: `com.semojum.backend`

---

## 인프라 (AWS, 계정 804136008552, 서울 ap-northeast-2)

| 구성요소 | 상세 |
|---|---|
| EC2 | `semojum-backend`(`i-0287cb956dfefdbde`), t3.medium·30GB, 고정 IP `43.200.184.56`, Ubuntu 22.04 + Docker/Compose v2 |
| RDS | `semojum-postgres`, PostgreSQL 18.4, db.t3.small, 20GB gp3(→100GB 자동증설), 백업 7일. 엔드포인트 `semojum-postgres.c3mk86a8cm0o.ap-northeast-2.rds.amazonaws.com` |
| S3 | `semojum-bucket` — 객체 공개 읽기(썸네일·원본 공개 URL). EC2는 IAM Role(`semojum-ec2-role`/`semojum-ec2-profile`)로 키리스 접근 |
| VPC | **기본(default) VPC** 사용(`vpc-008bb1c520fdf781e`) — 커스텀 VPC(프라이빗 서브넷+NAT)는 출시 후 보안 강화 항목. AI 서버 이전 시 같은 VPC에 넣어 사설 IP 통신 예정 |
| 보안그룹 | `semojum-ec2-sg`(80/443 공개, 22는 관리자 IP만) / `semojum-rds-sg`(5432는 EC2 SG+관리자 IP만) |
| AI VM A | `136.119.89.254`, gRPC `50051`, TLS, authority `semo-jum.com` (외부·GCP, 추후 같은 AWS 계정으로 이전 예정) |
| 도메인 | `api.semojum.app`, Cloudflare Flexible SSL |
| Redis | EC2 내 Docker 컨테이너 (compose에 포함) |
| Docker Hub | `zxhwan/semojum-backend:latest` |
| 예산 알람 | 월 $50의 80%·100% 도달 시 `contact@semo-jum.com` 메일 |

**배포 흐름**: `dev` 브랜치 push → GitHub Actions → Docker Hub → EC2 SSH(`ubuntu@43.200.184.56`, `/home/ubuntu/semojum`, `docker compose`)

### GCP → AWS 이전 상태 (2026-07-29 기준, 브랜치 `feat/aws-migration`)
- **완료**: S3·RDS·EC2 생성 / DB 전체 이관·검증(12테이블) / GCS→S3 객체 460개 이관·일치 확인 / EC2에서 E2E 검증(가입→Job 생성→S3 업로드→워커 다운로드) / GitHub 시크릿(VM_HOST·VM_USER·VM_SSH_KEY) EC2로 교체 완료
- **미완(컷오버 대기)**: ① AI 서버 gRPC 재테스트(테스트 당시 AI 서버 자체가 꺼져 있어 GCP 경로도 불통 — AWS 문제 아님. 방화벽에 `43.200.184.56` 허용 필요할 수 있음) ② 컷오버 직전 DB 델타 재덤프+S3 재동기화 ③ Cloudflare A레코드를 `43.200.184.56`으로 변경 ④ 본 브랜치 dev 머지(머지 시 CI가 EC2로 배포) ⑤ 안정화 후 GCP(VM·Cloud SQL) 정리 + 테스트 계정(`awstest@semojum.test`) 삭제
- **⚠️ 시크릿이 이미 EC2로 교체됨** → 컷오버 전에 dev push 금지(옛 GCS 코드가 EC2에 배포되면 크래시). 다음 dev 반영은 반드시 `feat/aws-migration` 머지
- 구 GCP 인프라(참고): BE VM `34.158.215.55` / Cloud SQL `34.47.68.184` / GCS `semojum-bucket` — 컷오버·안정화까지 유지(롤백 보험)
- 로컬 시크릿: RDS 비밀번호·EIP 등 `~/Desktop/semojum-aws-secrets.txt`(팀 비밀번호 관리자로 이관 권장), SSH 키 `~/.ssh/semojum-key.pem`

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
│   ├── org
│   │   ├── entity       Organization (기관, 계약 만료일)
│   │   └── repository   OrganizationRepository
│   ├── admin
│   │   ├── controller   AdminController (X-Admin-Key 검증)
│   │   ├── dto          AdminRequestDto, AdminResponseDto
│   │   └── service      AdminService (계정 발급·PW 재발급)
│   └── user
│       ├── controller   UserController
│       └── service      UserService
├── global
│   ├── exception        CustomException, ErrorCode, ApiResponse
│   ├── s3               S3Service (구 GcsService 대체, 동일 시그니처)
│   ├── grpc             BrailleGrpcClient
│   ├── jwt              JwtFilter, JwtProvider
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
| AUTH4001 | 401 | 아이디/비밀번호 오류 |
| AUTH4002 | 409 | 이미 사용 중인 로그인 ID |
| AUTH4003 | 401 | 액세스 토큰 만료/유효하지 않음 |
| USER4001 | 404 | 존재하지 않는 회원 |
| ORG4001 | 404 | 존재하지 않는 기관 |
| JOB4001 | 404 | 존재하지 않는 작업 |
| JOB4002 | 400 | 잘못된 파일 형식 |
| JOB4003 | 400 | 지원하지 않는 모드 |
| JOB4004 | 404 | 존재하지 않는 요소 |
| JOB4005 | 400 | 잘못된 elementType (TEXT/BRAILLE만 허용) |
| JOB4006 | 400 | 순서 목록이 현재 페이지 요소와 불일치 (블록 순서변경) |

---

## 구현 완료 목록

### 인증 (Auth) — V3 발급형 체제 (`feat/v3-auth`)
- **자체 가입·소셜 로그인 없음**: 운영자가 기관별 계정(loginId/PW)을 발급, 점역사는 부여받은 계정으로만 로그인 (1인 1계정)
- `POST /api/auth/login` — 발급 loginId/PW 로그인. **중복 로그인 금지**: 로그인 시 기존 활성 세션 전부 revoke(신규가 밀어냄, `revokeAllActiveByUser`)
- `POST /api/auth/logout` — 로그아웃 (리프레시 토큰 revoke)
- `POST /api/auth/refresh` — 액세스 토큰 재발급 (밀려난 세션은 여기서 차단됨)
- 자동 로그인 X: refresh 만료 30일 → **12시간**. 초기 비밀번호는 난수 발급·사용자 변경 불가(운영자 재발급만)
- DB 세션 관리: `user_sessions` 테이블, SHA-256 해시 저장
- **운영자 API** (`/api/admin`, `X-Admin-Key` 헤더 검증 — env `ADMIN_API_KEY`, 미설정 시 전부 차단):
  - `POST /api/admin/orgs` 기관 생성(계약 만료일 포함) / `POST /api/admin/accounts` 계정 발급(난수 PW 응답에 1회만 노출) / `POST /api/admin/accounts/{loginId}/password-reissue` PW 재발급
- 레거시(이메일/소셜) users 행은 login_id가 null이라 로그인 불가 상태로 보존

### Job
- `POST /api/jobs` — Job 생성 (multipart)
  - 모드 a/c: PDF 페이지별 분리 → S3 업로드
  - 모드 b: TXT/HWP 30줄 단위 청크 → S3 업로드
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
- S3 파일 다운로드 → gRPC 요청 (AI 서버) → ResultService.save() → Redis 상태 업데이트
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
  - cutoff는 `Instant`(절대시각) 기반 → 컨테이너/DB(RDS) 타임존과 무관하게 정확

### 마이페이지 (User)
- `GET /api/users/jobs` — 내 Job 목록 조회 (최신순, thumbnailUrl 포함)
- `GET /api/users/jobs/{jobId}/pages/{pageNo}` — 페이지별 변환 결과 조회 (모드별 직렬화)
  - 응답 **바깥 레벨**에 `original`(원본) 포함: mode a/c는 `{type:"pdf", url:<공개 URL>, lines:null}`, mode b는 `{type:"text", url:null, lines:[...]}` (S3의 `page-n.txt`를 `split("\n", -1)`로 읽음, **DB 컬럼 추가 없음**)
- 두 엔드포인트 모두 JWT 인증 필요, 타인 Job 접근 시 403
- `getMyJobs`/`getJobPage`는 `@Transactional(readOnly=true)` (OSIV off 대응)

### 점역사 수정 (Edit)
- `PATCH /api/jobs/{jobId}/pages/{pageNo}/elements/{elementId}` — 요소 수정 (`ElementEditService`, `@Transactional`)
  - body: `{ "elementType": "TEXT"|"BRAILLE", "contents": [...] }`
  - `{elementId}`는 응답/SSE에 내려가던 **AI element id(String)** (엔티티 PK 아님) → `findByPageResultAndElementId`로 조회
  - `current`(currentContents/currentContent)만 갱신, **`original`은 절대 보존**. 응답으로 갱신된 contents 반환
  - 본인 Job 검증(타인 403), 없는 요소 404, 잘못된 elementType 400. `is_blocked`/상태 무관 수정 허용
- **edit_logs 스냅샷 기록(RLHF용)**: 수정과 같은 트랜잭션에서 1수정=1행 저장
  - 공통: `action`(EDIT/DELETE/ADD) + before/after content + `ai_original_content` + mode/element_type/user/job/page
  - mode a/c: `source_pdf_path` + `image_width/height` + `bounding_box`(해당 요소)
  - mode b: `source_text`(변환에 쓴 원본 한글텍스트, S3 `.txt`에서 읽음)
  - 입력 컨텍스트까지 자기완결 스냅샷(같은 요소 반복 수정 시 컨텍스트 중복은 의도된 트레이드오프). PDF/이미지 바이너리는 저장 안 하고 s3 경로만(구 데이터는 gs:// 경로 — S3Service가 양쪽 호환)
  - 저장 로직은 `saveEditLog(...)` 공통 헬퍼로 EDIT/DELETE/ADD가 공유

### 블록 편집 (Block Edit)
`ElementEditService`, 모두 `@Transactional` + 본인 Job 검증(타인 403). 브랜치 `feat/block-edit`.
- **읽기 정렬 토대**: `text_elements`/`braille_elements`에 `is_deleted`(soft-delete) 추가. `findByPageResult`가 `is_deleted=false` 필터 + `ORDER BY reading_order`(`@Query`, 호출부 무변경) → 추가/삭제/순서변경이 `buildResult`(SSE·마이페이지) 응답에 반영됨.
- **순서(reading_order)는 서버가 소유**: 어떤 편집이든 살아있는 블록을 최종 순서대로 `reading_order = 1..N` 재번호. FE는 order 숫자를 계산하지 않고 "무엇을/어디에"만 전송.
- `POST /api/jobs/{jobId}/pages/{pageNo}/elements` — 블록 추가
  - body: `{ elementType, contents, afterElementId, type }` (`afterElementId` null이면 맨 앞, `type` 기본 "text")
  - 서버가 element_id 발급, `original=NULL`(=사용자 작성 블록 표시), `current=contents`. afterElementId 뒤 삽입 후 재번호. edit_logs `action=ADD`(before=`[]`, ai_original=null). 없는 afterElementId면 404
- `DELETE /api/jobs/{jobId}/pages/{pageNo}/elements/{elementId}?elementType=` — 블록 삭제
  - soft-delete(`is_deleted=true`) + 남은 블록 재번호. 이미 삭제된 요소 재삭제 시 404. edit_logs `action=DELETE`(after=`[]`)
- `PATCH /api/jobs/{jobId}/pages/{pageNo}/elements/order` — 순서변경
  - body: `{ elementType, orderedElementIds }` (그 페이지 최종 순서 전체) → `reading_order` 1..N 재작성. 살아있는 요소들의 순열이어야 함(불일치 시 JOB4006). edit_logs 미기록(내용 안 바뀌는 구조 변경 전용)
- **DDL(ddl-auto:none, 수동 적용 완료)**: `is_deleted`(text/braille, NOT NULL default false), `edit_logs.action`(varchar, 기존행 EDIT 백필), `original_contents`/`original_content`/`ai_original_content` NOT NULL 해제(사용자 추가 블록·ADD 로그는 AI 원본 없음)

### 썸네일 (ThumbnailService)
- Job 생성 시 자동 생성 후 S3 업로드, 공개 URL을 `jobs.thumbnail_url`에 저장
- mode a/c: PDFBox로 PDF 첫 페이지 → PNG 렌더링
- mode b: 텍스트를 NanumGothic 폰트로 흰 배경에 렌더링 → PNG
- 생성 실패 시 경고 로그만 남기고 Job 생성은 정상 진행

### Spring Security
- `JwtFilter`: PERMIT_URLS = `/api/auth/login`, `/api/admin/`(X-Admin-Key 자체 검증), `/swagger-ui`, `/v3/api-docs`
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
| users | 회원 — V3 발급형(login_id, organization_id). 레거시 이메일/소셜 행은 보존만 |
| organizations | 기관 (계약 만료일, 상태) — V3 신규 |
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
- RDS는 상시 가동(중지 시 7일 후 자동 재시작됨에 유의). 구 Cloud SQL은 컷오버 안정화 후 삭제 예정
- SSE 연결 시 Envoy `timeout: 0s` 필수. `idle_timeout`은 SSE 최대 수명(3시간=`EMITTER_TIMEOUT`) 이상으로 둘 것 (현재 `envoy.yaml`은 `10800s`로 정합)
- gRPC 타임아웃은 AI 서버 하드 타임아웃(180s)보다 높은 200s로 설정
- PageWorker `WORKER_COUNT = 1` (임시) — AI 서버 병렬처리 확인 후 6으로 복구
- `jobs.updated_at`(timestamptz)은 `ddl-auto:none`이라 수동 ALTER 필요. 엔티티는 `insertable/updatable=false`이고 DB default(now()) + touchJob/finishJob으로만 갱신 → 코드 배포 전에 DDL(컬럼 추가 → 백필 → NOT NULL → DEFAULT) 먼저 실행
- stale-job 타임아웃은 `application.yaml`의 `job.stale.in-progress-timeout`(1h) / `pending-timeout`(12h)로 조정
- V3 인증 개편 배포 전 `ddl/v3_auth.sql` 수동 실행 필요 (organizations 생성 + users에 login_id/organization_id 추가)
- `edit_logs` 테이블은 `ddl-auto:none`이라 자동 생성 안 됨 → 수정 API 배포 전에 DataGrip에서 `CREATE TABLE edit_logs (...)` 먼저 실행
- UserService와 SseService 간 result 직렬화 헬퍼 코드 중복 존재 → 추후 공통 컴포넌트로 리팩토링 예정
