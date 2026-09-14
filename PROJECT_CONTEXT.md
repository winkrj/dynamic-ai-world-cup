# 프로젝트 컨텍스트

갱신일: 2026-09-14.

목적: 좋은 후보군과 빠른 A/B 선택으로 결정 비용을 줄이는 Dynamic AI World Cup. Candidate Quality가 우선이다.

## 현재 구조와 상태

- 단일 저장소, `frontend` React/TypeScript/Vite, `backend` Java 21/Spring Boot 4.1.1. PostgreSQL 17/JDBC/Flyway 구현. 로컬 compose와 격리된 Testcontainers DB 검증을 사용한다.
- 큰 역할 A=플레이 경험, B=후보 품질과 서버. 제품 화면 작업과 서버 작업을 두 역할 안에서 계속 진행한다.
- `contracts/openapi.json` v1.0.0, 공유 fixture, 자동생성 TS, Java preview 직렬화 비교 테스트.
- CE-001 구현: fixed plan, candidate count/duplicate/unit/coverage, hard assessment, grounded evidence validity, independent semantic-review gate. 검증 통과 타입만 preview mapper에 넘긴다.
- 실제 HTTP 구현: health, 익명 생성 job/preview/전체 재생성, freeze/snapshot, 선택 batch, 공유/새 세션 API. cookie 소유권, idempotency, rate limit, worker lease/복구, 보존 정리 포함.
- 프론트는 개발 상태와 고정 fixture를 보여주는 초기 shell이다. 실제 provider/search, 게임/공유 화면과 배포는 미구현이다.
- `CandidateEngine` port로 엔진과 서버를 분리했다. dev의 합성 데이터는 개발용 표시, 기본/운영 미연결 엔진은 실패 처리한다. 개인화는 직접 선택 기록 최대 50건을 읽는 경계까지이며 실제 가중치 품질은 CE-003에서 평가한다.

## 검증

CE-001 완료. `./scripts/verify.sh` 통과: 공용 fixture 5개와 생성 타입, TS/production build, Java 42개 테스트(실패/오류 0), bootJar. 독립 Reviewer 2회 중 최초 Medium 1/Low 1을 수정했고 최종 Critical 0 / High 0, 남은 finding 없음. 초기 화면은 360px/1280px에서 가로 넘침과 console error가 없음을 확인했다. 상세는 `docs/VERIFICATION.md`다.

GitHub: https://github.com/winkrj/dynamic-ai-world-cup (public, winkrj). 2026-09-14 사용자 승인으로 공개 전환했으며 인증 없이 저장소와 브랜치 조회를 확인했다. 링크만으로 clone하고 개발을 시작할 수 있다. 원본 쓰기 권한이 없는 참여자는 Fork → 개발 → PR을 사용한다. 구체적인 합류 순서는 README와 `docs/COLLABORATION.md`에 있다.

초기 검증 당시 시스템 기본 Node는 23이므로 별도로 받은 임시 Node 24.21.0을 사용했다. 팀원은 `.nvmrc`에 맞는 Node 24를 준비한다. 전역 Node 설정은 변경하지 않았다.

2026-09-14 API Goal 브랜치 `feat/server/backend-api`: 구현·최종 verify·독립 review 완료. Java 78개, 실제 HTTP 응답 102개/8개 schema, 기존 fixture 5개, 웹 build, bootJar 통과. 리뷰 1회차 Medium 2개를 수정했고 2회차 Critical 0 / High 0 / 남은 finding 0. 실제 dev 서버의 자동 worker·8강 기록·공유 replay도 확인했다. 아직 main에 병합한 상태는 아니며 실제 엔진/프론트 완성·배포 완료를 의미하지 않는다. 상세는 `docs/VERIFICATION.md`다.

## 다음 작업

- A: [프론트 시작 안내](docs/FRONTEND_HANDOFF.md)와 [FE-001 상세 티켓](docs/tickets/FE-001.md)에서 입력·강수·미리보기를 시작한다. PR #1 미병합 시점에는 `feat/server/backend-api`를 내려받고 새 `feat/play/fe-001-integration` 브랜치를 만든다. 이전 `feat/play/fe-001-preview`는 초기 기반이라 최신 서버가 없다. fixture로 화면을 만들거나 로컬 dev API로 연동할 수 있다.
- B: `feat/engine/ce-002-generation`에서 실제 plan/생성/grounding/독립 assessment/Repair adapter와 eval.
- 모델/검색 제공자와 API 자격정보는 CE-002 실행 전 선택한다. 원래 PRD/Design/AC/Decision 첨부 원문을 확보하면 복원본과 대조한다.
- GitHub collaborator 초대는 역할별 실제 계정이 정해진 뒤 한다. 현 단계에서 팀원 초대/branch protection을 완료했다고 주장하지 않는다.
- 외부 공유 개발 API/Swagger UI와 최종 화면 시안은 아직 없다. OpenAPI JSON·생성 TS 타입·fixture가 전달물이며, `npm run api:smoke`는 로컬 dev API의 생성부터 공유 replay까지 연결을 확인한다. 실제 AI 품질이나 프론트 완주 UI 테스트를 대신하지 않는다.

핵심 동시성/게임 정책과 기술 선택은 `TECH_DESIGN.md`, 결정 변경은 `DECISIONS.md`, source 한계는 `docs/SOURCE_PROVENANCE.md`에 남긴다.
