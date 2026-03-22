# CLAUDE.md (frontend)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 아키텍처

### 주요 컴포넌트

```
src/
├── App.tsx           # 검색 탭 UI (닉네임 입력, API 키 입력, 결과 테이블)
├── ScannerPanel.tsx  # 실시간 스캔 탭 UI (화면 공유, ROI 조정, PiP 오버레이)
├── types.ts          # 공유 TypeScript 타입 (모든 API 타입은 여기서 관리)
└── main.tsx
```

### 탭별 역할

**App.tsx (검색 탭)**
- 닉네임 입력 유효성 검사: 2~12자, 한글/영문/숫자만 허용
- API 키 입력 시 확장 검색 모드 활성화 → `matchedNickname` 컬럼 추가 표시
- `/api/search` 호출 (`vite.config.ts`에서 `localhost:8080`으로 프록시)

**ScannerPanel.tsx (스캔 탭)**
- `navigator.mediaDevices.getDisplayMedia()` 로 화면 공유 (16 FPS)
- 슬라이더로 ROI 영역 조정 → 3초마다 OCR 서비스(`localhost:8000`)에 이미지 POST
- `BroadcastChannel`로 PiP 창과 메시지 통신
- Document Picture-in-Picture(Chrome 116+)로 게임 오버레이 표시

---

## 빌드/테스트

```bash
npm install
npm run dev      # 개발 서버 (포트 5173, /api → localhost:8080 프록시)
npm run build    # 프로덕션 빌드 → dist/
npm run preview  # 빌드 결과물 로컬 미리보기
```

---

## 코딩 컨벤션

- API 응답 타입은 반드시 `types.ts`에 정의 후 import해서 사용
- OCR 서비스 URL(`localhost:8000`) 하드코딩 — 환경변수 미사용
- PiP 기능은 Chrome 116+ 전용, 미지원 브라우저 분기 처리 필요
