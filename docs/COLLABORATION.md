# 두 사람의 큰 역할

역할은 화면별로 매번 바꾸지 않는다. **A는 플레이 경험 전체, B는 후보 품질과 서버 전체**를 책임진다. 각자 자신의 AI와 역할 내부 작업을 나누되, 다른 역할의 소유 파일을 동시에 수정하지 않는다.

| 큰 역할 | 결과 책임 | 소유 경로 |
| --- | --- | --- |
| A — 플레이 경험 | 입력부터 Champion·공유 진입까지 모바일 경험, 접근성, 애니메이션, 타이머, 게임 상태, API client와 mock, 브라우저 테스트 | `frontend/**` (자동생성 schema 제외) |
| B — 후보 품질과 서버 | Candidate Engine, grounding, eval, history, API, DB, snapshot 불변성, 세션/공유 데이터, 빌드/운영 | `backend/**`, `evals/**`, `scripts/**`, `.github/**`, 루트 빌드 설정/lockfile |
| 공동 계약 | 두 영역의 데이터/오류/상태/동시성 합의. B가 변경 작성, A가 영향 검토 | `contracts/**`, `frontend/src/api/schema.d.ts`, 기술/제품 기준 문서 |

사람의 실명/GitHub handle에 대한 역할 매핑과 collaborator 초대는 아직 없다. A/B는 책임 구분이며 자동 접근 제어가 아니다. 실명을 받은 뒤 CODEOWNERS를 연결한다. 사용자의 최신 요구처럼 작은 티켓은 **큰 역할 안의 작업 순서**이며 별도 역할이 아니다.

## 경계에서 주고받는 것

B는 `contracts/openapi.json`과 검증된 `contracts/fixtures`를 제공한다. A는 이 계약에서 생성한 TS 타입과 fixture로 개발할 수 있어 실제 생성 API 완성을 기다릴 필요가 없다. B는 frontend 없이 JUnit/API test로 개발한다.

- A는 생성/재생성 횟수나 freeze의 최종 판정을 로컬 flag만으로 결정하지 않는다. API 응답이 기준이다.
- B는 클라이언트 timer/animation 내부 상태를 API에 강요하지 않는다. snapshot rules와 선택 event 계약만 제공한다.
- A는 사용자 화면에 engine의 prompt/validator 내부 정보를 노출하지 않는다.
- B는 public candidate DTO에 constraint evidence/history를 섞지 않는다.

## Git 방식

`main`은 두 사람이 공유하는 검증 통합 기준이다. develop이나 사람별 영구 통합 브랜치를 추가하지 않는다. 큰 역할은 고정하되 각 작업은 짧은 feature branch에서 PR로 합친다.

```text
main
  feat/play/fe-001-preview       # A: 플레이 경험의 첫 작업
  feat/engine/ce-002-generation  # B: 후보 품질·서버의 다음 작업
```

첫 기반 작업/CE-001을 main에 기록한 뒤 두 시작 브랜치를 동일 commit에서 생성한다. 각 개발자는 별도 clone을 권장한다. 같은 컴퓨터에서 여러 AI가 작업할 때만 작업별 worktree를 사용한다. 서로의 checkout에서 branch를 바꾸지 않는다.

```sh
git clone https://github.com/winkrj/dynamic-ai-world-cup.git
cd dynamic-ai-world-cup
git switch feat/play/fe-001-preview
# B는 feat/engine/ce-002-generation 사용
npm ci
./scripts/verify.sh
```

PR 전에 main을 반영하고 검증한다. 계약 변경이 없다면 상대 역할 내부 파일을 수정할 필요가 없다. contract 변경은 먼저 요청/응답 fixture와 오류 의미를 포함한 작은 PR을 작성하고 양쪽 영향을 검토한다. additive 변경도 생성 타입/fixture 검증이 필요하다. 의미·필수 필드 변경은 계약 버전과 의사결정 기록을 함께 갱신한다.

실제 브랜치 보호/필수 리뷰는 계정 플랜과 설정에 따라 다르다. 현재 문서는 협업 규칙이며 설정 완료로 주장하지 않는다.

## 각자의 AI 시작 요청

A: “AGENTS.md와 frontend/AGENTS.md, PRD/DESIGN_SPEC, contracts와 FE-001을 읽고 플레이 경험 역할로 작업해. frontend 내부를 소유하고 계약 변경이 필요하면 먼저 차이를 제안해. fixture 모드가 실제 AI 생성처럼 보이지 않게 하고 관련 브라우저 검증을 해.”

B: “AGENTS.md와 backend/AGENTS.md, TECH_DESIGN, contracts와 CE-002를 읽고 후보 품질과 서버 역할로 작업해. backend/evals를 소유하고 최초 plan을 유지한 Generate→Ground→Validate→Repair를 구현해. 실제 provider 비용 호출은 준비된 eval 범위와 자격정보를 확인하고 수행해.”
