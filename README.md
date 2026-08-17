# 세모점 Backend

점역사를 위한 AI 점자 변환 에디터의 서비스 서버.
파일 업로드 → AI 변환(gRPC) → 실시간 스트리밍(SSE) → 점역사 편집 → 점자 파일(.brf) 출력까지의 파이프라인을 담당한다.

| 모드 | 변환 | 입력 | 출력 |
|---|---|---|---|
| a | 이미지 → 텍스트 | PDF | `.txt` |
| b | 텍스트 → 점자 | TXT, HWP | `.brf` |
| c | 이미지 → 점자 | PDF | `.brf` |

---

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5.13, Gradle |
| 데이터 | PostgreSQL 18 (AWS RDS), Redis, Flyway |
| 스토리지 | AWS S3 (EC2 IAM Role 키리스 인증) |
| 통신 | gRPC + TLS (AI 서버), SSE (실시간 진행 상황) |
| 인증 | Spring Security, JWT (jjwt), BCrypt |
| 배포 | Docker Compose, Envoy Proxy, GitHub Actions |

---

## 아키텍처

```
FE (Electron)
     │  HTTPS
     ▼
  Envoy ──▶ Backend (Spring Boot) ──gRPC/TLS──▶ AI 서버 (GPU)
                 │
     ┌───────────┼───────────┐
     ▼           ▼           ▼
PostgreSQL     Redis        S3
 (메타·결과)  (작업 큐)   (원본·썸네일)
```

- **작업 큐**: Job 생성 시 페이지 단위로 분리해 Redis 큐에 적재. `JobDispatcher`가 라운드로빈으로 공정하게 배분하고, 워커가 AI 서버 슬롯 수만큼 병렬 처리한다.
- **페이지 순서 보장**: 병렬 변환이지만 SSE는 페이지 번호 오름차순으로만 방출한다(커서 방식).
- **SSE**: `open-in-view=false` — 장시간 연결이 DB 커넥션을 점유하지 않는다.

---

## 시작하기

### 요구 사항

- JDK 21
- Docker (Redis 구동 · 통합 테스트용 Testcontainers)
- AI 서버 TLS 인증서 (`server.crt`)

### 로컬 실행

```bash
# 1) Redis
docker run -d -p 6379:6379 redis

# 2) 환경 변수 설정 (아래 표 참고)
export DB_HOST=... DB_PASSWORD=... JWT_SECRET=... GRPC_CERT_PATH=file:/path/to/server.crt

# 3) 실행
./gradlew bootRun
```

- 서버: `http://localhost:8080`
- API 문서(Swagger UI): `http://localhost:8080/swagger-ui/index.html`

### 환경 변수

| 변수 | 필수 | 설명 |
|---|---|---|
| `DB_HOST` | ✅ | PostgreSQL 호스트 |
| `DB_PASSWORD` | ✅ | DB 비밀번호 |
| `JWT_SECRET` | ✅ | JWT 서명 키 |
| `GRPC_CERT_PATH` | ✅ | AI 서버 인증서 경로 (`file:` 접두사) |
| `ADMIN_API_KEY` | — | 운영자 API 키. **미설정 시 운영자 API 전면 차단**(fail-closed) |
| `GRPC_AI_SERVERS` | — | AI 서버 목록 `host:port:슬롯수` (쉼표 구분). 증설·슬롯 조정은 이 값만 바꾸면 됨 |
| `S3_BUCKET` / `AWS_REGION` | — | 기본값 `semojum-bucket` / `ap-northeast-2` |

> 시간대는 KST로 고정되어 있다(`TZ`, `spring.jackson.time-zone`, `hibernate.jdbc.time_zone`). 어느 하나라도 빠지면 카드 날짜가 9시간 어긋난다.

---

## API

모든 응답은 공통 포맷으로 감싼다(파일 다운로드 제외).

```json
{ "isSuccess": true, "code": "COMMON2000", "message": "성공입니다.", "result": {} }
```

| 영역 | 주요 엔드포인트 |
|---|---|
| 인증 | `POST /api/auth/login` · `refresh` · `logout` |
| 운영자 | `POST /api/admin/orgs` · `/accounts` (`X-Admin-Key` 헤더 검증) |
| 변환 | `POST /api/jobs` (multipart) · `GET /api/jobs/{id}/events` (SSE) · `POST /api/jobs/{id}/cancel` |
| 편집 | `PUT /api/jobs/{id}/pages/{no}/elements` (페이지 일괄 저장) · `PATCH .../draft` |
| 출력 | `POST /api/jobs/{id}/download` (.txt / .brf) |
| 마이페이지 | `GET /api/users/jobs` · `/api/folders/...` · `/api/trash` |

상세 명세는 노션 참고.

---

## 프로젝트 구조

```
src/main/java/com/semojum/backend
├── domain
│   ├── auth       인증·세션          ├── folder   폴더 트리
│   ├── admin      계정 발급·관리      ├── trash    휴지통(30일)
│   ├── job        변환 작업·큐·SSE    ├── user     마이페이지 조회
│   ├── result     변환 결과·편집      └── org      기관
└── global
    ├── grpc       AI 서버 풀·클라이언트
    ├── hwp        HWP 실제 페이지 분리
    ├── s3         파일 스토리지
    ├── jwt        인증 필터
    └── thumbnail  썸네일 생성
```

- `com.semojum.brailleassist` — 점자 조판 라이브러리. [Semojum/braille-assist](https://github.com/Semojum/braille-assist)에서 복사한 파일이므로 **직접 수정 금지**(규칙 변경은 원 레포에서).
- `src/main/resources/db/migration` — Flyway 마이그레이션. 기동 시 자동 적용된다.

---

## 테스트

```bash
./gradlew test                              # 전체
./gradlew test --tests "*PageSaveServiceTest"  # 특정 클래스
```

- 통합 테스트는 Testcontainers로 실제 PostgreSQL·Redis 컨테이너를 띄운다 → **Docker 실행 필요**
- 조판 검증(`VectorsTest`)은 braille-assist 공통 벡터 42건을 매 빌드마다 대조한다

---

## 배포

`dev` 브랜치 push → GitHub Actions → Docker Hub → EC2에서 `scripts/deploy.sh`(블루그린)

- **무중단 배포** — 새 버전을 비활성 색 컨테이너로 띄우고 헬스 통과 후 구 색을 내린다. 새 버전 기동 실패 시 구버전이 계속 서비스한다
- SSH 22번 포트는 상시 개방하지 않고, 워크플로가 배포 동안만 러너 IP를 인바운드에 추가했다가 회수한다

---

## 컨벤션

**브랜치**

| 브랜치 | 역할 |
|---|---|
| `main` | 릴리스 기준선 — 태그를 다는 곳. 직접 커밋 금지 |
| `dev` | 개발 기본 브랜치 (배포 트리거) |
| `feat/*` · `fix/*` · `docs/*` | 작업 브랜치 → PR → `dev` |

**커밋**: `feat:` 새 기능 / `fix:` 버그 수정 / `chore:` 설정·의존성 / `docs:` 문서 / `test:` 테스트

**버전**: `v3.0.2` = 세대(V1·V2·V3) . 라운드(공통 릴리스) . 패치(파트별 독립). 릴리스 시 `dev` → `main` 머지 후 annotated 태그를 단다.

```bash
git switch main && git pull
git merge origin/dev
git diff origin/dev HEAD          # 비어야 정상
git tag -a v3.1.0 -m "v3.1.0" && git push origin main v3.1.0
```
