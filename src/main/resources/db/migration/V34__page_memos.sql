-- page_memos: 줄 메모 (2026-10-13).
--
-- 점역사가 텍스트·점자 각 줄에 남기는 메모. 화면에서는 말풍선으로 뜨고, 최종 저장 때 .smj 파일에도 함께 담긴다.
--
-- 앵커를 "면 기준 줄 번호"가 아니라 "블록 + 블록 안 줄 번호"로 잡는다.
--   블록이 List<String>으로 줄 배열을 들고 있어 자연스러운 주소이기도 하고,
--   줄당 칸 수·페이지당 줄 수를 바꾸면 면이 다시 나뉘어 면 기준 번호는 전부 밀리기 때문이다.
-- line_text는 그 줄의 원문 앞부분(메모 내용이 아니다) — 긴 줄이 쪼개져 line_index가 어긋났을 때
--   FE가 이것으로 다시 찾는다. 없어도 동작하지만 조판 설정을 바꾼 뒤 메모가 엉뚱한 줄에 남는다.
--
-- page_id로 찾는다(쪽 번호가 아니라) — 번호는 그때그때의 표시값이고 키로 쓸 만큼 안정적이지 않다.
-- FK는 걸지 않는다: 기존 테이블들과 같은 방침이고, 삭제 순서를 코드가 관리한다.

CREATE TABLE IF NOT EXISTS page_memos (
    id          bigserial    PRIMARY KEY,
    page_id     uuid         NOT NULL,
    panel       varchar(16)  NOT NULL,   -- TEXT(.txt) | BRAILLE(.brf) — 텍스트·점자 블록이 같은 id를 쓰므로 이 값으로 가른다
    element_id  varchar(64)  NOT NULL,   -- 메모가 붙은 블록
    line_index  int          NOT NULL,   -- 블록 안 몇 번째 줄 (0-based)
    line_text   varchar(80),             -- 그 줄의 원문 앞부분 — 줄이 밀렸을 때 재탐색용
    body        text         NOT NULL,   -- 사용자가 쓴 메모
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_page_memos_page_panel ON page_memos (page_id, panel);
