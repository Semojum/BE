# 세모점(Semojum) BE 개발 가이드

점역사(전문 점자 번역사)용 AI 점자 변환 플랫폼의 백엔드.

| 모드 | 변환 | 입력 |
|---|---|---|
| a | 이미지 → 텍스트 | PDF, HWP |
| b | 텍스트 → 점자 | TXT |
| c | 이미지 → 점자 | PDF, HWP |

> **API 상세(요청·응답·에러)는 노션 `Development / Dev. DB / [V3] API 명세서`가 정본**이다.
> 이 문서에는 코드만 봐서는 알 수 없는 것 — 설계 제약, 결정의 근거, 지뢰 — 만 적는다.

## 기술 스택

Java 21 · Spring Boot 3.5 · Gradle-Groovy · `com.semojum.backend`
PostgreSQL 18(RDS) + Flyway · Redis · AWS S3 · gRPC+TLS(AI 서버) · SSE
Spring Security + JWT(jjwt) · BCrypt · Docker Compose + Envoy · GitHub Actions

## 인프라 (AWS 서울, 계정 804136008552)

| 구성 | 상세 |
|---|---|
| EC2 | `semojum-backend` t3.medium, 고정 IP `43.200.184.56` |
| RDS | `semojum-postgres` (PG18). **로컬·개발·운영이 이 DB 하나를 공유** — 마이그레이션은 어느 환경에서 먼저 적용돼도 안전해야 한다. DB 이름은 `postgres` |
| S3 | `semojum-bucket` (EC2 IAM Role 키리스) |
| AI 서버 | `semojum-ai`(g5.2xlarge) — **같은 VPC라 반드시 사설 IP `172.31.47.101:50051`**. 공인 IP로 가면 AI 보안그룹의 "BE SG 허용" 규칙에 안 걸려 차단된다 |
| 도메인 | `api.semojum.app` — **Cloudflare DNS only + EC2 직접 TLS**. 프록시 경유 시 무료 플랜이 미국 엣지로 라우팅해 요청당 +0.5~0.7s(실측) → **프록시(주황 구름) 재활성화 금지**. 인증서는 certbot 자동 갱신, **인증서 파일은 uid 101 소유 필수**(envoy가 envoy 유저로 강등 실행) |

- 보안그룹: EC2 80/443 공개 · **22는 관리자 IP만**. **22번을 0.0.0.0/0으로 열지 말 것** — CI가 배포 동안만 러너 IP를 추가·회수한다(`if: always()`)
- 관리자 IP가 바뀌면 `semojum-ec2-sg`(22)·`semojum-rds-sg`(5432) 두 곳 갱신
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
│   ├── user      마이페이지 목록·페이지 조회
│   ├── folder    폴더 CRUD·트리·contents (FolderTouch)
│   ├── trash     휴지통 + TrashPurgeScheduler(04시)
│   ├── job       Job 생성·다운로드·취소·SSE
│   │   ├── scheduler   JobDispatcher(공정 큐), StaleJobScheduler
│   │   └── worker      PageWorker
│   ├── result    AI 결과 저장(ResultService), 페이지 일괄 저장
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
- **역할 3단**: ROLE_ADMIN(운영자) / ROLE_ORG_ADMIN(기관 관리자, `/api/org/**`) / ROLE_USER(점역사)
- **ROLE_ADMIN 웹/앱 분리** (`users.admin_scope` = WEB·APP·null): 로그인 시 브라우저가 붙이는 **Origin이 콘솔 주소면 콘솔 로그인**으로 보고 WEB 계정만 허용, 아니면 AUTH4005. WEB 계정은 콘솔 밖에서 로그인 불가
  - ⚠️ **curl로 `/api/admin/**`를 부를 때는 `Origin: https://admin.semo-jum.com` 헤더 필수** — 안 붙이면 AUTH4005
  - `admin.console-origins`(env `ADMIN_CONSOLE_ORIGINS`) — compose가 빈 값을 넘기면 yaml 기본값을 덮으므로 **compose에도 기본값이 있어야 한다**
- ⚠️ **CORS는 전면 허용(`allowedOriginPatterns *`) 유지** — Bearer라 origin 제한이 무의미하고, **좁히면 데스크톱 앱(Origin: null)이 403으로 깨진다**(2026-08-20 회귀 실측)
- ⚠️ adminPage(콘솔 FE)·`~/semojum/FE`는 **FE 영역, BE가 수정 금지**
- ⚠️ 운영자 "마이페이지로 보내기"(`send-to-mypage`) 사본은 **`jobs.admin_copy=true` + 원가 0**이다. **통계·수익성·layout-cost 등 집계 쿼리에서 반드시 제외할 것** — 새 쿼리를 짤 때 빼먹으면 숫자가 조용히 틀린다

## Job 생성 (`POST /api/jobs`)

- multipart. **조판 옵션 12개**(칸 수·줄 수·페이지행·꼬리말 정렬 등)를 함께 받아 **`jobs.layout_options`(jsonb) 한 칸**에 저장한다 — 기획 확정 전이라 컬럼을 쪼개지 않았다. 항목·기본값은 노션 명세 참조
- **설정은 업로드 후에도 바꿀 수 있다** — `PATCH /api/jobs/{jobId}/options`(에디터 조판 설정 모달·원본 쪽번호 버튼). 부분 갱신이고, **변환 중이면 JOB4010**(`advancedAi`가 gRPC 요청에 실려서 — 중간에 바뀌면 쪽마다 다른 설정으로 처리된다). ⚠️ `last_modified_at`을 갱신하지 않는다(설정은 내용이 아니다)
- 페이지 분리: a/c는 PDF 쪽별 / **b는 구분선 우선, 없으면 30줄 청크**
  - ⚠️ 구분선은 mode a 다운로드가 넣는 **하이픈 정확히 40개 줄**이다. 39·41개나 앞뒤에 글자가 붙으면 본문으로 본다(밑줄 장식 오인 방지). 구분선 없는 TXT의 30줄은 원문 쪽과 무관한 임의값이라 그때의 "원본 쪽 번호"는 청크 번호일 뿐이다
  - ⚠️ mode a는 **내용이 전부 빈 쪽에 구분선을 남기지 않는다** — 원문 중간에 빈 쪽이 있었다면 그만큼 번호가 당겨지고, .txt에 그 쪽이 없어 **복원할 수 없다**
- **HWP는 업로드 시 PDF로 변환**(pyhwp→ODT→LibreOffice, Dockerfile 내장) 후 기존 PDF 파이프라인. 머리말·꼬리말은 변환기가 유실하므로 hwplib로 읽어 마커로 주입한다
  - ⚠️ **HWPX 미지원** — 확장자가 `.hwp`여도 내용물이 ZIP이면 JOB4007. 최신 한글 저장본에서 흔하다
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

## 다운로드 (`POST /api/jobs/{jobId}/download`)

- mode a = `.txt`(요소 병합, 쪽 사이 `-`×40 구분선, 빈 블록·빈 쪽 스킵, `<!점역자주>` 마커 유지)
- mode b·c = `.brf` — **조판 전체를 braille-assist에 위임**한다
- ⚠️ **`com.semojum.brailleassist`는 원 레포(Semojum/braille-assist) 복사본 — 수정 금지.** 규칙 변경은 원 레포에서
- ⚠️ **동기화할 때는 본체·테스트·벡터 3종을 함께** 갈아끼운다(`BrailleAssist.java` + `VectorsTest.java` + `vectors.json`). 본체만 바꾸면 `VectorsTest`가 깨진다. 단 `VectorsTest`의 벡터 로딩부는 **BE 로컬 적응**이라 원 레포를 그대로 덮으면 안 되고 클래스패스 리소스 방식을 유지한다
- **꼬리말은 업로드 때 한 번 점역**해 `jobs.footer_braille`에 담고 화면·SSE·다운로드가 같은 값을 쓴다. AI 실패는 삼키고(null이면 조회 시점에 채움), SSE는 저장된 값만 읽는다(**방출 경로에서 gRPC 금지**)
- ⚠️ **꼬리말 길이 검증** — 점역 결과가 페이지행에 안 들어가면 COMMON4000. 라이브러리가 긴 꼬리말을 **말없이 뒤에서 자르기** 때문이다

## 원본 페이지 삭제 (`DELETE .../pages/{pageNo}` · 벌크 `?nos=5,6,7,8`)

- **영구 삭제 + 뒤 번호 자동 당김.** 휴지통을 안 거치고 되돌릴 수 없다
- **크레딧 환불 없음**(이미 원가가 났고 장부는 일어난 일을 적는 곳) · **편집 이력 보존**(RLHF 자료)
- ⚠️ 남기는 두 표는 **번호를 당기지 않는다** — 그때의 기록이라 사실이 달라진다. 삭제 뒤 page_no가 현재 쪽 번호와 어긋날 수 있다(의도)
- ⚠️ **S3 키는 옮기지 않는다** — 경로가 DB 컬럼이라 읽는 쪽이 그 값을 쓴다. 옮기면 삭제 한 번에 남은 쪽 수만큼 복사+삭제가 난다(205쪽이면 400회)
- ⚠️ **벌크는 큰 번호부터 지운다** — 단건을 반복 호출하면 지울 때마다 뒤 번호가 당겨져 두 번째부터 엉뚱한 쪽을 지운다. 하나라도 없는 쪽이 섞이면 아무것도 지우지 않는다

## 취소 (`POST /api/jobs/{jobId}/cancel`)

즉시가 아닌 **수렴**: 플래그 → 큐 배수 → 인플라이트 마무리 → 확정. 완료 0건이면 전부 BLOCKED+FAILED. 이미 끝난 작업 취소는 멱등.

## 마이페이지

- **탐색 vs 검색 분리**: 폴더 진입은 `/api/folders/{id}/contents`·`/api/folders/contents`(루트) / 전역·검색은 `/api/users/jobs` / 최신순 스트립은 `/api/users/jobs/recent`
- 세 경로 모두 응답이 `{folders, files:{items, nextCursor, hasMore}}`로 같다. 커서 요청(2페이지~)에는 `folders` 빈 배열(FE 누적 중복 방지)
- 검색은 현재 위치 **서브트리 전체**, 비검색은 한 층만
- ⚠️ **`jobs.last_modified_at`(내용이 바뀐 시각)과 `updated_at`(변환 진행, StaleJobScheduler 전용)을 섞지 말 것.** 카드 날짜·정렬·커서 기준은 전자이고 페이지 편집에서만 갱신된다 — 이름변경·이동·복원·즐겨찾기는 갱신하지 않는다
- ⚠️ `folders.last_modified_at`은 **직속 항목의 추가·삭제·이름변경만** 갱신(윈도우 탐색기 규칙). 상위 전파 없음. 갱신은 `FolderTouch`에 집약 — **폴더 안 항목을 바꾸는 코드를 추가하면 호출할 것**. V12 SQL 주석은 현재 동작과 다르다
- ⚠️ JobCard의 `folderId`/`folderPath` **제거 금지** — 위치 표시와 "폴더로 이동"에 쓴다
- **원본 미리보기는 서버가 미리 렌더한 JPEG**다(`PageWorker`가 AI 요청 직전에 생성). `original`은 `{type, url}` 하나로 통일 — `image`(정상) / `pdf`(렌더 실패 폴백) / `text`(b). **FE는 type으로 분기한다.** presigned URL은 15분이라 **장기 캐시 금지**
  - 근거(실측): pdf.js로 스캔본을 그리면 1,807~2,853ms(JPEG 2000은 브라우저 네이티브 디코더가 없다) vs `<img>` 6~9ms. 스위치 `page-image.enabled`

## 편집

- **유일한 편집 경로는 `PUT /api/jobs/{jobId}/pages/{pageNo}/elements`** — body는 페이지 최종 상태 전체. diff(EDIT/ADD/삭제/reorder)는 서버가 판정한다
- 편집 대상은 mode가 결정(a=text_elements, b·c=braille_elements). **`current`만 갱신, `original` 절대 보존**
- 대체 초안 선택(`PATCH .../draft`)은 포인터+복사 — drafts·original 불변. `selectedIdx=-1`이면 원본 복귀
- `page_edit_logs`: **1저장 = 1행**, 페이지 전체 before/after 스냅샷 + 입력 컨텍스트(자기완결). RLHF 학습용이라 삭제하지 않는다

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

## HWP 페이지 분리 (HwpPageExtractor — 프로덕션 미사용, 도구·이력으로 유지)

⚠️ **hwplib 1.1.9 필수** — 1.1.1은 일부 파일에서 무한 대기(다운그레이드 금지)
⚠️ `isFirstLineAtPage()`는 실파일에서 항상 false — **사용 금지**, y 리셋 방식 유지

---

# DB 스키마

users / organizations / user_sessions / jobs / pages / page_results / text_elements /
braille_elements / bounding_boxes / rule_trails / quality_critical_errors / quality_review_flags /
folders / page_edit_logs / pricing_configs / credit_transactions / coupons / notices /
inquiries / inquiry_attachments / orders / app_versions

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
