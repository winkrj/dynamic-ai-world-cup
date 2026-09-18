# 작업 순서

역할 두 개의 내부 backlog다. A/B 사이의 인력 재배정 목록이 아니다. 각 티켓은 관련 AC, 실행 검증, 한계를 남기고 종료한다.

## 현재 첫 티켓

**CE-001 — Candidate Quality hard gate와 계약 기반** · B · 완료 (2026-09-12)

목표: 검증되지 않거나 잘못된 후보셋을 READY로 내보낼 수 없도록 validation boundary를 구현한다.

범위: immutable Java 입력 모델, 계획/N/중복/unit/coverage/hard assessment/grounding/semantic review gate, 구조화 실패 code, 공용 preview fixture와 Java/TS contract check. Spring health 및 React 실행 shell, 단일 검증 명령은 기반 작업에 포함한다.

제외: LLM/search adapter, 실제 의미 판정, DB persistence, generation HTTP, 실제 토너먼트 화면. AC-02~06의 **판정 집행**이 대상이다. test fixture는 실제 모델 출력 품질 증거가 아니다.

완료: 관련 실패 사례 JUnit, contract drift check, Java build, frontend production build, 독립 review, 최종 verify가 통과하고 한계 기록.

결과: Java 테스트 42개, 공용 계약 fixture 5개, TS/build/bootJar 통과. 독립 검토에서 발견한 특수 공백/이미지 URL 계약 불일치를 수정했으며 최종 review clean. 상세 기록은 `VERIFICATION.md`.

## A — 플레이 경험

새 담당자는 [프론트 시작 안내](FRONTEND_HANDOFF.md) → [FE-001 상세 티켓](tickets/FE-001.md) 순서로 읽는다. 상태별 동작·오류·완료 체크리스트를 기준으로 착수한다.

| 순서 | 티켓 | 결과 / 검증 | 의존 |
| --- | --- | --- | --- |
| 1 | FE-001 입력·강수·전체 미리보기 | 모바일 화면, loading/error, regen 잔여 1회, API fixture adapter; 360px/키보드 검증 | 계약 v1만 필요 |
| 2 | FE-002 게임 실행 | snapshot→N−1 선택→Champion, 준비 후 7초, timeout/중복 입력/숨김/새로고침 테스트 | fixture snapshot만 필요 |
| 3 | FE-003 결과·공유 | 공유 landing/새 session, Web Share/복사 fallback, 원본 bracket 유지 | mock 먼저, B-003 후 연결 |
| 4 | FE-004 통합·디자인 마감 | 실제 API로 8/16/32 완주, 오류 흐름, 접근성/연출 | B API |

## B — 후보 품질과 서버

| 순서 | 티켓 | 결과 / 검증 | 의존 |
| --- | --- | --- | --- |
| 1 | CE-001 품질 gate | 위 첫 티켓 | 없음 |
| 2 | CE-002 생성 pipeline·실제 eval | plan/structured generation/grounding/독립 assessment/Repair 1회, provider adapter, 실제 8/16/32 결과·비용/시간 기록 | 모델·검색 제공자와 자격정보 |
| 3 | B-002 익명 생성·재생성 | PostgreSQL/Flyway, job worker, cookie, ownership, idempotency, CAS, 비용/속도 제한 | CE-002; provider fake로 먼저 통합 테스트 |
| 4 | B-003 freeze·공유 데이터 | snapshot immutability DB trigger, 새 session, same-bracket share, 동시성/DB 통합 테스트 | B-002 |
| 5 | B-004 기록·CE-003 history | 순차 event 검증/idempotent 저장, timeout 제외, 가중치 개인화 eval | B-003 |
| 6 | EVAL-001 / OPS-001 | blind 평가/telemetry, 같은 origin 배포, 운영 smoke, 제출 evidence | 양쪽 핵심 완성 |

## 오늘부터의 합류 기준

오늘 기준: 기반/CE-001 검증 결과를 main에 남기고 A FE-001 / B CE-002 시작 브랜치를 만든다. A는 실제 provider/API 작업과 독립적으로 fixture 개발을 진행한다. 두 사람이 처음 통합할 시점은 `generation READY → preview → freeze`가 같은 계약 fixture와 일치할 때다. 대회 최종 제출 시각은 현재 원문 요강을 확인하지 않았으므로 이 문서에 단정하지 않는다.

## 2026-09-14 백엔드 API Goal

브랜치: `feat/server/backend-api`. 아래 서버 범위의 구현·검증·독립 리뷰 완료. Java 78개, HTTP 응답 102개 schema 검사, 웹/서버 build 통과. 최종 review Critical 0 / High 0 / 남은 finding 0. main 자동 병합 없이 PR로 전달한다. 상세 결과는 VERIFICATION.md에 기록했다.

- B-002 서버 범위: PostgreSQL/Flyway, 익명 cookie/소유권, strict HTTP, idempotency, actor/IP quota, DB job/lease 복구, preview/성공 재생성 1회.
- B-003 서버 범위: atomic freeze, 원본 session 수렴, DB snapshot 불변성, share/replay 데이터.
- B-004 저장 범위: 8/16/32 순차 선택 검증, atomic batch, 시간 경계/중복 거절, 직접 선택만 최근 50건 읽기, 보존 정리.
- 제외/남은 의존: CE-002 실제 모델·검색·assessment·Repair·usage/비용, CE-003 맥락별 선호 가중치 eval, A의 화면/타이머/RNG/완주 E2E, 배포.

Google 원칙/DDD 적용은 BACKEND_DESIGN.md에 기록했다. 계약 경로/DTO는 변경하지 않는다. 엔진 대역은 dev/test 전용이고 기본 profile은 생성 실패로 닫혀 있다.
