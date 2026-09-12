# 공통 AI 개발 지침

## 먼저 읽기

`PROJECT_CONTEXT.md` → `PRD.md` / `DESIGN_SPEC.md` / `ACCEPTANCE_CRITERIA.md` → 해당 `TECH_DESIGN.md` / `contracts/openapi.json` → 자신의 역할 지침. 출처 한계는 `docs/SOURCE_PROVENANCE.md`, 최신 변경은 `DECISIONS.md`다.

## 큰 역할 두 개

- A: 플레이 경험 전체. `frontend/**` 소유.
- B: 후보 품질과 서버 전체. `backend/**`, `evals/**`, `scripts/**`, CI/루트 빌드 소유.
- 공통 접점은 `contracts/**`. B가 변경 작성, A가 영향 검토. 생성된 `frontend/src/api/schema.d.ts` 직접 수정 금지. 상세는 `docs/COLLABORATION.md`.
- 자신의 AI에게 티켓과 소유 경로를 명시한다. 공유 파일 변경이 필요하면 양쪽 영향부터 기록한다. 구현은 role별 feature branch, 안정 통합은 main.

## 실행 명령

- Node 24 LTS, Java 21. 루트에서 `npm ci`.
- 전체: `./scripts/verify.sh` — contract check, frontend type/build, backend test/bootJar.
- 프론트: `npm run dev:web`; 백엔드: `cd backend && ./gradlew bootRun`.
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
