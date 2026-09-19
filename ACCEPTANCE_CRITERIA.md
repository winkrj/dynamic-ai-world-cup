# Acceptance Criteria v1.0

구현 완료와 설계 확정을 구별한다. 초기 구현 범위는 `docs/TICKETS.md`와 `PROJECT_CONTEXT.md`에 기록한다.

TD-54(2026-09-19) 예외 범위: 아래 AC-03의 사전 quota 승인, AC-04/06의 독립 의미 판정, AC-07의 Repair, AC-20의 모든 gate 유지 조건은 기존 `staged` 전략에 적용된다. 사용자 승인한 신규 `catalog` 전략은 원문 조건을 최우선으로 하는 best-effort 단일 선택·보충 + 명시적 서버 구조 검사로 바꾼다. 미수행 검토를 PASS로 만들지 않는다. 최신 사실 필요 판단은 단일 모델에 의존하고 GROUNDING_REQUIRED를 반환하면 종료하며, 실제 외부 사실 검증 성공은 주장하지 않는다. 나머지 게임/계약/일일/비용 기준은 유지한다. 추가 검증 기준은 다음과 같다.

| ID | catalog 완료 기준 |
| --- | --- |
| AC-21 | 정확한 편집 preset/개인 이력 없음/후보 충분 시 DB로 생성, AI 0회. 조건이 추가된 문장을 정확 hit로 처리하지 않음 |
| AC-22 | 일반 요청의 DB ID 선택·부족분 보충은 provider 최대 1회. 검색/reviewer/Repair/느린 엔진 자동 fallback 없음. 실제 공급 ID만 허용, 알려진 이름/family 중복·N·단위·필드 검사 |
| AC-23 | FAST_BEST_EFFORT와 validatorVersion 구별, 독립 certificate 없음, 공용 승인 세트로 자동 승격 안 함. 프롬프트 의미 정확도/목표 속도를 실측 완료로 위장하지 않음 |
| AC-24 | V4 편집 후보의 provenance/time-independent/active를 확인. 새 private 생성 원문/결과 자동 공용화 없음. DB preset 생성·재생성·freeze·완주·동일 공유 연결에서 추가 AI 0회 |

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
| AC-19 | 익명 브라우저별 하루 2회 새 생성/재생성 접수, 서울 자정 초기화. 접수 후 FAILED 포함, 동일 key 재전송·접수 rollback·Repair·공유 플레이 추가 차감 없음. 동시 요청도 한도 보존, 정리 작업이 당일 기록을 지우지 않음. 429에 실제 해제 시각까지의 Retry-After와 수동 재시도 안내 | TD-40; DB/HTTP·프론트 회귀 테스트 |
| AC-20 | 16강 기본·8/16/32 유지. 생성·검토는 평범함/호불호/낮은 신선함만으로 적격 후보를 탈락시키지 않으며 필수 조건·중복·단위·지속성·실행 가능성·근거 검사를 보존. 서버의 FAIL/UNKNOWN 집행 및 Repair 합계 1회 유지 | TD-49/51; v23 생성·독립 검토·Repair 공통 지침 전달·기존 gate 회귀. 실제 의미 정확도는 별도 모델 관찰 |

CE-001은 판정 결과를 집행하는 코드다. LLM의 의미 판정이나 실제 장소의 사실 확인을 구현 완료했다고 해석하지 않는다.
