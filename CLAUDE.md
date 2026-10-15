# 세모점(Semojum) BE 개발 가이드

점역사(전문 점자 번역사)용 AI 점자 변환 플랫폼의 백엔드.

| 모드 | 변환 | 입력 |
|---|---|---|
| a | 이미지 → 텍스트 | (신규 생성 없음 — 2026-09-30 이전 작업만) |
| b | 텍스트 → 점자 (**AI 안 거침**, V3.1) | TXT · 새 문서 |
| c | 이미지 → 텍스트 + 점자 (3패널) | PDF, HWP, HWPX, 이미지(JPG·JPEG·PNG) |

- **모드는 사용자가 고르지 않는다(3패널 통합, 2026-09-30)** — 파일 종류가 정한다(`JobService.modeForExtension`). 구 클라이언트의 `mode`는 받기만 하고 무시(a를 보내도 c)
- c는 원본 | 텍스트 | 점자 3패널이다. **proto V3.1(2026-10-14)부터 AI는 교열한 묵자(`text_list`)만 주고 점자를 안 준다**(`braille_text_list` reserved) — 점역은 앱이 엔진으로 한다. 새 작업의 `braille_elements`는 비어 있고, 기존 작업의 점자는 그대로다
- ⚠️ **AI의 mode는 `a`(PDF·이미지) | `b`(글) 둘뿐이다.** BE는 c 작업을 **보낼 때만** `a`로 옮긴다(`PageWorker.aiModeOf`). DB의 `jobs.mode = 'c'`는 바꾸지 않는다 — 기존 작업 전부와 FE 분기가 흔들린다

> **API 상세(요청·응답·에러)는 노션 `Development / Dev. DB / [V3.1] API 명세서`가 정본**이다.
> 이 문서에는 코드만 봐서는 알 수 없는 것 — 설계 제약, 결정의 근거, 지뢰 — 만 적는다.

## 기술 스택

Java 21 · Spring Boot 3.5 · Gradle-Groovy · `com.semojum.backend`
PostgreSQL 18(RDS) + Flyway · Redis · AWS S3 · gRPC+TLS(AI 서버) · SSE
Spring Security + JWT(jjwt) · BCrypt · Docker Compose + Envoy · GitHub Actions

## 인프라 (AWS 서울, 계정 804136008552)

| 구성 | 상세 |
|---|---|
| EC2 | `semojum-backend` t3.medium, 고정 IP `43.200.184.56` |
| RDS | `semojum-postgres` (PG18). **로컬·개발·운영이 이 DB 하나를 공유** — 마이그레이션은 어느 환경에서 먼저 적용돼도 안전해야 한다. DB 이름은 `postgres`. **공개 접근 꺼짐**(2026-10-15) — VPC 안에서만 닿는다 |
| S3 | `semojum-bucket` (EC2 IAM Role 키리스) |
| AI 서버 | `semojum-ai`(g5.2xlarge) — **같은 VPC라 반드시 사설 IP `172.31.47.101:50051`**. 공인 IP로 가면 AI 보안그룹의 "BE SG 허용" 규칙에 안 걸려 차단된다 |
| 도메인 | `api.semojum.app` — **Cloudflare DNS only + EC2 직접 TLS**. 프록시 경유 시 무료 플랜이 미국 엣지로 라우팅해 요청당 +0.5~0.7s(실측) → **프록시(주황 구름) 재활성화 금지**. 인증서는 certbot 자동 갱신, **인증서 파일은 uid 101 소유 필수**(envoy가 envoy 유저로 강등 실행) |

- 보안그룹: EC2 80/443 공개 · **22는 관리자 IP만**. **22번을 0.0.0.0/0으로 열지 말 것** — CI가 배포 동안만 러너 IP를 추가·회수한다(`if: always()`)
- 관리자 IP가 바뀌면 `semojum-ec2-sg`(22) 한 곳만 갱신. `semojum-rds-sg`(5432)는 **BE 서버 보안그룹만** 허용한다(2026-10-15부터 IP 규칙 없음)
- ⚠️ **로컬·DB 툴은 BE 서버를 거치는 SSH 터널로 붙는다** — RDS에 공인 IP가 없어 직접 접속이 안 된다
  `ssh -f -N -L 5432:semojum-postgres.c3mk86a8cm0o.ap-northeast-2.rds.amazonaws.com:5432 semojum-aws` → 로컬 실행은 `DB_HOST=localhost`
  (datasource url이 `${DB_HOST}:5432` 고정이라 로컬 포트도 5432여야 한다. 로컬 PostgreSQL이 5432를 쓰면 끄고 열 것). DB 툴만 쓸 땐 아무 포트나 된다
  22번 허용 목록에 내 IP가 있어야 한다
- 서브넷은 아직 기본 VPC의 public subnet 4개 그대로다 — private subnet 이동은 후속(공개 접근은 이미 꺼서 인터넷에서는 닿지 않는다)
- 로컬 시크릿: `~/semojum/semojum-aws-secrets.txt`, SSH 키 `~/.ssh/semojum-key.pem`

## 배포 — 블루그린 무중단

**`dev` push = 운영 배포다.** GitHub Actions(테스트 게이트) → Docker Hub → EC2 `scripts/deploy.sh`.

- 활성 색 감지 → 비활성 색으로 기동 → `/api/health` 게이트(120s, **실패 시 구버전 유지·exit 1**) → Envoy 헬스체크 편입 → 구 색 graceful 정지
- 롤백 = 방금 내린 색 재기동 한 줄
- ⚠️ 색 서비스는 compose **profiles** — `docker compose up -d`로는 backend가 안 뜬다. 수동 조작 시 `--profile blue|green` 필수
- ⚠️ **변환 중 배포하면 AI가 처리 중이던 쪽이 유실된다.** 워커 종료는 5초만 기다리는데(`PageWorker.stopWorkers`) 한 쪽 처리는 수십~180초다. 인터럽트되면 `if (running)`이 false라 **재시도 큐에 다시 넣지 않고 버린다.** 큐에서 대기 중이던 쪽은 Redis에 남아 이어진다
- ⚠️ **`envoy.yaml`은 복사만 되고 적용되지 않는다** — `deploy.sh`가 평상시 Envoy를 재시작하지 않는다. 적용은 EC2에서 `docker compose up -d --force-recreate envoy`(1~2초 단절)
- Envoy: 액티브 헬스체크(`/api/health`) + 재시도는 connect-failure/refused-stream만(POST 중복 방지). **SSE 라우트 `timeout: 0s` 변경 금지**, `idle_timeout` 600s
- 안전핀: `-Xmx768m`, EC2 스왑 1GB, graceful shutdown 20s
- 로그 뷰어 `scripts/semlog.sh`. **배포로 컨테이너가 갈리면 로그가 사라지므로** deploy.sh가 `~/semojum/logs/`에 보관(최근 30개)

## 패키지 구조

```
com.semojum.backend
├── domain
│   ├── auth      로그인/로그아웃/refresh, User·UserSession
│   ├── admin     운영자 API (ROLE_ADMIN 전용)
│   ├── org       Organization (기관, 계약)
│   ├── user      계정 기본 점역 옵션 · 점역 양식 · 쪽 조회 · 사용량(T3)
│   ├── job       Job 생성·설정·취소·SSE
│   │   ├── scheduler   JobDispatcher(공정 큐), StaleJobScheduler
│   │   └── worker      PageWorker
│   ├── board     QnA 게시판
│   ├── result    AI 결과 저장(ResultService), 쪽 내용 저장(PageContentSaver)
│   └── rule      점자 규정 검색 (인메모리)
├── global        config · jwt · exception · grpc · s3 · hwp · thumbnail · logging
└── grpc          proto 생성 클래스
```

## 공통 응답

```json
{ "isSuccess": true, "code": "COMMON2000", "message": "성공입니다.", "result": {} }
```

에러 코드는 `global/exception/ErrorCode.java`가 정본이다. 자주 쓰는 것:
`COMMON4000`(잘못된 요청) · `COMMON4003`(권한 없음 — 타인 Job 포함) · `AUTH4005`(로그인 채널 위반) ·
`JOB4001`(없는 작업) · `JOB4010`(변환 중 조작 불가) · `JOB4013`(HWP→PDF 실패)

---

# 도메인별 핵심 규칙

## 인증

- 자체 가입 없음 — 운영자가 기관별 계정 발급, 1인 1계정. 로그인 시 기존 세션 전부 revoke(중복 로그인 금지)
- **로그인 5회 실패 → 10분 잠금**(V3.1, 기획 9-4) — `LoginAttemptService`, Redis `auth:login-fail:{loginId}`(마지막 실패 후 10분 TTL) · `auth:login-lock:{loginId}`. 틀리면 AUTH4001 + `result.remainingAttempts`, 5번째와 잠긴 동안은 AUTH4006 + `result.unlockAt`. **잠긴 동안은 비밀번호가 맞아도 거절**, 맞으면 센 것을 지운다
  - ⚠️ **없는 ID도 똑같이 센다** — 응답이 달라지면 ID 존재 여부가 드러난다. 그래서 운영 E2E는 없는 ID로 할 것(실계정으로 하면 그 계정이 10분 잠긴다)
- **역할 3단**: ROLE_ADMIN(운영자) / ROLE_ORG_ADMIN(기관 관리자, `/api/org/**`) / ROLE_USER(점역사)
- **ROLE_ADMIN 웹/앱 분리** (`users.admin_scope` = WEB·APP·null): 로그인 시 브라우저가 붙이는 **Origin이 콘솔 주소면 콘솔 로그인**으로 보고 WEB 계정만 허용, 아니면 AUTH4005. WEB 계정은 콘솔 밖에서 로그인 불가
  - ⚠️ **curl로 `/api/admin/**`를 부를 때는 `Origin: https://admin.semo-jum.com` 헤더 필수** — 안 붙이면 AUTH4005
  - `admin.console-origins`(env `ADMIN_CONSOLE_ORIGINS`) — compose가 빈 값을 넘기면 yaml 기본값을 덮으므로 **compose에도 기본값이 있어야 한다**
- ⚠️ **CORS는 전면 허용(`allowedOriginPatterns *`) 유지** — Bearer라 origin 제한이 무의미하고, **좁히면 데스크톱 앱(Origin: null)이 403으로 깨진다**(2026-08-20 회귀 실측)
- ⚠️ adminPage(콘솔 FE)·`~/semojum/FE`는 **FE 영역, BE가 수정 금지**
- ⚠️ 운영자 "마이페이지로 보내기" 사본은 **`jobs.admin_copy=true` + 원가 0**이다. **기능은 2026-10-14에 없앴지만 그 전 사본이 DB에 남아 있다** — 통계·수익성·layout-cost·사용량 등 집계 쿼리에서 계속 제외할 것(빼먹으면 숫자가 조용히 틀린다)

## Job 생성 (`POST /api/jobs`)

- multipart. **조판 옵션 12개**(칸 수·줄 수·페이지행·꼬리말 정렬 등)를 함께 받아 **`jobs.layout_options`(jsonb) 한 칸**에 저장한다 — 기획 확정 전이라 컬럼을 쪼개지 않았다. 항목·기본값은 노션 명세 참조
- **설정은 업로드 후에도 바꿀 수 있다** — 별도 API 없이 최종 저장(`POST /api/jobs/{jobId}/save`) 본문에 실려 온다(보낸 항목만 덮어쓰기). **변환 중이면 JOB4010**(`advancedAi`가 gRPC 요청에 실려서 — 중간에 바뀌면 쪽마다 다른 설정으로 처리된다). ⚠️ 설정만 바뀌면 `last_modified_at`을 갱신하지 않는다(설정은 내용이 아니다)
- 페이지 분리: a/c는 PDF 쪽별 / **이미지는 분리 없음 — 한 장이 한 쪽, 쪽 번호는 받은 순서** / **b(텍스트)는 서버가 나누지 않는다**
- **텍스트 작업(TXT · 새 문서)은 AI를 거치지 않는다(기획 확정 2026-10-14).** AI가 점자를 주지 않게 됐고 텍스트에서 해 줄 일이 없다 — 점역·쪽 나누기·조판은 앱이 원문으로 한다. 생성은 **작업 기록만** 남기고 즉시 COMPLETED(S3·큐·쪽 없음, 크레딧 0). 그래도 만드는 건 최종 저장이 jobId를 요구해서다
  - ⚠️ **쪽은 첫 저장이 앱이 보낸 쪽 번호대로 만든다**(`PageContentSaver.createTextPage`). 쪽 결과 상태는 **`SAVED`** — 운영 통계가 `COMPLETED`·`NEEDS_REVIEW`를 변환 쪽수로 세므로, AI가 돌지 않은 쪽이 섞이면 변환량·원가가 틀어진다. **새 집계 쿼리를 짤 때도 이 구분을 지킬 것**
  - 쪽 수 = 저장된 마지막 쪽 번호(앱이 쪽을 줄이면 준다). `pdf_path`는 빈 문자열이고 쪽 조회는 원본을 내려주지 않는다
  - PDF 작업은 쪽을 만들지 않는다 — 없는 쪽 번호는 JOB4001(앱 버그를 드러낸다)
  - V3.0 이전에 AI로 만든 텍스트 작업은 S3 원문·점자가 그대로 남아 있다
- **이미지는 여러 장을 한 번에 받는다(V3.1)** — `file` 파트를 반복해 보낸다(파트 이름은 그대로라 한 장만 보내는 기존 호출 불변). 이미지일 때만 복수이고, PDF 여러 개나 이미지+문서 혼합은 COMMON4000
  - ⚠️ **서버는 파일 이름으로 다시 정렬하지 않는다** — 쪽 번호는 받은 순서다. 이름순은 FE가 기본값으로 정렬해 보내고, 「순서 바꾸기」로 고친 결과도 그 순서로 온다. 서버가 정렬하면 사용자가 고친 순서가 뒤집힌다
  - ⚠️ **PDF로 변환하지 않는다** — 올라온 바이트를 그대로 S3에 두고 워커가 proto `image_data`·`image_mime_type`으로 보낸다(변환은 AI 쪽). 종류는 **경로 확장자**로 가른다 — 태스크에 표시를 넣으면 배포 순간 큐(Redis)에 남아 있던 구 형식 태스크엔 그 값이 없다
  - 미리보기를 렌더하지 않는다 — 원본이 곧 미리보기라 `pages.image_path`를 업로드 시점에 채운다
  - ⚠️ 여러 장의 **합계**가 `max-request-size`(105MB)를 넘으면 요청 자체가 거절된다. 장당 100MB 상한은 그대로
- **HWP는 업로드 시 PDF로 변환**(pyhwp→ODT→LibreOffice, Dockerfile 내장) 후 기존 PDF 파이프라인. 머리말·꼬리말은 변환기가 유실하므로 hwplib로 읽어 마커로 주입한다
  - **HWPX(.hwpx, 또는 내용물이 ZIP인 .hwp)** 는 LibreOffice가 못 연다(26.2 실측) → `HwpxToHtml`이 본문 XML을 HTML로 풀고 LibreOffice(Writer 필터)로 PDF. 확장자가 아니라 **내용물(ZIP 시그니처)로 가른다**. 표 테두리는 CSS가 아닌 `border` 속성이어야 LibreOffice가 그린다
- ⚠️ 큐 적재(`JobDispatcher.enqueueJob`)는 **트랜잭션 커밋 후** 실행 — 커밋 전에 적재하면 워커가 not found로 재시도한다
- ⚠️ **PDF 첫 장 렌더는 poppler `pdftoppm`(별도 프로세스) 우선, 실패 시 PDFBox 폴백** — PDFBox는 JPEG 2000 스캔본을 백지로 그리고, 순수 Java JPX 디코더는 768MB 힙에서도 OOM이다. **JVM 안에서 풀지 말 것**

## 스케줄링 · 워커

- 작업별 큐(`queue:job:{jobId}`) + **2계층 라운드로빈**(유저 링 → 작업 링 → 쪽 1장 pop). 우선순위 FG:BG = 4:1, FG 판정은 리스 키(TTL 30s)를 SSE·status 폴링이 갱신
- 링·큐는 전부 Redis → BE 재시작에도 복구. 선택 연산은 `synchronized poll()` (**BE 단일 인스턴스 전제**)
- ⚠️ **워커 수 = AI 서버 총 슬롯 수**(`grpc.ai.servers` 슬롯 합). 워커 하나가 슬롯 하나를 점유해 블로킹 gRPC
- 오류 시 큐 **머리** 재삽입(순서 유지) + 2초 대기, 최대 3회 → `markPageBlocked`(DB BLOCKED 후 **Redis put은 항상 실행** — SSE 종료 감지 보장)

## ResultService / Job 상태

- 종료 판정: 전 쪽 terminal 시 성공 0건이면 FAILED, 1건 이상 COMPLETED(**부분 성공 = 완료**)
- ⚠️ `touchJob`/`finishJob`의 `WHERE status IN ('PENDING','IN_PROGRESS')` 가드 — **종료된 Job을 페이지 이벤트가 못 되살린다. 제거 금지**
- StaleJobScheduler(5분): IN_PROGRESS 무진행 1h / 고아 PENDING 12h → FAILED
- rule_trail의 proto 필드가 `title`→`rule_name`, `excerpt`→`contents`로 개명됐지만 **번호가 같아 값은 동일** — DB·API 필드명은 그대로 두고 `ResultService`에서만 매핑한다(나가는 응답을 흔들지 않기 위해)

## SSE (`GET /api/jobs/{jobId}/events`)

- `queue_position` → `page_done` → `job_done`
- ⚠️ **page_done은 반드시 쪽 순서(1,2,3…)대로** 방출 — 뒤 쪽이 먼저 끝나도 보류(커서 방식, 재연결 시 완료분 순서 재전송)
- ⚠️ **이미지가 없으면 `original` 키 자체를 안 보낸다**(b·렌더 실패·비활성). PDF url을 주면 FE가 이미 쥔 로컬 파일 대신 S3에서 받는 더 느린 길로 간다
- **하트비트 30초** — 보낼 게 없는 구간(마지막 쪽들이 전부 AI에 들어가 있을 때, 최대 400초)에는 전송이 없어 죽은 연결을 알 수 없었다. 주석 줄(`: ping`)을 내보내 드러나게 한다. 모든 전송 지점에서 타이머를 초기화한다
- **끝난 작업이면 즉시 종료** — 상태 Hash는 종료 시 TTL 1h로 사라진다. 그 뒤 붙은 연결은 "아직 기록 전"과 구분이 안 돼 3시간 매달렸다(하트비트로는 못 잡는다 — 전송이 정상 성공하므로). Hash가 비면 DB로 상태를 확인한다
- 폴링 대체: `GET /api/jobs/{jobId}/status`

## 조판 · 다운로드 — 서버에 없다 (FE 이관, 2026-10-13)

- **`.brf`/`.txt` 생성과 다운로드는 앱이 로컬에서 한다.** `POST /api/jobs/{jobId}/download`는 삭제됐다(01b1db3). 조판 옵션(`layout_options`)은 서버가 **보관만** 하고 해석하지 않는다 — 허용값 검증(`JobService.validateLayoutOptions`)이 전부다
- ⚠️ **`com.semojum.brailleassist`는 BE에서 호출하지 않는다.** 원 레포(Semojum/braille-assist) 복사본으로 남아 있을 뿐이고(수정 금지) 조판 옵션 허용값을 이 라이브러리에 맞출 필요가 없다. 예: 꼬리말 정렬 `left`는 라이브러리엔 없지만 BE는 받는다
- 동기화할 일이 생기면 본체·테스트·벡터 3종을 함께 갈아끼운다(`BrailleAssist.java` + `VectorsTest.java` + `vectors.json`). `VectorsTest`의 벡터 로딩부는 BE 로컬 적응(클래스패스 리소스)이라 원 레포로 덮으면 안 된다
- **꼬리말은 서버가 점역하지 않는다(proto V3.1, TranslateText 삭제)** — 새 작업의 `jobs.footer_braille`은 null이고 앱이 `footerText`로 점역한다. V3.0 이전 작업의 점역 값은 그대로 돌려준다. 꼬리말이 판면에 들어가는지 보는 길이 검증은 점역 값이 있는 예전 작업에서만 돈다. 저장 때 묵자가 바뀌면 예전 점자를 비운다(어긋난 꼬리말 방지)
- ⚠️ **꼬리말 길이 검증** — 점역 결과가 페이지행에 안 들어가면 COMMON4000. 라이브러리가 긴 꼬리말을 **말없이 뒤에서 자르기** 때문이다

## 취소 (`POST /api/jobs/{jobId}/cancel`)

즉시가 아닌 **수렴**: 플래그 → 큐 배수 → 인플라이트 마무리 → 확정. 완료 0건이면 전부 BLOCKED+FAILED. 이미 끝난 작업 취소는 멱등.

## 쪽 조회 (`GET /api/users/jobs/{jobId}/pages/{pageNo}`)

- V3.1 앱에는 작업 목록 · 폴더 · 휴지통이 없다(작업물은 로컬 `.smj`). 서버에 남은 조회는 쪽 하나와 재시작 복구용 진행 중 작업(`/api/users/jobs/active`)뿐이다
- ⚠️ **`jobs.last_modified_at`(내용이 바뀐 시각)과 `updated_at`(변환 진행, StaleJobScheduler 전용)을 섞지 말 것.** 전자는 쪽 내용 저장에서만 갱신된다
- `jobs.deleted_at`이 있는 작업은 V3.0 때 휴지통에 넣은 것 — 휴지통은 없앴지만 계속 지운 것으로 본다
- **원본 미리보기는 서버가 미리 렌더한 JPEG**다(`PageWorker`가 AI 요청 직전에 생성). `original`은 `{type, url}` 하나로 통일 — `image`(정상) / `pdf`(렌더 실패 폴백) / `text`(b). **FE는 type으로 분기한다.** presigned URL은 15분이라 **장기 캐시 금지**
  - 근거(실측): pdf.js로 스캔본을 그리면 1,807~2,853ms(JPEG 2000은 브라우저 네이티브 디코더가 없다) vs `<img>` 6~9ms. 스위치 `page-image.enabled`

## 저장 (`POST /api/jobs/{jobId}/save`)

- **V3.1의 유일한 저장 경로.** 앱이 「저장」을 누를 때 한 번 — 자동 저장 없음. 본문은 작업 최종 상태 전체(설정 · 세 패널 · 줄 메모)이고 블록 추가 · 삭제 · 순서는 서버가 판정한다
- **점역은 앱이 해서 보낸다** — 저장 경로에서 AI를 부르지 않는다. 텍스트 · 점자 패널을 각각 받고 `current`만 갱신, `original`은 보존
- **쪽 단위 트랜잭션**(`JobSaveService` → `PageContentSaver`, `REQUIRES_NEW`) — 한 쪽이 실패해도 나머지는 저장하고 실패한 쪽 번호만 돌려준다. 둘을 나눈 건 같은 클래스 호출은 프록시를 안 타 트랜잭션이 안 걸려서다
- 설정(조판 · 점역 옵션 · 꼬리말)은 보낸 항목만 덮어쓴다. 변환 중이면 저장 자체가 JOB4010
- 대체 초안은 `selectedIdx`로 무엇을 골랐는지만 기록한다(본문은 앱이 이미 그 초안으로 채워 보낸다)
- 줄 메모(`page_memos`, V34)는 쪽 단위 전체 교체
- **수정 이력은 남기지 않는다**(2026-10-12 기획 결정) — 쓰던 `page_edit_histories` · `page_edit_logs`는 코드(#181)와 테이블(V41) 모두 지웠다
- 텍스트 작업(mode b)은 저장이 쪽을 만든다 — 「Job 생성」 참조

## 점자 규정 검색 (`GET /api/rules`)

- 데이터는 **DB가 아니라 클래스패스 리소스** `resources/rules/braille-rules.json`(239건). **AI 팀 rule registry와 같은 파일**이라 어긋나면 에디터 규정 배지가 깨진다. 코드와 함께 배포해 커밋 단위로 동기화를 보장한다 — **개정 = JSON 교체 + 재배포**
  - DB에 안 넣은 이유: 세 환경이 RDS 하나를 공유해 배포를 롤백해도 규정만 새 버전으로 남고, `rule_trails`가 이미 스냅샷을 갖고 있어 조인할 대상이 없다. 운영 중 편집이나 규정에 붙는 사용자 데이터가 생기면 그때 DB로 — 바꿀 곳은 `BrailleRuleRegistry.load()` 하나
- 여러 단어는 **AND**(OR로 하면 "한글 점자 약자"가 208/239건이 된다). 점수는 결과를 거르지 않고 순서만 정한다
- ⚠️ `displayOrder`에 **파일 키 순서를 쓰면 안 된다** — 문자열 정렬이라 `1.4.10`이 `1.4.8` 앞에 온다. 기관 → 편 → 조문 번호 **수치** 비교로 재정렬한다
- ⚠️ `contents`는 원문 전문이 아니라 **첫 문장 발췌**라 본문 뒷부분 단어는 안 걸린다. 전문 필드가 오면 `searchBlob`에 합치면 끝(API 스펙 불변)

## 기관 관리 T2 · 사용량 T3

- **T2(`/api/org/**`)는 ROLE_ORG_ADMIN 전용**, 권한 검증은 서비스에서 403
- **계정 잠금 = 즉시**: INACTIVE + 세션 revoke + **진행 중 변환 취소**. 쪽 단위 차감이라 "완료된 쪽까지만 차감"이 자동 성립. 본인 잠금 불가
- **열람 범위(기획 확정)**: 기관 관리자는 목록·상태·크레딧까지 — **파일 내용·접속 정보 제공 금지** / 점역사(T3)는 내 사용량 + 기관 전체 잔여만 — **타 계정 개별 소모량 제공 금지**
- ⚠️ **기관 관리자는 에디터 사용 불가** — ROLE_ORG_ADMIN의 Job 생성은 COMMON4003(`JobService.createJob` 가드)
- 문의는 메일함 IMAP **읽기 전용** 폴링(5분)으로도 들어온다. 자격증명 미설정이면 폴러 비활성(fail-safe)

## QnA 게시판 (`/api/board`, V39)

- 기획 6-2 · 피그마 게시판 화면. 로그인한 모든 계정(기관 관리자 포함). 운영자 기능(답변 · 숨김 · 삭제)은 `/api/admin/board/**`
- **글과 답변은 쓴 사람 본인만 고치고 지운다** — 운영자도 남의 글 본문은 못 고친다(숨김 · 삭제만). 다른 운영자의 답변도 못 고친다
- 비밀글: 목록엔 제목이 보이고 열람은 작성자 · 운영자만. **비밀글 본문은 남의 내용 검색에 안 걸린다**(걸리면 목록만 보고 본문 낱말을 알 수 있다)
- ⚠️ **아이디는 서버가 가린다**(앞 절반 + `***`). 원래 아이디를 응답에 싣지 않는다 — 운영자 콘솔 목록만 예외
- **공지는 따로 없다 — 운영자 공지(`notices`)가 게시판 맨 위에 뜬다**(고정 · 기간 안 · 전체 또는 내 기관). 검색 · 「내 글 보기」에는 안 붙는다. 운영자 「고정 해제」(`notices.pinned=false`, V40)면 게시판에서 내려간다 — 기관 관리자 공지 화면과는 무관
- 글 번호 = `board_posts.id`. 삭제는 `deleted_at` 표시만(번호 안 당김). 숨긴 글은 운영자에게만 보인다
- 조회수는 열 때마다 +1(본인 · 반복 포함, 기획 결정). 알림은 글 하나에 하나 — 「글쓴이에게 알림 보내기」 답변만 만들거나 다시 안 읽음으로 돌리고, 작성자가 글을 열면 읽음
- 첨부: 5개 · 한 개 10MB · 이미지 · 문서 · zip만. 「현재 작업 정보」는 앱이 만든 파일을 일반 첨부로 받는다. S3 키에 원래 이름을 쓰지 않는다(`board/{postId}/{uuid}.{ext}`)

## 사용량·원가·크레딧

- **AI는 측정값만 보낸다**(토큰·gpu_time·layout_type) — **금액·크레딧은 BE가 계산**한다
- ⚠️ **단가·배율은 `pricing_configs` 테이블. 코드에 하드코딩 금지.** 수정 = 새 행 추가(과거 판 불변, `page_results.pricing_config_id`가 근거를 가리킴)
- 계산 결과는 **쪽 처리 시점 값으로 확정 저장** — 단가를 바꿔도 과거 기록은 불변. 원자료도 함께 저장(감사·재검산)
- ⚠️ **단가표에 없는 모델은 0원으로 삼키지 않고 `cost_uncertain=true`** 로 표시한다
- 크레딧은 **성공한 쪽만** 차감(UNSPECIFIED 0 / TEXT 1 / FORMULA 2 / TABLE 3 / VISUAL 5), 실패 쪽 무차감. **0 차감도 기록**(고객 검산용). `(job_id, page_no)` 유니크라 워커 재시도에도 이중 차감 불가
- 쿠폰 우선 차감 — 잔여 게이지·수익성 매출은 **CONTRACT 차감만** 센다
- 원가 계산 실패가 변환 결과 저장을 막지 않는다

## 로깅

- ctx는 요청 `req-xxxxxxxx|loginId` / 워커 `jobId|pN` — **`grep <jobId>`·`grep <loginId>`로 전 과정이 묶인다**
- ⚠️ **4xx에 스택 금지** — `grep -E "WARN|ERROR"`가 곧 장애 화면이다. `/api/` 밖 401(봇 스캔)은 INFO로 격하
- ⚠️ `show-sql: false` 고정(stdout 직행 노이즈). SQL 디버깅은 `org.hibernate.SQL=DEBUG`
- ⚠️ **`/error`는 permitAll 유지** — 빼면 예외 1건이 인가 거부 스택 수백 줄로 증폭된다

## gRPC (AI 서버)

- `grpc.ai.servers` = `host:port:슬롯수[,…]` — 증설·슬롯 조정은 환경변수 + 재시작으로 끝
- deadline 400s (AI 하드 타임아웃 180s × 최악 케이스를 감쌈)
- ⚠️ TLS `authority: semo-jum.com` — SAN에 IP가 없어도 이 설정으로 검증된다. **제거하면 연결이 깨진다**
- **proto는 AI 팀이 정본이다** — 공유받은 파일을 그대로 넣는다(BE가 필드를 더하거나 고치지 않는다). V3.1(2026-10-14): RPC는 `ProcessPage` 하나뿐(TranslateText 삭제), 응답은 교열 묵자만
- 점역 옵션은 요청 10·11·12번(`korean_grade`·`english_grade`·`split_choices`)으로 간다 — 업로드 때 정해져 **태스크 JSON에 실린다**(`JobService.taskJson`). 구 태스크엔 칸이 없어 0·false = 서버 기본
- ⚠️ `mode` 값 변경처럼 **양쪽이 동시에 맞아야 하는 변경**은 배포 순서가 곧 장애 여부다. AI가 먼저 바뀌면 운영 BE 요청이 전부 거절된다

## HWP 페이지 분리 (HwpPageExtractor — 프로덕션 미사용, 도구·이력으로 유지)

⚠️ **hwplib 1.1.9 필수** — 1.1.1은 일부 파일에서 무한 대기(다운그레이드 금지)
⚠️ `isFirstLineAtPage()`는 실파일에서 항상 false — **사용 금지**, y 리셋 방식 유지

---

# DB 스키마

users / organizations / user_sessions / jobs / pages / page_results / text_elements /
braille_elements / bounding_boxes / rule_trails / quality_critical_errors / quality_review_flags /
board_posts / board_attachments / board_answers / board_notifications / page_memos / braille_forms / pricing_configs / credit_transactions / coupons / notices /
inquiries / inquiry_attachments / orders / app_versions

- V41(2026-10-14)에서 `folders` · `page_edit_histories` · `page_edit_logs` 테이블과 `jobs.folder_id` · `is_favorite` 칼럼을 지웠다(백업 없음)

- Page 상태: PENDING / RUNNING / COMPLETED / NEEDS_REVIEW / BLOCKED (+취소 창 동안만 CANCELED)
- Job 상태: PENDING → IN_PROGRESS → COMPLETED / FAILED (plain String)
- ⚠️ 마이그레이션 `db/migration/V{n}__*.sql`(Flyway) — **적용된 파일은 절대 수정 금지**(체크섬). 정정은 새 `V{n}`으로

# Redis 키

| 키 | 설명 |
|---|---|
| `queue:job:{jobId}` | 작업별 쪽 태스크 큐 |
| `sched:ring:users` / `sched:user:{userId}:jobs` | 스케줄러 유저 링 / 유저별 작업 링 |
| `sched:job:{jobId}:fg` | FG 리스 (TTL 30s) |
| `job:{jobId}:pages` | 쪽별 상태 Hash + total_pages (**종료 시 TTL 1h**) |
| `job:{jobId}:canceled` | 취소 플래그 (TTL 1h) |
| `auth:login-fail:{loginId}` | 로그인 틀린 횟수 (마지막 실패 후 TTL 10분) |
| `auth:login-lock:{loginId}` | 로그인 잠금, 값 = 해제 시각 epoch ms (TTL 10분) |

# 컨벤션

- 커밋 `feat:`/`fix:`/`chore:`/`docs:`. `dev`에서 직접 작업하지 않고 브랜치를 판다
- **머지는 사용자 지시가 있을 때만** — `dev` 머지가 곧 운영 배포다
- 최소 변경 원칙, 기존 주석 유지·보강. 에러는 `ApiResponse.failure(ErrorCode.xxx)`, 엔티티는 `@NoArgsConstructor(PROTECTED)` + `@Builder`
- 새 테스트는 **수정을 일부러 되돌려 실패하는지 확인**한다(되돌려도 통과하면 아무것도 검증하지 않는 테스트다)

# 주의사항 (지뢰 목록)

- **시간대 KST 3중 고정**: `TimeZone.setDefault` + yaml(jackson·hibernate) + compose TZ — **어느 하나도 제거 금지**. 새 시각 컬럼은 반드시 `timestamptz`
- `spring.jpa.open-in-view=false` — SSE 장기 연결의 커넥션 고갈 방지. 읽기 서비스는 `@Transactional(readOnly=true)`
- gRPC deadline(400s) > AI 하드 타임아웃 구조 유지
- **S3 CORS·presigned·공개정책(`*/thumbnail.png`만)은 3종 세트** — 하나라도 건드리면 FE 렌더링·보안에 영향
- **요소 단위 반복 조회 금지(N+1)** — 요소마다 rule_trail을 조회해 95개 페이지 응답이 266ms(콜드 1.4s)였다. 페이지 단위 배치 조회로 교체했고, 요소별 부가 데이터를 붙일 때도 같은 패턴을 지킬 것
- `task.md`는 `.gitignore` 등록
- UserService·SseService의 result 직렬화 헬퍼 중복 → 추후 공통화 예정
