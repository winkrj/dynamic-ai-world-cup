# 공통 AI 개발 지침

## 최신 추가 범위 — 2026-09-18 TD-39/40

TD-41/v19는 unit을 후보 종류·비교 수준으로 정의하고 constraints/softPreferences의 역할을 공통 프롬프트에서 분리하는 비용 없는 보완이다. 사용자 선택 범위·명시 조건·선호를 보존하고 해석 FAIL/UNKNOWN을 코드에서 완화하지 않는다. 새 유료 호출/DB 재사용 구현 승인으로 해석하지 않는다.

- 사용자가 실험 누적 상한 $6과 검색 없는 v18 hobby-solo/32 1회를 승인했고 **실행 완료**했다. 2회 응답 뒤 사전 해석 UNIT FAIL로 종료, preview/Repair/검색 없음. 추정 $0.0728365, 누적 장부 $4.5240143(미확인 예약 $0.50 포함), 잔여 $1.4759857. 자동 충전 OFF 유지, 새 승인 전 추가 유료 시도 금지. 아래 $5 기록은 이전 실행 당시 기준이다.
- 초기 익명 브라우저별 하루 2회 새 생성/재생성 접수, 서울 자정 초기화를 확정했다. 접수 후 FAILED 포함, 재전송/접수 rollback/Repair/공유 플레이 추가 차감 없음. 기존 burst/IP와 전역 예산 통제를 유지한다. 일일 횟수를 정확한 사람당 식별이나 운영 월 예산 승인으로 혼동하지 않는다.
- DB 우선 후보 재사용은 사용자의 후속 제안으로 TECH_DESIGN 8절에 기록했지만 아직 미구현·미확정이다. 생성됐다는 이유만으로 보류 후보를 공용 승인 pool에 넣거나 무승인 사전 생성·학습·새 DB를 시작하지 않는다.

## 먼저 읽기

`PROJECT_CONTEXT.md` → `PRD.md` / `DESIGN_SPEC.md` / `ACCEPTANCE_CRITERIA.md` → 해당 `TECH_DESIGN.md` / `contracts/openapi.json` → 자신의 역할 지침. 출처 한계는 `docs/SOURCE_PROVENANCE.md`, 최신 변경은 `DECISIONS.md`다.

## 큰 역할 두 개

- A: 플레이 경험 전체. `frontend/**` 소유.
- B: 후보 품질과 서버 전체. `backend/**`, `evals/**`, `scripts/**`, CI/루트 빌드 소유.
- 공통 접점은 `contracts/**`. B가 변경 작성, A가 영향 검토. 생성된 `frontend/src/api/schema.d.ts` 직접 수정 금지. 상세는 `docs/COLLABORATION.md`.
- 자신의 AI에게 티켓과 소유 경로를 명시한다. 공유 파일 변경이 필요하면 양쪽 영향부터 기록한다. 구현은 role별 feature branch, 안정 통합은 main.

## 현재 통합 작업 — 2026-09-18

사용자가 프론트까지 직접 맡기로 하여 A/B 모두 현재 작업 범위다. 더 이상 외부 프론트 인계를 기다리지 않는다. `feat/product/split-deck-flow`에서 아래 책임을 분리해 병렬 작업한다. 같은 checkout을 공유하므로 에이전트는 브랜치 전환·commit·push·타인 파일 수정 없이 담당 변경을 전달하고 통합 담당자만 Git을 조작한다.

| 역할 | 소유/판정 책임 | 넘어서는 안 되는 경계 |
| --- | --- | --- |
| PM / 통합 담당 | 루트 지침·문서·빌드·`frontend/src/app/**`·`main.tsx`, 계약 정합성·최종 검증 | 화면 완주를 후보 품질/배포 승인으로 선언하지 않음 |
| 프론트 상태 담당 | `frontend/src/play/**`, API client/types, 해당 단위 테스트 | React 연출·서버 엔진 변경 없음 |
| 디자이너 / UI 담당 | `frontend/src/ui/**`, style/index, 접근성·반응형 | A 상하 카드 유지, 가짜 API 상태·후보 설명·진행률 발명 금지 |
| 백엔드 담당 | 서버 API/저장 및 HTTP 통합 테스트, 아래 재개 범위의 후보 엔진 | 공개 계약·모델·예산 임의 변경 및 승인 범위 밖 유료 실험 금지 |
| 독립 Reviewer | 변경/테스트를 읽고 결함과 회귀 판정 | 구현자의 동의나 테스트 통과만으로 승인하지 않음; 수정하지 않음 |

- 구현 전 공용 타입/함수/오류·재시도 의미를 합의한다. 소유 파일이 겹치면 동시에 수정하지 말고 통합 담당자에게 구체적인 변경을 요청한다.
- PM은 PRD/AC 누락, 디자이너는 실제 사용 불가, 백엔드는 저장·보안 불변성, 프론트는 상태·복원 경합에 대해 **근거 있는 반대 의견**을 낸다. 취향을 결함으로 포장하거나 무조건 동의하지 않는다. `문제 → 파일/AC 근거 → 사용자 영향 → 가장 작은 대안`으로 설명한다.
- 충돌 우선순위는 최신 명시 사용자 결정 → 제품 불변 조건/AC → OpenAPI wire 계약 → 통합 Spec → 시안 데모다. 제품 규칙이나 계약 의미를 바꾸는 충돌은 통합 담당자가 기록하고 필요할 때만 사용자에게 묻는다.
- 완료 범위는 no-login 입력→8/16/32 preview/재생성→N−1 경기→서버 저장→공유·새 세션이다. 후보 사람 평가 FAIL·외부 사실 eval·운영 배포는 별도 미완료로 남긴다.
- 새로운 유료 호출/키 사용, API 예산 완화, 자동 충전, main 병합/공개 배포는 이번 화면 통합에 포함하지 않는다. 기존 실제 API client를 사용하되 검증은 합성 dev 서버/격리 DB로 한다.

## 엔진 수정 재개 — 2026-09-18

사용자가 수정 보류를 해제했다. 완성된 제품 흐름을 기반으로 `feat/engine/context-feasibility`에서 추가 과금 없는 후보 품질 보완을 진행한다. 위 화면 통합 당시 엔진/프롬프트 동결은 이 범위에서 해제한다. 후보별 필수 접근 전제의 미확인 상태를 별도 내부 판정으로 다루며, 일반 준비물을 모두 미확인으로 거절하거나 특정 취미를 금지하지 않는다. 공개 DTO·모델·호출 수·Repair 합계 1회·비용 예약은 유지한다. 실제 유료 재평가, 사람 품질 승인, main 병합/배포는 이 코드 검증과 구별한다. 기존 사람 평가 FAIL을 자동 PASS로 덮어쓰지 않는다.

## 실행 명령

2026-09-18 품질 기준(TD-38): 사용자는 소음 제한이 없는 요청의 하모니카 반복 연습은 집에서 가능하다고 확인했다. 일반적인 집 안 공간·통상적 개인 연습은 합리적인 기본 전제로 다루고, 미기재만으로 UNKNOWN을 만들지 않는다. 특정 자연환경·전용 시설·특수 장비와는 구분한다. 명시 소음/시간/예산 제한과 기존 UNKNOWN/FAIL 집행은 유지하며 후보명 allowlist로 우회하지 않는다. v18 코드 수정과 후속 TD-39의 실제 단일 평가 승인을 구별한다.

2026-09-18 후속 승인: 사용자가 **검색 없는 실제 32강 평가 1회** 재개를 승인했다. 아래 v17 수정의 단일 재평가만 실행하며 실패해도 재추첨하지 않는다. 이전 모든 사용액을 합산한 누적 $5 한도와 기존 호출 예약 정책·자동 충전 OFF를 유지한다. 이 승인은 추가 검색·반복 실험·공개 무인 운영으로 확대하지 않는다. **실행 결과: 첫 PLAN 호출이 401/expired_secret_key로 종료되어 승인한 시도는 사용했다.** 키 재발급·재개 전까지 추가 호출하지 않는다. $0.50 미확인 비용 예약은 임의로 지우지 않는다.

같은 날 추가 승인(TD-37): 사용자가 만료 키 교체·연결 후 같은 `hobby-solo/32`, v17 평가의 **추가 시도 1회**를 승인했고, 새 비공개 설정 저장 확인 후 실행을 마쳤다. **추가 승인도 사용 완료**다. 6회 응답은 HTTP 200이지만 최종 feasibility의 하모니카 연습 환경 UNKNOWN으로 `QUALITY_GATE_FAILED`, preview 없음, 사전 Repair 1회/검색 0회다. 추가 추정 $0.218444, 누적 장부 $4.4511778(미확인 예약 $0.50 포함), 잔여 $0.5488222. 새 승인 전 유료 재시도 금지. 검색·예약 완화·자동 충전 활성화·품질 승인으로 확대하지 않는다. 키는 채팅/로그/Git에 남기지 않는다.

- Node 24 LTS, Java 21. 루트에서 `npm ci`.
- 전체: `./scripts/verify.sh` — contract check, frontend test/type/build, backend test/bootJar/appJar.
- 프론트 상태/HTTP 단위 테스트: `npm run test:web` (Node 24 내장 test runner, 유료 호출 없음).
- 프론트: `npm run dev:web`; 백엔드: 루트 `docker compose up -d --wait postgres` 후 `cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev'`.
- 프론트 착수: `docs/FRONTEND_HANDOFF.md`와 `docs/tickets/FE-001.md`. API 연결: dev 서버 실행 후 루트 `npm run api:smoke` (합성 생성 2회, 로컬 기록 생성). 인수인계 도구 단위 테스트: `npm run test:handoff`.
- 계약: `npm run contracts:generate`, `npm run contracts:check`.
- Java 범위 테스트: `cd backend && ./gradlew test --tests '*CandidateQualityGateTest'`.

## 제품 불변 조건

Candidate Quality 우선. N=8/16/32 정확, hard constraint/grounding 실패 시 노출 금지. Coverage Plan은 질문별 동적. 전체 preview와 전체 재생성 성공 1회, 시작 때 freeze. 시스템 Repair는 별도. 7초/timeout uniform random/Undo 없음/결승 동일. Timeout을 preference에 넣지 않는다. 공유는 같은 snapshot과 새 session, AI 재호출 없음.

## 구현 경계 / 완료 조건

- 현재 티켓 범위와 AC를 명시하고 구현/미구현을 구분한다. synthetic fixture와 실제 provider 실행을 섞지 않는다.
- frontend에는 provider key·history·내부 evidence를 넣지 않는다. 서버가 판정한 품질 통과 없이 preview DTO를 발급하지 않는다.
- 새 프레임워크/DB/큐/계정/실시간 그룹 기능은 현재 설계 밖이다. 문서로만 있는 provider/API를 구현됐다고 보고하지 않는다.
- behavior 변경은 관련 테스트 후 read-only Reviewer 독립 검토, 마지막 verify. Critical/High가 남으면 완료 아님. 동일 실패 fix/retest 최대 3회, review는 최초 포함 2회.
- PR에 변경한 AC/계약, 검증, 실제 provider 호출 유무, 남은 한계를 남긴다. 사용자 prompt/비밀키/개인 데이터를 로그/fixture/commit에 넣지 않는다.
- 서버 API는 `docs/BACKEND_DESIGN.md`의 Google 리소스 설계 적용/호환 예외와 DDD 경계를 따른다. 스타일 통일만을 이유로 공유 API를 깨지 않는다. 실제 엔진 개발과 서버 저장 구현의 접점은 CandidateEngine port이며 dev 대역을 운영 성공으로 사용하지 않는다.
