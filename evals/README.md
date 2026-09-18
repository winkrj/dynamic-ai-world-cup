# Candidate Quality 평가

담당: B — 후보 품질과 서버. CE-001 JUnit은 품질 판정을 집행하는 코드의 회귀 테스트다. 실제 추천 품질 평가는 CE-002 provider 연결 뒤 별도로 수행한다.

## 최초 실제 실행 집합

`cases.json`의 6개 고민을 8/16/32강으로 각각 실행한다(18세트). 각 질문의 기준 날짜/timezone, 모델/prompt/validator 버전, generation strategy를 기록한다. 질문/크기별 첫 세트로 실패 유형을 확인하고 비교 실험은 같은 조건에서 독립 실행한다. 이전 대화의 원래 12개 Lab 질문 원문은 없으므로 이 파일을 원본이라고 부르지 않는다.

## 판정

Hard gate: 요청 개수 일치, 명시 hard 조건 전부 만족, 동일/의미 중복 없음, 같은 비교 단위, 실제 후보의 이용 가능성 확인. 하나라도 실패/판정 불가이면 노출 실패다. code gate 통과 뒤 사람이 출처/조건/의미를 검토한다. LLM self-score 하나로 pass를 만들지 않는다.

통과 세트의 Relevance, Diversity, Coverage, Tournament Playability, Context Fit을 1–5로 평가한다. 기존 내부 기준: 평균 ≥4.0, 모든 축 ≥3.0. 최소 2명의 사람이 버전명을 가린 결과를 독립 평가하고 차이를 기록한다.

## 실제 harness가 기록할 필드

runId, caseId, size, startedAt, timezone, strategy, model/provider/prompt/schema/validator version, raw provider response, initial/final candidate set, source URL·조회일, hard violation codes, repair count, stage latency/total latency, input/output usage, 계산 가능한 실제 비용, 평가자별 점수/메모.

API token/개인 원문은 저장하지 않는다. 실제 결과는 `reports/local/`(gitignore)에 보관하고 제출용 익명화 결과만 review 후 commit한다. 오류와 실패 결과도 유지한다.

## 사람 검토 자료

[후보 평가 R](review/R.md)는 기존 실제 미리보기 한 세트의 이름·태그·순서를 옮긴 검토용 자료다. 평가자에게는 이 시트만 전달하고 두 사람의 답변을 별도로 받는다. 공개 자료와 앞선 대화를 이미 본 경우 그 노출을 기록하며 완전한 눈가림을 주장하지 않는다. 원 실행 대응과 한계는 `docs/CANDIDATE_ENGINE.md`의 ‘사람 검토 인계’에 남긴다. 시트 작성 당시에는 사람 점수나 품질 승인이 없었다.

2026-09-18 후속: 사용자 검토에서 비교 단위와 실행 가능성이 FAIL로 남았다. 이는 2명 독립 평가 완료나 품질 승인이 아니며, 내부 근거를 나중에 보더라도 당시 preview 판단은 덮어쓰지 않는다. v17의 후보별 필수 전제 검사는 이 피드백을 반영한 구현 보완이다. 합성 회귀 테스트와 실제 모델의 판단 개선을 구분하고, 대조 평가 기준/미실행 상태는 `docs/CANDIDATE_ENGINE.md` v17 절을 따른다.

## 실행 현황과 구분

2026-09-16: `seed-v1` 고유 **11/18세트**를 처음 실행했다. 첫 결과는 READY 5세트(home/8, social/32, solo/8·16·32), `QUALITY_GATE_FAILED` 6세트(social/8·16, home/16·32, seoul-indoor/8, seoul-parent/8)다. 일반 취미 9세트의 최초 실행을 마쳤고 외부 사실이 필요한 7세트는 미실행이다. 후속 비교는 첫 결과를 덮어쓰지 않는다. v15 solo/32는 사전 Repair 후 preview까지 연결됐지만 퍼즐·그림·독서 분할과 비운동 제외 조건의 soft 분류를 자동 검토가 통과시켜 사람 기준을 충족했다고 보지 않는다. home/32는 시간 조건 해석과 포괄적인 강좌 후보 문제로 차단됐다. 이전 Terra/Luna 생성-only 4회와 별도 `hobby-calibration`은 seed 완료로 세지 않는다. 버전별 실행·비용·검색 예약 제약은 `docs/CANDIDATE_ENGINE.md`에 통합 기록한다. 2명 독립 사람 평가는 미완료다.

유료 harness는 `CANDIDATE_LIVE_CASE`로 이 파일의 사례 ID를 선택하며 질문 원문을 변경하지 않는다. 기본 `hobby-calibration`은 datasetVersion `calibration-2026-09-15`로 별도 표시한다. `CANDIDATE_LIVE_SIZE=8|16|32`; 일반 검증에서는 `CANDIDATE_LIVE_TEST=false`로 실제 과금을 막는다. 정확한 실행 환경·키/예산 주입·누적 비용 대조는 `docs/CANDIDATE_ENGINE.md`를 따른다.
