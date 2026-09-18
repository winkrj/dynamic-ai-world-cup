# 프로젝트 컨텍스트

갱신일: 2026-09-18.

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

현재 우선순위는 **시안 4 기반 제품 통합**이다. 사용자가 대결 A(상하 카드)를 선택했다. [제품 통합 Spec](docs/INTEGRATION_SPEC.md)에 기획·화면·공개 API·실제 코드 차이와 A/B 소유 경계를 묶었다. 후보 엔진의 추가 수정/유료 실험은 보류하고 통합용 현재 버전을 사용하되 운영 품질 승인을 의미하지 않는다. `feat/server/design-integration`에서 B는 동일 origin app jar와 공유 deep-link 진입을 구현한다. A의 실제 화면·전체 완주는 별도이며, GitHub 프론트 브랜치는 아직 초기 shell이다. 아래는 보존된 엔진 체크포인트다.

2026-09-16 최신 CE-002/v16: 실행마다 PLAN schema 속성 순서가 달라 후보를 조건보다 먼저 쓰던 구조적 불일치를 고쳤다. 조건·선호→비교 단위/성격·grounding→결정→coverage 순서를 고정한다. 새 필드·모델 호출 없이 기존 Context/Constraint→Unit→Coverage 설계를 반영한다. v15의 단일 선택 기준, 조건별 근거/독립 검토, 사전·상세 Repair **합계 최대 1회**, 시간/비용 한도와 공개 API는 그대로다.

v16 solo/32 비교 1회는 **READY·preview 32개**, 217.268초, 6회 호출/사전 Repair 1회, $0.249958. 실제 schema와 출력 순서가 같았고 비운동 제외·월 예산이 별도 필수 조건에 들어가 32×2=64개 독립 assessment를 모두 받았다. 순서 고정과 이번 실행에서의 제외 조건 보존은 확인했지만 인과관계나 보편적 의미 정확도 개선율은 확정하지 않는다. 초기 35개 제안 중 장르/재료 중복 등 14개가 탈락해 교체됐고 재검토 후 32개를 승인했다. 최종 감상·필사·퍼즐 세분화와 장비 전제 판단의 일관성은 사람 기준 대조가 남아 있어 품질 완료가 아니다.

v15의 미실행 일반 취미 3세트를 각각 한 번 확인했다. **solo/8 READY(62.728초/$0.072544), solo/32 READY(189.510초/$0.2141135)**, home/32는 해석·후보 문제로 FAILED(114.110초/$0.1066105)였다. solo/32에서 사전 활동 교체 1회 후 재검토→실제 preview까지 연결됐다. 이전 home/8·16, social/32, solo/16의 READY도 보존하며 버전별 결과는 `docs/CANDIDATE_ENGINE.md`에서 확인한다.

**자동 READY는 품질 최종 승인이 아니다.** solo/32는 스케치/색칠, 독서/주제 탐구, 퍼즐 분할을 통과시켰고 ‘운동은 싫어’를 필수 조건 대신 softPreferences와 unit에만 남긴 해석도 PASS였다. 실제 후보는 비운동 활동이지만 제외 조건의 독립 assessment 누락을 정확히 잡았다는 증거는 아니다. home/32는 원문 30분을 계획이 ‘30분 내외’로 표현한 문제를 두 번째 검토에서야 거절했고 상세 생성/preview 없이 끝났다. 후보 경계와 해석 판정 일관성이 남아 있어 Goal은 미완료다. 같은 사례를 통과할 때까지 재추첨하지 않는다.

서울 접근성 사례는 v14에서도 근거 부족으로 FAILED였다. 후보 선정 시점의 검증 가능성과 유효한 대체 활동 확보가 남아 있으며 이번 v15에서는 유료 검색을 하지 않았다.

v16 최종 `verify`: Java **171개 실행/실패·오류 0(유료 1개 제외)**, handoff 6개, fixture 5개, 실제 HTTP 102개/8개 schema, 웹 build/bootJar 통과. 집중 테스트 120개 후 독립 리뷰 1회 Critical/High/actionable 0, 이후 코드 변경 없이 전체 검증했다. 별도 live HTTP 69개도 schema 적합(실패 응답 포함). 누적 실험 추정 **$3.7327338 / 승인 $5**, 제공자 124회, 미확인 비용 예약 없음, 자동 충전 OFF 유지. seed **11/18세트**의 첫 실행 READY 5/FAILED 6은 그대로이며 일반 취미 9세트는 모두 최초 실행했고 외부 사실 7세트와 2명 사람 평가는 남아 있다. **잔여 $1.2672662 < 검색 예약 $2.00**여서 추가 유료 검색은 보류한다. 한도/예약을 임의로 완화하지 않으며 배포·프론트 완주 완료를 뜻하지 않는다.

- A: [프론트 시작 안내](docs/FRONTEND_HANDOFF.md)와 [FE-001 상세 티켓](docs/tickets/FE-001.md)에서 입력·강수·미리보기를 시작한다. PR #1 미병합 시점에는 `feat/server/backend-api`를 내려받고 새 `feat/play/fe-001-integration` 브랜치를 만든다. 이전 `feat/play/fe-001-preview`는 초기 기반이라 최신 서버가 없다. fixture로 화면을 만들거나 로컬 dev API로 연동할 수 있다.
- B: `feat/engine/ce-002-generation`에서 외부 사실의 출처/적용 범위, 큰 후보군의 활동 분할·filler를 우선 보완하고 미실행 사례를 확인한다. 사용자 A 선호를 반영한 Terra는 개발 기본값이며 최종 모델 선정/2명 품질 평가 완료가 아니다.
- OpenAI API는 별도 사용자 결제/키와 기존 누적 실험 예산 $5 범위에서 사용한다. 자동 충전 OFF. 저장소에 키나 실제 비공개 평가 원문을 추가하지 않는다. 원래 PRD/Design/AC/Decision 첨부 원문을 확보하면 복원본과 대조한다.
- GitHub collaborator 초대는 역할별 실제 계정이 정해진 뒤 한다. 현 단계에서 팀원 초대/branch protection을 완료했다고 주장하지 않는다.
- 외부 공유 개발 API/Swagger UI와 최종 화면 시안은 아직 없다. OpenAPI JSON·생성 TS 타입·fixture가 전달물이며, `npm run api:smoke`는 로컬 dev API의 생성부터 공유 replay까지 연결을 확인한다. 실제 AI 품질이나 프론트 완주 UI 테스트를 대신하지 않는다.

핵심 동시성/게임 정책과 기술 선택은 `TECH_DESIGN.md`, 결정 변경은 `DECISIONS.md`, source 한계는 `docs/SOURCE_PROVENANCE.md`에 남긴다.
