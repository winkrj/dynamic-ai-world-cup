# 프론트엔드 시작 안내

> 기준일 2026-09-18. 현재 통합 작업 브랜치는 `feat/engine/context-feasibility`다. 사용자가 프론트도 맡았으며 시안 4/A 상하 카드 기반 입력·미리보기·게임·Champion·공유 화면과 기존 API 연결이 구현돼 있다. 새 프론트를 다시 만들거나 기존 작업을 덮어쓰지 말고 루트/frontend AGENTS와 [제품 통합 Spec](INTEGRATION_SPEC.md)을 따른다. 실제 AI 품질 승인과 공개 배포는 아직 완료되지 않았다.

이 문서는 현재 코드로 실행·유지보수할 때의 안내입니다. A는 `frontend/**`의 플레이 경험, B는 후보 품질과 서버를 담당합니다. [FE-001](tickets/FE-001.md)은 초기 착수 범위이고 현재 완료/잔여 상태는 [프로젝트 컨텍스트](../PROJECT_CONTEXT.md)와 [검증 기록](VERIFICATION.md)을 확인합니다.

## 전달 기준과 준비 상태

현재 전달 브랜치는 [feat/engine/context-feasibility](https://github.com/winkrj/dynamic-ai-world-cup/tree/feat/engine/context-feasibility)입니다. **main은 아직 최신 통합 코드를 포함하지 않습니다.** 아래 명령으로 전달 브랜치에서 시작하세요. 초기 서버 인계는 `feat/server/backend-api`와 [PR #1](https://github.com/winkrj/dynamic-ai-world-cup/pull/1)의 과거 기록이며 현재 시작점과 구별합니다. main 병합 후에는 실제 병합 상태를 확인하고 안내를 갱신합니다.

- 준비됨: 공개 OpenAPI, TS 타입, 예제 JSON, 생성/미리보기/재생성/시작/기록/공유 API, PostgreSQL 저장, React 전체 플레이 흐름, 로컬 실행 및 연결 확인 명령.
- 아직 없음: 외부에서 함께 쓰는 개발 API 주소/Swagger UI 서버/공개 운영 URL, 최신 엔진 실제 품질 승인과 최신 실제 후보로의 완주 증거.
- `dev` 서버의 후보는 **[개발용] 합성 후보**입니다. 실제 추천 품질을 테스트하는 모드가 아닙니다. 실제 AI는 아래 CE-002 브랜치의 명시적 `live` 모드에서만 사용하며 기본 모드는 계속 생성 실패로 닫혀 있습니다.
- GitHub 링크는 문서/코드 주소입니다. API base URL이 아닙니다. 현재는 담당자 자신의 컴퓨터에서 서버를 실행합니다.

### 실제 AI 연결과 검증 범위 — 2026-09-18

현재 통합 브랜치에는 Spring Boot `CandidateEngine` port 뒤의 실제 OpenAI 엔진이 포함됩니다. **생성·플레이 API의 요청·응답 필드와 URL은 기존 계약을 유지합니다.** A는 기존 fixture/dev로 화면·오류·복원 흐름을 작업할 수 있습니다. 프론트에 provider 키나 내부 평가 자료를 넣지 않으며 기본 실행을 live로 바꾸지 않습니다.

실제 실행은 [Candidate Engine의 live 실행 안내](CANDIDATE_ENGINE.md#실제-ai를-켜는-방법)를 따릅니다. API 키·예산은 서버 담당자의 환경 설정이며 프론트에 키를 넣지 않습니다. 이 엔진은 생성 뒤 독립 품질 검토와 필요시 Repair를 수행하므로 수십 초~수 분이 걸릴 수 있습니다. `RUNNING`을 고정 30초/60초 뒤 실패로 간주하거나 생성 POST를 자동 반복하지 말고 terminal 상태를 확인합니다. live 엔진의 작업 예산은 280초, worker lease는 300초이며 장애 복구 시 더 길어질 수 있습니다. 게임의 **대진당 7초**와는 별개입니다.

품질 미달은 `QUALITY_GATE_FAILED`, 예산 부족은 `RATE_LIMITED`, 제공자/시간 제한 문제는 `PROVIDER_UNAVAILABLE`로 기존 오류 처리 경로를 사용합니다. 아래 `api:smoke`는 **dev 합성 서버용**입니다. 실제 AI의 검증 범위와 한계는 [엔진 문서](CANDIDATE_ENGINE.md)에 기록합니다.

**이전 버전에는 8/16/32강 실제 READY·preview 연결 사례가 있지만 최신 품질 통과를 뜻하지 않습니다.** v20의 마지막 전체 32강 평가는 Repair 후에도 활동 30/32만 승인돼 FAILED였고 preview가 없습니다. v21은 고정된 실패 계획의 조건 해석 진단 1회에서 비운동 제외 조건의 오분류를 검출했습니다. 새 후보·preview를 생성한 검증은 아니며 전체 품질은 미완료입니다. 진단 승인은 소진됐고 누적 장부 $4.8716363/$6의 잔여액은 추가 호출 권한이 아닙니다. 자동 충전 OFF를 유지합니다. 사전·상세 Repair는 합쳐서 최대 1회입니다.

**회귀 검증과 후보 품질을 구별합니다.** 최신 전체 회귀는 Java 325개 실행/유료 2개 제외, 프론트 60개, 실제 HTTP 166개/9 schemas, 웹·두 JAR가 통과했고 독립 재리뷰 Critical/High/actionable 0입니다. 그 뒤 같은 코드의 실제 단일 진단 1개도 별도로 PASS했습니다. 여기서 HTTP는 격리 서버와 합성 후보 검증입니다. 실제 READY 결과를 추가 AI 호출 없이 freeze→N−1 선택→Champion→동일 snapshot 공유까지 검사하는 경로도 준비했지만 최신 실제 후보로의 완주는 하지 못했습니다. 사람 평가의 비교 단위/실행 가능성 FAIL과 외부 사실 평가 미완료는 별도입니다.

## 1. 브랜치와 실행

새로 내려받는 경우입니다. 기존 작업이 있는 폴더를 덮어쓰거나 reset하지 마세요.

```sh
git clone --branch feat/engine/context-feasibility https://github.com/winkrj/dynamic-ai-world-cup.git
cd dynamic-ai-world-cup
git switch -c feat/play/fe-001-integration
npm ci
```

Node 24 LTS를 사용합니다. `npm run dev:web`는 고정 예제가 아닌 실제 앱이며 아래 dev 서버가 필요합니다. 서버 없이 가능한 범위는 `npm run test:web`와 타입/웹 빌드, 공용 fixture를 사용하는 테스트입니다. 현재 API client와 상태 흐름은 `frontend/src/api/**`, `frontend/src/app/**`, 게임 규칙은 `frontend/src/play/**`에 있습니다.

실제 API 연동에는 Java 21과 실행 중인 Docker가 추가로 필요합니다. 별도 터미널에서 저장소 루트 기준으로 실행합니다.

```sh
docker compose up -d --wait postgres
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

다른 터미널에서는 저장소 루트로 이동한 뒤 실행합니다.

```sh
npm run api:smoke
npm run dev:web
```

`api:smoke`가 성공하면 생성 → 같은 요청 재전송 → preview → 전체 재생성 1회 → start → 7개 선택 저장 → 공유 → 새 익명 replay가 연결된 것입니다. 이 명령은 로컬 DB에 생성 작업 2개와 합성 경기/공유 기록을 남깁니다. 사용자 데이터는 삭제하지 않습니다. 익명 브라우저별 하루 2회(생성/재생성/접수 후 실패 포함, 서울 자정 초기화), actor/IP 각각 5회/10분이므로 단기 반복 실행은 제한될 수 있습니다. 동일 요청 재전송과 공유 플레이는 추가 차감하지 않습니다. 429는 실제 Retry-After를 따르고 고정 10분으로 간주하지 않습니다. 프론트 timer/RNG나 실제 AI 품질을 검증하는 명령은 아닙니다.

화면은 **http://127.0.0.1:5173**, 서버 상태는 **http://localhost:8080/api/v1/health**입니다. 프론트는 `/api/v1` 상대 경로로 요청하세요. 기존 Vite proxy가 8080 서버로 전달하므로 CORS를 별도로 열지 않아도 됩니다. 프론트 origin을 `localhost:5173` 등으로 바꾸면 서버의 `PUBLIC_ORIGIN`도 정확히 맞춰야 합니다.

DB는 `127.0.0.1:55432`의 프로젝트 전용 volume을 사용합니다. 로컬 예시 암호를 운영에 사용하지 마세요. 작업 후 서버는 Ctrl+C, DB는 저장소 루트에서 `docker compose stop postgres`로 중지합니다. volume은 유지됩니다. `.env.example`은 참고 파일이며 Spring이 자동으로 읽지 않습니다.

Windows 서버 실행은 `gradlew.bat`, 문서의 Unix 명령은 Git Bash/WSL을 사용하면 됩니다. Docker/Java 준비 전에도 Node와 fixture로 화면 작업은 가능합니다.

## 2. 읽을 문서와 파일

| 필요한 내용 | 기준 |
| --- | --- |
| 제품 목적·범위·고정 규칙 | [PRD](../PRD.md) |
| 각 화면·타이머·숨김/새로고침·접근성 | [Design Spec](../DESIGN_SPEC.md) |
| 첫 작업의 상태·오류·완료 체크리스트 | [FE-001](tickets/FE-001.md) |
| 요청·응답 필드의 정확한 타입 | [OpenAPI 3.1 JSON](../contracts/openapi.json) — source of truth |
| API 상태·오류·재전송 의미 | [API contract](API_CONTRACT.md) |
| React/TS에서 사용할 타입 | [자동생성 schema.d.ts](../frontend/src/api/schema.d.ts) — 직접 편집 금지 |
| 서버 없이 쓸 데이터 | [공용 fixtures](../contracts/fixtures), [fixture import](../frontend/src/api/fixtures.ts) |
| 완료 판정·후속 작업 | [Acceptance Criteria](../ACCEPTANCE_CRITERIA.md), [티켓 목록](TICKETS.md) |
| 소유권·PR 방식 | [협업 안내](COLLABORATION.md), [프론트 AI 지침](../frontend/AGENTS.md) |

OpenAPI 파일은 다운로드해 OpenAPI 지원 도구에서 읽을 수 있습니다. 수작업으로 따로 관리하는 API 명세를 만들지 말고 이 파일을 기준으로 합니다. 지금은 배포된 Swagger UI 주소가 없습니다.

## 3. 화면과 API 연결

아래 경로 앞에는 모두 `/api/v1`이 붙습니다. 모든 POST에는 `Idempotency-Key`가 필요합니다.

| 화면 / 행동 | 호출과 다음 처리 |
| --- | --- |
| 입력 완료 | `POST /generation-jobs` → 202의 jobId 저장 |
| 생성 대기 | `GET /generation-jobs/{jobId}` → QUEUED/RUNNING 동안 대기, READY의 draftId로 이동, FAILED의 error 처리 |
| 전체 미리보기 | `GET /drafts/{draftId}` → candidates 전체, version, regenerationsRemaining 보관 |
| 전체 다시 만들기 | `POST /drafts/{draftId}/regenerations` + 현재 expectedVersion → job 조회 후 같은 draft를 다시 읽기 |
| 이대로 시작 | `POST /drafts/{draftId}/start` + 현재 expectedVersion → sessionId와 snapshot을 FE-002 게임에 전달 |
| 게임 복원 | start 응답의 snapshot 또는 `GET /snapshots/{snapshotId}`. 시작 후 draft를 다시 읽는 흐름을 사용하지 않기 |
| 선택 기록 | `POST /sessions/{sessionId}/selections` + events → nextSequence/championId 확인 |
| Champion 공유 | 모든 선택이 저장되어 COMPLETED가 된 뒤 `POST /sessions/{sessionId}/shares` → url |
| 공유 진입 | `GET /shares/{token}` → 만든 사람 champion + 원본 snapshot |
| 같은 대진 해보기 | `POST /shares/{token}/sessions` → 새 sessionId + 같은 snapshot |

공유 생성과 replay POST에는 `{}`나 `null` JSON도 보내지 말고 **본문을 생략**하세요. 그 외 정확한 body는 OpenAPI를 따릅니다. 공유 URL의 프론트 경로 `/shares/{token}`은 현재 앱에 구현돼 있습니다.

첫 생성 요청 예시입니다. cookie는 서버의 HttpOnly cookie이므로 JS로 읽거나 localStorage에 복사하지 않습니다.

```ts
import type { components } from './api/schema'; // src 파일에서 사용하는 예시
type GenerationRequest = components['schemas']['GenerationRequest'];

const input: GenerationRequest = {
  prompt: '퇴근 후 시작할 취미를 고르고 싶어', size: 16,
  locale: 'ko-KR', timezone: 'Asia/Seoul',
};
const requestKey = crypto.randomUUID(); // 이 사용자 동작의 네트워크 재전송에는 같은 값 사용
const response = await fetch('/api/v1/generation-jobs', {
  method: 'POST', credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestKey },
  body: JSON.stringify(input),
});
// response.ok/status를 확인한 뒤 성공 DTO 또는 ApiError로 분기한다.
```

이 예시는 첫 HTTP 요청만 설명합니다. 실제 앱 수정은 기존 API client/상태 controller를 재사용하고 별도 요청 경로를 중복 구현하지 않습니다.

## 4. 연동 시 지킬 규칙

- `size`는 8/16/32, 고민은 공백 제외 내용이 있어야 하며 최대 500자입니다. locale은 ko-KR, timezone은 유효한 IANA 값입니다. FE-001에서는 Asia/Seoul을 사용합니다.
- job 조회는 1초 간격으로 시작합니다. READY/FAILED에서 멈추고 같은 화면에서 polling loop를 중복 생성하지 않습니다. 재시도도 무한 loop로 만들지 않습니다. 화면 이탈 시 조회를 멈추는 것은 서버 job 취소가 아닙니다.
- 생성 성공 전에 예제 후보를 실제 생성 결과처럼 표시하지 않습니다. preview는 N개 전체를 보여주고 개별 교체 UI는 만들지 않습니다.
- 재생성 중에는 이미 본 preview를 보존하고 시작/재생성 버튼을 잠급니다. 실패하면 보존한 preview를 유지합니다. 성공했을 때만 최신 version/잔여 횟수로 갱신합니다.
- 네트워크 결과가 불명확하면 **같은 key/body**를 다시 보냅니다. 사용자가 새로 요청하거나 terminal FAILED 뒤 다시 생성하는 것은 새 key입니다. 같은 key는 다른 payload에 재사용하지 않습니다.
- version/잔여 횟수는 서버 응답이 기준입니다. start 재전송은 같은 snapshot/session으로 수렴합니다. 시작 후 후보·initialOrder·rules를 변경하지 않습니다.
- FE-002에서 선택 eventId/sequence를 한 번 발급하고 업로드 재시도에도 그대로 유지합니다. 서버가 nextSequence를 수락했다고 확인한 prefix만 전송 대기열에서 제거합니다. 저장 실패는 Undo가 아닙니다.
- 챔피언 화면은 로컬 완주와 서버 저장 완료를 구분합니다. 공유는 마지막 선택까지 서버가 수락한 뒤 가능합니다.
- 오류는 message 문자열 비교 대신 code로 분기합니다. 버튼/안내의 구체적인 처리는 [FE-001 오류 표](tickets/FE-001.md#오류와-재시도)를 따릅니다.

## 5. 후속 PR과 완료 확인

담당 경로는 `frontend/**`이며 생성된 `schema.d.ts`는 제외합니다. 서버와 공용 계약 변경이 필요하면 B에게 상태/필드 차이를 요청하세요. 계약에 맞추려고 backend 동작을 프론트 PR에서 임의 변경하지 않습니다.

```sh
npm run contracts:check
npm run build:web
```

본인이 추가한 화면/상태 테스트도 실행하고, 360px·키보드·이미지 fallback을 확인합니다. Java/Docker가 준비됐다면 `./scripts/verify.sh`와 `npm run api:smoke`로 실제 연동을 확인합니다. 검증하지 못한 항목은 PR에 남깁니다.

현재 통합 브랜치에서 파생한 PR은 `feat/engine/context-feasibility`를 base로 두고 본인 변경만 검토합니다. main 병합 후에는 실제 base와 diff를 다시 확인합니다. 쓰기 권한이 없다면 [Fork 방식](COLLABORATION.md#초대-없이-작업하는-방법)을 사용합니다. 이 문서 전달만으로 collaborator 권한이 생기지는 않습니다.

## 6. 남은 완료 조건

시안 4/A 상하 카드의 합성 브라우저 검증 범위는 [제품 통합 Spec](INTEGRATION_SPEC.md)과 [검증 기록](VERIFICATION.md)에 있습니다. 실제 엔진 품질, 최신 실제 후보의 브라우저 완주, 준비/비용 판단에 미리보기 정보가 충분한지, 공개 운영 배포는 아직 별도 확인이 필요합니다. 이전 기획 첨부 원문은 없으며 [현재 문서의 출처](SOURCE_PROVENANCE.md)를 따릅니다.

현재 API에는 생성 취소 endpoint와 모델이 작성한 추가 질문 내용을 전달하는 계약이 없습니다. 다만 초기 `CLARIFICATION_REQUIRED`에는 제품 고정 보충 질문 1개와 명시적 재제출 UI가 구현돼 있습니다. 원래 고민+답변을 새 생성으로 보내므로 하루 2회 한도에 포함됩니다. 재생성 실패는 기존 preview를 유지합니다. 화면 이탈을 서버 취소로 표시하지 않습니다.

## AI에게 전달할 후속 요청

> AGENTS.md, frontend/AGENTS.md, PROJECT_CONTEXT.md, docs/INTEGRATION_SPEC.md, docs/FRONTEND_HANDOFF.md를 읽고 현재 구현과 배정된 후속 티켓의 차이를 먼저 확인해. A — 플레이 경험으로 frontend 내부만 소유하고 생성된 schema/backend를 임의 변경하지 마. 기존 입력·preview·게임·공유·복원 흐름을 재사용하고 관련 단위 테스트와 360px/접근성을 검증해. dev 합성 API를 사용하며 live 호출·키 사용·품질 승인·공개 배포는 하지 마. 계약 변경이 필요하면 차이와 서버 영향을 먼저 보고해.
