# 원문과 구현 기준의 출처

기준일: 2026-09-12. 참조 대화: [해커톤 목적 요강 분석](chatgpt-conversation://6a9e6446-bda4-83ee-96b4-e8c5a45b2f3e).

현재 ChatGPT 프로젝트의 `sources/`는 비어 있다. 이전 대화에는 PRD / Design Spec / Acceptance Criteria / Decision Log의 작성 완료 메시지와 첨부 참조가 있으나, 해당 첨부 원문은 현재 도구에서 제공되지 않았다. 이 저장소의 동명 문서는 원문 복사본이 아니라 **최신 사용자 요청과 조회한 대화의 확정 결정을 복원한 구현 기준**이다. 원문을 확보하면 차이를 대조하며, 복원되지 않은 내용을 과거 합의였다고 주장하지 않는다.

우선순위: 현재 사용자 명시 지시 > 대화의 최신 확정 결정 > 이번 Technical Design의 구현 결정 > 과거 제안. 과거 Undo / 경기 중 리롤 / reserve 후보 제안은 최신 결정에서 폐기됐다.

| 출처 | 확인된 내용 |
| --- | --- |
| 이번 사용자 요청 | Candidate Engine 단계와 순서, 8/16/32, 전체 미리보기, 전체 재생성 1회, freeze, 7초, timeout random, Undo 없음, 동일 결승 규칙, immutable 공유, 2인 병렬 개발 |
| 이번 사용자 후속 답변 | 백엔드는 Java·Spring Boot |
| 대화의 최신 기획 마감 메시지 | 시스템 Repair는 사용자 재생성 횟수를 소모하지 않음; 후보 전체 미리보기 후 시작 |
| 대화의 UX v0.2 승인 및 후속 수정 | no-login, 카드 이름·이미지·태그 최대 2개, 카드 완전 표시/진입 연출 후 타이머, 결승 정보 추가 없음, Champion의 공유 CTA |
| Candidate Lab Run 003 및 앞선 기준 | Coverage Plan은 질문에 따라 동적 생성, history는 filtering 아닌 weight shift, timeout은 preference 제외, 평가 hard gate 및 5개 품질축 |
| Candidate Lab 마감 | 대화 속 calibration 점수는 실제 API 실험 성과가 아님 |

이전 문서의 배경 전환, 실패 재시도, 익명 쿠키, 보존 기간, 모델/검색 제공자 상세는 원문 확인 불가다. 이번 선택은 `DECISIONS.md`의 TD 항목으로 명시한다.

사용자가 함께 언급한 `work-blog-skill-handoff.zip`은 블로그 편집 스킬 전달 파일이며 이 제품의 요구사항 문서로 사용하지 않았다.
