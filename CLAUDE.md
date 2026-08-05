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
| 보안그룹 | `semojum-ec2-sg`(80/443 공개, 22는 관리자 IP만 — 배포 시 러너 IP 임시 허용) / `semojum-rds-sg`(5432는 EC2 SG+관리자 IP만) |
| 배포용 IAM | `semojum-github-actions` — 보안그룹 인바운드 토글 권한만(최소 권한). 액세스 키는 GitHub Secrets에만 존재 |
| AI 서버 | `semojum-ai`(`i-0b93026dfaaca87c0`), **g5.2xlarge(GPU)**, **같은 VPC** — BE는 **사설 IP `172.31.47.101:50051`** 로 접속. TLS, authority `semo-jum.com` |
| 도메인 | `api.semojum.app`, Cloudflare Flexible SSL |
| Redis | EC2 내 Docker 컨테이너 (compose에 포함) |
| Docker Hub | `zxhwan/semojum-backend:latest` |
| 예산 알람 | 월 $50의 80%·100% 도달 시 `contact@semo-jum.com` 메일 |

**배포 흐름**: `dev` 브랜치 push → GitHub Actions → Docker Hub → EC2 SSH(`ubuntu@43.200.184.56`, `/home/ubuntu/semojum`, `docker compose`)

### CI 배포의 SSH 접근 방식 (중요)
EC2의 22번 포트는 **관리자 IP에만** 열려 있고 GitHub Actions 러너는 IP가 매번 바뀌므로, 워크플로우가 **배포 동안만 러너 IP를 인바운드에 추가하고 회수**한다.
- 순서: `Configure AWS credentials` → `Open SSH for runner IP`(러너 IP/32 추가) → scp·ssh 배포 → `Close SSH for runner IP`(**`if: always()`** 로 성공·실패 무관 회수)
- 전용 IAM 사용자 **`semojum-github-actions`**: 인라인 정책 `sg-ssh-toggle` — `semojum-ec2-sg`에 대한 `AuthorizeSecurityGroupIngress`/`RevokeSecurityGroupIngress` + `DescribeSecurityGroups`만 보유(인스턴스 생성·삭제 등 불가)
- **22번 포트를 0.0.0.0/0으로 열지 말 것.** 이 토글 방식이 상시 개방을 대체한다
- 배포가 SSH 단계에서 실패하면 러너 IP 회수 여부를 먼저 확인(`aws ec2 describe-security-groups`로 22번 규칙에 관리자 IP만 남아야 정상)

**GitHub Secrets 목록**: `VM_HOST`(EC2 EIP) · `VM_USER`(ubuntu) · `VM_SSH_KEY`(semojum-key.pem) · `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`(semojum-github-actions) · `EC2_SECURITY_GROUP_ID` · `DOCKER_HUB_USERNAME`/`DOCKER_HUB_TOKEN`

**관리자 IP가 바뀌면**(네트워크 이동 등) 보안그룹 두 곳을 갱신해야 SSH·DataGrip 접속이 된다 — `semojum-ec2-sg`(22), `semojum-rds-sg`(5432).

### GCP → AWS 이전 상태 (2026-07-29 기준, 브랜치 `feat/aws-migration`)
- **완료**: S3·RDS·EC2 생성 / DB 전체 이관·검증(12테이블) / GCS→S3 객체 460개 이관·일치 확인 / EC2에서 E2E 검증(가입→Job 생성→S3 업로드→워커 다운로드) / GitHub 시크릿(VM_HOST·VM_USER·VM_SSH_KEY) EC2로 교체 완료
- **컷오버 완료(2026-07-31)**: Cloudflare A레코드 전환 → 트래픽이 EC2·RDS·S3로 서비스 중. **AI 서버도 같은 AWS 계정·VPC로 이전 완료**, gRPC 실변환 E2E 검증됨(TLS 통과, 0.9초 내 COMPLETED)
- **남은 정리**: GCP 리소스(VM·Cloud SQL) 삭제 · 테스트 계정/Job 정리 · 무중단 배포 전환(현재 배포마다 약 20초 중단)
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
│   ├── hwp              HwpPageExtractor (HWP 실제 페이지 분리)
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
| COMMON4004 | 404 | 존재하지 않는 경로 (매핑 없음) |
| COMMON4005 | 405 | 지원하지 않는 요청 방식 (경로는 있으나 메서드 불일치) |
| COMMON5000 | 500 | 서버 에러 |
| AUTH4001 | 401 | 아이디/비밀번호 오류 |
| AUTH4002 | 409 | 이미 사용 중인 로그인 ID |
| AUTH4003 | 401 | 액세스 토큰 만료/유효하지 않음 |
| AUTH4004 | 403 | 비활성화된 계정 (status=INACTIVE) |
| USER4001 | 404 | 존재하지 않는 회원 |
| ORG4001 | 404 | 존재하지 않는 기관 |
| JOB4001 | 404 | 존재하지 않는 작업 |
| JOB4002 | 400 | 잘못된 파일 형식 |
| JOB4003 | 400 | 지원하지 않는 모드 |
| JOB4004 | 404 | 존재하지 않는 요소 |
| JOB4005 | 400 | 잘못된 elementType (TEXT/BRAILLE만 허용) |
| JOB4006 | 400 | 순서 목록이 현재 페이지 요소와 불일치 (블록 순서변경) |
| JOB4007 | 400 | HWP 파싱 실패 (손상·미지원 형식) |
| JOB4008 | 400 | 암호 설정/배포용 HWP (변환 불가) |

---

## 구현 완료 목록

### 인증 (Auth) — V3 발급형 체제 (`feat/v3-auth`)
- **자체 가입·소셜 로그인 없음**: 운영자가 기관별 계정(loginId/PW)을 발급, 점역사는 부여받은 계정으로만 로그인 (1인 1계정)
- `POST /api/auth/login` — 발급 loginId/PW 로그인. **중복 로그인 금지**: 로그인 시 기존 활성 세션 전부 revoke(신규가 밀어냄, `revokeAllActiveByUser`)
- `POST /api/auth/logout` — 로그아웃 (리프레시 토큰 revoke)
- `POST /api/auth/refresh` — 액세스 토큰 재발급 (밀려난 세션은 여기서 차단됨)
- 자동 로그인 X: refresh 만료 30일 → **12시간**. 초기 비밀번호는 난수 발급·사용자 변경 불가(운영자 재발급만)
- DB 세션 관리: `user_sessions` 테이블, SHA-256 해시 저장
- **운영자 API** (`/api/admin`, `X-Admin-Key` 헤더 검증):
  - `POST /api/admin/orgs` 기관 생성(계약 만료일·**기관 코드** — 미입력 시 orgNN 자동) / `POST /api/admin/accounts` **계정 일괄 발급**(기관 ID+수량 → 서버가 `{기관코드}{순번}`으로 생성, 난수 PW 응답에 1회만 노출) / `POST /api/admin/accounts/{loginId}/password-reissue` PW 재발급 / `PATCH /api/admin/accounts/{loginId}/status` 계정 상태 변경(`UserStatus` enum ACTIVE·INACTIVE — INACTIVE 시 활성 세션 revoke, 로그인·refresh가 AUTH4004로 차단) / `PATCH /api/admin/accounts/{loginId}/role` 계정 역할 변경(`Role` enum ROLE_ADMIN·ROLE_USER — ROLE_ADMIN=운영·테스트용 분류, JWT 인증 시 Spring Security 권한으로 반영되어 2차 hasRole 전환 토대. verify01=ROLE_ADMIN)

#### X-Admin-Key 사용법
관리자 페이지가 2차로 미뤄져, 그때까지 운영자 API를 보호하는 **임시 수단**이다. JWT로는 막을 수 없어(로그인한 점역사면 누구나 통과) 공유 비밀키를 헤더로 검증한다.

- **키 저장 위치**: EC2 `/home/ubuntu/semojum/.env`의 `ADMIN_API_KEY` (코드·저장소에 없음). 로컬 사본은 `~/Desktop/semojum-admin-key.txt`
- **검증 방식**: `AdminController.validateAdminKey()` — `MessageDigest.isEqual`로 **constant-time 비교**(타이밍 공격 방지)
- **fail-closed**: 키가 비어 있으면(env 미설정) 운영자 API를 **전부 차단**. 실수로 열려 있는 상황을 만들지 않는다
- 실패 시 응답: `COMMON4003 권한이 없습니다`

```bash
KEY=$(cat ~/Desktop/semojum-admin-key.txt)

# 기관 생성 — code는 계정 loginId 프리픽스(소문자 영숫자 2~12자). 미입력 시 orgNN 자동 부여
curl -X POST https://api.semojum.app/api/admin/orgs \
  -H "X-Admin-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"name":"한국점자도서관","code":"kblib","contractExpiresAt":"2027-12-31"}'

# 계정 일괄 발급 — 수량만 주면 서버가 kblib01, kblib02… 순번으로 생성 (1~50개)
# 응답의 password들은 이때 1회만 노출됨 — 서버는 BCrypt 해시만 보관
curl -X POST https://api.semojum.app/api/admin/accounts \
  -H "X-Admin-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"organizationId":"<org uuid>","count":5}'

# 비밀번호 재발급 (계정·작업물 유지, PW만 교체)
curl -X POST https://api.semojum.app/api/admin/accounts/kblib001/password-reissue \
  -H "X-Admin-Key: $KEY"
```

**키 회전**: EC2 `.env`의 `ADMIN_API_KEY` 수정 → `docker compose up -d` 재기동. 유출 의심 시 즉시 회전할 것.
**한계(2차에서 교체)**: 키만 있으면 누구나 실행 가능해 **작업자 추적·감사 로그가 없고**, 권한 세분화도 불가. 관리자 페이지 구축 시 운영자 계정+역할+감사 로그로 대체한다.
⚠️ 키를 Git·노션·채팅에 올리지 말 것. 팀 비밀번호 관리자에 보관.
- 레거시(이메일/소셜) users 행과 그 작업 데이터는 V4 마이그레이션(`V4__users_v3_cleanup.sql`)으로 전부 삭제됨 — users는 V3 발급 계정만 존재

### Job
- `POST /api/jobs` — Job 생성 (multipart, `mode` + **`insertPageNumber`**(선택, 기본 false))
  - **페이지 번호 삽입 여부는 업로드 시 결정**(에디터 토글 폐지, 2026-08-04 확정) → `jobs.insert_page_number`(V8)에 기록. 점자 판면 마지막 줄 쪽번호 표기 기준이라 조판·에디터 렌더링(26줄 전체 vs 본문 25줄)이 이 값을 따름. Create 응답과 페이지 조회(JobDetail) 응답에 포함
  - 모드 a/c: PDF 페이지별 분리 → S3 업로드
  - 모드 b: **HWP는 실제 페이지 단위**(레이아웃 기반, 표 내용 포함) / TXT는 30줄 단위 청크 → S3 업로드
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
- `GET /api/users/jobs` — **전체보기·검색(전역)**. 폴더+파일을 `{folders, files}`로 함께 반환. 조회 범위는 항상 전역이며 `folderId`·`scope` 파라미터는 없다(폴더 범위 조회는 `/api/folders/{folderId}/contents` 담당)
- `GET /api/users/jobs/recent` — **최근 작업(파일만)**. 위치 무관 전역·최신순 고정·필터 없음. 첫 화면의 "최근 작업" 스트립(`size=5`)과 전체보기(S9)가 쓴다. 두 화면 모두 폴더를 그리지 않아 폴더를 담지 않는다
- **탐색 vs 검색 분리**: 폴더를 타고 들어가는 화면(S1·S2)은 `/api/folders/.../contents`, 위치 무관 전역 나열(S9·검색)은 `/api/users/jobs`. **세 경로 모두 응답 구조가 `{folders, files:{items, nextCursor, hasMore}}`로 동일**해 FE가 같은 방식으로 그린다. 커서를 files 안에 두는 것은 의도적 — 커서는 파일에만 해당하고(폴더는 200개 상한이라 항상 전부 반환) 소속이 구조로 드러나야 한다
- **폴더 화면 API 두 종류**: `GET /api/folders/{folderId}/contents` (**폴더+파일 한 번에** — 폴더 내부 화면 S2) / `GET /api/folders/contents` (최상위 폴더+루트 파일 — 마이페이지 첫 화면 S1). 파일 쪽 필터·정렬·커서는 목록 조회와 동일하고, **조회 범위는 경로가 정한다**(쿼리의 folderId·scope는 무시) / `GET /api/folders/tree` (전체 중첩 트리 — 이동 모달처럼 구조 전체가 필요한 화면)
- **폴더 정렬 기준은 `folders.last_modified_at`**(V12) — 생성일이 아니다. **윈도우 탐색기 규칙**을 따라 직속 항목의 **추가·삭제·이름변경**에만 갱신한다. **파일 내용 편집·즐겨찾기 토글은 갱신하지 않고**(NTFS도 파일 내용만 바뀌면 상위 폴더를 건드리지 않음), **상위 폴더로 전파하지도 않는다**. 갱신은 `FolderTouch` 한곳에 모여 있으니 폴더 안 항목을 바꾸는 코드를 추가하면 여기도 호출할 것
  - ⚠️ V12 SQL 파일 주석에는 "내용 편집도 포함"이라고 적혀 있으나 **현재 동작과 다르다**. 이미 적용된 마이그레이션이라 checksum 때문에 수정할 수 없어 남겨둔 것 — 기준은 이 문서와 `Folder.touchModified()` 주석이다
- **커서(2페이지 이후) 요청에는 `folders`가 빈 배열**로 나간다 — 폴더는 페이지네이션이 없어 매번 전체가 실려 오므로, FE가 그대로 누적하면 폴더가 중복 표시된다. 세 경로 모두 동일
- **검색은 현재 위치 "아래 전체"를 훑는다**(깊이 무관, 탐색기와 동일). 루트에서는 전역, 폴더 안에서는 그 폴더의 **서브트리 전체**(손자 이하 포함, 자기 자신은 제외). 검색이 아닐 때는 한 층만. 파일 범위는 `JobSearchCondition.withFolderScope(서브트리 id들)`로 넘어간다
- `contents`의 **필터 규칙**(윈도우 탐색기 원칙): 상태·모드 필터가 걸리면 **폴더는 결과에서 빠진다**(폴더에 없는 속성) / 즐겨찾기·정렬은 폴더+파일 모두 적용 / 검색어는 파일명과 **폴더명 양쪽**에 적용
- **목록 카드(`JobCard`) 필드**: `jobId·mode·status·originalFileName·thumbnailUrl·displayDate·totalPages·lastEditedPage·isFavorite·folderId·folderPath`. 진행률은 담지 않는다(카드는 "변환 중"만 표시, 실시간은 SSE 담당). `lastModifiedAt` 원본 시각도 담지 않는다(화면은 `displayDate`, 다음 페이지는 불투명 `nextCursor`). **`folderId`/`folderPath`는 제거 금지** — 전체보기(S9)·검색 결과의 위치 표시와 "폴더로 이동"에 쓰인다
- **`jobs.last_edited_page`** = 마지막으로 편집한 페이지 번호(재시작 복구용 — FE는 가장 최근 수정 작업의 이 페이지로 이동). `markContentEdited(pageNo)`가 `last_modified_at`·`is_edited`와 함께 기록한다
- **`jobs.last_modified_at` = "파일 내용이 마지막으로 바뀐 시각"** (카드 날짜·목록 정렬·커서 키·재시작 복구 기준). 점역사의 페이지 편집에서만 `Job.markContentEdited()`로 갱신하고 `is_edited`도 함께 세운다. 이름 변경·폴더 이동·휴지통 복원·즐겨찾기 토글은 **내용이 안 바뀌므로 갱신하지 않는다**(윈도우 탐색기의 '수정한 날짜'와 동일). 변환 진행 상황은 `updated_at`(StaleJobScheduler 전용)이 따로 담당 — 두 컬럼을 섞지 말 것
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

### HWP 페이지 분리 (HwpPageExtractor)
- **실제 페이지 경계 복원**: 한글이 저장 시 계산해 둔 레이아웃 캐시(LineSeg)의 `lineVerticalPosition`(줄 세로 위치)을 사용. 같은 페이지에선 y가 증가하고 페이지가 바뀌면 상단으로 리셋되므로 **y가 작아지는 지점 = 페이지 경계**
- **다단(멀티 칼럼) 문서 보정**: 열이 바뀔 때도 y가 리셋되므로, 단 설정(`ControlColumnDefine`의 단 개수 N)을 문서 흐름 따라 추적해 **N번째 리셋만 페이지 경계**로 판정(나머지는 열 이동). 단 정의 문단의 첫 줄 리셋은 항상 페이지 경계(새 페이지에서 단 영역 시작). 검증: 3단 요약본 33→11p, 2단 요약본 26→13p, 1단 문서들은 불변. 한계: 열 하나가 완전히 비는 비정형 문서는 어긋날 수 있음
- 수동 검증 도구: `HwpPageExtractorDebugTest` — `HWP_DEBUG_FILE=<경로> ./gradlew test --tests HwpPageExtractorDebugTest --rerun`으로 페이지별 분리 결과 덤프(CI에선 자동 스킵)
- ⚠️ `LineSegItemTag.isFirstLineAtPage()`는 **실제 파일에서 항상 false**(tag 하위 비트를 한글이 쓰지 않음) → 사용 금지, y 리셋 방식 유지할 것
- **표·중첩 표 셀 문단까지 재귀 추출** — 최상위 문단만 읽으면 서식 문서 내용의 40~96%가 누락됨(검증: 논문심사의견서 21자→576자, 한이음 수행계획서 3843자→6497자)
- **표 구조 보존**: 표는 `[표 시작]`/`[표 끝]` 마커로 감싸고 **행=줄, 칸=탭**으로 기록(셀 내 여러 문단은 공백으로 결합, 빈 행은 생략). 중첩 표는 바깥 표 블록 뒤에 별도 블록으로. 마커가 없으면 점역사·AI가 표인지 줄글인지 구분 불가
- **각주·미주·머리말·꼬리말 추출**: 이전에는 표만 처리해 전부 누락됐음(KSCI 논문양식의 저자 연락처·투고일·Copyright 5줄 소실 확인). 페이지 기록 순서는 원문 판면 그대로 — **`[머리말]` → 본문 → `[각주]` → `[꼬리말]`**, 미주(`ControlEndnote`)는 본문이 있는 마지막 페이지에 `[미주]`로 모음
  - 머리말·꼬리말은 HWP 의미대로 **정의된 페이지부터 이후 모든 페이지에 반복** 적용(빈 정의는 그 지점부터 해제, 그래서 빈 머리말만 있는 문서엔 마커가 안 붙음). 쪽번호는 위치 지정만 저장되고 숫자는 한글이 인쇄 시 생성하므로 추출 불가 — 점자 판면 규칙으로 별도 처리
  - 본문이 없는 페이지는 머리말만으로 페이지가 생기지 않도록 제외
- 암호 설정/배포용/공인인증 암호화 문서는 `JOB4008`로 거부 (본문 대신 안내문만 들어 있어 점역 불가)
- **hwplib 1.1.9 필수**: 1.1.1은 일부 실제 파일에서 파싱이 무한 대기(hang)함 — 워커 1개 구조라 스레드가 영구 정지될 수 있어 다운그레이드 금지
- 문단이 페이지를 걸치면 줄 단위로 분리해 각 페이지에 나눠 기록. 표는 앵커 문단이 속한 페이지에 귀속(페이지를 걸친 표는 시작 페이지로 — 한계)

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

### gRPC (AI 서버 연동)
- **사설 IP `172.31.47.101:50051`** TLS 연결, authority `semo-jum.com`. 주소는 `GRPC_AI_ADDRESS` 환경변수로 재배포 없이 교체 가능
- ⚠️ **반드시 사설 IP를 쓸 것** — AI 보안그룹(`semojum-ai-sg`)은 *BE 보안그룹(`semojum-ec2-sg`)에서 오는 트래픽*만 50051을 허용한다. 공인 IP(`3.37.198.245`)로 접속하면 트래픽이 VPC를 벗어났다 돌아오면서 이 규칙에 매칭되지 않아 **차단된다**(실측 확인). 사설 IP는 전송비 0·지연 감소 이점도 있음

#### TLS 인증서 운영
- AI 서버의 **자체 서명 인증서**를 신뢰 앵커로 사용. EC2의 `~/semojum/server.crt`를 compose가 컨테이너로 볼륨 마운트(`GRPC_CERT_PATH=file:/home/joha-eun/server.crt`)
- 현재 인증서: 2026-07-30 발급, **2036-07-27 만료**, SAN `DNS:semo-jum.com` (구 GCP 인증서는 `server.crt.gcp-backup-20260731`로 백업)
- SAN에 IP가 없지만 `authority-override: semo-jum.com` 덕분에 사설 IP로 접속해도 검증됨 → **이 설정을 제거하면 연결이 깨진다**

**인증서 교체 절차** (AI팀에서 새 인증서를 받았을 때)
```bash
# 1. 배치 — 볼륨 마운트라 이미지 재빌드·재배포 불필요
scp <새 인증서> semojum-aws:~/semojum/server.crt

# 2. 적용 (앱이 기동 시 읽으므로 재시작 필요, 약 20초 중단)
ssh semojum-aws 'cd ~/semojum && docker compose restart backend'

# 3. 검증 — 세 지문이 모두 같아야 정상
openssl x509 -in <새 인증서> -noout -fingerprint -sha256
ssh semojum-aws 'openssl x509 -in ~/semojum/server.crt -noout -fingerprint -sha256'
ssh semojum-aws 'docker exec semojum-backend-1 openssl x509 -in /home/joha-eun/server.crt -noout -fingerprint -sha256'
```
- 교체 전 기존 파일을 `server.crt.backup-<날짜>`로 남길 것
- 연결 확인: `ssh semojum-aws 'openssl s_client -connect 172.31.47.101:50051 -alpn h2 -CAfile ~/semojum/server.crt -servername semo-jum.com </dev/null 2>&1 | grep "Verify return code"'` → `0 (ok)` 이어야 함
- proto: `BrailleRequest` / `BrailleResponse`
- BE gRPC 타임아웃: 200s (AI 서버 하드 타임아웃 180s보다 높게)

---

## DB 스키마 요약

### 주요 테이블
| 테이블 | 설명 |
|---|---|
| users | 회원 — V3 발급형(login_id NOT NULL, organization_id, password)만. 이메일·이름·소셜 컬럼과 레거시 행은 V4 마이그레이션으로 제거됨 |
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
- **시간대는 한국 표준시(KST) 고정**: ① `BackendApplication`이 기동 시 `TimeZone.setDefault(Asia/Seoul)` ② `application.yaml`의 `spring.jackson.time-zone`(응답 직렬화)·`hibernate.jdbc.time_zone`(DB 세션) ③ docker-compose의 `TZ`/`JAVA_TOOL_OPTIONS` — 3중으로 고정돼 있으니 **어느 하나도 제거하지 말 것**. 컨테이너가 UTC로 돌던 구간에 작업 시각이 9시간 이르게 저장돼 마이페이지 카드 날짜("1시간 전"/"어제")가 어긋난 적이 있다. V11 이후 모든 시각 컬럼은 `timestamptz`(절대시각)이므로 새 컬럼도 반드시 `timestamptz`로 만들 것
- V3 인증 개편 배포 전 `ddl/v3_auth.sql` 수동 실행 필요 (organizations 생성 + users에 login_id/organization_id 추가)
- `edit_logs` 테이블은 `ddl-auto:none`이라 자동 생성 안 됨 → 수정 API 배포 전에 DataGrip에서 `CREATE TABLE edit_logs (...)` 먼저 실행
- UserService와 SseService 간 result 직렬화 헬퍼 코드 중복 존재 → 추후 공통 컴포넌트로 리팩토링 예정
