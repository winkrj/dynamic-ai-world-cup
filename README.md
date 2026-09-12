# Dynamic AI World Cup

자연어 고민을 입력하고, 후보를 둘씩 골라 하나의 결정을 끝내는 월드컵.

8/16/32강 후보 전체를 미리 본 뒤, 한 번 다시 만들거나 그대로 시작한다. 시작하면 대진은 고정된다. 경기당 7초, 시간 초과는 랜덤 진출, Undo 없이 Champion까지 이어진다. 공유받은 사람도 같은 후보와 초기 대진으로 플레이한다.

## 현재 상태

개발 기반과 첫 **CE-001 후보 품질 검증 모듈**을 구현했다. API 계약, 공용 예제 데이터, React 화면 기반, Spring health endpoint, 검증 명령이 있다. 실제 AI 생성, DB 저장, 토너먼트 완주와 공유 기능은 아직 구현하지 않았다. 개발 화면의 취미 8강은 고정 예제이며 모델 생성 결과가 아니다.

## 두 사람의 역할

| 역할 | 담당 |
| --- | --- |
| A — 플레이 경험 | 프론트엔드·화면 디자인·미리보기·게임/타이머·결과/공유 화면 |
| B — 후보 품질과 서버 | Candidate Engine·생성/검증/개인화·API·DB·고정 대진/공유 데이터 |

두 영역은 [API 계약](contracts/openapi.json)과 공용 fixture로 연결한다. [역할·브랜치·AI 작업 경계](docs/COLLABORATION.md)를 기준으로 각자 개발한다.

## 실행

Node 24 LTS와 Java 21이 필요하다. Gradle은 저장소의 wrapper를 사용한다.

```sh
npm ci
npm run dev:web
```

화면은 `http://127.0.0.1:5173`에서 열린다. 다른 터미널에서 서버를 실행한다.

```sh
cd backend
./gradlew bootRun
```

서버 상태: `http://localhost:8080/api/v1/health`. 초기 실행에는 DB나 API key가 필요 없다. 서버의 다른 제품 API는 계약만 있고 구현되지 않았다.

## 검증

저장소 루트에서 실행한다.

```sh
./scripts/verify.sh
```

계약과 생성 타입의 일치, 공용 fixture의 schema, 프론트 TypeScript/production build, Java 테스트/실행 JAR 생성을 확인한다. 결과와 미검증 영역은 [검증 기록](docs/VERIFICATION.md)에 남긴다. 계약 수정 후에는 `npm run contracts:generate`로 TS 타입을 다시 만든다.

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
