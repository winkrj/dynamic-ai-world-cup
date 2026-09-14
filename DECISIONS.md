# Decision Log

`P`는 대화에서 확인한 최신 제품 결정, `TD`는 이번 기술 설계 결정이다. 과거 전체 로그 원문은 확보되지 않았으며 이 파일은 그 원문을 대체한 것으로 주장하지 않는다.

| ID | 상태 | 결정 / 이유 |
| --- | --- | --- |
| P-01 | 확정 | Solo-first / Group-compatible. 실시간 그룹 투표 제외 |
| P-02 | 확정 | Candidate Quality 최우선. N개 전체의 다양성·비교 단위·실제 선택 가능성을 검증 |
| P-03 | 확정 | Coverage Plan을 질문마다 생성. 범용 후보 카테고리를 코드에 고정하지 않음 |
| P-04 | 확정 | 현재 bracket에 history로 후보를 교체하지 않음. 다음 생성의 weight shift에만 사용 |
| P-05 | 확정 | 전체 미리보기 → 전체 재생성 1회 → 시작 때 freeze. 이전 Undo/현재 대진 리롤/reserve 제안 폐기 |
| P-06 | 확정 | 경기당 기본 7초; 결승 동일; timeout random은 preference 제외 |
| P-07 | 확정 | 공유된 후보/초기 대진 불변; 재생성 없이 새 세션. 결과 공유와 재플레이 진입 통합 |
| P-08 | 확정 | 시스템 Repair는 사용자 재생성 횟수를 소모하지 않음 |
| P-09 | 확정 | Lab 대화의 calibration 점수를 실제 모델 성과로 제출하지 않음 |
| TD-01 | 확정 (사용자) | Java·Spring Boot 백엔드 |
| TD-02 | 확정 v1 | React/TypeScript/Vite + Java 21/Spring Boot 4.1.1 + PostgreSQL 17. 별도 frontend/backend 디렉터리, 단일 Git 저장소 |
| TD-03 | 확정 v1 | OpenAPI 3.1 JSON이 공통 계약. TS 타입 자동생성, Java 경계는 같은 fixture에 대한 직렬화/역직렬화 테스트 |
| TD-04 | 확정 v1 | 생성은 DB-backed job + polling. 한 Spring 프로세스의 bounded worker로 시작. Redis/별도 큐/agent framework 없음 |
| TD-05 | 확정 v1 | Draft와 immutable Snapshot, Session을 분리. DB transaction/CAS/unique key로 재생성·freeze·재시도 처리 |
| TD-06 | 확정 v1 | 솔로 경기 timer/RNG는 클라이언트 실행; 서버는 업로드된 bracket 진행을 검증. 경쟁/보상용 anti-cheat는 범위 밖 |
| TD-07 | 확정 v1 | 숨김 시 현재 deadline 유지, 복귀 시 현재 경기만 timeout. timestamp 조작 완전 방지는 하지 않음 |
| TD-08 | 확정 v1 | 재생성은 성공 교체 1회, 진행 중 슬롯 예약. 실패 재시도는 같은 operation; 동시 요청 409 |
| TD-09 | 확정 v1 | 생성 Repair 최대 1회. 임시 생성/검색 장애는 bounded retry와 전체 deadline 내 처리; 실패 후보 노출 금지 |
| TD-10 | 확정 v1 | 초기 모델/검색은 adapter 경계로 격리, 구체 제공자/모델은 실제 8/16/32 eval 후 결정. 초기 티켓에서 유료 API를 호출하지 않음 |
| TD-11 | 확정 v1 | 운영은 동일 origin의 정적 frontend + Spring API + PostgreSQL. 배포 사업자 계정/요금은 배포 티켓에서 결정; 현재 배포 없음 |
| TD-12 | 확정 v1 | 현재 선택된 GitHub 계정의 private 저장소로 생성. 팀원 GitHub ID를 받기 전 협업자 초대/실명 CODEOWNERS 등록 없음 |
| TD-13 | 확정 (사용자, 2026-09-14) | 링크를 받은 동료가 바로 개발을 시작하도록 저장소를 public으로 전환. 기존 커밋 작성자 정보 공개도 승인. TD-12의 공개 범위는 이 결정으로 변경한다. 초대 없는 참여자는 clone/Fork/PR, 원본 직접 push는 쓰기 권한이 필요하며 기존 ownership은 유지 |

변경은 `문제 → 대안 → 결정 → 영향받는 AC/계약 → 검증`을 기록한다. 확정된 제품 규칙 변경은 사용자 결정이 필요하다.
