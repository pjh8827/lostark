# CLAUDE.md (ocr-service)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 아키텍처

### OCR 엔진: Tesseract

```
POST /ocr 요청
  → Tesseract OCR (로컬, 무료, 한국어+영문)
      └─ 전처리: 그레이스케일 → 반전 → 업스케일 → Otsu 이진화
```

사전 요구사항: Tesseract 바이너리 + 한국어 언어팩(kor) 설치 필요

### 닉네임 추출 4단계 전략

1. **Lv.XX 우측 스캔** — "Lv.XX" 텍스트 찾고 같은 Y축 오른쪽에서 닉네임 탐색
2. **아이콘 옆 스캔** — 클래스 아이콘(이미지) 오른쪽 위치의 텍스트에서 닉네임 탐색
3. **서버명 하단 스캔** — 서버명 텍스트 아래에서 닉네임 탐색
4. **행 클러스터링** — Y 좌표 기준으로 텍스트를 행으로 묶어 유효 닉네임 추출

닉네임 유효성: 2~12자, 한글/영문/숫자, UI 텍스트("참가자", "확인", "모집", 서버명 등) 제외

### 엔드포인트

| 엔드포인트 | 용도 |
|---|---|
| `GET /health` | 서비스 상태 확인 |
| `POST /ocr` | 이미지 → `{nicknames, groups, engine}` |
| `POST /debug` | 바운딩박스 포함 원시 OCR 결과 (개발용) |

---

## 빌드/테스트

### Tesseract 설치

```bash
# Windows (UB Mannheim 인스톨러 권장)
# https://github.com/UB-Mannheim/tesseract/wiki
# 설치 시 "Additional language data" → Korean 선택

# Linux
apt-get install tesseract-ocr tesseract-ocr-kor
```

### 실행

```bash
pip install -r requirements.txt
python main.py      # 개발 서버 실행 (포트 8000)
```

### 환경변수 (선택)

```bash
export TESSERACT_CMD=/path/to/tesseract   # Tesseract 바이너리 경로 (PATH에 없을 때)
```

---

## 코딩 컨벤션

- 신뢰도 임계값: 전략별 0.2~0.35 — 낮추면 오탐 증가, 높이면 미탐 증가
- PSM 이중 실행: PSM 6 (균일 블록) → 결과 없으면 PSM 11 (스파스 텍스트) 재시도
- `/debug` 엔드포인트는 프로덕션 배포 시 비활성화 권장
