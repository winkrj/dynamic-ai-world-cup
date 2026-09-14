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

첫 기반 작업/CE-001과 두 시작 브랜치는 이미 저장소에 올라와 있다. 새로 합류하면 갱신된 안내를 포함하는 최신 `main`에서 자신의 작업 브랜치를 만든다. 각 개발자는 별도 clone을 권장한다. 같은 컴퓨터에서 여러 AI가 작업할 때만 작업별 worktree를 사용한다. 서로의 checkout에서 branch를 바꾸지 않는다.

```sh
git clone https://github.com/winkrj/dynamic-ai-world-cup.git
cd dynamic-ai-world-cup
git switch -c feat/play/fe-001-preview
# B는 위 브랜치 이름 대신 feat/engine/ce-002-generation 사용
npm ci
./scripts/verify.sh
```

PR 전에 main을 반영하고 검증한다. 계약 변경이 없다면 상대 역할 내부 파일을 수정할 필요가 없다. contract 변경은 먼저 요청/응답 fixture와 오류 의미를 포함한 작은 PR을 작성하고 양쪽 영향을 검토한다. additive 변경도 생성 타입/fixture 검증이 필요하다. 의미·필수 필드 변경은 계약 버전과 의사결정 기록을 함께 갱신한다.

실제 브랜치 보호/필수 리뷰는 계정 플랜과 설정에 따라 다르다. 현재 문서는 협업 규칙이며 설정 완료로 주장하지 않는다.

## 초대 없이 작업하는 방법

이 저장소는 public이다. 링크를 받은 사람은 초대 없이 clone해서 실행·수정할 수 있다. 원본 저장소에 push하는 권한은 별개이므로, 쓰기 권한을 받기 전에는 Fork와 PR로 변경을 전달한다.

GitHub에서 `winkrj/dynamic-ai-world-cup`을 자신의 계정으로 Fork한다. 위 명령으로 원본을 이미 clone했다면 아래처럼 원본은 `upstream`, 자신의 Fork는 `origin`으로 둔다. `YOUR_GITHUB_ID`는 본인의 GitHub 아이디로 바꾼다.

```sh
git remote rename origin upstream
git remote add origin https://github.com/YOUR_GITHUB_ID/dynamic-ai-world-cup.git
```

담당 작업을 구현하고 관련 검증을 마친 뒤 변경 파일을 commit한다. A의 경우 다음 명령으로 자신의 Fork에 올린다. B는 브랜치 이름을 `feat/engine/ce-002-generation`으로 바꾼다.

```sh
git push -u origin feat/play/fe-001-preview
```

GitHub에서 base를 `winkrj/dynamic-ai-world-cup`의 `main`, compare를 본인 Fork의 작업 브랜치로 선택해 PR을 만든다. 원본의 변경을 가져올 때는 `git fetch upstream` 후 작업 브랜치에서 `git merge upstream/main`으로 반영한다.

협업자 초대를 수락해 원본 쓰기 권한이 생기면 Fork 없이 원본의 작업 브랜치로 push할 수 있다. 공개 전환만으로 모든 방문자에게 원본 쓰기 권한이 생기지는 않는다.

## 각자의 AI 시작 요청

A: “AGENTS.md와 frontend/AGENTS.md, PRD/DESIGN_SPEC, contracts와 FE-001을 읽고 플레이 경험 역할로 작업해. frontend 내부를 소유하고 계약 변경이 필요하면 먼저 차이를 제안해. fixture 모드가 실제 AI 생성처럼 보이지 않게 하고 관련 브라우저 검증을 해.”

B: “AGENTS.md와 backend/AGENTS.md, TECH_DESIGN, contracts와 CE-002를 읽고 후보 품질과 서버 역할로 작업해. backend/evals를 소유하고 최초 plan을 유지한 Generate→Ground→Validate→Repair를 구현해. 실제 provider 비용 호출은 준비된 eval 범위와 자격정보를 확인하고 수행해.”
