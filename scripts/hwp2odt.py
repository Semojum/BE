# HWP(5.x) -> ODT 변환 래퍼 (mode a HWP 지원 — HwpToPdfConverter가 호출)
# pyhwp의 RelaxNG 검증을 생략한다: 최신 lxml과의 호환 문제로 검증 단계가 항상 실패하는데,
# 검증은 산출물 품질과 무관(스키마 형식 검사)이라 우회해도 LibreOffice가 정상적으로 읽는다.
# 사용: python3 hwp2odt.py <입력.hwp> <출력.odt>
import sys

from hwp5 import plat

plat.get_relaxng_compile = lambda: None

from hwp5.hwp5odt import main  # noqa: E402  (모듈 로드 전에 검증 우회가 걸려야 함)

sys.argv = ["hwp5odt", "--output", sys.argv[2], sys.argv[1]]
sys.exit(main())
