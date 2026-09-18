# Candidate Quality 평가

TD-51 최신 관측: v23은 단일 생성→독립 전체 검토→필요 시 Repair 1회·재검토로 전환했다. 검색 없는 hobby-solo/16 1회는 READY·같은 후보의 완주 저장·공유까지 PASS(127.012초/4회/$0.1380685/Repair 1회)다. 누적 장부 $5.0736678/$6, 자동 충전 OFF. 일반 주방을 과도하게 미확인으로 본 판정과 v23 8/32 실측·외부 사실/사람 평가 미완료는 남는다. 아래 과거 금액·FAIL, 18세트 첫 실행 11/18 및 R 사람 FAIL은 덮어쓰지 않는다. 상세는 `docs/CANDIDATE_ENGINE.md` TD-51 절이다.

최신 TD-50: 사용자가 이후 필요한 실제 평가를 순차 1건씩 사전 승인했다. 매번 승인 질문은 생략하되 기존 누적 $6·검색 없음·자동 충전 OFF·모델/Repair/예약 한도와 동일 실패 수정–검증 최대 3회는 유지한다. 실험 목적·기존 결과·누적 장부를 먼저 대조하고 실패를 성공할 때까지 재추첨하지 않는다. 일반 테스트는 두 유료 플래그 false, 해당 실험 자식 프로세스만 opt-in한다. 소진한 고정 TD-48 진단은 재사용하지 않는다. 아래 1회 승인 소진/추가 미승인은 각 실행 당시 기록이다. 현재 장부 $4.9355993/$6이며 v22 실제 16강 결과는 `docs/CANDIDATE_ENGINE.md` TD-49에 기록했다.

담당: B — 후보 품질과 서버. CE-001 JUnit은 품질 판정을 집행하는 코드의 회귀 테스트다. 실제 추천 품질 평가는 CE-002 provider 연결 뒤 별도로 수행한다.

## 최초 실제 실행 집합

`cases.json`의 6개 고민을 8/16/32강으로 각각 실행한다(18세트). 각 질문의 기준 날짜/timezone, 모델/prompt/validator 버전, generation strategy를 기록한다. 질문/크기별 첫 세트로 실패 유형을 확인하고 비교 실험은 같은 조건에서 독립 실행한다. 이전 대화의 원래 12개 Lab 질문 원문은 없으므로 이 파일을 원본이라고 부르지 않는다.

## 판정

TD-49 1차 버전: 평범하거나 호불호 있는 후보도 적격할 수 있다. 필수 조건·중복·비교 단위·지속성·실행 가능성과 주관적 매력을 구분한다. 모든 입력 또는 모든 후보의 최상급 흥미를 입증할 때까지 같은 32강을 재추첨하지 않는다. 아래 2명 독립 평가와 18세트는 확장 품질 평가 계획으로 보존하며, 사용자 기준의 1차 구현 완료를 전체 평가 완료·운영 승인으로 바꾸어 보고하지 않는다.

Hard gate: 요청 개수 일치, 명시 hard 조건 전부 만족, 동일/의미 중복 없음, 같은 비교 단위, 실제 후보의 이용 가능성 확인. 하나라도 실패/판정 불가이면 노출 실패다. code gate 통과 뒤 사람이 출처/조건/의미를 검토한다. LLM self-score 하나로 pass를 만들지 않는다.

통과 세트의 Relevance, Diversity, Coverage, Tournament Playability, Context Fit을 1–5로 평가한다. 기존 내부 기준: 평균 ≥4.0, 모든 축 ≥3.0. 최소 2명의 사람이 버전명을 가린 결과를 독립 평가하고 차이를 기록한다.

## 실제 harness가 기록할 필드

runId, caseId, size, startedAt, timezone, strategy, model/provider/prompt/schema/validator version, raw provider response, initial/final candidate set, source URL·조회일, hard violation codes, repair count, stage latency/total latency, input/output usage, 계산 가능한 실제 비용, 평가자별 점수/메모.

API token/개인 원문은 저장하지 않는다. 실제 결과는 `reports/local/`(gitignore)에 보관하고 제출용 익명화 결과만 review 후 commit한다. 오류와 실패 결과도 유지한다.

## 사람 검토 자료

[후보 평가 R](review/R.md)는 기존 실제 미리보기 한 세트의 이름·태그·순서를 옮긴 검토용 자료다. 평가자에게는 이 시트만 전달하고 두 사람의 답변을 별도로 받는다. 공개 자료와 앞선 대화를 이미 본 경우 그 노출을 기록하며 완전한 눈가림을 주장하지 않는다. 원 실행 대응과 한계는 `docs/CANDIDATE_ENGINE.md`의 ‘사람 검토 인계’에 남긴다. 시트 작성 당시에는 사람 점수나 품질 승인이 없었다.

2026-09-18 후속: 사용자 검토에서 비교 단위와 실행 가능성이 FAIL로 남았다. 이는 2명 독립 평가 완료나 품질 승인이 아니며, 내부 근거를 나중에 보더라도 당시 preview 판단은 덮어쓰지 않는다. v17의 후보별 필수 전제 검사는 이 피드백을 반영한 구현 보완이다. 합성 회귀 테스트와 실제 모델의 판단 개선을 구분하고, 대조 평가 기준/미실행 상태는 `docs/CANDIDATE_ENGINE.md` v17 절을 따른다.

## 실행 현황과 구분

최신 TD-48: 승인한 조건 해석 진단 1회는 실행 완료했다. 고정 v20 계획의 비운동 제외 soft 오분류를 v21 검토기가 CONSTRAINTS FAIL로 검출해 진단은 PASS, 1회/$0.021828/검색·새 생성·Repair 0이다. 누적 장부 $4.8716363/$6(미확인 예약 $0.50 포함), 자동 충전 OFF·추가 호출 미승인. seed 완료 수·전체 품질·사람 R FAIL은 바뀌지 않는다. 아래 미실행/이전 비용은 당시 기록이다.

최신 상태(2026-09-18, TD-46/47): 마지막 승인 v20 hobby-solo/32는 사전 Repair 뒤에도 활동 30/32만 승인돼 FAILED, preview 없음이다. v21은 제외 조건 분류 지침의 오프라인 보완만 검증했으며 실제 호출 0회다. 현재 누적 장부 $4.8498083/$6(미확인 예약 $0.50 포함), 자동 충전 OFF, 추가 유료 실행 미승인이다. 아래 v17/seed 첫 실행 기록은 과거 결과로 보존한다. 최신 원인·검증 범위는 `docs/CANDIDATE_ENGINE.md` TD-46/47을 따른다. 반복된 중복 실패를 새 버전명으로 재시도하거나 기존 R 사람 FAIL을 덮지 않는다.

2026-09-18 v17 `hobby-solo/32` 후속 비교: 만료 키로 종료한 시도 뒤 별도 승인된 재시도 1회를 마쳤다. 인증 복구/6회 HTTP 200/32개 상세 후보 생성은 확인했으나 최종 하모니카 연습 환경 UNKNOWN으로 `QUALITY_GATE_FAILED`, preview 없음이다. 사전 Repair 1회, 검색 0회, 180.132초/$0.218444. 내부 64개 조건 PASS와 feasibility PASS 31/UNKNOWN 1을 구별하며 사람 품질 통과로 세지 않는다. 새 seed 첫 실행이 아니므로 아래 11/18 및 최초 결과는 바꾸지 않는다. 최신 누적 장부와 잔여 한도는 `docs/CANDIDATE_ENGINE.md` TD-37 결과를 따른다. 추가 승인도 사용 완료해 새 승인 전 유료 재시도하지 않는다.

2026-09-16: `seed-v1` 고유 **11/18세트**를 처음 실행했다. 첫 결과는 READY 5세트(home/8, social/32, solo/8·16·32), `QUALITY_GATE_FAILED` 6세트(social/8·16, home/16·32, seoul-indoor/8, seoul-parent/8)다. 일반 취미 9세트의 최초 실행을 마쳤고 외부 사실이 필요한 7세트는 미실행이다. 후속 비교는 첫 결과를 덮어쓰지 않는다. v15 solo/32는 사전 Repair 후 preview까지 연결됐지만 퍼즐·그림·독서 분할과 비운동 제외 조건의 soft 분류를 자동 검토가 통과시켜 사람 기준을 충족했다고 보지 않는다. home/32는 시간 조건 해석과 포괄적인 강좌 후보 문제로 차단됐다. 이전 Terra/Luna 생성-only 4회와 별도 `hobby-calibration`은 seed 완료로 세지 않는다. 버전별 실행·비용·검색 예약 제약은 `docs/CANDIDATE_ENGINE.md`에 통합 기록한다. 2명 독립 사람 평가는 미완료다.

유료 harness는 `CANDIDATE_LIVE_CASE`로 이 파일의 사례 ID를 선택하며 질문 원문을 변경하지 않는다. 기본 `hobby-calibration`은 datasetVersion `calibration-2026-09-15`로 별도 표시한다. `CANDIDATE_LIVE_SIZE=8|16|32`; 일반 검증에서는 `CANDIDATE_LIVE_TEST=false`로 실제 과금을 막는다. 정확한 실행 환경·키/예산 주입·누적 비용 대조는 `docs/CANDIDATE_ENGINE.md`를 따른다.

## 재생성 없는 조건 해석 진단 — 1회 실행 완료, 승인 소진

아래는 실행한 단일 진단의 통제 조건이다. 고정 경로는 이미 사용했으므로 다시 실행하거나 경로/장부 기준을 바꿔 재시도하지 않는다. 결과와 한계는 `docs/CANDIDATE_ENGINE.md` TD-48에 기록했다.

`LiveInterpretationDiagnosticTest`는 기록된 v20의 첫 ALLOCATE 입력만 v21 검토기에 보내는 별도 진단이다. 원래 요청·기준 시각·잘못 분류된 계획을 그대로 보존하고, PLAN/후보 생성/Repair/검색/preview를 실행하지 않는다. 목적은 비운동 제외 조건이 softPreferences에만 들어간 오류를 검토기가 발견하는지 확인하는 것이다. 중복 해결이나 32강 품질 승인, seed 첫 실행으로 집계하지 않는다.

일반 검증에서는 `CANDIDATE_LIVE_TEST=false CANDIDATE_INTERPRETATION_DIAGNOSTIC=false`를 유지한다. 이번에는 별도 명시 승인 후에만 후자를 `true`로 바꾸고 `./gradlew --no-daemon test --tests '*LiveInterpretationDiagnosticTest'` 한 클래스를 실행했다. 전자는 `false`였고 두 플래그를 파일에 저장하지 않았다. 키는 기존 비공개 환경으로만 주입했으며, 승인·자동 충전 OFF·전체 누적 장부를 먼저 확인했다. 환경 플래그나 잔여 예산 자체는 승인이 아니다.

- 고정 v20 기록의 SHA-256과 누적 장부 $4.8498083이 맞아야 실행한다. 과거 생성-only 4회 $0.0725468 및 미확인 비용을 포함한다. 1회 예약 $0.50/전체 $6 이내이며 다른 실험으로 장부가 달라졌다면 재검토 없이 기준값을 바꾸지 않는다.
- 고정 로컬 경로 `reports/local/live-engine/interpretation-v20-allocate-v21-once`를 원자적으로 생성해 재실행을 차단한다. 실패·중단돼도 지우거나 이름을 바꿔 재시도하지 않는다. 호출 전 미확인 예약을 기록하고 응답/DB 장부로 갱신한다. 장부는 같은 디렉터리의 임시 파일 완성 후 원자적으로 교체하며 기존 예약 파일을 먼저 비우지 않는다. 테스트 실패를 이유로 자동 재호출하지 않는다.
- 해석 FAIL과 원문에 연결된 CONSTRAINTS finding을 함께 확인한다. PASS/UNKNOWN, 후보 중복만 지적한 결과, 엉뚱한 필드의 FAIL은 검출 성공이 아니다. 검출 표시는 사람의 이유 검토가 필요하며 후보 품질 PASS로 승격하지 않는다.
- 원시 기록과 비용은 gitignore인 로컬 경로에만 보존한다. 실제 진단 비용은 $0.021828이며 재실행하지 않았다.
