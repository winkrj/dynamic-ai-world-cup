# 프로젝트 컨텍스트

갱신일: 2026-09-16.

목적: 좋은 후보군과 빠른 A/B 선택으로 결정 비용을 줄이는 Dynamic AI World Cup. Candidate Quality가 우선이다.

## 현재 구조와 상태

- 단일 저장소, `frontend` React/TypeScript/Vite, `backend` Java 21/Spring Boot 4.1.1. PostgreSQL 17/JDBC/Flyway 구현. 로컬 compose와 격리된 Testcontainers DB 검증을 사용한다.
- 큰 역할 A=플레이 경험, B=후보 품질과 서버. 제품 화면 작업과 서버 작업을 두 역할 안에서 계속 진행한다.
- `contracts/openapi.json` v1.0.0, 공유 fixture, 자동생성 TS, Java preview 직렬화 비교 테스트.
- CE-001 구현: fixed plan, candidate count/duplicate/unit/coverage, hard assessment, grounded evidence validity, independent semantic-review gate. 검증 통과 타입만 preview mapper에 넘긴다.
- 실제 HTTP 구현: health, 익명 생성 job/preview/전체 재생성, freeze/snapshot, 선택 batch, 공유/새 세션 API. cookie 소유권, idempotency, rate limit, worker lease/복구, 보존 정리 포함.
- 프론트는 개발 상태와 고정 fixture를 보여주는 초기 shell이다. 게임/공유 화면과 배포는 미구현이다.
- `CandidateEngine` port 뒤에 CE-002의 OpenAI plan/생성/선택적 hosted search/독립 검토/Repair 최대 1회를 연결했다. `live`는 명시적 키/누적 예산이 필요하며 dev 혼용은 금지한다. dev 합성 후보는 개발용 표시, 기본 profile은 계속 실패 처리한다. 구현과 실제 검증 범위는 `docs/CANDIDATE_ENGINE.md`를 따른다.
- 직접 선택 history 최대 50건 중 관련 있는 선택만 계획에 반영하도록 했다. 이 호출 경계 구현과 실제 개인화 가중치 품질(CE-003)은 구별한다.

## 검증

CE-001 완료. `./scripts/verify.sh` 통과: 공용 fixture 5개와 생성 타입, TS/production build, Java 42개 테스트(실패/오류 0), bootJar. 독립 Reviewer 2회 중 최초 Medium 1/Low 1을 수정했고 최종 Critical 0 / High 0, 남은 finding 없음. 초기 화면은 360px/1280px에서 가로 넘침과 console error가 없음을 확인했다. 상세는 `docs/VERIFICATION.md`다.

GitHub: https://github.com/winkrj/dynamic-ai-world-cup (public, winkrj). 2026-09-14 사용자 승인으로 공개 전환했으며 인증 없이 저장소와 브랜치 조회를 확인했다. 링크만으로 clone하고 개발을 시작할 수 있다. 원본 쓰기 권한이 없는 참여자는 Fork → 개발 → PR을 사용한다. 구체적인 합류 순서는 README와 `docs/COLLABORATION.md`에 있다.

초기 검증 당시 시스템 기본 Node는 23이므로 별도로 받은 임시 Node 24.21.0을 사용했다. 팀원은 `.nvmrc`에 맞는 Node 24를 준비한다. 전역 Node 설정은 변경하지 않았다.

2026-09-14 API Goal 브랜치 `feat/server/backend-api`: 구현·최종 verify·독립 review 완료. Java 78개, 실제 HTTP 응답 102개/8개 schema, 기존 fixture 5개, 웹 build, bootJar 통과. 리뷰 1회차 Medium 2개를 수정했고 2회차 Critical 0 / High 0 / 남은 finding 0. 실제 dev 서버의 자동 worker·8강 기록·공유 replay도 확인했다. 아직 main에 병합한 상태는 아니며 실제 엔진/프론트 완성·배포 완료를 의미하지 않는다. 상세는 `docs/VERIFICATION.md`다.

## 다음 작업

2026-09-16 최신 CE-002/v15: 한 후보가 독립 대안의 메뉴가 되지 않도록 기존 단일 선택 기준을 모든 후보 단계에 공유한다. 한 활동의 예시·장르·보완 단계는 허용하며 퍼즐 세부 유형의 사용자 결정을 대신하지 않는다. v14의 조건별 근거/독립 검토, 사전·상세 Repair **합계 최대 1회**, 시간/비용 한도와 공개 API는 그대로다.

v15의 미실행 일반 취미 3세트를 각각 한 번 확인했다. **solo/8 READY(62.728초/$0.072544), solo/32 READY(189.510초/$0.2141135)**, home/32는 해석·후보 문제로 FAILED(114.110초/$0.1066105)였다. solo/32에서 사전 활동 교체 1회 후 재검토→실제 preview까지 연결됐다. 이전 home/8·16, social/32, solo/16의 READY도 보존하며 버전별 결과는 `docs/CANDIDATE_ENGINE.md`에서 확인한다.

**자동 READY는 품질 최종 승인이 아니다.** solo/32는 스케치/색칠, 독서/주제 탐구, 퍼즐 분할을 통과시켰고 ‘운동은 싫어’를 필수 조건 대신 softPreferences와 unit에만 남긴 해석도 PASS였다. 실제 후보는 비운동 활동이지만 제외 조건의 독립 assessment 누락을 정확히 잡았다는 증거는 아니다. home/32는 원문 30분을 계획이 ‘30분 내외’로 표현한 문제를 두 번째 검토에서야 거절했고 상세 생성/preview 없이 끝났다. 후보 경계와 해석 판정 일관성이 남아 있어 Goal은 미완료다. 같은 사례를 통과할 때까지 재추첨하지 않는다.

서울 접근성 사례는 v14에서도 근거 부족으로 FAILED였다. 후보 선정 시점의 검증 가능성과 유효한 대체 활동 확보가 남아 있으며 이번 v15에서는 유료 검색을 하지 않았다.

v15 최종 `verify`: Java **171개 실행/실패·오류 0(유료 1개 제외)**, handoff 6개, fixture 5개, 실제 HTTP 102개/8개 schema, 웹 build/bootJar 통과. 집중 테스트 120개 후 독립 리뷰 1회 Critical/High/actionable 0, 이후 코드 변경 없이 전체 검증했다. 별도 live HTTP 66개도 schema 적합(실패 응답 포함). 누적 실험 추정 **$3.4827758 / 승인 $5**, 제공자 118회, 미확인 비용 예약 없음, 자동 충전 OFF 유지. seed **11/18세트**의 첫 실행 READY 5/FAILED 6이며 일반 취미 9세트는 모두 최초 실행했고 외부 사실 7세트와 2명 사람 평가는 남아 있다. **잔여 $1.5172242 < 검색 예약 $2.00**여서 추가 유료 검색은 보류한다. 한도/예약을 임의로 완화하지 않으며 배포·프론트 완주 완료를 뜻하지 않는다.

- A: [프론트 시작 안내](docs/FRONTEND_HANDOFF.md)와 [FE-001 상세 티켓](docs/tickets/FE-001.md)에서 입력·강수·미리보기를 시작한다. PR #1 미병합 시점에는 `feat/server/backend-api`를 내려받고 새 `feat/play/fe-001-integration` 브랜치를 만든다. 이전 `feat/play/fe-001-preview`는 초기 기반이라 최신 서버가 없다. fixture로 화면을 만들거나 로컬 dev API로 연동할 수 있다.
- B: `feat/engine/ce-002-generation`에서 외부 사실의 출처/적용 범위, 큰 후보군의 활동 분할·filler를 우선 보완하고 미실행 사례를 확인한다. 사용자 A 선호를 반영한 Terra는 개발 기본값이며 최종 모델 선정/2명 품질 평가 완료가 아니다.
- OpenAI API는 별도 사용자 결제/키와 기존 누적 실험 예산 $5 범위에서 사용한다. 자동 충전 OFF. 저장소에 키나 실제 비공개 평가 원문을 추가하지 않는다. 원래 PRD/Design/AC/Decision 첨부 원문을 확보하면 복원본과 대조한다.
- GitHub collaborator 초대는 역할별 실제 계정이 정해진 뒤 한다. 현 단계에서 팀원 초대/branch protection을 완료했다고 주장하지 않는다.
- 외부 공유 개발 API/Swagger UI와 최종 화면 시안은 아직 없다. OpenAPI JSON·생성 TS 타입·fixture가 전달물이며, `npm run api:smoke`는 로컬 dev API의 생성부터 공유 replay까지 연결을 확인한다. 실제 AI 품질이나 프론트 완주 UI 테스트를 대신하지 않는다.

핵심 동시성/게임 정책과 기술 선택은 `TECH_DESIGN.md`, 결정 변경은 `DECISIONS.md`, source 한계는 `docs/SOURCE_PROVENANCE.md`에 남긴다.
