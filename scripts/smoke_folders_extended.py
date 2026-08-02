#!/usr/bin/env python3
"""확장 스모크: 실제 Job 업로드 → 이름변경(JOB4010/성공) → 폴더 이동 → 캐스케이드 휴지통
→ 복원 → 벌크 롤백 → 완전 삭제(DB+S3) 검증. 로컬 앱 + 공유 RDS + S3."""
import json, subprocess, sys, time, urllib.request, urllib.error, uuid, pathlib

BASE = "http://localhost:8080"
SCRATCH = pathlib.Path(sys.argv[1]).parent
PW = json.load(open(sys.argv[1]))["result"]["password"]
DB_PW = [l.split(": ", 1)[1].strip() for l in open(pathlib.Path.home() / "Desktop/semojum-aws-secrets.txt")
         if l.startswith("RDS master password")][0]
RDS = "semojum-postgres.c3mk86a8cm0o.ap-northeast-2.rds.amazonaws.com"

results = []
def check(ok, desc):
    results.append(("PASS" if ok else "FAIL", desc))

def call(method, path, body=None, token=None, expect_code=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            d, status = json.load(r), r.status
    except urllib.error.HTTPError as e:
        d, status = json.load(e), e.code
    code = d.get("code")
    ok = (code == expect_code) if expect_code else d.get("isSuccess", False)
    check(ok, f"{method} {path} -> {status} {code}")
    return d

def upload_job(token, filename, content):
    boundary = uuid.uuid4().hex
    parts = []
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\nb\r\n")
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                 f"Content-Type: text/plain\r\n\r\n{content}\r\n")
    parts.append(f"--{boundary}--\r\n")
    body = "".join(parts).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/jobs", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    check(d.get("isSuccess", False), f"POST /api/jobs (multipart 업로드) -> {d.get('code')}")
    return d["result"]["jobId"]

def psql(sql):
    out = subprocess.run(["docker", "run", "--rm", "postgres:18", "psql",
                          f"host={RDS} user=postgres password={DB_PW} dbname=postgres sslmode=require",
                          "-t", "-A", "-c", sql], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return out.stdout.strip()

def s3_count(prefix):
    out = subprocess.run(["aws", "s3", "ls", f"s3://semojum-bucket/{prefix}", "--recursive"],
                         capture_output=True, text=True, timeout=60)
    return len([l for l in out.stdout.splitlines() if l.strip()])

# ===== 시나리오 =====
token = call("POST", "/api/auth/login", {"loginId": "verify01", "password": PW})["result"]["accessToken"]

# 1. 실제 파일 업로드 (mode b, 소형 txt)
job_id = upload_job(token, "확장스모크.txt", "점자 변환 확장 스모크 테스트 본문입니다.\n두 번째 줄.")
check(s3_count(job_id + "/") >= 1, f"S3에 업로드 객체 존재 ({s3_count(job_id + '/')}개)")

# 2. 변환 중(PENDING) 상태에서 이름 변경 → JOB4010
call("PATCH", f"/api/jobs/{job_id}", {"fileName": "이름변경시도"}, token, expect_code="JOB4010")

# 3. 상태를 종결(COMPLETED)로 강제 → 이름 변경 성공
psql(f"UPDATE jobs SET status='COMPLETED', finished_at=now() WHERE id='{job_id}'")
d = call("PATCH", f"/api/jobs/{job_id}", {"fileName": "확장스모크-개명.txt"}, token)
check(d.get("result", {}).get("fileName") == "확장스모크-개명.txt", "이름 변경 응답에 새 이름 반영")

# 4. 폴더 생성 → 실제 Job 벌크 이동
folder_id = call("POST", "/api/folders", {"name": "확장스모크폴더"}, token)["result"]["folderId"]
call("POST", "/api/jobs/move", {"jobIds": [job_id], "targetFolderId": folder_id}, token)
check(psql(f"SELECT folder_id FROM jobs WHERE id='{job_id}'") == folder_id, "DB에서 folder_id 이동 확인")

# 5. 작업이 든 폴더 삭제 → 캐스케이드 휴지통 + 목록 집계
call("DELETE", f"/api/folders/{folder_id}", None, token)
trash = call("GET", "/api/trash", None, token)["result"]["items"]
row = next((i for i in trash if i["id"] == folder_id), None)
check(row is not None and row["itemCount"] == 1, f"휴지통: 폴더 한 줄 + itemCount=1 (실제={row and row['itemCount']})")
check(not any(i["id"] == job_id for i in trash), "폴더째 삭제된 작업은 개별 행으로 노출되지 않음")

# 6. 복원 → 작업·폴더 관계 유지
call("POST", f"/api/trash/{folder_id}/restore", None, token)
r = psql(f"SELECT deleted_at IS NULL, folder_id FROM jobs WHERE id='{job_id}'").split("|")
check(r[0] == "t" and r[1] == folder_id, "복원 후 작업 활성 + 폴더 소속 유지")

# 7. 벌크 삭제에 유령 id 섞기 → 전체 롤백
call("POST", "/api/jobs/trash", {"jobIds": [job_id, "job_000000000000_ghost00000"]}, token, expect_code="JOB4001")
check(psql(f"SELECT deleted_at IS NULL FROM jobs WHERE id='{job_id}'") == "t", "롤백 확인: 실제 작업은 삭제되지 않음")

# 8. 다시 폴더 삭제 → 완전 삭제 → DB·S3 모두 정리
call("DELETE", f"/api/folders/{folder_id}", None, token)
before = s3_count(job_id + "/")
call("DELETE", f"/api/trash/{folder_id}", None, token)
check(psql(f"SELECT count(*) FROM jobs WHERE id='{job_id}'") == "0", "완전 삭제: DB에서 작업 행 제거")
check(psql(f"SELECT count(*) FROM folders WHERE id='{folder_id}'") == "0", "완전 삭제: DB에서 폴더 행 제거")
after = s3_count(job_id + "/")
check(before >= 1 and after == 0, f"완전 삭제: S3 객체 정리 (전 {before}개 → 후 {after}개)")

# 결과
fails = [r for r in results if r[0] == "FAIL"]
for s, d in results: print(f"[{s}] {d}")
print(f"\n총 {len(results)}건 중 PASS {len(results)-len(fails)} / FAIL {len(fails)}")
sys.exit(1 if fails else 0)
