# API contract v1.0

`contracts/openapi.json`이 DTO source of truth다. 아래 endpoint의 서버·저장 경로를 구현했다. 현재 엔진 브랜치에는 실제 AI adapter도 연결되어 있으며 `live`는 명시적 키·예산이 필요하다. `dev`는 합성 provider, 기본 profile은 fail-closed다. 실제 연결과 운영 품질 승인은 별개이며 `CANDIDATE_ENGINE.md`를 따른다. 시안과 API의 조정 기준은 [제품 통합 Spec](INTEGRATION_SPEC.md), 서버 구현/검증 경계는 `BACKEND_DESIGN.md`와 `VERIFICATION.md`를 함께 읽는다.

프론트 담당자의 시작점은 [인수인계 안내](FRONTEND_HANDOFF.md)다. dev 서버를 켠 뒤 `npm run api:smoke`로 전체 HTTP 연결을 확인할 수 있다. 이 명령은 로컬 데이터와 생성 작업 2개를 만들며 실제 AI/프론트 UI 검증은 아니다.

## 공통

- Base `/api/v1`. JSON / UTF-8. 날짜 UTC RFC3339. opaque ID는 UUID라고 frontend가 가정하지 않는다.
- 서버가 익명 cookie를 발급한다. job/draft/session은 cookie owner만 읽고 쓴다. 공개 share 조회만 owner 불필요.
- 모든 mutation의 `Idempotency-Key`는 operation별 UUID 권장. 서버는 `(actor,route,key)`에 body hash와 응답을 저장한다. 동일 body 재전송=같은 결과, 다른 body=409 `IDEMPOTENCY_CONFLICT`. pending은 202 operation 또는 409 `OPERATION_IN_PROGRESS`로 수렴한다.
- `expectedVersion` stale은 409. 존재하지 않거나 소유하지 않은 private resource는 404. 잘못된 입력 400, 의미/품질 실패 422, rate limit 429, 의존 장애 503.
- 오류 `{code,message,requestId,retryable}`. 클라이언트는 `code`로 분기하며 message에 의존하지 않는다. 외부 provider 원문 오류를 공개하지 않는다.
- JSON의 알 수 없는 필드, 문자열→숫자/소수→정수 변환, 필수 필드 누락/null은 거절한다. 본문 최대 64 KiB, body가 있으면 application/json. 너무 큰 본문 413, 지원하지 않는 content type 415, 지원하지 않는 HTTP method 405도 같은 오류 형식이다.
- mutation은 Idempotency-Key 필수이며 브라우저 Origin은 설정된 PUBLIC_ORIGIN과 정확히 같아야 한다. 임의 CORS를 열지 않는다. Origin이 없는 비브라우저 클라이언트는 사용할 수 있다. 요청 ID는 응답 X-Request-Id에도 있다.
- 202 생성 응답은 Location(조회 경로), Retry-After: 1을 포함한다. 새 생성/재생성 접수는 익명 actor별 서울 날짜 기준 하루 2회(접수 후 FAILED 포함), actor/IP 각각 5회/최근 10분이다. 초과 접수는 429 `RATE_LIMITED`, 해제까지 올림한 초 단위 `Retry-After`를 반환한다. 일일·단기 제한이 겹치면 가장 늦은 해제 시각을 따른다. 재전송은 기존 응답을 재현하고, 접수 실패는 횟수를 차감하지 않는다. 공유 플레이는 생성 횟수를 쓰지 않는다.
- `RATE_LIMITED`는 전체 provider 예산 부족도 표현한다. 해제 시각을 모르면 `Retry-After`를 발명하지 않는다. job FAILED의 Error에도 대기 시각 필드는 없으므로 고정 600초/자정을 표시하거나 자동 생성 POST를 반복하지 않는다. Error DTO/공개 enum은 변경하지 않는다.

| 경로 | 의미 |
| --- | --- |
| GET /health | 프로세스 생존 상태. DB·후보 품질·AI 계정 정상임을 보장하지 않음 |
| GET /ready | DB 조회·웹 번들·worker 설정·명시 엔진 profile 준비 시 200 READY, 아니면 503 NOT_READY. Readiness DTO만 반환, 유료 호출/쿠키 생성 없음. 품질·잔액·공급자 인증 성공 판정 아님 |
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

regeneration 실행 중 preview GET은 409 OPERATION_IN_PROGRESS, 클라이언트는 이미 읽은 preview를 보존하고 시작 버튼을 잠근다. 교체 성공 후 GET으로 version 2를 받는다. 복수 요청의 회수 제한은 DB row lock/condition으로 보장한다. worker의 제한된 복구 재시도는 동일 job에 귀속한다. terminal FAILED 후 동일 key 재전송은 원래 접수 응답을 반환하므로 같은 job을 조회한다. 다시 생성하고 싶으면 새 key로 새 요청을 보낸다. 실패한 regeneration은 성공 회수를 소모하지 않는다.

idempotency 보존은 24시간이며 cookie를 수신한 동일 actor 기준이다. 최초 cookie 수신 전 네트워크 유실은 새 actor로 재시도될 수 있다. GET draft는 FROZEN 이후 409 ALREADY_FROZEN이며, 이미 시작한 화면은 start 응답/GET snapshot을 사용한다. 완료 job/draft는 24시간, session/이력은 30일 후 주기 정리되며 공개 snapshot/share는 유지한다.

start 재시도는 같은 snapshot/session을 반환한다. 같은 draft의 다른 key start도 이미 만들어진 동일 owner session을 반환하며 추가 session 생성은 share replay 경로로만 한다. start와 regen 경합은 둘 중 하나만 성공한다.

initialOrder는 각 candidate id를 정확히 한 번 포함한다. pair는 인접 두 개, 다음 라운드는 winner 순서다. selections의 `sequence`는 0부터 증가하며 server가 pair/round를 도출한다. `winnerId`는 해당 pair에 있어야 한다. USER_SELECTED는 elapsedMs < matchDurationMs, TIMEOUT_RANDOM은 elapsedMs ≥ matchDurationMs. client telemetry의 위조 방지는 MVP 범위 밖이다. JSON schema만으로 이러한 cross-field invariant를 모두 검증할 수 없으므로 application test가 필요하다.

schemaVersion/size/rules/candidates/initialOrder/frozenAt이 snapshot의 불변 본문이다. creator champion은 share/session의 값이며 snapshot에 기록하지 않는다. 이미지 링크가 나중에 실패해도 같은 후보를 fallback으로 보여준다.

## 변경 절차

contract JSON → fixture → TS 생성 타입 → Java contract test → verify 순서로 갱신. A/B 양쪽 영향 확인 후 병합한다. 실제 endpoint 구현이 추가되면 OpenAPI의 `x-implementation-status`와 이 문서의 구현 현황을 함께 갱신한다.

## 로컬 API 연동

README대로 dev 서버를 실행한 뒤 같은 cookie를 유지한다. 아래 cookie 파일은 로컬 테스트용 익명 token이므로 commit하지 않는다. 생성 응답의 jobId를 조회해 READY가 된 뒤 draftId를 읽는다.

```sh
curl -i -c /tmp/worldcup-dev.cookies http://localhost:8080/api/v1/generation-jobs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-generation-001' \
  -d '{"prompt":"퇴근 후 취미를 고르고 싶어","size":8,"locale":"ko-KR","timezone":"Asia/Seoul"}'

curl -b /tmp/worldcup-dev.cookies http://localhost:8080/api/v1/generation-jobs/JOB_ID
curl -b /tmp/worldcup-dev.cookies http://localhost:8080/api/v1/drafts/DRAFT_ID
curl -b /tmp/worldcup-dev.cookies http://localhost:8080/api/v1/drafts/DRAFT_ID/start \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: local-start-001' \
  -d '{"expectedVersion":1}'
```

브라우저는 동일 origin의 `/api/v1`을 사용하고 cookie를 유지한다. 매 사용자 동작마다 새 key를 만들되 네트워크 재전송에는 같은 key/body를 사용한다. 공유 생성/재플레이 POST에는 본문을 보내지 않는다. 공유 URL은 프론트의 `/shares/{token}` 화면을 가리키며 해당 화면 구현과 배포는 별도다.
