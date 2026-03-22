# CLAUDE.md (backend)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 아키텍처

### 패키지 구조

```
src/main/java/com/lostark/invenchecker/
├── controller/
│   └── SearchController.java       # GET /api/search 단일 엔드포인트
├── service/
│   ├── InvenSearchService.java     # 인벤 게시판 스크래핑 핵심 로직
│   └── LostArkApiService.java      # Lost Ark Open API 연동
└── model/
    ├── BoardPost.java              # 검색 결과 DTO
    └── CharacterInfo.java          # 캐릭터 정보 DTO
```

### 스레드 풀 구성

| 풀 | 스레드 수 | 용도 |
|---|---|---|
| pageExecutor | 15 | 인벤 페이지 병렬 fetch |
| postExecutor | 10 | 게시글 본문 병렬 검증 |
| characterExecutor | 6 | 확장 검색 시 캐릭터별 병렬 검색 |

스레드 수 변경 시 반드시 부하 테스트 후 적용.

### 닉네임 매칭 전략 (InvenSearchService)

세 가지 전략을 순서대로 시도:
1. "게임 닉네임" 섹션 → "대상:" 하위 섹션 파싱
2. 정규식 템플릿 레이블 패턴 매칭
3. 폴백: 전문 검색 + 단어 경계 정규식

---

## 빌드/테스트

```bash
mvn spring-boot:run                # 개발 서버 실행 (포트 8080)
mvn test                           # 전체 테스트
mvn test -Dtest=ClassName          # 단일 테스트 클래스 실행
mvn clean install                  # 전체 빌드
```

### 테스트 구조

- WireMock으로 인벤 게시판 HTTP 응답 모킹
- HTML 픽스처 위치: `src/test/resources/__files/`
  - `inven-post-with-target.html` — 대상 닉네임 포함 게시글
  - `inven-post-without-target.html` — 대상 닉네임 미포함 게시글
  - `inven-post-multi-target.html` — 대상 닉네임 복수 포함 게시글

---

## 코딩 컨벤션

- 외부 HTTP 요청(JSoup, LostArkApiService)은 반드시 타임아웃 설정
- 모바일 UA(`Mozilla/5.0 ... Mobile`) 유지 — 변경 시 봇 탐지 가능성 있음
- `@CrossOrigin(origins = "*")` 유지 — 개발 환경 프록시 우회용
