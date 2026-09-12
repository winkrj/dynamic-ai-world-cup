# Candidate Quality 평가

담당: B — 후보 품질과 서버. CE-001 JUnit은 품질 판정을 집행하는 코드의 회귀 테스트다. 실제 추천 품질 평가는 CE-002 provider 연결 뒤 별도로 수행한다.

## 최초 실제 실행 집합

`cases.json`의 6개 고민을 8/16/32강으로 각각 실행한다(18세트). 각 질문의 기준 날짜/timezone, 모델/prompt/validator 버전, generation strategy를 기록한다. 질문/크기별 첫 세트로 실패 유형을 확인하고 비교 실험은 같은 조건에서 독립 실행한다. 이전 대화의 원래 12개 Lab 질문 원문은 없으므로 이 파일을 원본이라고 부르지 않는다.

## 판정

Hard gate: 요청 개수 일치, 명시 hard 조건 전부 만족, 동일/의미 중복 없음, 같은 비교 단위, 실제 후보의 이용 가능성 확인. 하나라도 실패/판정 불가이면 노출 실패다. code gate 통과 뒤 사람이 출처/조건/의미를 검토한다. LLM self-score 하나로 pass를 만들지 않는다.

통과 세트의 Relevance, Diversity, Coverage, Tournament Playability, Context Fit을 1–5로 평가한다. 기존 내부 기준: 평균 ≥4.0, 모든 축 ≥3.0. 최소 2명의 사람이 버전명을 가린 결과를 독립 평가하고 차이를 기록한다.

## 실제 harness가 기록할 필드

runId, caseId, size, startedAt, timezone, strategy, model/provider/prompt/schema/validator version, raw provider response, initial/final candidate set, source URL·조회일, hard violation codes, repair count, stage latency/total latency, input/output usage, 계산 가능한 실제 비용, 평가자별 점수/메모.

API token/개인 원문은 저장하지 않는다. 실제 결과는 `reports/local/`(gitignore)에 보관하고 제출용 익명화 결과만 review 후 commit한다. 오류와 실패 결과도 유지한다. 현재 실제 실행 횟수는 **0**이다.
