FROM eclipse-temurin:21-jre

# HWP → PDF 변환 도구 (mode a HWP 지원, 2026-08-24)
# - pyhwp: HWP 5.x → ODT (자체 파서, 자바로 대체 불가)
# - libreoffice-writer: ODT → PDF 렌더링 (headless)
# - poppler-utils: pdftoppm — PDF 썸네일 첫 장 렌더(JPEG 2000 스캔본 지원, 2026-08-27)
# - fonts-noto-cjk·fonts-nanum: 한글 폰트 — 없으면 글자가 □로 렌더됨
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 python3-pip \
        libreoffice-writer \
        poppler-utils \
        fonts-noto-cjk fonts-nanum \
    && pip3 install --no-cache-dir --break-system-packages pyhwp six \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY scripts/hwp2odt.py hwp2odt.py
COPY build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
