# Candidate Engine — CE-002 구현 설계

2026-09-15. B(후보 품질과 서버) 소유. AC-02~07/14/18이 대상이며 공개 OpenAPI와 프론트 소유 경로는 변경하지 않는다. 이 문서는 구현 설계이며 완료 증거는 마지막에 기록한다.

## 실행 경계

기존 `GenerationWorker → CandidateEngine → ValidatedSet → DraftContent` 경계를 유지한다. Spring/HTTP/JDBC를 순수 후보 도메인에 넣지 않는다. 새로운 SDK·agent framework·큐·서비스는 추가하지 않는다. Java 21 HTTP client, 기존 Jackson 3와 PostgreSQL을 사용한다.

1. **계획**: 원 입력의 context/constraint와 비교 단위를 먼저 정하고 관련 직접 선택 history만 참고한 뒤 동적 coverage quota를 만든다. 명시 조건의 원문 근거, 의미 추정/외부 사실 구분, 핵심 활동 수준, 지속성 기준을 내부 DTO로 보존한다. quota 합은 N이다. 계획의 누락·모호함은 실패로 닫으며 생성/Repair 중 바꾸지 않는다.
2. **생성**: 고정 계획의 슬롯을 채운다. 후보별 활동·지속 방법·필요한 조건을 내부 검토용으로 받는다. 후보 ID/공개 title은 서버가 관리하며 이미지 URL을 모델에게 생성시키지 않는다. 취미를 도구/재료/스타일로 쪼개거나 기록·잡일을 덧붙여 개수를 채우지 않는다.
3. **선택적 근거 확인**: 실제 장소·가격·운영·접근성 등이 필요하면 별도의 OpenAI hosted web-search 요청으로 후보와 조건을 대조한다. 생성기의 URL이나 자기 선언은 근거가 아니다. 실제 tool-call 출처에 결합된 별도 fact assessment만 받아들이고 날짜/신선도와 미확인 상태를 확인한다. 수집 실패나 근거 부족은 UNKNOWN이다. 애플리케이션이 모델이 만든 URL을 직접 요청하지 않아 SSRF 경로를 만들지 않는다. 이 방식은 제공자 보조 사실 판정이며 출처의 진실성을 수학적으로 보장하거나 사람의 정확도 평가를 대신하지 않는다.
4. **독립 검토**: 생성 요청과 conversation을 공유하지 않는 별도 요청이 원 입력/고정 계획/후보/근거를 보고 계획의 충실성, 모든 hard 조건, 비교 단위, 의미 중복, 취미 지속성·개수 채우기를 판정한다. 생성기의 점수를 그대로 PASS로 복사하지 않는다. 사실 확인 필요성을 계획이 빠뜨렸어도 이 단계에서 거절한다.
5. **집행과 Repair**: 기존 Java gate와 추가 내부 품질 판정을 모두 통과해야 `ValidatedSet`을 반환한다. 실패 후보만 최대 1회 교체하며 계획·N·정상 후보는 유지한다. 수정된 전체 집합을 다시 근거 확인/독립 검토한다. 전역 실패·구조 파싱 실패는 전체 후보가 수리 대상일 수 있다. 계획 자체가 원 입력을 누락했다면 후보 Repair로 덮지 않고 실패한다. 32→16 축소나 무한 재생성은 없다.

## 모델·비용·실행 시간

- opt-in `live` profile에서만 실제 제공자를 사용한다. 기본은 기존 fail-closed, `dev`는 명시적 합성 데이터다. `live`와 `dev` 혼용은 시작 오류다. 실제 API 키는 환경 변수로만 받으며 frontend/공개 DTO/로그에 넣지 않는다.
- 개발 기본 모델은 사용자 16강 선호를 반영한 `gpt-5.6-terra`; 모델별 설정은 교체 가능하다. 단 1회 선호를 최종 모델 품질 인증으로 표시하지 않는다. Gemini 등 새 제공자·새 계정은 추가하지 않는다.
- 요청마다 `store=false`, standard tier, 제한된 출력, 별도의 instructions/data, 자동 HTTP 재시도 없음. refusal/incomplete/알 수 없는 모델·사용량·비정상 JSON은 성공으로 취급하지 않는다. 네트워크 실패로 과금 여부가 불명확하면 예산 예약을 해제하지 않는다.
- 실제 생성 30~53초에 맞춰 기존 v1의 초기 60초 job/25초 호출 가정은 **live 한정** 설정으로 분리한다. live job lease 기본 300초, 엔진 전체 280초, 개별 요청 최대 90초이며 항상 남은 deadline이 우선한다. 기존 dev/test lease 기본 60초와 attempt fencing/최대 두 crash-recovery attempt는 유지한다. 기존 공개 job polling 계약은 바뀌지 않는다. 빠른 완료를 달성했다는 수치는 실제 측정 전 주장하지 않는다.
- 기존 worker 동시성 2를 유지한다. 단계별 호출 전에 DB에 비용을 보수적으로 예약하고 완료 usage를 기록한다. DB 잠금으로 두 worker의 잔여 예산 초과를 막고 재시작 후에도 내역을 보존한다. 기술적 예약 추정은 제공자의 확정 청구 hard cap이 아니며 외부 과금 지연/미확인 비용을 0으로 만들지 않는다.
- 예산 기본값 0(명시적 허용 필요). 이 Goal의 실제 실험에서는 이전 누적 $0.0725468을 포함해 $5 이내가 되도록 추가 예산을 보수적으로 설정한다. 자동 충전 OFF를 유지한다. 배포용 무제한 과금이나 자동 충전은 추가하지 않는다.
- 모델/프롬프트/검토 버전, 단계·attempt, 토큰·비용·지연·안전한 실패 code를 내부 기록한다. 원문/응답을 남기는 live eval은 합성 사례에 한해 gitignored `reports/local/`로 제한하고 일반 서버 로그와 분리한다.

## 완료 검증과 전달

집중 테스트는 고정 계획/실패 차단/Repair 1회/정상 후보 보존/deadline/독립 검토/근거 출처 결합, 로컬 stub HTTP 오류/응답, DB 예산 동시성과 lease 회귀에 한정한다. 이후 기존 전체 verify, 읽기 전용 Reviewer, 실제 합성 입력의 API→READY preview 연결을 확인한다. 운영 후보 품질 최종 확정은 별도의 최소 2명 독립 사람 평가이며 구현 완료와 구별한다.

프론트는 기존 계약과 `docs/FRONTEND_HANDOFF.md`로 계속 개발한다. live 실행법과 검증 결과만 이 문서/인수인계 문서에 보충한다. 게임/공유는 frozen snapshot을 사용하므로 엔진 코드를 수정하지 않는다. 배포·최종 UI·추가 질문의 새 wire contract는 이 Goal에서 완료했다고 주장하지 않는다.

## 실제 AI를 켜는 방법

`feat/engine/ce-002-generation`은 서버 API 브랜치의 변경을 포함한다. 프론트 작업 중인 checkout을 reset하지 말고 담당자와 이 브랜치의 통합 시점을 맞춘다. 기존 `dev` 실행은 비용 없는 UI 개발용으로 그대로 둔다.

로컬 DB를 `docker compose up -d --wait postgres`로 준비한 뒤, backend 터미널 환경에 `OPENAI_API_KEY`, `DATABASE_PASSWORD`, `PUBLIC_ORIGIN`, `CANDIDATE_BUDGET_USD`를 설정한다. Spring은 `.env` 파일을 자동으로 읽지 않는다. 키를 명령 인자/채팅/로그에 붙여 넣지 말고 IDE의 비공개 실행 환경이나 터미널의 숨김 입력을 사용한다. 키는 서버에만 필요하며 프론트 담당자에게 전달하지 않는다.

```sh
# 저장소 루트. 키는 이미 비공개 환경에 주입한 상태.
export DATABASE_PASSWORD=worldcup-local-only
export PUBLIC_ORIGIN=http://127.0.0.1:5173
# 예시: 이 DB의 누적 허용액 $1. 자동 초기화되지 않음.
export CANDIDATE_BUDGET_USD=1.00
cd backend
./gradlew bootRun --args='--spring.profiles.active=live --worldcup.cookie-secure=false'
```

위의 HTTP/비보안 쿠키 설정은 **자기 컴퓨터의 loopback 개발에만** 사용한다. 배포는 `prod,live`, HTTPS origin, Secure cookie와 별도 DB 자격정보를 사용한다. 실제 공개 배포/무인 과금 허용은 아직 범위 밖이다.

모델 설정 `CANDIDATE_MODEL`, `CANDIDATE_REVIEW_MODEL`은 `gpt-5.6-terra` 또는 `gpt-5.6-luna`다. 가격 표에 없는 모델은 호출하지 않는다. `provider_call`에는 원문/키 없이 단계, 모델, 사용량, 추정 비용만 남는다. `accounted_usd` 합계에는 진행 중·미확인 비용 예약도 포함된다. 이 DB를 교체해도 계정의 과금이 사라지는 것은 아니므로 새 DB/평가 실행은 기존 계정 사용액을 포함해 남은 예산을 정한다. 실패 예약을 임의로 지우거나 예산을 올리지 않는다.

일반 `test`/`verify`는 실제 제공자를 호출하지 않는다. 별도 승인 예산 안의 합성 입력 평가만 `CANDIDATE_LIVE_TEST=true`, 남은 `CANDIDATE_BUDGET_USD`, 서버 키를 환경에 설정하고 아래를 실행한다. `CANDIDATE_LIVE_SIZE`는 8(기본)/16/32이며 한 번에 한 입력만 호출한다. 유료 테스트 DB는 매 실행 격리되므로 **재실행 전** `reports/local/live-engine/*/ledger.json`과 이전 계정 실험 사용액을 합산해야 한다.

```sh
./gradlew --no-daemon test --tests '*LiveEngineHttpTest' --rerun-tasks
```

원 요청 설정·첫 응답·Repair 응답·사용량·HTTP 결과는 gitignored `reports/local/live-engine/`에 남는다. 일반 서버에는 이 원문 기록 기능을 연결하지 않는다. 통과한 1개 사례가 보편적인 품질/성공률/지연 보장이 되는 것은 아니다.

## 2026-09-15 실제 결과와 남은 일

합성 입력은 “집에서 혼자 조용히 하루 30분씩 꾸준히 할 취미를 고르고 싶어”다. 생성·검토 모델 모두 Terra, reasoning medium, 출력 최대 8,192 tokens. 아래는 최종 `ce002-v4`의 **강수별 1회 실행**이며 전체 시간은 HTTP 제출부터 worker 처리와 최종 조회까지다.

| 강수 | 공개 job 결과 | 호출 / Repair | 시간 | 사용량 기반 추정 비용 |
| --- | --- | --- | --- | --- |
| 8 | READY, 실제 preview 8개 | 3 / 0 | 46.479초 | $0.049174 |
| 16 | QUALITY_GATE_FAILED, preview 미노출 | 5 / 1 | 158.777초 | $0.174444 |
| 32 | QUALITY_GATE_FAILED, preview 미노출 | 5 / 1 | 263.580초 | $0.303001 |

16강은 독서를 여러 장르로 쪼갠 중복 등이 Repair 후에도 남았다. 32강은 논리 퍼즐의 하위 유형 중복과 시간 조건 미확인이 남았다. 모두 미통과 후보를 공개하지 않았다. **8강의 실제 연결만 성공 증거가 있으며, 16/32강 품질 준비 완료나 출시 가능한 성공률을 주장하지 않는다.** 32강은 timeout이 아니라 품질 판정으로 종료했다.

앞선 8강 v1/v2/v3도 모두 보존했다. 각각 조건에 억지로 맞춘 식물 돌봄, schema와 서버 사이의 ID 규칙 불일치, Repair 후 드로잉 중복으로 실패했다. ID schema와 계획의 실현 가능성 지침을 수정했으며 gate 기준은 완화하지 않았다. 같은 8강 사례의 수정–검증 3회 뒤 이 반복은 종료했다. 최종 16/32강은 각각 한 번만 실행했으며 이를 통과할 때까지 다시 뽑지 않았다.

이번 6개 작업·제공자 24회 호출의 추정 비용은 **$0.707748**. 이전 4회 비교 실험 $0.0725468을 포함한 누적은 **$0.7802948 / 승인 $5**다. 실제 usage의 cached/cache-write/output(reasoning 포함)로 계산했으며 확정 청구서 금액은 아니다. 미확인 비용 상태는 없었다. 자동 충전은 앞서 OFF로 저장한 상태를 변경하지 않았다. 비공개 원본은 `reports/local/live-engine/`에 있으며 Git에 올리지 않는다.

코드 전달 범위는 실제 엔진 adapter·기존 API 연결·비용/시간 제한·실패 차단이다. 후속 핵심은 **Coverage Plan의 quota가 서로 다른 핵심 활동으로 실현 가능한지 생성 전에 판별**하는 것, 16/32 품질과 지연 개선, 실제 검색 근거 정확도와 2명 독립 사람 평가다. 프론트 화면·배포는 A/배포 작업으로 남는다. 이 결과를 근거로 전체 Goal이나 CE-002 품질 평가를 완료 처리하지 않는다.

최종 일반 검증: Node 24의 `scripts/verify.sh` 통과. Java 119개 실행(실패/오류 0), opt-in 유료 테스트 1개는 의도적으로 제외. handoff 6개 테스트, 웹 build, bootJar, 공용 fixture 5개, 기존 실제 HTTP 응답 102개/8개 schema 통과. 별도 실제 AI 실행의 HTTP 응답 13개도 원 OpenAPI schema와 대조해 통과했으며 FAILED 응답을 READY 성공으로 세지 않았다. 첫 전체 검증의 Gradle cache 접근 권한 오류는 필요한 권한으로 재실행해 해소했다.

읽기 전용 독립 리뷰는 2회 완료, 최종 Critical 0 / High 0 / 남은 코드 finding 0. 발견한 Medium ID schema 불일치는 수정과 집중 테스트로 확인했다. 외부 검색의 실제 사실 정확도와 제품 후보 품질이 이 코드 리뷰만으로 검증된 것은 아니다. `frontend/**`와 `contracts/**`는 변경하지 않았다.

공식 근거: [Responses](https://developers.openai.com/api/reference/cli/resources/responses/methods/create), [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Web search와 실제 sources](https://developers.openai.com/api/docs/guides/tools-web-search), [Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra), [Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna). 2026-09-15 확인.
