# 프로젝트 컨텍스트

갱신일: 2026-09-16.

목적: 좋은 후보군과 빠른 A/B 선택으로 결정 비용을 줄이는 Dynamic AI World Cup. Candidate Quality가 우선이다.

## 현재 구조와 상태

- 단일 저장소, `frontend` React/TypeScript/Vite, `backend` Java 21/Spring Boot 4.1.1. PostgreSQL 17/JDBC/Flyway 구현. 로컬 compose와 격리된 Testcontainers DB 검증을 사용한다.
- 큰 역할 A=플레이 경험, B=후보 품질과 서버. 제품 화면 작업과 서버 작업을 두 역할 안에서 계속 진행한다.
- `contracts/openapi.json` v1.0.0, 공유 fixture, 자동생성 TS, Java preview 직렬화 비교 테스트.
- CE-001 구현: fixed plan, candidate count/duplicate/unit/coverage, hard assessment, grounded evidence validity, independent semantic-review gate. 검증 통과 타입만 preview mapper에 넘긴다.
- 실제 HTTP 구현: health, 익명 생성 job/preview/전체 재생성, freeze/snapshot, 선택 batch, 공유/새 세션 API. cookie 소유권, idempotency, rate limit, worker lease/복구, 보존 정리 포함.
- 프론트는 개발 상태와 고정 fixture를 보여주는 초기 shell이다. 게임/공유 화면과 배포는 미구현이다.
- `CandidateEngine` port 뒤에 CE-002의 OpenAI plan/생성/선택적 hosted search/독립 검토/Repair 최대 1회를 연결했다. `live`는 명시적 키/누적 예산이 필요하며 dev 혼용은 금지한다. dev 합성 후보는 개발용 표시, 기본 profile은 계속 실패 처리한다. 구현과 실제 검증 범위는 `docs/CANDIDATE_ENGINE.md`를 따른다.
- 직접 선택 history 최대 50건 중 관련 있는 선택만 계획에 반영하도록 했다. 이 호출 경계 구현과 실제 개인화 가중치 품질(CE-003)은 구별한다.

## 검증

CE-001 완료. `./scripts/verify.sh` 통과: 공용 fixture 5개와 생성 타입, TS/production build, Java 42개 테스트(실패/오류 0), bootJar. 독립 Reviewer 2회 중 최초 Medium 1/Low 1을 수정했고 최종 Critical 0 / High 0, 남은 finding 없음. 초기 화면은 360px/1280px에서 가로 넘침과 console error가 없음을 확인했다. 상세는 `docs/VERIFICATION.md`다.

GitHub: https://github.com/winkrj/dynamic-ai-world-cup (public, winkrj). 2026-09-14 사용자 승인으로 공개 전환했으며 인증 없이 저장소와 브랜치 조회를 확인했다. 링크만으로 clone하고 개발을 시작할 수 있다. 원본 쓰기 권한이 없는 참여자는 Fork → 개발 → PR을 사용한다. 구체적인 합류 순서는 README와 `docs/COLLABORATION.md`에 있다.

초기 검증 당시 시스템 기본 Node는 23이므로 별도로 받은 임시 Node 24.21.0을 사용했다. 팀원은 `.nvmrc`에 맞는 Node 24를 준비한다. 전역 Node 설정은 변경하지 않았다.

2026-09-14 API Goal 브랜치 `feat/server/backend-api`: 구현·최종 verify·독립 review 완료. Java 78개, 실제 HTTP 응답 102개/8개 schema, 기존 fixture 5개, 웹 build, bootJar 통과. 리뷰 1회차 Medium 2개를 수정했고 2회차 Critical 0 / High 0 / 남은 finding 0. 실제 dev 서버의 자동 worker·8강 기록·공유 replay도 확인했다. 아직 main에 병합한 상태는 아니며 실제 엔진/프론트 완성·배포 완료를 의미하지 않는다. 상세는 `docs/VERIFICATION.md`다.

## 다음 작업

2026-09-16 최신 CE-002/v14 체크포인트: 독립 확인 가능한 조건은 별도 ID로 분리하고, GROUND/최종 REVIEW는 정확한 경험·조건 전체·시점·출처 제약을 대조한다. 최종 REVIEW는 앞 단계 PASS 표시를 근거로 신뢰하지 않는다. 인원·총예산의 수식 관계와 OR는 보존하며 조건을 채우려고 임의의 숫자/당일 방문/예약 보장을 만들지 않는다. 모든 constraints는 필수이고 mode는 검증 방법이다. 일반 적합성/외부 사실 양쪽 모두 누락/FAIL/UNKNOWN은 차단한다. 해석 오류는 후보 Repair로 바꾸지 않는다. 사전 활동 Repair와 상세 후보 Repair는 **한 engine attempt의 같은 1회 예산**을 공유하며 기존 시간/비용 제한·공개 계약은 그대로다.

**hobby-home/8(v11)·16(v12), hobby-social/32(v11)의 실제 READY·preview 연결을 확인했다.** 각각 65.505초/$0.0685255, 77.082초/$0.1002145, 117.055초/$0.1526235이며 Repair 없이 4회 호출이었다. 현재 버전에서 전 사례를 재실행한 것은 아니다. 16강의 읽기 분할·천문 자료 학습과 32강의 보드게임/체스/바둑, 메이커/전자공작 등은 사람 기준과 대조해야 하므로 자동 PASS를 사람 승인으로 사용하지 않는다. v12 seoul-parent/8 첫 실행은 검색 응답 범위 오류와 무계단·보행 근거 부족으로 FAILED였고 미리보기를 공개하지 않았다. 엔진/API 연결과 후보 품질 완료를 구별하여 Goal은 미완료로 유지한다. 버전별 결과·남은 판정 문제는 `docs/CANDIDATE_ENGINE.md` 한 문서에 모았다.

v14 seoul-parent/8 비교에서는 무계단/보행 조건이 분리되어 같은 원문을 공유했고, 무계단 PASS가 생겨도 보행 UNKNOWN을 그대로 유지했다. 실제 결과는 근거 부족으로 FAILED(261.253초, 8회 호출/상세 Repair 1회, $0.5165575). 여유 승인 대안 없이 설명만 바뀐 Repair는 사실 확보를 해결하지 못했다. 후보 선정 시점의 검증 가능성과 유효한 대체 활동 확보가 남은 문제다.

v14 hobby-solo/16 첫 실행은 **READY·preview 16개**, 97.283초, 4회 호출/Repair 0회, $0.1074955. 비운동·월 총비용 조건과 혼자 선호를 반영한 일반 활동으로 모든 자동 검토를 통과했다. 체스·바둑 묶음과 초기 장비 비용의 가정은 사람 대조가 남아 있고 실제 가격 검색이나 품질 최종 승인은 아니다.

v14 일반 `verify`: Java 165개 실행/실패·오류 0(유료 1개 제외), handoff 6개, fixture 5개, 실제 HTTP 102개/8개 schema, 웹 build/bootJar 통과. 집중 테스트 114개 후 독립 리뷰 1회 Critical/High/actionable 0, 이후 코드 변경 없이 전체 검증했다. 별도 live HTTP 58개도 공개 schema 적합(실패 응답 포함). 누적 실험 추정 **$3.0895078 / 승인 $5**, 제공자 104회, 미확인 비용 예약 없음, 자동 충전 OFF 유지. seed 고유 8/18세트의 첫 실행 READY 3/FAILED 5이며 10세트와 2명 사람 평가는 남아 있다. **잔여 $1.9104922가 검색 호출 예약 $2.00보다 작아 추가 유료 검색은 보류한다.** 일반 활동 실험/비용 없는 구현·분석은 가능하며 한도나 예약을 임의로 완화하지 않는다. 실제 공개 서버 배포나 프론트 완주를 완료한 것은 아니다.

- A: [프론트 시작 안내](docs/FRONTEND_HANDOFF.md)와 [FE-001 상세 티켓](docs/tickets/FE-001.md)에서 입력·강수·미리보기를 시작한다. PR #1 미병합 시점에는 `feat/server/backend-api`를 내려받고 새 `feat/play/fe-001-integration` 브랜치를 만든다. 이전 `feat/play/fe-001-preview`는 초기 기반이라 최신 서버가 없다. fixture로 화면을 만들거나 로컬 dev API로 연동할 수 있다.
- B: `feat/engine/ce-002-generation`에서 외부 사실의 출처/적용 범위, 큰 후보군의 활동 분할·filler를 우선 보완하고 미실행 사례를 확인한다. 사용자 A 선호를 반영한 Terra는 개발 기본값이며 최종 모델 선정/2명 품질 평가 완료가 아니다.
- OpenAI API는 별도 사용자 결제/키와 기존 누적 실험 예산 $5 범위에서 사용한다. 자동 충전 OFF. 저장소에 키나 실제 비공개 평가 원문을 추가하지 않는다. 원래 PRD/Design/AC/Decision 첨부 원문을 확보하면 복원본과 대조한다.
- GitHub collaborator 초대는 역할별 실제 계정이 정해진 뒤 한다. 현 단계에서 팀원 초대/branch protection을 완료했다고 주장하지 않는다.
- 외부 공유 개발 API/Swagger UI와 최종 화면 시안은 아직 없다. OpenAPI JSON·생성 TS 타입·fixture가 전달물이며, `npm run api:smoke`는 로컬 dev API의 생성부터 공유 replay까지 연결을 확인한다. 실제 AI 품질이나 프론트 완주 UI 테스트를 대신하지 않는다.

핵심 동시성/게임 정책과 기술 선택은 `TECH_DESIGN.md`, 결정 변경은 `DECISIONS.md`, source 한계는 `docs/SOURCE_PROVENANCE.md`에 남긴다.
