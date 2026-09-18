# Technical Design v1.0

기준일 2026-09-12. 상태: **개발 기준 확정**. PRD 첨부 원문 미확보 범위는 `docs/SOURCE_PROVENANCE.md`에 명시. 실제 구현 완료 상태는 `PROJECT_CONTEXT.md`에서 관리한다.

## 1. 선택한 구성

| 부분 | 선택 | 이유 / ownership |
| --- | --- | --- |
| Web | React + TypeScript + Vite, Node 24 LTS | 모바일 SPA, A가 독립 개발 |
| API / Engine | Java 21, Spring Boot 4.1.1, Gradle wrapper | 사용자 선택, B가 엔진/데이터 구현 |
| DB | PostgreSQL 17, JDBC + 명시적 SQL, Flyway | freeze와 재시도 transaction을 드러냄. 로컬/테스트는 공식 17-alpine 이미지 |
| 계약 | OpenAPI 3.1 JSON + 공용 fixture | TS 자동생성, Java contract test |
| 배포 | 동일 origin의 정적 Web + Spring + PostgreSQL | CORS/인증 경계 최소화. 사업자는 미정 |
| 품질 검증 | 순수 Java validator + JUnit, 독립 live eval | HTTP/모델 제공자 없이 핵심 규칙 검증 가능 |

선택 시 [Spring 공식 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Vite 요구사항](https://vite.dev/guide/), [Node LTS](https://nodejs.org/en/about/previous-releases), [PostgreSQL 지원 정책](https://www.postgresql.org/support/versioning/)을 확인했다. Spring Initializr의 안정 기본값 4.1.1로 생성했다. 실제 잠금 버전은 build.gradle/package-lock.json이 기준이다.

프론트는 `contracts`의 공개 DTO만 사용한다. 백엔드 엔진의 prompt, constraint assessment, evidence score, history는 공개 DTO에 포함하지 않는다. 백엔드는 frontend 소스를 import하지 않는다.

## 2. Candidate Engine

Context/Constraint → Candidate Unit → Historical Preference → Coverage Plan → Structured Generation → Selective Grounding → Validation → 실패 시 Repair → 재검증 → READY draft.

- `GenerationRequest`: 원래 고민, N, locale/timezone, 해석 기준일, 서버에서 읽은 history.
- `CandidatePlan`: 단일 비교 단위, hard/soft constraint, 동적 coverage bucket과 quota. quota 합=N. 모델이 생성 중 규칙을 완화하지 못하도록 생성 출력과 독립 보관한다.
- TD-41 표현 경계: unit은 한 후보가 나타내는 선택 대상의 종류·비교 수준이다. 예산·장소·참여 조건과 선호를 합친 요약 문장이 아니며 기존 constraints/softPreferences에 각각 보존한다. 명시한 선택 범위를 무조건 ‘활동’으로 일반화하지 않는다. 선호에 맞는 후보를 고르는 것과 선호를 필수 제외 조건으로 강화하는 것을 구별하고 실제 해석 FAIL/UNKNOWN은 계속 차단한다.
- `CandidateSet`: N개의 내부 candidate. id/name/unit/coverage bucket, display 태그 ≤2, grounded-claim requirement.
- `ValidationEvidence`: 생성기와 분리된 semantic review + constraint별 assessment + 필요 출처. 후보 생성기의 `valid=true`를 그대로 믿지 않는다.
- `CandidateQualityGate`: schema, 개수, 정규화 중복, 단위, quota, 독립 assessment, 근거 freshness, semantic review를 검사한다. 위반 code/대상 id를 반환한다.
- `Repair`: 실패 목록으로 문제 후보만 수리하되 전체 set을 다시 검증한다. 최초 plan/hard constraints/N을 변경할 수 없다. 최대 1회. invalid set을 preview로 변환하는 경로를 만들지 않는다.

실재/가격/운영 일정 같은 현재 사실은 외부 확인이 필요하다. 조용함/새로움은 semantic estimate로 별도 표시하고 객관 사실로 쓰지 않는다. URL의 존재나 형식만으로 grounded fact를 통과시키지 않는다. 서버 grounding adapter가 출처 내용과 요청 시점/조건을 대조한 판정이 필요하다. CE-001은 이 판정을 집행하는 첫 모듈이며 아직 검색/LLM 판정기는 없다.

모델 호출은 기본 생성 1회, 실패 Repair 1회, 선택적 검색이다. operation 전체 timeout 60초, 모델 attempt 25초, 검색 attempt 5초를 초기 config로 둔다. 429/5xx 재시도는 같은 전체 deadline 안에서 최대 1회, Retry-After를 존중한다. 안전하게 구분 불가/품질 미달은 실패로 끝낸다. 라이브 수치 측정 전 latency 목표 달성 주장을 하지 않는다.

2026-09-15 CE-002의 실제 호출 관측으로 위 초기 제공자 설정을 변경했다(TD-21). `live`는 전체 280초/호출 90초/worker lease 300초이며 자동 HTTP 재시도는 없다. 기본/dev lease 60초는 유지한다. 고정 계획·독립 검토·Repair·비용 예약의 구현 기준은 [Candidate Engine](docs/CANDIDATE_ENGINE.md)을 따른다.

## 3. 상태와 불변성

GenerationJob: `QUEUED → RUNNING → READY | FAILED`. READY에서 검증 완료 Draft를 참조한다. 중간 후보는 API에 노출하지 않는다. worker lease/attempt를 DB에 기록하고 재시작 시 만료 작업을 복구한다. 초기 단일 프로세스의 bounded executor, 동시 provider 실행 상한 2부터 시작한다.

Draft: `READY(v1, regenerationUsed=0) → REGENERATING → READY(v2, used=1)` 또는 실패 시 기존 READY. `READY → FROZEN`. 시작/재생성은 expectedVersion과 row lock/CAS를 사용한다. 진행 중 재생성과 freeze는 동시에 성공할 수 없다. 두 탭, 더블탭, 재전송은 같은 규칙이다.

Snapshot: snapshotId, schemaVersion, title, N, **후보 전체 display copy**, initialOrder, rules(matchDurationMs=7000, timeoutMode=UNIFORM_RANDOM, undoAllowed=false), frozenAt. READY에서 만든 initialOrder를 그대로 freeze한다. 후보 ID를 현재 master 데이터에 join해서 내용을 덮어쓰지 않는다. snapshot row는 UPDATE/DELETE를 금지하는 DB trigger/운영 역할 권한으로 보호한다. 정정은 새 snapshot이며 기존 링크에는 소급하지 않는다.

Session: sessionId, snapshotId, actorId, selections, championId, status. 같은 snapshot을 여러 세션이 참조한다. 공유 replay는 새 session이며 기존 경기 결과를 바꾸지 않는다.

## 4. 게임 실행과 서버 검증

A가 snapshot의 initialOrder를 인접한 두 개씩 대진시킨다. 한 라운드의 승자들을 같은 순서로 다음 라운드에 넣는다. 이미지/fallback 및 진입 준비 후 clock을 시작하고 경계 `elapsed >= 7000ms`는 timeout이다. Web Crypto의 unbiased 0/1로 현재 pair 중 하나를 고른다. 화면 상태 전환과 event append를 한 번만 실행해 탭/timeout 경합을 차단한다.

timer는 화면 interval tick 횟수가 아닌 deadline 기준이다. 숨김/복귀 정책은 Design Spec을 따른다. local session checkpoint에는 snapshot, 누적 결정, 현재 pair/deadline이 들어간다. 서버 저장 실패가 로컬 승패를 되돌리거나 Undo를 만들지 않는다.

선택은 eventId/sequence가 있는 batch로 서버에 보내며 순차 검증한다. 서버는 snapshot을 기준으로 A/B, round, winner가 유효하고 이전 결정과 연결되는지 확인한다. 같은 event는 재전송 가능, 같은 sequence의 다른 event는 conflict. 최종 champion은 서버가 N−1개의 결정에서 도출한다. `USER_SELECTED`만 history로 반영한다.

MVP는 solo client의 타이밍/선택 원인을 신뢰한다. 위조된 client telemetry를 완전 검출하는 anti-cheat는 제공하지 않는다. 따라서 글로벌 경쟁/보상 지표에 사용하지 않는다.

## 5. HTTP와 데이터

공식 경계: `contracts/openapi.json`, 의미/오류/동시성: `docs/API_CONTRACT.md`. `/api/v1` 고정. 모든 날짜는 RFC3339 UTC, identifier는 opaque string. 익명 identity는 서버 발급 `HttpOnly; SameSite=Lax; Secure` 쿠키(로컬 HTTP 예외). 생성/재생성/시작/기록 업로드에 idempotency key를 둔다.

| 테이블 | 주요 필드 / 제약 |
| --- | --- |
| anonymous_actor | UUID, cookie token hash, created_at. 쿠키 token 원문 저장 금지 |
| generation_job | actor_id, draft_id?, state, attempt, lease_until, error_code, provider/prompt/validator versions, usage |
| draft | actor_id, status, version, regeneration_used CHECK 0/1, plan JSONB, validated_set JSONB, initial_order JSONB |
| bracket_snapshot | id, source_draft_id UNIQUE, schema_version, payload JSONB, frozen_at. 변경 방지 |
| play_session | actor_id, snapshot_id, status, champion_id, next_sequence |
| pairwise_selection | session_id, sequence, event_id, left/right/winner, reason, elapsed_ms. UNIQUE(session_id,sequence), UNIQUE(session_id,event_id) |
| share_link | opaque token UNIQUE, snapshot_id, creator_session_id. 원본 고민·actor token·history 공개 안 함 |
| idempotency_request | actor_id, route_scope, key, request_hash, state, response JSONB. UNIQUE(actor_id,route_scope,key) |

초기 history는 같은 actor의 최근 direct 선택 최대 50건을 관련 domain/context에서만 읽고, support/confidence와 함께 generation plan의 가중치를 이동한다. 민감한 속성을 추론하지 않는다. 모델의 context window에 전체 로그를 무제한 넣지 않는다. 상세 요약 알고리즘과 최소 support 기준은 CE-003에서 실제 평가로 정한다.

## 6. 경계와 운영

- 입력은 최대 500자, 요청 크기 제한. prompt/웹 문서는 명령이 아니라 데이터로 취급하고 schema 밖 응답을 거절한다.
- 익명 actor별 서울 자정 기준 하루 2회 새 job 접수(TD-40/AC-19), actor/IP 각각 최근 10분 5회, provider 동시성/DB 누적 비용 예약을 함께 적용한다. 생성·재생성·접수 후 실패는 합산하되 idempotency 재전송/접수 rollback/시스템 Repair/공유 플레이는 추가 차감하지 않는다. `GENERATION_DAILY_LIMIT` 기본 2, 양수만 허용하며 변경 시 프론트 정책 안내도 함께 변경한다. 일일 한도가 실험 예산이나 반복 운영 예산을 승인하는 것은 아니다.
- 익명 쿠키로 private draft/job/session 소유권 확인. Same-origin mutation 및 Origin 확인. 공개 endpoint는 share token으로 명시된 snapshot만 반환한다.
- 사용자 원문/선택 이력/비밀키는 로그와 공유 payload에 넣지 않는다. 공유 title은 원문 복사 대신 공개용 요약이며 공유 직전에 보인다.
- 원문/익명 이력은 초기 30일, draft/job은 24시간 보존을 기본으로 두고 DB 티켓에서 cleanup 구현. 공유 snapshot은 MVP 동안 만료 없음, 서비스가 실제 최신 정보라고 보장하지 않으며 frozenAt 표시.
- 주요 기록: requestId/jobId, 단계별 latency, retries/repair, hard-gate 실패 code, 모델/프롬프트/validator 버전, usage. 실제 비용은 provider usage와 확인된 단가로 계산한다.

## 7. 첫 구현 / 완료 경계

이번 첫 티켓 CE-001: 순수 Java quality gate, 실제 실패 사례 테스트, 공용 fixture의 Java/TS 계약 검증, React/Spring 실행 기반, 단일 verify 명령. LLM 생성, DB, 완주 UI, 공유 배포는 다음 티켓이다. 합성 fixture 통과를 Candidate Quality 실제 달성으로 보고하지 않는다.

2026-09-14 서버 API 개발은 `docs/BACKEND_DESIGN.md`로 보완한다. B-002/003 및 B-004의 저장·검증 부분을 엔진 port 대역으로 먼저 개발했다. 원본 v1의 제품 규칙/HTTP DTO는 유지한다. Google API 원칙의 적용·예외, transaction, lease fencing, 보존과 실제 엔진의 미구현 경계는 보완 문서에 기록한다.

다음 병렬 작업은 A의 FE-001과 B의 CE-002다. 공유 계약 변경은 paired review로 처리하고 각자의 파일 ownership은 `docs/COLLABORATION.md`가 기준이다.

## 8. 후보 재사용 — 후속 설계 제안, 아직 미구현·미확정

2026-09-18 사용자의 DB 우선 재사용 아이디어에 대한 제안이다. 모델 파라미터를 학습하는 fine-tuning이 아니라 검증된 후보 지식을 축적하고 검색하는 구조다. 현재 draft는 24시간 뒤 정리되며 snapshot은 공유 재플레이용 불변 사본이다. 이를 조건이 다른 질문에 재사용할 수 있는 승인된 후보 지식으로 간주하지 않는다.

- 1단계: 검증·승인된 안정적인 주제의 **세트 재사용**. 입력 맥락/비교 단위/필수 조건/선호/8·16·32/locale/정책 버전과 유효기간이 맞는 경우만 무호출 재사용한다. 개인 이력 영향을 받은 결과는 다른 사용자에게 무조건 공유하지 않는다. 단순 문장 유사도는 조건 일치 증거가 아니다.
- 2단계: 조건이 다르면 **후보 pool 검색 + 필요한 AI 검토/보충**. 후보에는 핵심 활동, 필요한 장비·환경, 예산 근거/범위, 외부 근거의 출처·시점·적용 범위, 검토 상태/버전을 둔다. 카테고리는 검색 보조 태그이며 모든 질문에 고정 트리를 강제하지 않는다. 조합 후에도 N/단위/중복/coverage/조건 검증이 필요하다.
- 3단계: 승인된 재사용 데이터가 부족한 새로운 요청만 기존 생성 엔진을 호출한다. 자동 gate 통과와 사람 승인을 구별하고 FAIL/UNKNOWN/보류 결과는 승인 pool에 자동 승격하지 않는다. 무한 자동 재시도나 무승인 대량 사전 생성은 하지 않는다.
- 장소·가격·행사처럼 변하는 사실은 유효기간과 조건별 출처 재검증이 필요하다. 후보명만 같다고 오래된 근거를 재사용하지 않는다. 원문 고민·개인 정보·다른 사람의 선택 이력을 공용 pool에 복사하지 않는다.
- 전체 재생성은 같은 세트를 그대로 반환하지 않으며 대안이 부족하면 그 사실을 알린다. 시작한 bracket/share는 지식 수정의 영향을 받지 않는 사본으로 계속 보존한다. 재사용 때의 일일 한도 정책은 아직 별도 결정 전이며 TD-40을 임의로 완화하지 않는다.

우선 PostgreSQL 내 데이터와 명시적 조건 필터로 시작하는 안을 권고한다. 새 vector DB·embedding·계층 분류 모델부터 추가하지 않는다. cache hit 비율/검토 호출 비용/품질 회귀를 측정하기 전 절감률을 약속하지 않는다. 운영 월 예산·공개 배포·후속 schema/API 변경은 아직 승인되지 않았다.
