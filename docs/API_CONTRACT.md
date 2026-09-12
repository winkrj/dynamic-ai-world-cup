# API contract v1.0

`contracts/openapi.json`이 DTO source of truth다. 모든 제품 endpoint는 계약만 확정됐으며 초기 실행 서버에서 구현된 것은 health뿐이다. 없는 기능에 임시 성공을 반환하지 않는다.

## 공통

- Base `/api/v1`. JSON / UTF-8. 날짜 UTC RFC3339. opaque ID는 UUID라고 frontend가 가정하지 않는다.
- 서버가 익명 cookie를 발급한다. job/draft/session은 cookie owner만 읽고 쓴다. 공개 share 조회만 owner 불필요.
- 모든 mutation의 `Idempotency-Key`는 operation별 UUID 권장. 서버는 `(actor,route,key)`에 body hash와 응답을 저장한다. 동일 body 재전송=같은 결과, 다른 body=409 `IDEMPOTENCY_CONFLICT`. pending은 202 operation 또는 409 `OPERATION_IN_PROGRESS`로 수렴한다.
- `expectedVersion` stale은 409. 존재하지 않거나 소유하지 않은 private resource는 404. 잘못된 입력 400, 의미/품질 실패 422, rate limit 429, 의존 장애 503.
- 오류 `{code,message,requestId,retryable}`. 클라이언트는 `code`로 분기하며 message에 의존하지 않는다. 외부 provider 원문 오류를 공개하지 않는다.

| 경로 | 의미 |
| --- | --- |
| POST /generation-jobs | prompt/N/locale/timezone로 job 생성, 202. worker 완료 전 후보 없음 |
| GET /generation-jobs/{jobId} | QUEUED/RUNNING/READY/FAILED. READY에 draftId, FAILED에 error |
| GET /drafts/{draftId} | READY인 N개 전체 preview. regenerationRemaining 0/1 |
| POST /drafts/{draftId}/regenerations | expectedVersion으로 전체 교체 예약, 202 job. 성공시에만 used=1/version+1; 실패 기존 draft 복구 |
| POST /drafts/{draftId}/start | transaction에서 expectedVersion, READY, no in-flight regen 확인 → snapshot + 새 session. 201 |
| GET /snapshots/{snapshotId} | 해당 snapshot의 원본 owner 또는 session owner 조회. 공개 접근은 share 경로만 사용 |
| POST /sessions/{sessionId}/selections | eventId/sequence/winner/reason/elapsed batch, 200 ack. atomic batch; 이미 수락한 prefix의 동일 재전송 허용 |
| POST /sessions/{sessionId}/shares | completed session에 공개 share token 생성, snapshotId/championId 고정. 201 |
| GET /shares/{token} | public snapshot + creator champion. 원문 prompt/history/actor 정보 없음 |
| POST /shares/{token}/sessions | 동일 snapshot으로 익명 새 세션 생성. LLM 호출 없음. 201 |

## 상태/재시도 상세

regeneration 실행 중 preview GET은 409 OPERATION_IN_PROGRESS, 클라이언트는 이미 읽은 preview를 보존하고 시작 버튼을 잠근다. 교체 성공 후 GET으로 version 2를 받는다. 복수 요청의 회수 제한은 DB row lock/condition으로 보장한다. 실패 job 재시도 방식은 동일 operation에 귀속하며 같은 사용자 regen 요청을 여러 성공 교체로 만들 수 없다.

start 재시도는 같은 snapshot/session을 반환한다. 같은 draft의 다른 key start도 이미 만들어진 동일 owner session을 반환하며 추가 session 생성은 share replay 경로로만 한다. start와 regen 경합은 둘 중 하나만 성공한다.

initialOrder는 각 candidate id를 정확히 한 번 포함한다. pair는 인접 두 개, 다음 라운드는 winner 순서다. selections의 `sequence`는 0부터 증가하며 server가 pair/round를 도출한다. `winnerId`는 해당 pair에 있어야 한다. USER_SELECTED는 elapsedMs < matchDurationMs, TIMEOUT_RANDOM은 elapsedMs ≥ matchDurationMs. client telemetry의 위조 방지는 MVP 범위 밖이다. JSON schema만으로 이러한 cross-field invariant를 모두 검증할 수 없으므로 application test가 필요하다.

schemaVersion/size/rules/candidates/initialOrder/frozenAt이 snapshot의 불변 본문이다. creator champion은 share/session의 값이며 snapshot에 기록하지 않는다. 이미지 링크가 나중에 실패해도 같은 후보를 fallback으로 보여준다.

## 변경 절차

contract JSON → fixture → TS 생성 타입 → Java contract test → verify 순서로 갱신. A/B 양쪽 영향 확인 후 병합한다. 실제 endpoint 구현이 추가되면 OpenAPI의 `x-implementation-status`와 이 문서의 구현 현황을 함께 갱신한다.
