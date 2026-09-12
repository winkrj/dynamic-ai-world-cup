# Acceptance Criteria v1.0

구현 완료와 설계 확정을 구별한다. 초기 구현 범위는 `docs/TICKETS.md`와 `PROJECT_CONTEXT.md`에 기록한다.

| ID | 완료 기준 | 티켓 / 검증 |
| --- | --- | --- |
| AC-01 | 8/16/32만 허용, 빈 고민 거절, no-login 생성 | B-002 API 및 FE-001 |
| AC-02 | 요청 N과 후보 수 일치, 동일 ID/정규화 이름 중복 거절 | CE-001 단위 테스트 |
| AC-03 | Candidate Unit 일치, Coverage Plan quota 합=N, 각 quota 충족 | CE-001 단위 테스트 |
| AC-04 | 모든 hard constraint의 독립 assessment=PASS; 누락/UNKNOWN/FAIL 거절 | CE-001; 실제 assessment 정확도는 CE-002 live eval |
| AC-05 | 외부 확인 필요 후보는 날짜/출처를 가진 확인 근거가 있어야 하며 오래되거나 확인 안 된 근거 거절 | CE-001; 검색 적합성은 CE-002 live eval |
| AC-06 | semantic duplicate/비교 가능성 review 미수행 또는 실패는 gate 거절 | CE-001; review 정확도는 실제 eval |
| AC-07 | LLM/schema/grounding 실패 시 최대 1회 Repair, 재검증 실패하면 노출 금지, 자동 강수 축소 없음 | CE-002 |
| AC-08 | 전체 N개 미리보기; 전체 성공 교체 최대 1회; Repair는 횟수 소모 없음 | B-002 + FE-001; 동시/재시도 테스트 |
| AC-09 | 시작의 expectedVersion 일치 검사와 atomic freeze; freeze 후 후보/순서/rules 변경 불가 | B-003 DB 통합 테스트 |
| AC-10 | 두 카드 준비 후 7000ms; 6999ms direct, 7000ms timeout; 진입/이미지 로딩 중 미소모 | FE-002 fake-clock 테스트 |
| AC-11 | timeout 후보는 현재 A/B 중 균등 랜덤; 중복 클릭/timeout 경합에서 승자 1개 | FE-002 RNG/경합 테스트 |
| AC-12 | N−1 결정, 결승 동일 규칙, Undo/경기 중 리롤 없음 | FE-002 8/16/32 진행 테스트 |
| AC-13 | 숨김/복귀/새로고침 때 현재 경기 deadline 유지, 숨겨진 다음 경기 미자동진행 | FE-002 브라우저 테스트 |
| AC-14 | direct만 preference에 반영, timeout/중복 업로드 제외, 현재 조건이 history보다 우선 | B-004 + CE-003 |
| AC-15 | 공유는 원본 snapshot ID/후보/initialOrder/rules 보존, AI 호출=0, 새 세션은 별도 선택/우승 | B-003 + FE-003 통합 테스트 |
| AC-16 | 생성/재생성/시작/선택 업로드 idempotent; 소유권/버전/불일치 요청 거절 | B-002/003/004 |
| AC-17 | 360px 화면·키보드·fallback·reduced motion으로 완주 | FE-001/002/003 브라우저 검증 |
| AC-18 | 실제 provider 실행의 후보 원문·버전·latency·repair·usage·평가를 기록, calibration과 분리 | CE-002 + EVAL-001 |

CE-001은 판정 결과를 집행하는 코드다. LLM의 의미 판정이나 실제 장소의 사실 확인을 구현 완료했다고 해석하지 않는다.
