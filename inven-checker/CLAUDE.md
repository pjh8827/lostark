# CLAUDE.md (글로벌)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 절대 규칙

- 항상 한국어로 답변
- Plan Mode 없이 절대 코드 수정 금지
- 커밋 전 반드시 테스트 실행

---

## 아키텍처

로스트아크 인벤 신고 게시판에서 특정 닉네임이 신고된 게시글을 검색하는 풀스택 애플리케이션.

### 서비스 구성

```
frontend/       React + TypeScript (포트 5173)
    ↕ /api 프록시
backend/        Java Spring Boot (포트 8080)
    ↕ HTTP
ocr-service/    Python FastAPI (포트 8000)
```

### 전체 데이터 흐름

```
[검색 모드]
frontend → GET /api/search?nickname=XXX&apiKey=optional → backend
  ├─ 기본: 인벤 게시판 스크래핑 → BoardPost[]
  └─ 확장(API 키): 계정 내 캐릭터 조회 → 상위 6개 병렬 검색 → 중복 제거

[실시간 스캔 모드]
frontend 화면공유 캡처 → ocr-service OCR → 닉네임 추출
  → backend 검색 → frontend PiP 오버레이 표시
```

---

## 도메인 컨텍스트

| 용어 | 설명 |
|---|---|
| 신고 게시글 | 인벤 신고 게시판(`m.inven.co.kr/board/lostark/5355`)의 게시글 |
| 확장 검색 | Lost Ark API 키로 같은 계정의 모든 캐릭터를 함께 검색 |
| ROI | 실시간 스캔 시 닉네임을 추출할 화면 관심 영역 |
| matchedNickname | 확장 검색에서 어떤 캐릭터로 게시글이 매칭됐는지 표시 |

---

## 코딩 컨벤션

- 커밋 메시지: 한국어, 명령형 동사로 시작 (예: `검색 로직 수정`, `OCR 서비스 추가`)
- 각 서비스별 세부 컨벤션은 해당 디렉토리의 CLAUDE.md 참고
