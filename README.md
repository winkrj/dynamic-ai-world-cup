# Dynamic AI World Cup

자연어 고민을 입력하고, 후보를 둘씩 골라 하나의 결정을 끝내는 월드컵.

8/16/32강 후보 전체를 미리 본 뒤, 한 번 다시 만들거나 그대로 시작한다. 시작하면 대진은 고정된다. 경기당 7초, 시간 초과는 랜덤 진출, Undo 없이 Champion까지 이어진다. 공유받은 사람도 같은 후보와 초기 대진으로 플레이한다.

## 현재 상태

후보 품질 검증 모듈과 서버 API를 구현했다. PostgreSQL에 생성 작업·미리보기·재생성·고정 대진·선택 기록·공유 데이터를 저장한다. 같은 요청의 재전송과 동시 시작을 처리하고, 공유받은 사람은 같은 대진으로 별도 세션을 만들 수 있다.

실제 AI 생성·검색과 플레이 화면은 아직 연결하지 않았다. 프론트는 고정 취미 예제를 보여주는 초기 화면이며, 서버의 `dev` 모드는 명시적으로 개발용 합성 후보를 반환한다. 기본/운영 모드에서는 실제 엔진이 없으면 생성에 실패한다.

## 두 사람의 역할

| 역할 | 담당 |
| --- | --- |
| A — 플레이 경험 | 프론트엔드·화면 디자인·미리보기·게임/타이머·결과/공유 화면 |
| B — 후보 품질과 서버 | Candidate Engine·생성/검증/개인화·API·DB·고정 대진/공유 데이터 |

두 영역은 [API 계약](contracts/openapi.json)과 공용 fixture로 연결한다. [역할·브랜치·AI 작업 경계](docs/COLLABORATION.md)를 기준으로 각자 개발한다.

다음 작업은 A가 **FE-001 입력·강수·전체 미리보기**, B가 **CE-002 후보 생성 pipeline**이다. B 내부에서는 사람과 후보 품질을 결정하는 엔진 작업과 API·저장 구현을 분리한다. [서버·엔진 접점](docs/BACKEND_DESIGN.md), [작업 순서와 완료 기준](docs/TICKETS.md)을 읽고 담당 영역의 `AGENTS.md`를 AI에 함께 제공한다.

## 실행

공개 저장소이므로 별도 초대 없이 내려받고 개발을 시작할 수 있다. 아래는 A의 시작 예시다. B는 브랜치 이름을 `feat/engine/ce-002-generation`으로 바꾼다. 최신 `main`에서 자신의 작업 브랜치를 만든다.

```sh
git clone https://github.com/winkrj/dynamic-ai-world-cup.git
cd dynamic-ai-world-cup
git switch -c feat/play/fe-001-preview
```

프론트 작업에는 Node 24 LTS, 서버 작업에는 Java 21과 실행 중인 Docker가 필요하다. 전체 검증에는 모두 필요하며 Gradle은 저장소의 wrapper를 사용한다. Windows에서는 Git Bash/WSL로 문서의 명령을 실행하거나 서버 wrapper의 `gradlew.bat`를 사용한다.

```sh
npm ci
npm run dev:web
```

화면은 `http://127.0.0.1:5173`에서 열린다. 프론트 담당자는 서버 없이 고정 예제로 화면 작업을 시작할 수 있다. 서버를 실행하려면 저장소 루트에서 다음 명령을 실행한다.

```sh
docker compose up -d --wait postgres
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

서버 상태: `http://localhost:8080/api/v1/health`. 로컬 DB는 `127.0.0.1:55432`에서 실행되어 기본 5432 포트의 다른 프로젝트와 분리된다. 데이터는 전용 Docker volume에 남는다. `docker compose stop postgres`로 중지할 수 있다. 이 설정의 암호는 로컬 개발용 예시이며 운영에 사용하지 않는다.

브라우저는 `http://127.0.0.1:5173`에서 열고 `/api/v1` 상대 경로로 호출한다. Vite가 서버로 전달하며 Origin 확인을 통과한다. 다른 주소로 개발하면 `PUBLIC_ORIGIN`을 정확한 origin으로 설정한다. 환경변수 이름은 [.env.example](.env.example)에 있지만 Spring이 이 파일을 자동으로 읽지는 않는다.

실제 키 없이도 서버 흐름을 확인할 수 있다. [API 연동 예시](docs/API_CONTRACT.md#로컬-api-연동)는 익명 cookie를 유지하면서 생성 작업 조회부터 진행한다. 실제 모델/검색 제공자 선택은 엔진 작업에서 정한다.

## 변경사항 전달

공개 저장소여도 원본에 push하려면 쓰기 권한이 필요하다. 초대 전에는 GitHub에서 이 저장소를 **Fork**하고, 자신의 Fork에 변경을 push한 뒤 원본 `main`으로 **Pull Request**를 보내면 된다. 위 명령으로 원본을 이미 clone한 경우의 원격 연결 방법은 [협업 안내](docs/COLLABORATION.md#초대-없이-작업하는-방법)에 있다.

협업자 초대를 수락한 팀원은 원본의 작업 브랜치로 직접 push하고 PR을 만들 수 있다. 두 방식 모두 담당 영역과 API 계약 경계를 지킨다.

## 검증

저장소 루트에서 실행한다.

```sh
./scripts/verify.sh
```

계약과 생성 타입, 프론트 build, Java 테스트/실행 JAR, 실제 HTTP 응답의 schema를 확인한다. DB 테스트는 Testcontainers가 별도의 PostgreSQL을 자동 생성·정리하며 개발 DB에 연결하지 않는다. Docker가 없으면 DB 검증을 건너뛰지 않고 실패한다. 결과와 미검증 영역은 [검증 기록](docs/VERIFICATION.md)에 남긴다. 계약 수정 후에는 `npm run contracts:generate`로 TS 타입을 다시 만든다.

## 저장소 구성

```text
frontend/    React·TypeScript, 플레이 경험
backend/     Java 21·Spring Boot, 후보 품질과 서버
contracts/   OpenAPI와 두 영역이 함께 쓰는 fixture
evals/       실제 Candidate Quality 평가 기준과 사례
scripts/     계약 검사와 전체 검증 명령
docs/        출처, API 의미, 협업, 티켓, 검증 기록
```

처음 합류하면 [프로젝트 상태](PROJECT_CONTEXT.md) → [PRD](PRD.md) → [디자인](DESIGN_SPEC.md) → [기술 설계](TECH_DESIGN.md) → [담당 작업](docs/TICKETS.md) 순서로 읽는다. AI 작업은 [AGENTS.md](AGENTS.md)를 함께 제공한다.

이전 대화의 첨부 원문은 현재 확보되지 않아 제품 문서는 최신 대화 결정의 복원본이다. 출처와 새 기술 결정은 [출처 기록](docs/SOURCE_PROVENANCE.md), [Decision Log](DECISIONS.md)에 구분했다.
