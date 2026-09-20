# B — 후보 품질과 서버

TD-59: compact v6-clear-subject는 활동 외 작품·일반 선택을 지원한다. family는 비교 단위에 따른 동일 선택의 키이며, 작품에서는 장르/가수가 아닌 작품 식별이다. v5의 실제 추가 질문 실패는 보존하고 선택적 취향 미기재만으로 명확한 주제를 거절하지 않는다. 중복 검사를 없애거나 실제 검색을 추가하지 않는다. 제목/창작자 정확도는 모델 추정이고 최신 차트·현재 이용 가능성은 GROUNDING_REQUIRED다. docs/CATALOG_ENGINE.md의 최신 검증/미배포 상태를 따른다.

TD-58 현재 제출 수정: 운영 catalog의 compact v4-activity-context는 지역·요일 활동 아이디어와 실제 장소/최신 사실을 구별한다. 후자는 별도 GROUNDING_REQUIRED이며 DB·HTTP에 그대로 보존한다. 공개 오류 enum 추가 외 요청/DB/모델/호출/예산은 불변이다. 최신 관측과 배포 상태는 docs/V1_SUBMISSION.md를 따르며 아래 staged 미배포 표시는 당시 기록이다.

TD-55 제출 준비(2026-09-20): catalog 운영 적용을 준비하며 compact prompt `ce003-v3-core-experience`를 사용한다. 새32강2관측/추가$0.032856 뒤 최신 실험 누적$5.4206158/$6, 잔여$0.5793842(미확인.50보존). 실제 연결은 통과했지만32강의 의미 유사 후보는 남았으며 품질PASS로 기록하지 않는다. 루트 TD-55와 docs/V1_SUBMISSION.md/CATALOG_ENGINE.md가 최신. 과거 TD-54의7회·v2수치는 당시기록이다.

TD-54 로컬 구현·검증 완료: 실제 8개 실험/유료 7회, 추가 $0.09224, 누적 **$5.3877598/$6**(미확인 .50 포함), 잔여 $0.6122402. 최종 compact prompt `ce003-v2-visible-choice`; 식사 조건의 보이지 않는 변형 문제를 1회 수정/재비교했다. 전체 verify Java451 실행·유료2 제외·최종 독립 리뷰 Critical/High/actionable0. 운영 기본 staged/미배포. 상세 관측·한계는 docs/CATALOG_ENGINE.md.

최신 TD-54는 루트 지침과 `docs/CATALOG_ENGINE.md`를 따른다. 사용자 승인한 `catalog` 전략은 DB 편집 후보 + 최대 1회 compact 선택/보충, 별도 best-effort acceptance이며 기존 독립 evidence를 합성하지 않는다. 정확한 preset 외 자연어 조건의 의미 판단은 모델의 한 번의 선택에 의존한다. 신규 V4 카탈로그만 사용하고 V1~V3/기존 FAILED/certificate/snapshot은 보존한다. 실제 장소·최신 사실은 이 경로에서 검증됐다고 주장하지 않는다. 기존 `staged`는 아래 규칙을 그대로 유지한다. 운영 기본 전략은 아직 staged이며 로컬 측정과 배포는 별도다. 새 전략의 실험도 기존 누적 $6·.50 예약·검색 없음·순차 실행·bounded loop를 따른다.

최신 TD-51: v23은 GENERATE(plan→전체 cards) → 필요한 selective grounding → 독립 REVIEW → 필요 시 REPAIR 1회·전체 재검토다. 계획의 조건/단위/coverage는 Repair에서 고정, 미지정 후보는 동일하게 보존하며 새 coreActivity도 독립 검토한다. 해석 FAIL/UNKNOWN을 후보 Repair로 우회하지 않는다. 사전 allocation은 runtime port 밖으로 제거, 과거 진단 경로만 보존한다. certificate는 실제 최종 review만 저장하고 policy=v4-engine-v23로 구 데이터의 재사용을 거절한다. 공개 계약/모델/8·16·32/게임 규칙/토큰·시간·누적 예산·검색 승인은 불변이다.

최신 TD-50: 사용자는 이후 목적이 명확한 실제 평가를 순차 1건씩 사전 승인했다. 매번 승인 질문은 생략하되 기존 누적 $6·검색 없음·자동 충전 OFF·모델/Repair/예약 한도와 동일 실패 수정–검증 최대 3회는 유지한다. 매 실험 전 전체 장부를 합산하고 자식 프로세스에만 live opt-in을 전달한다. v23/16 실제 1회는 READY·완주 저장·동일 공유/새 세션 PASS(127.012초/4회/$0.1380685/Repair 1회)다. 후속 v23/32는 185.026초/4회/$0.221852, Repair 1회 뒤 그림 중복 FAIL로 종료했다. preview 없음·재추첨 없음, 현재 누적 $5.2955198, 잔여 $0.7044802다. 선불 잔액 소진을 즉시 차단 보장으로 보지 않는다. 고정 TD-48 진단 재사용·무한 재추첨·운영 예산 승인은 아니다. 아래 추가 미승인 표시는 당시 기록으로 읽는다.

최신 TD-49: 1차 버전은 8/16/32·16강 기본을 유지하고 평범함/호불호/낮은 신선함 자체를 탈락 사유로 쓰지 않는다. v22 생성·검토 지침에서 주관적 매력과 필수 적격성을 분리한다. 조건·중복·단위·지속성·feasibility·grounding, FAIL/UNKNOWN 집행과 Repair 합계 1회는 유지한다. 정책 v3-engine-v22로 이전 승인 데이터와 분리하며, 32강 제거/자동 축소는 없다. 후속 승인한 검색 없는 hobby-solo/16 1회는 UNIT 해석 FAIL·승인 활동 15/16으로 실패했다. 4회/$0.063963, 누적 장부 $4.9355993/$6(미확인 예약 $0.50 포함), 자동 충전 OFF. 승인 소진·재시도 없음·preview/완주 없음, 두 유료 플래그는 false로 유지한다. 아래 버전·검증·비용 기록은 당시 결과다.

최신 TD-48: 승인한 고정 입력 조건 해석 진단 1회를 실행했다. v21 검토기가 비운동 제외의 soft 오분류를 CONSTRAINTS FAIL로 검출했고 테스트 PASS, 1회/$0.021828/검색·생성·Repair 0이다. 누적 장부 $4.8716363/$6(미확인 예약 $0.50 포함), 자동 충전 OFF. 승인 소진, 추가 유료 호출 없음. `CANDIDATE_LIVE_TEST=false CANDIDATE_INTERPRETATION_DIAGNOSTIC=false`를 유지한다. 전체 32강·사람 품질·중복 해결은 미완료이며 아래 미실행/이전 비용은 과거 기록이다.

TD-47/v21: 직접적인 범주 거부와 상대적 선호·부정문을 공통 프롬프트에서 대조한다. 키워드별 서버 예외나 자동 PASS는 추가하지 않는다. 재사용 정책 v3-engine-v21은 이전 해석 기준의 승인 세트를 그대로 사용하지 않게 분리한다. 모델/API/schema/호출·Repair·예산 상한 유지, 추가 유료 실행 0, 실제 분류 개선/중복 해결은 미검증이다.

최신 TD-46 승인 소진: v20 hobby-solo/32 실제 1회는 사전 Repair 뒤에도 승인 활동 30/32로 실패했다. 4회/$0.08744/검색 0, 상세 후보·preview 없음. 누적 장부 $4.8498083/$6(미확인 예약 $0.50 포함), 자동 충전 OFF, 추가 유료 호출 금지. 이후 검증은 `CANDIDATE_LIVE_TEST=false`로 한다. 같은 결과의 완주/공유 검사 코드는 합성 HTTP 테스트로 통과했지만 이번 실제 결과에는 실행하지 못했다. AWS 비용 기준은 루트 TD-45의 Free plan 내 실제 결제 $0 우선이며 자원 미생성이다. 아래 금액·실행 상태는 과거 기록이다.

최신 TD-42~44: DB 검증 재사용·서비스 마감·배포 구현은 승인됐다. 사업자/월 운영비는 답변 대기. v19 검색 없는 32강 1회 승인은 사용 완료(실패, $0.238354), 누적 장부 $4.7623683/$6·새 유료 호출 승인 없음. v20은 사용자가 정정한 '구입·준비 가능한 장비/기술 vs 통제하기 어려운 외부 환경'을 구별한다. 오븐 미보유/미기재 자체를 UNKNOWN으로 만들지 않되 명시 구매·총예산/공간/소음 조건은 지킨다. 기존 FAILED를 승인된 재사용 데이터로 승격하지 않는다. 아래는 이전 시점 기록이다.

TD-41/v19: unit은 선택 대상의 종류·비교 수준, 필수 조건/선호는 기존 constraints/softPreferences에 분리한다. 사용자 요청 범위를 임의로 일반화하거나 해석 FAIL/UNKNOWN을 무시하지 않는다. 공통 프롬프트와 회귀 테스트 보완만 하며 모델/공개 계약/schema/Repair·비용 한도는 유지한다. 실제 v19 평가는 미실행·추가 승인 없음.

최신 TD-39/40: 누적 실험 상한 $6, 승인한 v18 hobby-solo/32 단일 시도는 사용 완료했다. PLAN/ALLOCATE 2회 뒤 해석 UNIT FAIL, preview 없음. 누적 장부 $4.5240143(미확인 예약 $0.50 포함), 잔여 $1.4759857, 자동 충전 OFF 유지. 추가 유료 호출 승인 없음. 아래 v17/$5 기록은 당시 상태다. 일일 접수 한도는 익명 브라우저별 2회/서울 자정(생성·재생성·접수 후 실패 합산)이며 재전송/접수 rollback/Repair/공유 비차감과 기존 IP burst·전역 비용 차단을 유지한다. DB 후보 재사용은 아직 설계 제안이며 이번 quota 구현에 섞지 않는다.

TD-38/v18: 소음 제한이 없으면 일반적인 집 안 하모니카 연습을 제안할 수 있다는 사용자 판단을 반영한다. 통상적 집 안 환경과 특정 자연환경·전용 시설·특수 장비를 공통 생성/검토 프롬프트에서 구분한다. 명시 조건·grounding·UNKNOWN/FAIL 차단·Repair 합계 1회는 그대로다. 실제 유료 평가 현황은 위 최신 TD-39를 따른다.

2026-09-18 첫 평가 승인: 수정된 v17 엔진의 검색 없는 실제 32강 평가 **1회**만 기존 누적 $5 한도 내에서 허용했다. 첫 PLAN이 401/expired_secret_key로 종료되어 해당 시도는 사용했다. 새 키/재개 전 추가 호출 금지. 실행 전 이전 ledger/생성 비교 비용을 합산하고 잔여액을 격리 평가 DB의 한도로 주입한다. 실패 재추첨·추가 검색·예약액 완화 금지. $0.50 미확인 비용 예약은 보존한다.

후속 TD-37의 키 교체 후 v17 `hobby-solo/32` 추가 시도 1회도 사용 완료했다. 인증은 복구됐으나 하모니카 연습 환경 feasibility UNKNOWN으로 `QUALITY_GATE_FAILED`; 사전 Repair 1회/검색 0회/preview 없음. 당시 장부 $4.4511778, 잔여 $0.5488222였으며 $0.50 예약·검색 없음·자동 충전 OFF를 보존했다. 최신 한도와 단일 평가 사용 상태는 위 TD-39를 따른다. 실제 근거는 docs/CANDIDATE_ENGINE.md의 TD-37 결과를 따른다.

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
