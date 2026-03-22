# -*- coding: utf-8 -*-
import os
import warnings

warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", message=".*urllib3.*")
warnings.filterwarnings("ignore", message=".*chardet.*")

"""
ocr-service/main.py
FastAPI + Tesseract OCR 닉네임 추출 서비스

사전 요구사항:
  - Tesseract 바이너리 설치 (한국어 언어팩 포함)
    Windows: https://github.com/UB-Mannheim/tesseract/wiki
    Linux:   apt-get install tesseract-ocr tesseract-ocr-kor
  - pip install -r requirements.txt

환경변수 (선택):
  TESSERACT_CMD: Tesseract 바이너리 경로 (기본: PATH에서 자동 탐색)
"""
import asyncio
import base64
import logging
import re
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
import pytesseract
import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

# ── 엔진 설정 ─────────────────────────────────────────────────────────────────

TESSERACT_CMD = os.environ.get("TESSERACT_CMD", "").strip()
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD
log.info("엔진: Tesseract OCR")

_executor = ThreadPoolExecutor(max_workers=1)

app = FastAPI(title="Inven OCR Service")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# ── 상수 ─────────────────────────────────────────────────────────────────────

NICKNAME_RE = re.compile(r"^(?=.*[\uAC00-\uD7A3a-zA-Z])[\uAC00-\uD7A3a-zA-Z0-9]{2,12}$")
LV_RE       = re.compile(r"[Ll][Vv]\.?\s*\d+")
CHAT_TS_RE  = re.compile(r"\[?\d{1,2}:\d{2}\]?")

SERVER_NAMES = {
    "루페온", "카제로스", "아브렐슈드", "실리안", "아만", "카마인", "카단", "니나브", "기타",
}

UI_TEXTS = {
    "참가자", "확인", "거절", "수락", "신청", "모집", "대기", "검색",
    "전체", "파티", "준비", "완료", "시작", "종료", "취소", "닫기",
    "입장", "모든", "연동", "레이드",
    "상세보기", "나가기",
    "상시", "모집중", "습득", "분배",
}

# 텍스트에 포함되면 제외할 UI 키워드 (오독/합쳐진 변형 대응)
UI_KEYWORDS = {
    "참가", "신청", "취소", "모집", "습니다",
}

EXCLUDE_TEXTS = SERVER_NAMES | UI_TEXTS


# ── DTO ───────────────────────────────────────────────────────────────────────

class OcrRequest(BaseModel):
    image: str

class DetectRegion(BaseModel):
    x: int; y: int; w: int; h: int  # 원본 이미지 기준 픽셀 좌표

class OcrResponse(BaseModel):
    nicknames: list[str]                    # 하위 호환 (모든 후보 포함)
    groups: list[list[str]]                 # groups[i][0]=원본, 나머지=자모보정 후보
    engine: str                             # 사용된 엔진 이름
    party_finder_detected: bool             # 신청자 탭 감지 여부
    detect_region: DetectRegion | None = None  # 감지된 콘텐츠 영역 (디버그용)


# ── 전처리 ────────────────────────────────────────────────────────────────────

def preprocess_for_tesseract(img: np.ndarray) -> tuple[np.ndarray, float]:
    """Tesseract OCR용 전처리: CLAHE 대비 강화 + 그레이스케일

    게임 UI 특성: 어두운 배경 + 다양한 색상의 텍스트(흰색, 푸른색, 회색).
    이진화하면 푸른색/회색 텍스트가 소실되므로 이진화 생략.
    Tesseract LSTM(OEM 3)은 그레이스케일 입력을 잘 처리.

    Returns:
        (processed_image, scale_factor)
    """
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # CLAHE로 대비 균일화 (어두운 영역의 텍스트도 살림)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    gray = clahe.apply(gray)

    # 업스케일: 짧은 변 200px 미만이면 2배
    h, w = gray.shape
    scale = 1.0
    if min(h, w) < 200:
        scale = 2.0
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

    return gray, scale


def preprocess_for_content(img: np.ndarray) -> tuple[np.ndarray, float]:
    """콘텐츠 영역 전처리: 반전 + CLAHE + 업스케일

    콘텐츠 영역 특성: 어두운 배경 + 작은 흰색/회색 텍스트
    → 반전하여 밝은 배경 + 어두운 텍스트로 변환 (Tesseract 최적)
    탭 감지와 달리 파란색 등 다양한 색상이 없으므로 반전 안전.
    """
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.bitwise_not(gray)

    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    gray = clahe.apply(gray)

    h, w = gray.shape
    scale = 2.0
    gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

    return gray, scale


# ── 탭 감지 ──────────────────────────────────────────────────────────────────

def _scale_box(bs: dict, factor: float) -> dict:
    """box_stats 딕셔너리의 좌표를 factor배 스케일링"""
    return {
        "x_min": bs["x_min"] * factor, "x_max": bs["x_max"] * factor,
        "y_min": bs["y_min"] * factor, "y_max": bs["y_max"] * factor,
        "x_center": bs["x_center"] * factor, "y_center": bs["y_center"] * factor,
        "width": bs["width"] * factor, "height": bs["height"] * factor,
    }


def _get_tab_boxes(img: np.ndarray) -> list[tuple]:
    """탭 감지 전용 OCR: 전체 이미지에서 (box_stats, text) 쌍 반환

    파티찾기 창이 화면 어디에든 위치할 수 있으므로 전체 이미지 탐색.
    원본 해상도 유지 (축소하면 게임 UI 한글 텍스트가 뭉개짐).
    PSM 11(스파스 텍스트)로 산발적 UI 텍스트 인식.
    """
    h, w = img.shape[:2]
    scale = 1.0

    results: list[tuple] = []
    try:
        processed, ocr_scale = preprocess_for_tesseract(img)
        data = pytesseract.image_to_data(
            processed, lang="kor+eng", config="--psm 11 --oem 3",
            output_type=pytesseract.Output.DICT,
        )
        for i in range(len(data["text"])):
            text = data["text"][i].strip()
            conf = int(data["conf"][i])
            if not text or conf < 0:
                continue
            x = data["left"][i] / ocr_scale
            y = data["top"][i] / ocr_scale
            bw = data["width"][i] / ocr_scale
            bh = data["height"][i] / ocr_scale
            pts = [[x, y], [x + bw, y], [x + bw, y + bh], [x, y + bh]]
            results.append((box_stats(pts), text))

        log.info(f"탭 감지 OCR: {len(results)}개 텍스트 감지")
        for bs, text in results:
            log.info(f"  탭후보: '{text}' x={int(bs['x_min'])}-{int(bs['x_max'])} y={int(bs['y_min'])}-{int(bs['y_max'])}")
    except Exception as e:
        log.warning(f"탭 감지 OCR 오류: {e}")

    if scale != 1.0:
        results = [(_scale_box(bs, 1.0 / scale), text) for bs, text in results]

    return results


def _find_divider(img: np.ndarray, x_start: int, x_end: int,
                   y_start: int, y_end_max: int) -> int | None:
    """신청자 리스트와 채팅 영역 사이의 수평 구분선 위치 반환.

    이동 평균 대비 급격한 밝기 하락 + 가로 연속성 검증으로 구분선 감지.
    높이의 8~80% 범위에서 탐색 (신청자 1명일 때 구분선이 ~20% 위치).
    """
    region = img[y_start:y_end_max, x_start:x_end]
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape

    search_top = int(h * 0.08)
    search_bottom = int(h * 0.8)
    if search_top >= search_bottom:
        return None

    row_means = np.mean(gray[search_top:search_bottom, :], axis=1)

    win = max(5, int(len(row_means) * 0.02))
    kernel = np.ones(win) / win
    smoothed = np.convolve(row_means, kernel, mode='same')

    for i in range(win, len(row_means) - win):
        local_avg = smoothed[i]
        if local_avg < 1:
            continue
        drop_ratio = row_means[i] / local_avg
        if drop_ratio < 0.7:
            row_pixels = gray[search_top + i, :]
            row_median = float(np.median(gray[search_top:search_bottom, :]))
            dark_ratio = np.sum(row_pixels < row_median * 0.5) / w
            if dark_ratio > 0.5:
                divider_y = y_start + search_top + i
                log.info(f"구분선 감지: y={divider_y} (drop={drop_ratio:.2f}, dark={dark_ratio:.2f})")
                return divider_y

    return None


def detect_shinchungja_content(img: np.ndarray, all_boxes: list[tuple] | None = None):
    """
    파티 찾기 창의 "신청자" 탭이 활성화된 경우 (콘텐츠 이미지, x, y, w, h) 튜플 반환.
    탭 미감지 또는 신청자 탭 비활성 시 None 반환.

    탭 바 ("참가자 | 신청자 | 모집 설정") 위치를 앵커로 사용.
    활성 탭 판별: 탭 배경 픽셀 밝기 비교 (신청자 탭이 타 탭보다 +20 이상 밝을 때 활성)
    """
    if all_boxes is None:
        all_boxes = _get_tab_boxes(img)

    # 1단계: 모든 탭 텍스트 후보 수집 (같은 텍스트가 여러 위치에 나올 수 있음)
    # Tesseract가 "참가자"→"잠가자" 등으로 오독하거나 글자 분리하는 경우 대비
    tab_candidates: dict[str, list[dict]] = {"참가자": [], "신청자": [], "모집 설정": []}
    for stats, text in all_boxes:
        if "참가자" in text or "가자" == text:
            tab_candidates["참가자"].append(stats)
        elif "신청자" in text:
            tab_candidates["신청자"].append(stats)
        elif "모집" in text and ("설정" in text or len(text) <= 4):
            tab_candidates["모집 설정"].append(stats)
        elif text == "설정":
            tab_candidates["모집 설정"].append(stats)

    # 2단계: 같은 Y 레벨의 참가자-모집설정 쌍 찾기 (탭 바 식별)
    tab_boxes: dict[str, dict] = {}
    for p in tab_candidates["참가자"]:
        for m in tab_candidates["모집 설정"]:
            y_tol = max(p["height"], m["height"]) * 2
            if abs(p["y_center"] - m["y_center"]) < y_tol:
                tab_boxes["참가자"] = p
                tab_boxes["모집 설정"] = m
                for s in tab_candidates["신청자"]:
                    if abs(s["y_center"] - p["y_center"]) < y_tol:
                        tab_boxes["신청자"] = s
                        break
                break
        if tab_boxes:
            break

    if "신청자" not in tab_boxes:
        if "참가자" in tab_boxes and "모집 설정" in tab_boxes:
            # 활성 탭의 빛번짐으로 "신청자" 텍스트가 깨짐 → 위치로 추정
            p = tab_boxes["참가자"]
            m = tab_boxes["모집 설정"]
            # 갭 전체가 아닌, 중앙에 다른 탭과 비슷한 너비의 영역만 사용
            gap_center = (p["x_max"] + m["x_min"]) / 2
            tab_width_avg = (p["width"] + m["width"]) / 2
            x_min = gap_center - tab_width_avg / 2
            x_max = gap_center + tab_width_avg / 2
            y_min = min(p["y_min"], m["y_min"])
            y_max = max(p["y_max"], m["y_max"])
            tab_boxes["신청자"] = {
                "x_min": x_min, "x_max": x_max,
                "y_min": y_min, "y_max": y_max,
                "x_center": gap_center,
                "y_center": (y_min + y_max) / 2,
                "width": tab_width_avg,
                "height": y_max - y_min,
            }
            log.info(
                f"탭 감지: 신청자 위치를 참가자-모집설정 사이로 추정 "
                f"x={int(x_min)}-{int(x_max)} y={int(y_min)}-{int(y_max)} "
                f"(탭 너비={int(tab_width_avg)})"
            )
        else:
            log.info("탭 감지: 탭 텍스트 부족 (참가자/모집설정 미발견)")
            return None

    found_tabs = [t for t in ["참가자", "신청자", "모집 설정"] if t in tab_boxes]
    if len(found_tabs) < 2:
        log.info(f"탭 감지: 탭 수 부족 ({len(found_tabs)}개) — 파티 찾기 창 아님")
        return None

    def tab_bg_brightness(stats: dict) -> float:
        """탭 하단 중앙의 밝기를 측정 (활성 탭은 하단에 파란 라인이 있음)"""
        h = img.shape[0]
        w = img.shape[1]
        x_center = int(stats["x_center"])
        tab_h = int(stats["height"])
        # 하단 30% 영역만 샘플링
        y_bottom = min(h, int(stats["y_max"]))
        y_top = max(0, y_bottom - max(tab_h // 3, 4))
        # 중앙 50% 너비만 샘플링
        half_w = max(int(stats["width"]) // 4, 4)
        x1 = max(0, x_center - half_w)
        x2 = min(w, x_center + half_w)
        if x1 >= x2 or y_top >= y_bottom:
            return 0.0
        region = img[y_top:y_bottom, x1:x2]
        gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
        return float(np.mean(gray))

    shin_brightness   = tab_bg_brightness(tab_boxes["신청자"])
    other_tabs        = [t for t in found_tabs if t != "신청자"]
    other_brightness  = float(np.mean([tab_bg_brightness(tab_boxes[t]) for t in other_tabs]))

    log.info(f"탭 밝기 — 신청자: {shin_brightness:.1f}, 기타 평균: {other_brightness:.1f}")

    if shin_brightness < other_brightness + 20:
        log.info("탭 감지: 신청자 탭 비활성")
        return None

    all_stats    = [tab_boxes[t] for t in found_tabs]
    tab_h_avg    = max(int(np.mean([b["height"] for b in all_stats])), 8)
    tab_x_min    = int(min(b["x_min"] for b in all_stats))
    tab_x_max    = int(max(b["x_max"] for b in all_stats))
    tab_width    = tab_x_max - tab_x_min
    x_start      = max(0, tab_x_min - tab_h_avg * 2 - 70)
    x_end        = min(img.shape[1], tab_x_max + int(tab_width * 0.6))
    y_tab_max    = int(max(b["y_max"] for b in all_stats))
    y_start      = y_tab_max + max(2, int(tab_h_avg * 0.2))
    content_width = x_end - x_start
    y_end_search = min(img.shape[0], y_start + int(content_width * 1.0))
    y_end_fallback = min(img.shape[0], y_start + int(content_width * 0.7))
    y_end        = _find_divider(img, x_start, x_end, y_start, y_end_search) or y_end_fallback
    y_end        = max(y_start + 1, y_end - 50)

    if x_start >= x_end or y_start >= y_end:
        log.info("탭 감지: 유효하지 않은 콘텐츠 영역")
        return None

    log.info(
        f"신청자 탭 감지 성공: 콘텐츠 영역 "
        f"x={x_start}-{x_end}, y={y_start}-{y_end} "
        f"({x_end - x_start}×{y_end - y_start}px)"
    )
    return img[y_start:y_end, x_start:x_end], x_start, y_start, x_end - x_start, y_end - y_start


def detect_group_popup(img: np.ndarray, all_boxes: list[tuple] | None = None):
    """'그룹 신청 정보' 팝업 감지 → 콘텐츠 영역 반환.

    상단 앵커: "그룹 신청 정보" (또는 분리된 "그룹"+"신청"+"정보")
    하단 앵커: "수락" 또는 "거절" 버튼
    """
    if all_boxes is None:
        all_boxes = _get_tab_boxes(img)

    # 상단 앵커: "그룹" 포함 텍스트 찾기
    header_box = None
    for stats, text in all_boxes:
        if "그룹" in text and ("신청" in text or "정보" in text):
            header_box = stats
            log.info(f"그룹팝업: 헤더 감지 '{text}' y={int(stats['y_center'])}")
            break

    # 분리된 경우: 같은 Y레벨의 "그룹", "신청", "정보" 조합
    if not header_box:
        kw_boxes = [(s, t) for s, t in all_boxes if t in ("그룹", "신청", "정보")]
        for i, (s1, t1) in enumerate(kw_boxes):
            for s2, t2 in kw_boxes[i + 1:]:
                if t1 == t2:
                    continue
                y_tol = max(s1["height"], s2["height"]) * 2
                if abs(s1["y_center"] - s2["y_center"]) < y_tol:
                    x_min = min(s1["x_min"], s2["x_min"])
                    x_max = max(s1["x_max"], s2["x_max"])
                    y_min = min(s1["y_min"], s2["y_min"])
                    y_max = max(s1["y_max"], s2["y_max"])
                    header_box = {
                        "x_min": x_min, "x_max": x_max,
                        "y_min": y_min, "y_max": y_max,
                        "x_center": (x_min + x_max) / 2,
                        "y_center": (y_min + y_max) / 2,
                        "width": x_max - x_min, "height": y_max - y_min,
                    }
                    log.info(f"그룹팝업: 헤더 조합 감지 '{t1}'+'{t2}' y={int(header_box['y_center'])}")
                    break
            if header_box:
                break

    if not header_box:
        return None

    # 하단 앵커: "수락" 또는 "거절" (헤더보다 아래에 있는 것만)
    footer_box = None
    for stats, text in all_boxes:
        if text in ("수락", "거절") and stats["y_center"] > header_box["y_center"]:
            if footer_box is None or stats["y_center"] > footer_box["y_center"]:
                footer_box = stats

    if not footer_box:
        log.info("그룹팝업: 수락/거절 버튼 미감지")
        return None

    log.info(f"그룹팝업: 하단 앵커 '{text}' y={int(footer_box['y_center'])}")

    # 콘텐츠 영역: 헤더 아래 ~ 수락/거절 위, X는 팝업 너비 추정
    popup_padding = 20
    x_start = max(0, int(min(header_box["x_min"], footer_box["x_min"])) - popup_padding)
    x_end = min(img.shape[1], int(max(header_box["x_max"], footer_box["x_max"])) + popup_padding)
    y_start = int(header_box["y_max"]) + 2
    y_end = int(footer_box["y_min"]) - 2

    if x_start >= x_end or y_start >= y_end:
        log.info("그룹팝업: 유효하지 않은 콘텐츠 영역")
        return None

    log.info(
        f"그룹팝업 감지 성공: 콘텐츠 영역 "
        f"x={x_start}-{x_end}, y={y_start}-{y_end} "
        f"({x_end - x_start}×{y_end - y_start}px)"
    )
    return img[y_start:y_end, x_start:x_end], x_start, y_start, x_end - x_start, y_end - y_start


# ── 박스 통계 ─────────────────────────────────────────────────────────────────

def box_stats(vertices: list) -> dict:
    """4개 꼭짓점 리스트 → 바운딩박스 통계"""
    xs = [p[0] for p in vertices]
    ys = [p[1] for p in vertices]
    return {
        "x_min":    min(xs), "x_max": max(xs),
        "y_min":    min(ys), "y_max": max(ys),
        "y_center": (min(ys) + max(ys)) / 2,
        "x_center": (min(xs) + max(xs)) / 2,
        "height":   max(ys) - min(ys),
        "width":    max(xs) - min(xs),
    }


def is_valid_nick(text: str) -> bool:
    if NICKNAME_RE.match(text) is None:
        return False
    if text in EXCLUDE_TEXTS:
        return False
    if CHAT_TS_RE.search(text):
        return False
    if any(sv in text for sv in SERVER_NAMES):
        return False
    if any(kw in text for kw in UI_KEYWORDS):
        return False
    return True


# ── 닉네임 추출 전략 ──────────────────────────────────────────────────────────

def _retry_nick_crop(content_img: np.ndarray, lv_box: dict, row_h: float) -> str | None:
    """Lv.XX 오른쪽 닉네임 영역을 크롭하여 한국어 전용 OCR 재시도"""
    if content_img is None:
        return None
    h, w = content_img.shape[:2]
    x1 = max(0, int(lv_box["x_max"]) + 2)
    x2 = min(w, x1 + int(row_h * 15))  # 닉네임 최대 너비 추정
    y1 = max(0, int(lv_box["y_min"]) - int(row_h * 0.5))
    y2 = min(h, int(lv_box["y_max"]) + int(row_h * 0.5))
    if x2 - x1 < 10 or y2 - y1 < 5:
        return None

    crop = content_img[y1:y2, x1:x2]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    gray = cv2.bitwise_not(gray)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    gray = clahe.apply(gray)
    # 3배 업스케일
    scale = 3.0
    gray = cv2.resize(gray, (int(gray.shape[1] * scale), int(gray.shape[0] * scale)),
                       interpolation=cv2.INTER_CUBIC)

    try:
        # PSM 7 (단일 텍스트 라인) + 한국어 전용
        result = pytesseract.image_to_string(
            gray, lang="kor", config="--psm 7 --oem 3"
        ).strip()
        # 공백/특수문자 제거
        result = re.sub(r"[^가-힣a-zA-Z0-9]", "", result)
        if result and is_valid_nick(result):
            log.info(f"  [Lv재시도] '{result}' (크롭 x={x1}-{x2} y={y1}-{y2})")
            return result
    except Exception as e:
        log.warning(f"  [Lv재시도 실패] {e}")
    return None


def strategy_lv_right(boxes: list, content_img: np.ndarray = None) -> list[str]:
    """전략 1: Lv.XX 오른쪽 같은 Y 레벨에서 닉네임 탐색"""
    found: list[str] = []
    for box, text, conf in boxes:
        if not LV_RE.search(text):
            continue

        # Case A: "Lv.70 비르깃" 처럼 같은 박스에 닉네임 포함
        after_lv = LV_RE.sub("", text).strip()
        if after_lv and is_valid_nick(after_lv) and conf > 0.2:
            if after_lv not in found:
                found.append(after_lv)
                log.info(f"  [Lv내부] '{after_lv}'")
            continue

        # Case B: Lv와 닉네임이 별도 박스
        lv_y     = box["y_center"]
        lv_x_max = box["x_max"]
        row_h    = max(box["height"], 8)

        candidates = []
        for ob, ot, oc in boxes:
            if ot == text:
                continue
            if abs(ob["y_center"] - lv_y) > row_h * 1.5:
                continue
            if ob["x_min"] < lv_x_max - 5:
                continue
            if is_valid_nick(ot) and oc > 0.2:
                candidates.append((ob["x_min"], ot))

        if candidates:
            candidates.sort(key=lambda c: c[0])
            nick = candidates[0][1]
            if nick not in found:
                found.append(nick)
                log.info(f"  [Lv오른쪽] '{nick}'")
        else:
            # Case C: 닉네임을 못 찾은 경우 → 크롭 재시도
            nick = _retry_nick_crop(content_img, box, row_h)
            if nick and nick not in found:
                found.append(nick)
    return found


def strategy_icon_adjacent(boxes: list, content_width: int) -> list[str]:
    """전략 2: 클래스 아이콘 오른쪽에 위치한 닉네임 (Lv 패턴 없는 레이아웃)

    클래스 아이콘은 OCR로 감지 불가하므로, 콘텐츠 영역 왼쪽 3~20% 범위에서
    시작하는 텍스트를 아이콘 옆 닉네임 후보로 판별.
    """
    icon_zone_min = content_width * 0.03
    icon_zone_max = content_width * 0.20

    found: list[str] = []
    for box, text, conf in boxes:
        if LV_RE.search(text):
            continue
        if text in SERVER_NAMES:
            continue
        if icon_zone_min < box["x_min"] < icon_zone_max:
            if is_valid_nick(text) and conf > 0.3:
                if text not in found:
                    found.append(text)
                    log.info(f"  [아이콘옆] '{text}'")
    return found


def strategy_server_below(boxes: list) -> list[str]:
    """전략 3: 서버명 바로 아래 = 닉네임"""
    found: list[str] = []
    for box, text, _ in boxes:
        if text not in SERVER_NAMES:
            continue
        srv_y   = box["y_center"]
        srv_ctr = box["x_center"]
        row_h   = max(box["height"], 8)

        candidates = []
        for ob, ot, oc in boxes:
            if ot == text:
                continue
            if not (row_h * 0.3 < ob["y_center"] - srv_y < row_h * 3.0):
                continue
            if abs((ob["x_min"] + ob["x_max"]) / 2 - srv_ctr) > box["width"] * 1.5:
                continue
            if is_valid_nick(ot) and oc > 0.2:
                candidates.append((ob["y_center"], ot))

        if candidates:
            candidates.sort(key=lambda c: c[0])
            nick = candidates[0][1]
            if nick not in found:
                found.append(nick)
                log.info(f"  [서버명아래] '{nick}'")
    return found


def strategy_row_cluster(boxes: list) -> list[str]:
    """전략 4 (폴백): 행 클러스터링 후 유효 닉네임 탐색"""
    if not boxes:
        return []
    sorted_b = sorted(boxes, key=lambda b: b[0]["y_center"])
    rows: list[list] = []
    cur = [sorted_b[0]]
    for item in sorted_b[1:]:
        if abs(item[0]["y_center"] - cur[-1][0]["y_center"]) < cur[-1][0]["height"] * 2.0:
            cur.append(item)
        else:
            rows.append(cur)
            cur = [item]
    rows.append(cur)

    found: list[str] = []
    for row in rows:
        cands = [(b["x_min"], t) for b, t, c in row if is_valid_nick(t) and c > 0.35]
        if cands:
            cands.sort()
            nick = cands[0][1]
            if nick not in found:
                found.append(nick)
                log.info(f"  [행클러스터] '{nick}'")
    return found


def extract_nicknames_from_boxes(boxes: list, content_width: int = 0,
                                 content_img: np.ndarray = None) -> list[list[str]]:
    """boxes → 닉네임 그룹 추출 (4단계 전략 적용)"""
    log.info(f"Tesseract 감지: {len(boxes)}개 텍스트")

    # 취소된 행 감지: "확인", "취소", "했습니다" 등 취소 관련 텍스트가 있는 Y영역 제외
    CANCEL_KEYWORDS = {"확인", "취소", "했습니다", "했습니다."}
    cancel_y_ranges: list[tuple[float, float]] = []
    for box, text, conf in boxes:
        if text in CANCEL_KEYWORDS or text.endswith("했습니다"):
            row_h = max(box["height"], 8)
            y_min = box["y_center"] - row_h * 3
            y_max = box["y_center"] + row_h * 3
            cancel_y_ranges.append((y_min, y_max))
            log.info(f"  [취소행 감지] '{text}' y={int(box['y_center'])} → y={int(y_min)}-{int(y_max)} 제외")

    if cancel_y_ranges:
        filtered = []
        for box, text, conf in boxes:
            in_cancel = any(y_min <= box["y_center"] <= y_max for y_min, y_max in cancel_y_ranges)
            if in_cancel:
                log.info(f"  [취소행 제외] '{text}'")
            else:
                filtered.append((box, text, conf))
        boxes = filtered

    # 모든 전략을 누적 실행 (하나가 찾아도 나머지 계속 실행)
    nicks: list[str] = []
    nicks.extend(strategy_lv_right(boxes, content_img))
    if content_width > 0:
        nicks.extend(strategy_icon_adjacent(boxes, content_width))
    nicks.extend(strategy_server_below(boxes))
    if not nicks:
        nicks.extend(strategy_row_cluster(boxes))

    seen: set[str] = set()
    groups: list[list[str]] = []
    for nick in nicks:
        if nick not in seen:
            seen.add(nick)
            groups.append([nick])

    log.info(f"최종: {[g[0] for g in groups]}")
    return groups


# ── Tesseract OCR 엔진 ────────────────────────────────────────────────────────

def _parse_tesseract_data(data: dict, scale: float = 1.0) -> list[tuple]:
    """pytesseract.image_to_data() 결과 → 공통 boxes 형식 변환

    Tesseract가 한글을 글자 단위로 쪼개는 경우가 있으므로,
    같은 Y레벨에서 X좌표가 인접한 한글 조각을 병합한다.
    """
    raw: list[tuple] = []
    for i in range(len(data["text"])):
        text = data["text"][i].strip()
        conf = int(data["conf"][i])
        if not text or conf < 0:
            continue
        conf_normalized = conf / 100.0
        x = data["left"][i] / scale
        y = data["top"][i] / scale
        w = data["width"][i] / scale
        h = data["height"][i] / scale
        raw.append((x, y, w, h, text, conf_normalized))

    # 같은 Y레벨 + X 인접한 한글 조각 병합
    merged: list[tuple] = []
    used = [False] * len(raw)
    for i, (x1, y1, w1, h1, t1, c1) in enumerate(raw):
        if used[i]:
            continue
        # 한글 1글자 조각인 경우 인접 조각 탐색
        group_texts = [t1]
        group_x_min, group_x_max = x1, x1 + w1
        group_y_min, group_y_max = y1, y1 + h1
        group_confs = [c1]
        used[i] = True

        nick_char_re = re.compile(r"^[\uAC00-\uD7A3a-zA-Z0-9]+$")
        if len(t1) <= 3 and nick_char_re.match(t1):
            changed = True
            while changed:
                changed = False
                for j, (x2, y2, w2, h2, t2, c2) in enumerate(raw):
                    if used[j]:
                        continue
                    if not nick_char_re.match(t2):
                        continue
                    # 같은 Y레벨 확인
                    y_center1 = (group_y_min + group_y_max) / 2
                    y_center2 = y2 + h2 / 2
                    max_h = max(group_y_max - group_y_min, h2)
                    if abs(y_center1 - y_center2) > max_h * 0.5:
                        continue
                    # X 인접 확인 (겹치거나 가까움)
                    gap = max(0, x2 - group_x_max, group_x_min - (x2 + w2))
                    if gap > max_h * 0.5:
                        continue
                    # 병합
                    group_texts.append(t2)
                    group_x_min = min(group_x_min, x2)
                    group_x_max = max(group_x_max, x2 + w2)
                    group_y_min = min(group_y_min, y2)
                    group_y_max = max(group_y_max, y2 + h2)
                    group_confs.append(c2)
                    used[j] = True
                    changed = True

        final_text = "".join(group_texts)
        final_conf = sum(group_confs) / len(group_confs)
        final_w = group_x_max - group_x_min
        final_h = group_y_max - group_y_min
        merged.append((group_x_min, group_y_min, final_w, final_h, final_text, final_conf))

    boxes: list[tuple] = []
    for x, y, w, h, text, conf_normalized in merged:
        stats = {
            "x_min": x, "x_max": x + w,
            "y_min": y, "y_max": y + h,
            "x_center": x + w / 2,
            "y_center": y + h / 2,
            "width": w, "height": h,
        }
        valid = is_valid_nick(text)
        mark = "[닉네임후보]" if valid else "[제외]     "
        log.info(
            f"  {mark} '{text}' conf={conf_normalized:.2f} "
            f"x={int(x)}-{int(x + w)} y={int(y)}-{int(y + h)}"
        )
        boxes.append((stats, text, conf_normalized))
    return boxes


def run_tesseract_ocr(img: np.ndarray, content_width: int = 0) -> list[list[str]]:
    """Tesseract OCR 실행 (PSM 이중 실행 전략)"""
    processed, scale = preprocess_for_content(img)

    # 1차: PSM 6 (균일 텍스트 블록)
    data = pytesseract.image_to_data(
        processed, lang="kor+eng", config="--psm 6 --oem 3",
        output_type=pytesseract.Output.DICT,
    )
    boxes = _parse_tesseract_data(data, scale)
    groups = extract_nicknames_from_boxes(boxes, content_width, content_img=img)

    if not groups:
        # 2차: PSM 11 (스파스 텍스트)
        log.info("PSM 6 결과 없음, PSM 11로 재시도")
        data = pytesseract.image_to_data(
            processed, lang="kor+eng", config="--psm 11 --oem 3",
            output_type=pytesseract.Output.DICT,
        )
        boxes = _parse_tesseract_data(data, scale)
        groups = extract_nicknames_from_boxes(boxes, content_width, content_img=img)

    return groups


# ── OCR 메인 실행 ─────────────────────────────────────────────────────────────

def run_ocr_sync(image_b64: str) -> tuple[list[list[str]], str, bool, tuple | None]:
    raw = image_b64
    if "," in raw:
        raw = raw.split(",", 1)[1]

    nparr = np.frombuffer(base64.b64decode(raw), np.uint8)
    img   = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if img is None:
        return [], "error", False, None

    log.info(f"이미지 처리: {img.shape[1]}x{img.shape[0]}")

    # 탭 감지 OCR 1회 실행, 결과를 두 감지 함수에 공유
    all_boxes = _get_tab_boxes(img)

    # 1순위: 그룹 신청 정보 팝업 (모달이므로 우선)
    result = detect_group_popup(img, all_boxes)
    if result is None:
        # 2순위: 신청자 탭
        result = detect_shinchungja_content(img, all_boxes)
    if result is None:
        return [], "not_detected", False, None

    content_img, rx, ry, rw, rh = result
    region = (rx, ry, rw, rh)

    groups = run_tesseract_ocr(content_img, content_width=rw)
    return groups, "tesseract", True, region


# ── 엔드포인트 ───────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status": "ok",
        "engine": "tesseract",
    }


@app.post("/ocr", response_model=OcrResponse)
async def ocr(req: OcrRequest):
    try:
        loop                               = asyncio.get_event_loop()
        groups, engine, detected, region   = await loop.run_in_executor(_executor, run_ocr_sync, req.image)
        all_nicks                          = [n for g in groups for n in g]
        detect_region = DetectRegion(x=region[0], y=region[1], w=region[2], h=region[3]) if region else None
        return OcrResponse(
            nicknames=all_nicks, groups=groups,
            engine=engine, party_finder_detected=detected,
            detect_region=detect_region,
        )
    except Exception as e:
        log.error(f"OCR 오류: {e}", exc_info=True)
        return OcrResponse(nicknames=[], groups=[], engine="error", party_finder_detected=False)


@app.post("/debug")
async def debug_ocr(req: OcrRequest):
    """원시 OCR 결과 확인용 (전처리 전후 비교 포함)"""
    try:
        raw = req.image
        if "," in raw:
            raw = raw.split(",", 1)[1]
        nparr = np.frombuffer(base64.b64decode(raw), np.uint8)
        img   = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            return {"error": "decode failed"}

        result: dict = {
            "original_size": {"w": img.shape[1], "h": img.shape[0]},
            "engine": "tesseract",
        }

        processed, scale = preprocess_for_tesseract(img)
        result["processed_size"] = {
            "w": processed.shape[1],
            "h": processed.shape[0],
        }
        result["scale_factor"] = scale

        data = pytesseract.image_to_data(
            processed, lang="kor+eng", config="--psm 6 --oem 3",
            output_type=pytesseract.Output.DICT,
        )
        items = []
        for i in range(len(data["text"])):
            text = data["text"][i].strip()
            conf = int(data["conf"][i])
            if not text or conf < 0:
                continue
            x = data["left"][i] / scale
            y = data["top"][i] / scale
            w = data["width"][i] / scale
            h = data["height"][i] / scale
            items.append({
                "text": text, "conf": round(conf / 100.0, 3),
                "x": int(x), "y": int(y),
                "w": int(w), "h": int(h),
                "is_nick": is_valid_nick(text),
            })
        result["texts"] = items

        return result
    except Exception as e:
        return {"error": str(e)}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
