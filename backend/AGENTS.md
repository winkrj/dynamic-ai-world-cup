# B — 후보 품질과 서버

루트 AGENTS와 TECH_DESIGN, API_CONTRACT를 따른다. 책임은 Candidate Engine/grounding/eval/history, API/DB, snapshot/session/share 데이터, 운영이다. frontend 경험 내부를 변경하지 않는다.

Java 21 records와 순수 Java domain부터 시작한다. LLM/검색 adapter는 generation/assessment/grounding 책임을 구분하고 generated metadata를 독립 검증 결과로 간주하지 않는다. 첫 CE-001은 판정 집행이며 사실 검증기 구현 완료가 아니다.

HTTP DTO는 contracts와 맞춰 테스트하고 내부 plan/evidence를 그대로 반환하지 않는다. DB transaction과 unique constraint로 idempotency/regen/freeze를 보장한다. snapshot은 master candidate 참조가 아니라 display copy로 저장한다.

`./gradlew test bootJar`와 관련 API/DB 통합 테스트를 실행한다. 실제 provider 검증 전 모델 성능/latency/비용 성공 수치를 만들지 않는다. 유료 provider key는 환경에서만 주입하고 fixture/로그/프론트에 넣지 않는다.

## 서버 구현 이후의 경계

`docs/BACKEND_DESIGN.md`의 DDD/Google API 적용 기준을 따른다. HTTP 경로/DTO를 스타일 취향으로 바꾸지 말고 계약 영향부터 확인한다. 도메인에는 HTTP/JDBC를 넣지 않으며 transaction은 application service, SQL은 infrastructure repository에 둔다.

엔진 작업은 `CandidateEngine` port 뒤에서 수행한다. `Context`의 deadline/attempt를 지키고, 실제 output은 독립 검증을 통과한 ValidatedSet이어야 한다. `DevelopmentCandidateEngine`은 dev 합성 데이터 전용이다. 일반 profile의 미연결 엔진 실패를 임시 성공으로 바꾸지 않는다.

DB schema 변경은 새 Flyway migration으로 추가한다. 이미 배포/공유된 migration을 편집하거나 snapshot UPDATE/DELETE trigger를 해제하지 않는다. tests는 PostgresSupport가 만든 격리 DB만 초기화한다. 개발자 DB를 test 대상이나 cleanup 대상으로 쓰지 않는다.

전체 검증에는 실행 중인 Docker가 필요하다. `WorldcupHttpTest`의 실제 응답 샘플을 `scripts/check-contracts.mjs --http`가 schema 검사한다. 새로운 endpoint/DTO는 HTTP 테스트와 이 검사에 추가한다. 스케줄러/동시성/보존 정책을 변경하면 해당 DB 테스트도 함께 실행한다.

2026-09-18 사용자가 엔진 수정 보류를 해제했다. 추가 과금 없이 필수 접근 전제/실행 가능성 검토를 보완한다. 명시 hard constraint를 발명하지 않고 별도 내부 후보별 판정으로 검사하며 UNKNOWN/FAIL·판정 누락을 통과시키지 않는다. 공개 API/DB·모델·유료 실험 정책은 그대로다. 기존 API가 입력부터 공유까지 지원하므로 필요한 근거 없이 endpoint/DB를 확장하지 않는다. 프론트의 부분 batch 업로드·동일 key 재시도·cached ACK·공유 전 완료 판정·새 actor replay를 실제 HTTP로 검증한다. 재시도 응답이 과거 ACK라는 계약을 바꾸어 클라이언트 버그를 숨기지 않는다.
