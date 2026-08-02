#!/usr/bin/env python3
"""V3 마이페이지 폴더·휴지통 API 스모크 테스트 (로컬 앱 → 공유 RDS)"""
import json, urllib.request, urllib.error, sys

BASE = "http://localhost:8080"
reissue = json.load(open(sys.argv[1]))
PW = reissue["result"]["password"]

results = []

def call(method, path, body=None, token=None, expect_code=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as r:
            d = json.load(r)
            status = r.status
    except urllib.error.HTTPError as e:
        d = json.load(e)
        status = e.code
    code = d.get("code")
    ok = (code == expect_code) if expect_code else d.get("isSuccess", False)
    results.append(("PASS" if ok else "FAIL", f"{method} {path} -> {status} {code}"))
    return d

# 1. 로그인
login = call("POST", "/api/auth/login", {"loginId": "verify01", "password": PW})
token = login["result"]["accessToken"]

# 2. 폴더 생성 (루트 + 하위)
f1 = call("POST", "/api/folders", {"name": "스모크테스트"}, token)["result"]["folderId"]
f2 = call("POST", "/api/folders", {"name": "하위폴더", "parentFolderId": f1}, token)["result"]["folderId"]

# 3. 트리 조회 — 스모크테스트 > 하위폴더 구조 확인
tree = call("GET", "/api/folders/tree", None, token)["result"]["folders"]
found = any(n["name"] == "스모크테스트" and any(c["name"] == "하위폴더" for c in n["children"]) for n in tree)
results.append(("PASS" if found else "FAIL", "트리 구조: 스모크테스트 > 하위폴더"))

# 4. 이름 변경
call("PATCH", f"/api/folders/{f2}", {"name": "하위폴더-개명"}, token)

# 5. 중복 이름 → FOLDER4002
call("POST", "/api/folders", {"name": "스모크테스트"}, token, expect_code="FOLDER4002")

# 6. 순환 이동 (부모를 자식 밑으로) → FOLDER4003
call("PATCH", f"/api/folders/{f1}", {"parentFolderId": f2}, token, expect_code="FOLDER4003")

# 7. 폴더 삭제(휴지통, 하위 포함)
call("DELETE", f"/api/folders/{f1}", None, token)

# 8. 휴지통 목록 — FOLDER 한 줄(진입점만)
trash = call("GET", "/api/trash", None, token)["result"]["items"]
folder_rows = [i for i in trash if i["type"] == "FOLDER" and i["id"] == f1]
sub_rows = [i for i in trash if i["id"] == f2]
ok = len(folder_rows) == 1 and len(sub_rows) == 0
results.append(("PASS" if ok else "FAIL", f"휴지통: 진입점 폴더만 한 줄 (rows={len(folder_rows)}, 하위노출={len(sub_rows)})"))

# 9. 복원 → 트리에 다시 존재
call("POST", f"/api/trash/{f1}/restore", None, token)
tree2 = call("GET", "/api/folders/tree", None, token)["result"]["folders"]
found2 = any(n["name"] == "스모크테스트" and any(c["name"] == "하위폴더-개명" for c in n["children"]) for n in tree2)
results.append(("PASS" if found2 else "FAIL", "복원 후 트리 구조 유지 (하위 개명 포함)"))

# 10. 다시 삭제 → 완전 삭제 → 트리·휴지통 모두 없음
call("DELETE", f"/api/folders/{f1}", None, token)
call("DELETE", f"/api/trash/{f1}", None, token)
tree3 = call("GET", "/api/folders/tree", None, token)["result"]["folders"]
trash3 = call("GET", "/api/trash", None, token)["result"]["items"]
gone = not any(n["name"] == "스모크테스트" for n in tree3) and not any(i["id"] == f1 for i in trash3)
results.append(("PASS" if gone else "FAIL", "완전 삭제 후 흔적 없음"))

# 11. 에러 경로: 없는 작업 벌크 삭제 / 이름 변경
call("POST", "/api/jobs/trash", {"jobIds": ["job_000000000000_deadbeef00"]}, token, expect_code="JOB4001")
call("PATCH", "/api/jobs/job_000000000000_deadbeef00", {"fileName": "x"}, token, expect_code="JOB4001")

# 결과 출력
fails = [r for r in results if r[0] == "FAIL"]
for status, desc in results:
    print(f"[{status}] {desc}")
print(f"\n총 {len(results)}건 중 PASS {len(results)-len(fails)} / FAIL {len(fails)}")
sys.exit(1 if fails else 0)
