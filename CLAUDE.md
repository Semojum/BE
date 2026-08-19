# 세모점(Semojum) BE 개발 가이드

## 프로젝트 개요

**세모점** — 점역사(전문 점자 번역사)를 위한 AI 기반 점자 변환 플랫폼.

| 모드 | 변환 | 입력 파일 |
|---|---|---|
| a | 이미지 → 텍스트 | PDF |
| b | 텍스트 → 점자 | TXT, HWP |
| c | 이미지 → 점자 | PDF |

## 기술 스택

- Java 21, Spring Boot 3.5.x, Gradle-Groovy, 패키지 베이스 `com.semojum.backend`
- PostgreSQL 18 (AWS RDS) + **Flyway**(스키마 자동 마이그레이션), Redis (EC2 내 Docker)
- AWS S3 (`semojum-bucket`, EC2 IAM Role 키리스), gRPC+TLS (AI 서버), SSE (FE 스트리밍)
- Spring Security + JWT(jjwt), BCrypt, SHA-256
- Docker Compose + Envoy, GitHub Actions CI/CD, **블루그린 무중단 배포**

## 인프라 (AWS 서울, 계정 804136008552)

| 구성요소 | 상세 |
|---|---|
| EC2 | `semojum-backend`, t3.medium, 고정 IP `43.200.184.56`, Ubuntu 22.04 + Docker Compose v2 |
| RDS | `semojum-postgres` (PostgreSQL 18), 엔드포인트 `semojum-postgres.c3mk86a8cm0o.ap-northeast-2.rds.amazonaws.com`. 백업 7일. **로컬·개발·운영이 이 DB 하나를 공유** — 마이그레이션은 어느 환경에서 먼저 적용돼도 안전해야 함 |
| S3 | 공개 읽기는 `*/thumbnail.png`만. 원본 페이지는 **presigned URL(15분)**. CORS `GET/HEAD`·origin `*` — 제거 시 에디터 원본 렌더링이 깨짐 |
| AI 서버 | `semojum-ai`(g5.2xlarge), **같은 VPC — 반드시 사설 IP `172.31.47.101:50051`로 접속**. 공인 IP로 가면 AI 보안그룹의 "BE SG 허용" 규칙에 안 걸려 차단됨 |
| 도메인 | `api.semojum.app` (Cloudflare Flexible SSL) |
| Docker Hub | `zxhwan/semojum-backend:latest` |
| 예산 알람 | 월 $50의 80%·100% → `contact@semo-jum.com` |

- 보안그룹: EC2는 80/443 공개·**22는 관리자 IP만** / RDS 5432는 EC2 SG+관리자 IP만
- **22번을 0.0.0.0/0으로 열지 말 것** — CI가 배포 동안만 러너 IP를 추가·회수한다(`if: always()`). 전용 IAM `semojum-github-actions`는 SG 토글 권한만 보유
- 관리자 IP가 바뀌면 `semojum-ec2-sg`(22)·`semojum-rds-sg`(5432) 두 곳 갱신
- 로컬 시크릿: `~/Desktop/semojum-aws-secrets.txt`, SSH 키 `~/.ssh/semojum-key.pem`, 어드민 키 `~/Desktop/semojum-admin-key.txt`

## 배포 — 블루그린 무중단 (2026-08-16 전환)

- 흐름: `dev` push → GitHub Actions(테스트 게이트) → Docker Hub → EC2에서 `scripts/deploy.sh`
- deploy.sh: 활성 색 감지 → 새 이미지를 비활성 색(backend-blue/green)으로 기동 → `/api/health` 게이트(최대 120s, **실패 시 구버전 유지·exit 1**) → Envoy 헬스체크(3s×2) 자동 편입 → 구 색 graceful 정지
- 롤백 = 방금 내린 색 재기동 한 줄
- ⚠️ 색 서비스는 compose **profiles** — `docker compose up -d`로는 backend가 안 뜬다. 수동 조작 시 `--profile blue|green` 필수
- Envoy: 액티브 헬스체크(`/api/health`) + 재시도는 connect-failure/refused-stream만(POST 중복 방지). **SSE 라우트 timeout 0s·idle_timeout 10800s 변경 금지**
- 안전핀: `-Xmx768m`(JAVA_TOOL_OPTIONS), EC2 스왑 1GB, graceful shutdown 20s(+stop_grace_period 30s)
- 로그 뷰어: `scripts/semlog.sh` (ERROR 빨강·WARN 노랑·REQ 시안)

## 패키지 구조 (요약)

```
com.semojum.backend
├── domain
│   ├── auth      로그인/로그아웃/refresh, User·UserSession (V3 발급형 계정)
│   ├── admin     운영자 API (ROLE_ADMIN JWT 전용)
│   ├── org       Organization (기관, 계약 만료일)
│   ├── user      마이페이지 목록·페이지 조회 (UserService)
│   ├── folder    폴더 CRUD·트리·contents (FolderService, FolderTouch)
│   ├── trash     휴지통 목록/복원/완전삭제 + TrashPurgeScheduler(04시)
│   ├── job       Job 생성·다운로드·취소·SSE, JobManageService(이름변경/이동/삭제)
│   │   ├── scheduler   JobDispatcher(공정 큐), StaleJobScheduler
│   │   └── worker      PageWorker
│   └── result    AI 결과 저장(ResultService), 페이지 일괄 저장(PageSaveService)
├── global
│   ├── config    SecurityConfig, RedisConfig, S3Config, SchedulingConfig, SwaggerConfig
│   ├── jwt       JwtFilter, JwtProvider
│   ├── exception CustomException, ErrorCode, ApiResponse
│   ├── grpc      AiServerPool, BrailleGrpcClient
│   ├── s3 / hwp / thumbnail / health / logging / util
└── grpc          proto 생성 클래스
```

## 공통 응답 구조

```json
{ "isSuccess": true, "code": "COMMON2000", "message": "성공입니다.", "result": {} }
```

### 에러 코드
| 코드 | HTTP | 설명 |
|---|---|---|
| COMMON4000 | 400 | 잘못된 요청 |
| COMMON4001 | 401 | 인증 필요 |
| COMMON4003 | 403 | 권한 없음 |
| COMMON4004 | 404 | 존재하지 않는 경로 |
| COMMON4005 | 405 | 지원하지 않는 메서드 |
| COMMON5000 | 500 | 서버 에러 |
| AUTH4001 | 401 | 아이디/비밀번호 오류 |
| AUTH4002 | 409 | 이미 사용 중인 로그인 ID |
| AUTH4003 | 401 | 액세스 토큰 만료/무효 |
| AUTH4004 | 403 | 비활성화된 계정 |
| USER4001 | 404 | 존재하지 않는 회원 |
| ORG4001 | 404 | 존재하지 않는 기관 |
| JOB4001 | 404 | 존재하지 않는 작업 |
| JOB4002 | 400 | 잘못된 파일 형식 |
| JOB4003 | 400 | 지원하지 않는 모드 |
| JOB4004 | 404 | 존재하지 않는 요소 |
| JOB4006 | 400 | 요소 목록 불일치 (일괄 저장 중복 id 등) |
| JOB4007 | 400 | HWP 파싱 실패 |
| JOB4008 | 400 | 암호 설정/배포용 HWP |
| JOB4010 | 409 | 변환 중 조작 불가 |
| JOB4012 | 400 | 다운로드할 변환 결과 없음 |
| FOLDER4001~4 | — | 폴더 관련 (미존재/깊이/중복/상한) |

## 도메인별 핵심 규칙

### 인증 (V3 발급형)
- 자체 가입·소셜 없음 — 운영자가 기관별 계정(loginId/PW) 발급, 1인 1계정
- **역할 3단**: ROLE_ADMIN(운영자) / **ROLE_ORG_ADMIN(기관 관리자 — T2 `/api/org/**` 접근)** / ROLE_USER(점역사)
- 로그인 시 기존 활성 세션 전부 revoke(중복 로그인 금지), refresh 만료 12시간(자동 로그인 X). 성공 시 `users.last_login_at` 기록
- `user_sessions`에 SHA-256 해시 저장. 로그아웃은 리프레시만 revoke(액세스는 만료까지 유효 — JWT stateless)
- JwtFilter PERMIT_URLS: `/api/auth/login·refresh·logout`, `/api/health`, `/api/public/`, swagger 2종 — `/api/admin/`은 JWT 필수

### 운영자 API (ROLE_ADMIN JWT 전용 — X-Admin-Key는 2026-08-19 폐기)
- 인증 = 운영자 콘솔(admin.semo-jum.com) 로그인 → Bearer 토큰. SecurityConfig `hasRole(ADMIN)` + AdminController `validateAdminRole()` 이중 방어, 비ADMIN 토큰 403·무토큰 401(JSON)
- **기관·계정 관리(T1-6·7)**: 통합 표(`GET /api/admin/orgs?month=` — 기관별 계정+소계) / 기관 상세·수정(`GET·PATCH /api/admin/orgs/{orgId}` — 이름·**계약 유형(V24: 유료 BASIC·STANDARD·PREMIUM / 무료 FREE(체험)·COUPON(쿠폰 제공) — 신규 기본 FREE)**·기간·**할당 크레딧 설정**) / **삭제는 소프트**(`DELETE /api/admin/orgs/{orgId}`=소속 계정 전부 잠금+deleted_at, `DELETE /api/admin/accounts/{loginId}` — 실삭제는 보관 기간 정책 확정 후, V21). 삭제된 기관엔 계정 발급 불가, 삭제 계정은 T2 목록·제어에서 제외
- 기관 생성(`POST /api/admin/orgs`) / 계정 일괄 발급(기관 ID+수량 → `{기관코드}{순번}`, PW는 응답에 1회만 노출) / PW 재발급 / 상태(ACTIVE·INACTIVE)·역할 변경 / 단가표(`GET·PUT /api/admin/pricing`) / 공지(`POST·GET /api/admin/notices`) / 문의(`GET /api/admin/inquiries`, `PATCH .../{id}/status` — OPEN·IN_REVIEW·ANSWERED) / 주문·수납(`POST·GET /api/admin/orders`, `PATCH .../{id}` 입금·계산서 기록) / **모니터링(`GET /api/admin/jobs` — 전 기관 최근 24h 기본·10초 폴링, `GET /api/admin/jobs/{jobId}` — 접속 메타데이터·원가·크레딧·쪽별 결과+사유, `GET .../pages/{pageNo}` — T1-5 미리보기(소유자 검증 없는 페이지 결과+presigned 원본, UserService.getJobPageAsAdmin), `POST .../send-to-mypage` — 사본을 운영자 계정 마이페이지로(AdminCopyService: Job~품질 행 전체+S3 서버사이드 복사, 편집 original/current 보존, page_edit_logs 미복사, 대상은 ROLE_ADMIN만·진행 중 작업 거부). 진행 중 작업의 원가는 null(끝나야 확정), 재시도 중복 page_results는 최신만)** / **통계(`GET /api/admin/stats/overview?period=today|week|month` 건수·쪽수·시계열+누적 원가, `/stats/workload?unit=daily|weekly|monthly|all` 완료·실패취소 스택, `/stats/layout-cost?month=` 유형별 평균 원가 비싼 순+전월 대비, `/stats/profitability?month=` **기관별 수익성 — 차액=환산 매출(계약분 차감×유형별 단가)−원가. 단가는 pricing_configs.creditPricesByContract(관리 변수, biz 확정 2026-08-18: BASIC 200/STANDARD 150/PREMIUM 120/FREE·COUPON 0), 매출은 조회 시점 환산(확정 회계는 orders 담당)** — 네이티브 date_trunc 집계(AdminStatsRepository))**
- 구 X-Admin-Key·`ADMIN_API_KEY`(.env)·어드민 키 파일은 폐기 — 운영 스크립트도 로그인 토큰 사용

### Job 생성 (`POST /api/jobs`)
- multipart: `mode` + `insertPageNumber`(선택, 업로드 시 확정 — 에디터 토글 폐지) + `footerText`(선택, 묵자 최대 200자, 다운로드 때 점역)
- 페이지 분리: a/c는 PDF 페이지별 / b는 HWP 실제 페이지(레이아웃 기반)·TXT 30줄 청크 → S3 업로드
- 적재는 JobDispatcher.enqueueJob — **트랜잭션 커밋 후** 실행(커밋 전 적재 시 워커가 not found 재시도)
- **접속 메타데이터 수집(V19)**: 생성 시 `jobs.client_ip·client_os·client_browser·client_user_agent` 기록(`ClientInfoResolver` — IP는 CF-Connecting-IP > XFF 첫 항목 > remoteAddr, UA는 간이 파싱+원본 보존). **위치는 저장 안 함** — 표시 시점에 IP로 GeoIP 조회(과거 작업도 가능). T1-4 요청 정보의 원천, 사용자 응답에는 안 실림
- 썸네일 자동 생성(a/c: PDF 첫 장 렌더, b: 텍스트 렌더) — 실패해도 Job 생성은 진행

### 스케줄링 (JobDispatcher — 공정 큐)
- 구 단일 `task_queue` 폐기. 작업별 큐(`queue:job:{jobId}`) + **2계층 라운드로빈**: 유저 링 회전 → 그 유저의 작업 링 회전 → 페이지 1장 pop — 유저 간·작업 간 공평
- 우선순위 FG(보는 중):BG(앱 종료) = 4:1. FG 판정은 리스 키(`sched:job:{id}:fg`, TTL 30s) 존재 — SSE·status 폴링이 갱신, 끊기면 자연 강등
- 링·큐 상태는 전부 Redis → BE 재시작에도 복구. 선택 연산은 synchronized poll() (BE 단일 인스턴스 전제)

### PageWorker
- **워커 수 = AI 서버 총 슬롯 수** (`grpc.ai.servers`의 슬롯 합, AiServerPool). 워커 하나가 슬롯 하나를 점유해 블로킹 gRPC
- S3 다운로드 → gRPC → ResultService.save() → Redis 상태 갱신. 오류 시 큐 **머리** 재삽입(순서 유지) + 2초 대기, 최대 3회 → 초과 시 `markPageBlocked`(DB BLOCKED 후 Redis put은 항상 실행 — SSE 종료 감지 보장)
- 취소 플래그(`job:{id}:canceled`)를 pop 직후·재시도 직전 검사. `@PreDestroy` graceful shutdown

### ResultService / Job 상태
- 저장: page_results, text_elements, braille_elements, bounding_boxes, rule_trails, quality_* — drafts는 jsonb `List<Map>` 매핑(응답에 JSON 배열로 직렬화)
- 종료 판정: 전 페이지 terminal 시 성공 0건이면 FAILED, 1건 이상 COMPLETED(부분 성공=완료)
- `touchJob`/`finishJob` 모두 `WHERE status IN ('PENDING','IN_PROGRESS')` 가드 — **종료된 Job을 페이지 이벤트가 못 되살림. 가드 제거 금지**
- StaleJobScheduler(5분): IN_PROGRESS 무진행 1h / 고아 PENDING 12h → FAILED (`job.stale.*`로 조정, Instant 기반)

### SSE (`GET /api/jobs/{jobId}/events`)
- `queue_position` → `page_done`(모드별 직렬화) → `job_done`. **page_done은 반드시 페이지 순서(1,2,3…)대로 방출** — 뒤 페이지가 먼저 끝나도 보류(커서 방식, 재연결 시 완료분 순서 재전송)
- 폴링 대체: `GET /api/jobs/{jobId}/status` (Redis Hash)

### 다운로드 (`POST /api/jobs/{jobId}/download`)
- body `{fileName}`(선택), 응답은 파일 스트림. mode a=`.txt` / b·c=`.brf`
- a: current를 읽기 순서로 병합 — 요소 간 `\n`, 페이지 간 `-`×40 구분선 1줄, **빈 블록·빈 페이지 스킵**, `<!점역자주>` 마커 유지
- b·c: **braille-assist 라이브러리에 조판 전체 위임** — `BrailleAssist`는 원 레포(Semojum/braille-assist) 복사본, **수정 금지·규칙 변경은 원 레포에서**
- 꼬리말은 `jobs.footer_text`를 다운로드 시점에 점역. 항상 DB 최신 편집본으로 즉시 생성. 변환 중 JOB4010, 결과 없음 JOB4012

### 취소 (`POST /api/jobs/{jobId}/cancel`)
- 즉시가 아닌 **수렴**: 플래그 → 큐 배수 → 인플라이트 마무리 → 확정(완료된 마지막 페이지 뒤는 Page 삭제+total_pages 축소, 사이 구멍은 BLOCKED)
- 완료 0건이면 전부 BLOCKED+FAILED. 확정 시 `canceled_at`·`original_total_pages` 기록(운영·CS용). 이미 끝난 작업 취소는 멱등

### 마이페이지 (목록 3경로 + 페이지 조회)
- **탐색 vs 검색 분리**: 폴더 진입 화면은 `GET /api/folders/{folderId}/contents`(S2)·`GET /api/folders/contents`(S1 루트) / 전역 나열·검색은 `GET /api/users/jobs` / 파일만 최신순은 `GET /api/users/jobs/recent`(첫 화면 스트립·S9)
- **세 경로 모두 응답 `{folders, files:{items, nextCursor, hasMore}}` 동일**. 커서 요청(2페이지~)에는 `folders` 빈 배열 — FE 누적 중복 방지
- 검색은 현재 위치 **서브트리 전체**(탐색기와 동일), 비검색은 한 층만. 상태·모드 필터 시 폴더는 결과에서 제외, 즐겨찾기·정렬은 폴더+파일, 검색어는 파일명+폴더명
- JobCard: `jobId·mode·status·progress·originalFileName·thumbnailUrl·displayDate·totalPages·lastEditedPage·isFavorite·folderId·folderPath`
  - `progress`: **변환 중일 때만 0~100**(완료 페이지 비율, Redis), 그 외 null (2026-08-17 복원). 생성 중 카드가 있는 동안 FE 10초 재조회
  - **`folderId`/`folderPath` 제거 금지** — S9·검색의 위치 표시와 "폴더로 이동"에 사용
- `jobs.last_modified_at` = **내용이 바뀐 시각**(카드 날짜·정렬·커서·복구 기준) — 페이지 편집(`markContentEdited`)에서만 갱신. 이름변경·이동·복원·즐겨찾기는 갱신 안 함. 변환 진행은 `updated_at`(StaleJobScheduler 전용) — **두 컬럼 섞지 말 것**
- `folders.last_modified_at`(폴더 정렬 기준) = 직속 항목의 추가·삭제·이름변경만 갱신(윈도우 탐색기 규칙), 내용 편집·즐겨찾기는 제외, 상위 전파 없음. 갱신은 `FolderTouch`에 집약 — 폴더 안 항목을 바꾸는 코드 추가 시 호출할 것. ⚠️ V12 SQL 주석("내용 편집도 포함")은 현재 동작과 다름 — 기준은 이 문서와 `Folder.touchModified()` 주석
- 페이지 조회(`GET /api/users/jobs/{jobId}/pages/{pageNo}`): 응답 바깥에 `original` — a/c는 presigned URL(15분, **URL 장기 캐시 금지**), b는 텍스트 줄 배열. 타인 Job 403

### 편집 — 페이지 일괄 저장 (`PUT /api/jobs/{jobId}/pages/{pageNo}/elements`)
- **유일한 편집 경로** (구 요소 단위 API 4종·edit_logs는 V13에서 제거). body = 페이지 최종 상태 전체 순서대로 `[{id|null, contents}]`
- diff는 서버 판정: id+contents 다름=EDIT / id null=ADD(UUID 발급, original=NULL) / 빠짐=soft-delete / 상대 순서 변화=reorder. 모르는 id 404, 중복 id JOB4006
- 편집 대상은 mode가 결정: a=text_elements, b·c=braille_elements. `current`만 갱신, **`original` 절대 보존**. reading_order는 서버가 배열 순서로 1..N 재번호
- 변경 있으면 `markContentEdited(pageNo)`(+`last_edited_page`), 없으면 아무것도 안 건드림

### 대체 초안 선택 (`PATCH .../elements/{elementId}/draft`)
- drafts 중 선택 → `selected_idx` 갱신 + current 교체(포인터+복사 — drafts·original 불변). `selectedIdx=-1` = 원본 복귀
- 값은 mode가 결정: b·c는 `draft.contents`(점자) / a는 `draft.text`(기존 본문이 `<!점역자주>` 마커면 새 텍스트도 감쌈)
- page_edit_logs에 `draft_selected` 기록 — RLHF 선호 신호

### page_edit_logs (RLHF 학습용)
- **1저장 = 1행**, 페이지 전체 before/after 스냅샷(jsonb, origin ai/user 구분) + `changed`(edited/added/deleted/reordered)
- 입력 컨텍스트 자기완결: a/c는 source_pdf_path+이미지 크기, b는 source_text. 저장과 같은 트랜잭션

### HWP 페이지 분리 (HwpPageExtractor)
- 페이지 경계 = 레이아웃 캐시 LineSeg의 y좌표 **리셋 지점**. 다단 문서는 단 개수 N 추적해 N번째 리셋만 경계로. ⚠️ `isFirstLineAtPage()`는 실파일에서 항상 false — **사용 금지, y 리셋 방식 유지**
- 표·중첩 표 셀까지 재귀 추출(`[표 시작]`/`[표 끝]`, 행=줄·칸=탭), 머리말·꼬리말·각주·미주 추출(판면 순서: 머리말→본문→각주→꼬리말)
- 암호/배포용 문서는 JOB4008 거부. **hwplib 1.1.9 필수** — 1.1.1은 일부 파일에서 무한 대기(다운그레이드 금지)
- 디버그: `HWP_DEBUG_FILE=<경로> ./gradlew test --tests HwpPageExtractorDebugTest --rerun`

### 기관 관리 T2 (`/api/org/**`) · 사용량 T3 (`/api/users/usage`)
- **T2 (ROLE_ORG_ADMIN 전용, 권한 검증은 서비스 403)**: `GET /dashboard`(계약·크레딧 할당/사용/잔여·월별 추이 6개월) / `GET /accounts`(소속 계정 + 월 사용 크레딧) / `PATCH /accounts/{loginId}/alias` / `PATCH /accounts/{loginId}/lock` / `GET /accounts/{loginId}/jobs`(T2-2, 기간 기본 30일)
- **잠금 = 즉시**: INACTIVE + 세션 전부 revoke + **진행 중 변환 취소(JobCancelService 재사용)** — 쪽 단위 차감이라 "완료된 쪽까지만 차감" 자동 성립. **본인 잠금 불가**(COMMON4000), 타 기관 계정 403
- **열람 범위(기획 확정)**: 기관 관리자는 목록·상태·크레딧까지 — 파일 내용·접속 정보 제공 금지 / 점역사(T3)는 내 사용량 + 기관 전체 잔여만 — **타 계정 개별 소모량 제공 금지**
- **기관 관리자는 점역(에디터) 사용 불가(기획 확정 2026-08-19)**: ROLE_ORG_ADMIN의 Job 생성은 COMMON4003 — JobService.createJob 가드. FE도 T2 화면만 노출
- T3: `GET /api/users/usage?month=YYYY-MM`(이번 달/지난달) / `GET /api/users/usage/jobs?from&to` — 진행 중 작업의 크레딧은 null(끝나야 확정), donePages는 Redis(JobProgressReader, 장애 시 null)
- 기관 크레딧 잔여 = `organizations.credit_allocated`(V17, 운영자 설정) − credit_transactions 합. 계약 시작일·계정 별칭도 V17 (계약 유형은 V24에서 5종으로 개편 — 운영자 API 절 참조)
- **문의 메일 연동 (V20, MailInboxPoller)**: 회사 메일함(Google Workspace)을 5분 주기 IMAP **읽기 전용** 폴링(메일함 읽음 표시 안 건드림, 답장은 메일함에서) → inquiries에 `type=EMAIL·sender_email·subject`로 저장, 기존 상태 관리 공유. 중복 방지 `mail_uid`("UIDVALIDITY:UID") 유니크. **자격증명은 EC2 `.env`의 `MAIL_INBOX_USERNAME`/`MAIL_INBOX_PASSWORD`(Workspace 앱 비밀번호)** — 미설정이면 폴러 비활성(fail-safe)
- **문의·공지·주문 (support 도메인, V18)**: 공지=운영자 작성 → T2 `GET /api/org/notices`(전체+자기 기관, **노출 기간 내만 — 스케줄러 없이 조회 시 판정**) / 주문=운영자 기록 → T2 `GET /api/org/orders`(+증빙 이메일, `PATCH /api/org/receipt-email`) / **T2 요청**(`POST·GET /api/org/requests`, `DELETE .../{id}`) = 크레딧 추가·계정 발급 요청이 inquiries로 접수돼 T1-9 목록에 모임. **취소는 자기 기관+요청 유형+OPEN일 때만**(hard delete)
- **홈페이지 공개 문의 (`POST /api/public/inquiries`, 무인증)**: 유형 ONBOARDING·ERROR_REPORT·ETC, 미가입 접수(org·user null — T1-9에 이름·이메일 표시). 남용 방어는 서비스 계층 — 허니팟(website 채워지면 성공한 척 폐기) + IP 시간당 5건(Redis, 장애 시 접수 허용)
- **주문 증빙 파일 (V25)**: 운영자 업로드 `POST /api/admin/orders/{id}/receipt`(multipart, pdf·png·jpg ≤10MB, 재업로드=교체 — S3 `receipts/{orderId}/` 기존 삭제 후 저장) / 내려받기 `GET /api/admin/orders/{id}/receipt`·`GET /api/org/orders/{id}/receipt`(자기 기관만 403, presigned 15분). 주문 목록 응답에 `receiptFileName`(null=미첨부)
- 미구현(다음 단계): 점역 기본 설정(AI 스키마 대기), 실삭제(보관 기간 정책 대기)

### 사용량·원가·크레딧 (billing — proto 08.17)
- **AI는 측정값만 보낸다** (`UsageReport`: layout_type 4종+UNSPECIFIED, 모델별 토큰, gpu_time_ms) — **금액·크레딧은 BE가 계산** (`UsageCostService`, AI팀 노션 "BE 관리 변수" 계산식). BLOCKED 응답에도 실림(→ save() 경로에서 저장; markPageBlocked는 gRPC 실패용이라 응답 자체가 없어 해당 없음)
- **단가·배율은 `pricing_configs` 테이블** (수정 = 새 행 추가, 과거 판 불변 — `page_results.pricing_config_id`가 계산 근거를 가리킴). 키: modelPrices / gpuUsdPerHour / usdKrw / cardFeeRate / creditMultiplier. **코드에 하드코딩 금지** — V16 시드가 초기값
- 계산 결과(원가 USD·KRW·크레딧)는 **쪽 처리 시점 값으로 확정 저장** — 단가를 바꿔도 과거 기록 불변. 원자료(토큰·gpu_time)도 함께 저장(감사·재검산용)
- **단가표에 없는 모델 = 0원으로 삼키지 않고 `cost_uncertain=true`(미계상) 표시** (proto 주석 명시)
- 크레딧: **성공한 쪽만** 배율 차감(UNSPECIFIED 0 / TEXT 1 / FORMULA 2 / TABLE 3 / VISUAL 5 — biz 확정 2026-08-17), 실패 쪽 무차감. **0 차감도 `credit_transactions`에 기록**(고객 검산용). `(job_id, page_no)` 유니크 — 워커 재시도 재진입에도 이중 차감 불가
- **쿠폰 우선 차감 (V23, CreditDeductionService)**: 유효 기간 내·전액 들어갈 잔량 있는 쿠폰(오래된 순)이 있으면 `source=COUPON`, 아니면 `CONTRACT`. 쪽 차감은 원자 단위라 잔량 부족 쿠폰은 건너뜀(쪼개 담지 않음). 쿠폰 행 잠금(PESSIMISTIC_WRITE)으로 병렬 워커 초과 소진 방지. **잔여 게이지·수익성 매출은 CONTRACT 차감만** — 쿠폰 차감은 계약 잔여 불변·매출 0(수익성에서 원가만큼 마이너스). 발급·목록: `POST·GET /api/admin/orgs/{orgId}/coupons`
- 원가 계산 실패는 변환 결과 저장을 막지 않는다(로그만, usage null 저장)

### 로깅
- 형식: `시각 레벨 [ctx] 로거 : 메시지` — ctx는 요청 `req-xxxxxxxx`(RequestLogFilter) / 워커 `jobId|pN`. `grep <jobId>`로 전 로그 묶임
- 액세스 로그 `REQ 메서드 경로 → 상태 (ms) user=…` 한 줄. 레벨=결과(4xx WARN·5xx ERROR). **4xx에 스택 금지** — `grep -E "WARN|ERROR"`가 곧 장애 화면
- **`show-sql: false` 고정**(stdout 직행 노이즈), SQL 디버깅은 `org.hibernate.SQL=DEBUG`. **`/error`는 permitAll 유지**(빼면 예외 1건이 인가 거부 스택 수백 줄로 증폭)
- SSE page_done 전문은 `sse.payload` 로거. compose 로그 rotation 10MB×5. 저장 로그에 색·이모지 금지(색칠은 semlog)

### gRPC (AI 서버)
- 설정 `grpc.ai.servers` = `host:port:슬롯수[,…]` (`GRPC_AI_SERVERS`로 교체 가능) — 서버 증설·슬롯 조정은 환경변수+재시작으로 끝
- deadline 400s (AI 하드 타임아웃 180s × 대기+변환 최악 케이스 360s를 감쌈)
- TLS: AI 자체 서명 인증서를 EC2 `~/semojum/server.crt`로 볼륨 마운트. `authority: semo-jum.com` — SAN에 IP가 없어도 이 설정으로 검증됨, **제거하면 연결이 깨진다**
- 인증서 교체: scp로 파일 교체(재빌드 불필요) → `docker compose restart backend` → 지문 3곳(로컬/EC2/컨테이너) 일치 확인. 교체 전 백업

## DB 스키마

users / organizations / user_sessions / jobs / pages / page_results / text_elements / braille_elements / bounding_boxes / rule_trails / quality_critical_errors / quality_review_flags / **folders** / **page_edit_logs** / **pricing_configs** / **credit_transactions** / **notices** / **inquiries** / **orders**

- 마이그레이션: `src/main/resources/db/migration/V{n}__*.sql` (Flyway, baseline=1). **적용된 파일은 절대 수정 금지**(체크섬) — 정정은 새 V{n}으로
- Page 상태: PENDING / RUNNING / COMPLETED / NEEDS_REVIEW / BLOCKED (+취소 창 동안만 CANCELED)
- Job 상태: PENDING → IN_PROGRESS → COMPLETED / FAILED (plain String)

## Redis 키

| 키 | 설명 |
|---|---|
| `queue:job:{jobId}` | 작업별 페이지 태스크 큐 |
| `sched:ring:users` / `sched:user:{userId}:jobs` | 스케줄러 유저 링 / 유저별 작업 링 |
| `sched:job:{jobId}:fg` | FG 리스 (TTL 30s — SSE·status 폴링이 갱신) |
| `job:{jobId}:pages` | 페이지별 상태 Hash + total_pages |
| `job:{jobId}:canceled` | 취소 플래그 (TTL 1h) |

## 컨벤션

- 커밋: `feat:` / `fix:` / `chore:`. 브랜치: `dev` 메인 + `feat/{기능명}` → PR 머지. 릴리스는 dev→main 머지 후 main에 annotated 태그
- 최소 변경 원칙, 기존 주석 유지, 에러는 `ApiResponse.failure(ErrorCode.xxx)`, 엔티티는 `@NoArgsConstructor(PROTECTED)` + `@Builder`

## 주의사항 (지뢰 목록)

- **시간대 KST 3중 고정**: `TimeZone.setDefault` + yaml(jackson·hibernate) + compose TZ — **어느 하나도 제거 금지**. 새 시각 컬럼은 반드시 `timestamptz`
- `spring.jpa.open-in-view=false` — SSE 장기 연결의 커넥션 고갈 방지. 읽기 서비스는 `@Transactional(readOnly=true)`
- gRPC deadline(400s) > AI 하드 타임아웃 구조 유지
- S3 CORS·presigned·공개정책(`*/thumbnail.png`만)은 3종 세트 — 하나라도 건드리면 FE 렌더링·보안에 영향
- `task.md`는 `.gitignore` 등록(커밋 제외)
- UserService·SseService의 result 직렬화 헬퍼 중복 → 추후 공통화 예정
