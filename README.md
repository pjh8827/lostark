# 로스트아크 마켓 제작기

# LostArk Open API 분석 기록 (Markets / 시세 스냅샷)

## 📅 날짜

- 2026-02-03

## 🎯 목표

- LostArk Open API의 **Markets API**를 활용해**특정 종목(itemId)의 시세를 매일 오후 12시(KST)에 스냅샷으로 저장**하는 기능 설계
- Spring Boot 기반 백엔드에서 배치/스케줄러로 수집

---

## 1. Open API 인증 방식 정리

### 인증 방식

- Authorization Header 사용

```
Authorization: bearer {JWT}

```