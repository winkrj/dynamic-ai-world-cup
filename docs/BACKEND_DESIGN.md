# 백엔드 API 설계와 작업 경계

2026-09-14, B-002/003/004 서버 범위. 기존 Technical Design v1.0과 공개 DTO/경로는 유지한다. 실제 Candidate Engine은 별도 품질 개발 작업이며 이 문서의 합성 테스트를 실제 추천 성능으로 해석하지 않는다.

## Google API 원칙의 적용 범위

Google의 [리소스 중심 설계](https://google.aip.dev/121)를 참고해 job, draft, snapshot, session, share를 나눴다. 데이터베이스 테이블을 그대로 공개하지 않고 클라이언트에 필요한 리소스와 상태만 반환한다. 조회는 GET, 생성과 상태 변경은 POST이며 저장 transaction이 끝난 후 응답한다.

[장기 실행 작업](https://google.aip.dev/151)의 취지는 DB-backed job과 polling으로 적용한다. 202 응답의 Location에서 상태를 읽고, READY일 때만 draft를 조회한다. 진행 중 후보는 공개하지 않는다. [요청 식별](https://google.aip.dev/155)의 재시도 안전성은 actor/실제 경로/key의 유일성, 요청 hash, 저장된 성공 응답으로 보장한다. [오류 원칙](https://google.aip.dev/193)에 따라 기계가 판단할 code와 사람이 읽을 안전한 message를 분리한다.

Google AIP 완전 준수 구현은 아니다. 기존 프론트 계약을 호환시키기 위해 다음 차이를 의도적으로 남긴다.

- [custom method의 `:verb` 표기](https://google.aip.dev/136) 대신 기존 `/drafts/{id}/start` 유지.
- google.longrunning.Operation 대신 기존 GenerationJob DTO, request_id 대신 필수 Idempotency-Key header 유지.
- google.rpc.Status 대신 `{code,message,requestId,retryable}` 유지. 전체 목록 API나 gRPC/protobuf는 추가하지 않는다.
- 새로운 경로나 필수 DTO 필드는 없다. 프론트의 wire contract는 그대로이며 구현 상태와 오류/보존 의미를 보충한다.

## DDD와 유지보수 경계

| 영역 | 책임 / 바꾸면 안 되는 경계 |
| --- | --- |
| candidate | 기존 순수 Java quality gate, gate만 발급할 수 있는 ValidatedSet |
| generation | 입력, Draft 상태/버전, 생성 job와 worker, CandidateEngine port |
| tournament | frozen snapshot과 순차 선택의 도메인 규칙, 서버가 도출하는 champion |
| sharing | 완료 세션의 고정 결과, 같은 snapshot의 새 세션 |
| identity | 익명 token 발급과 hash 조회 |
| api | 공개 DTO, strict JSON/입력 검증, cookie/Origin, HTTP 상태/오류 |
| infrastructure | 명시적 JDBC SQL, transaction 재시도 기록, scheduler/보존 작업/설정 |

도메인 규칙에는 Spring/JDBC/HTTP를 넣지 않는다. Application service는 transaction을 묶고 의미 있는 repository 메서드를 호출한다. API controller는 인증/DTO/idempotency 경계만 연결한다. 범용 CRUD·공통 aggregate 프레임워크·이벤트 버스·CQRS 인프라는 추가하지 않았다. 서버는 기존처럼 한 Spring 애플리케이션이다.

## 사람이 정할 엔진 / 지금 개발한 서버

서버가 제공하는 `CandidateEngine.generate(GenerationInput, Context)`는 job ID, attempt, deadline, 같은 actor의 최근 30일 직접 선택 최대 50건을 받는다. timeout은 전달하지 않는다. 엔진은 전달받은 후보 단위/맥락에서 관련성·최소 support를 다시 판단하고, 명시 조건을 우선해야 한다. 개인화 가중치 알고리즘은 CE-003에서 평가한다.

엔진의 출력은 gate-issued ValidatedSet, 공유 가능한 안전한 제목, provider/validator 버전이다. 원문 고민을 공개 제목으로 복사하지 않는다. 서버는 요청 N과 결과 N을 다시 검사하고, 검증된 결과에서만 DraftContent를 새로 만든다. DB에서 읽는 DraftContent는 이미 이 경계를 지난 저장 표현이며 외부 요청 DTO로 받지 않는다.

`DevelopmentCandidateEngine`은 `dev`에서만 활성화된다. `dev`와 `prod`를 함께 선택하거나 `prod`의 Secure cookie를 끄면 서버 시작을 거절한다. 모든 합성 후보·제목에 개발용 표시가 있으며 고민을 해석하지 않는다. 기본/운영 모드의 미연결 엔진은 PROVIDER_UNAVAILABLE로 실패한다. 실제 모델, Grounding, 독립 semantic assessment, Repair, usage/비용 계측은 CE-002에서 이 port 뒤에 구현해야 한다. 합성 evidence는 실제 검증 증거가 아니다.

## transaction과 실패 처리

- idempotency 예약 → business mutation → 응답 JSON 저장을 하나의 transaction으로 처리한다. 동시 같은 key는 DB에서 직렬화되고 실패는 예약·quota와 함께 rollback된다.
- 생성 속도는 actor와 실제 socket peer IP 각각 5회/최근 10분이다. IP는 hash만 저장한다. forwarding header를 신뢰하지 않으며 reverse proxy 도입 시 신뢰 경계를 별도로 설정해야 한다.
- worker는 DB에서 QUEUED를 SKIP LOCKED로 하나 claim한다. provider 실행 중에는 DB transaction을 열어두지 않는다. 두 실행 슬롯, 메모리 대기 queue 없음. 단일 프로세스 운영 기준이다.
- attempt/lease를 검사한 현재 worker만 결과를 저장한다. 기본/dev lease는 60초, CE-002 `live`는 300초다([설정 근거](CANDIDATE_ENGINE.md)). 만료 작업은 같은 job에서 최대 두 attempt까지 복구한다. 이후 FAILED, 재생성이라면 기존 READY를 복원한다. 이는 crash recovery이며 엔진 내부 Repair 1회와 다르다.
- deadline이 지난 provider 결과는 버린다. 실제 adapter는 네트워크 timeout/interrupt를 지켜야 한다. 무한 대기하는 adapter를 Java thread에서 강제 종료한다고 보장하지 않으며, 이런 adapter는 연결하지 않는다.
- 재생성 성공 transaction에서만 후보/초기 순서 교체, version+1, used=1. 실패하면 기존 내용 전체를 유지한다.
- start는 draft row lock 아래에서 버전을 검사하고 snapshot+원본 session+FROZEN을 함께 저장한다. source_draft 유일성으로 다른 key의 재시도도 같은 원본 session을 반환한다.
- 선택 batch는 session lock 아래에서 전체 검증 후 저장한다. 기존 prefix의 완전히 같은 event는 허용하고, 다른 event/건너뛴 순서/잘못된 winner/시간 경계는 거절한다. 최종 결과는 서버가 N−1개 결정에서 도출한다.
- snapshot은 후보 표시 사본과 초기 순서/규칙을 저장하고 UPDATE/DELETE trigger로 보호한다. DB 관리자 권한으로 trigger/테이블을 우회할 수 없는 암호학적 저장소라는 의미는 아니다.

## 보존과 접근

cookie token 원문은 DB/로그에 저장하지 않는다. HttpOnly, SameSite=Lax, Secure(개발 HTTP 예외), 30일 수명, `/api/v1` 경로다. 최초 cookie를 받기도 전에 연결이 끊겨 다른 actor가 생성되는 경우까지 actor 기반 idempotency로 통합하지는 않는다.

idempotency 응답과 완료 job/draft는 24시간, session/선택 이력은 30일 뒤 정리한다. 실행 중 job과 재생성 예약은 먼저 worker가 복구한다. 주기 정리는 한 시간 간격이라 TTL 직후 즉시 삭제되는 것은 아니다. snapshot/share에는 원문 입력을 넣지 않고 MVP 동안 유지한다. 만료 private 데이터의 source ID는 식별값만 남겨 FK cascade로 공개 링크가 삭제되지 않게 한다. snapshot owner hash는 소유권 확인을 위해 유지한다.

공유 결과는 생성 당시 champion을 link에 저장하므로 원본 session이 만료되어도 변하지 않는다. 공유 재플레이는 항상 새 session이고 AI 호출이 없다. 실제 공유 화면/배포는 A/OPS 작업으로 남아 있으며, API가 만든 공개 URL만으로 아직 배포된 플레이 화면을 보장하지 않는다.

## 완료 증거

`./scripts/verify.sh`: fixture/TS, 웹 build, 순수 domain 테스트, 격리된 PostgreSQL migration·transaction·경합 테스트, 실제 HTTP의 요청/응답 테스트, 실제 HTTP 응답의 OpenAPI schema 검사, bootJar. Docker가 없으면 DB 테스트를 skip해서 통과 처리하지 않고 실패한다. 최종 실행 결과와 독립 검토는 `VERIFICATION.md`에 기록한다.
