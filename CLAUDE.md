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
- 오류 발생 시 task를 큐에 재등록 후 2초 대기 (자동 재시도)
- `@PreDestroy`로 graceful shutdown 처리

### ResultService
- AI gRPC 응답(BrailleResponse)을 DB에 저장
- 저장 테이블: `page_results`, `text_elements`, `braille_elements`, `bounding_boxes`, `rule_trails`, `quality_critical_errors`, `quality_review_flags`
- Page 상태 업데이트, Job 완료 여부 확인 및 상태 업데이트

### 마이페이지 (User)
- `GET /api/users/jobs` — 내 Job 목록 조회 (최신순, thumbnailUrl 포함)
- `GET /api/users/jobs/{jobId}/pages/{pageNo}` — 페이지별 변환 결과 조회 (모드별 직렬화)
- 두 엔드포인트 모두 JWT 인증 필요, 타인 Job 접근 시 403

### 썸네일 (ThumbnailService)
- Job 생성 시 자동 생성 후 GCS 업로드, 공개 URL을 `jobs.thumbnail_url`에 저장
- mode a/c: PDFBox로 PDF 첫 페이지 → PNG 렌더링
- mode b: 텍스트를 NanumGothic 폰트로 흰 배경에 렌더링 → PNG
- 생성 실패 시 경고 로그만 남기고 Job 생성은 정상 진행

### Spring Security
- `JwtFilter`: PERMIT_URLS = `/api/auth/signup`, `/api/auth/login`, `/api/auth/google`, `/api/auth/kakao`, `/swagger-ui`, `/v3/api-docs`
- 미인증 요청 시 `COMMON4001` JSON 반환

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
| jobs | 변환 작업 |
| pages | 페이지별 파일 경로 |
| page_results | AI 변환 결과 |
| text_elements | 텍스트 요소 (text_list) |
| braille_elements | 점자 요소 (braille_text_list) |
| bounding_boxes | 바운딩박스 (mode a, c) |
| rule_trails | 적용된 점역 규정 |
| quality_critical_errors | 품질 오류 |
| quality_review_flags | 검토 필요 항목 |

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
| IN_PROGRESS | 처리 중 |
| COMPLETED | 전체 완료 |

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
- SSE 연결 시 Envoy `idle_timeout: 0s`, `timeout: 0s` 필수
- gRPC 타임아웃은 AI 서버 하드 타임아웃(180s)보다 높은 200s로 설정
- PageWorker `WORKER_COUNT = 1` (임시) — AI 서버 병렬처리 확인 후 6으로 복구
- UserService와 SseService 간 result 직렬화 헬퍼 코드 중복 존재 → 추후 공통 컴포넌트로 리팩토링 예정
