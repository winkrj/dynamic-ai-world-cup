# 제품 통합 Spec v1.0 — 시안 4

2026-09-20 TD-58 제출 마감: A 상하/기존 연출·라운드/게임 규칙은 유지하고 사진·이모지는 추가하지 않는다. 일반 지역·요일 활동 요청은 생성 대상, 실명 장소·최신 사실은 새 `GROUNDING_REQUIRED` 오류로 지원 한계/입력 수정을 안내한다. 보충 질문 `CLARIFICATION_REQUIRED`와 구별한다. jobId 없는 확정429는 최초/보충 모두 수동 대기·입력 수정을 허용하며 reload/만료 자동 POST는 없다. 응답 유실/접수 job은 기존 key/body와 진행을 보존한다. API/프론트는 함께 배포하며 DB migration은 없다. 최신 실행 증거는 [제출본](V1_SUBMISSION.md)을 따른다.

2026-09-20 TD-57: 심사 기간은 서버 `GENERATION_DAILY_LIMIT=0`으로 일일 cap만 해제한다. 기본/누락은2, 일반 복귀도2. 프론트는 일일 숫자를 하드코딩하지 않고 로그인 불필요·실패/보충 입력은 새 접수·반복 요청 일시 제한·공유는 새 AI 생성 없음의 안내를 쓴다. actor/IP5회/10분과 실제429의 Retry-After, 비용 예약·재생성1회·기존7초/라운드 연출은 유지한다. ‘무제한 생성 비용’ 또는 ‘보호 기능 전체 해제’가 아니다.

2026-09-20 TD-56: 원본 A의900ms 맞붙는 움직임/VS1.34배/강한 KO를 다시 대조해, 고정 button 내부4px(마지막3초6px) 압박·패자32% 이탈·승자슬램으로 보완한다. 피드백280ms/선택7000ms는 유지. 첫 경기 및 라운드 경계에만500ms 시작/진출 안내, 항상 표시되는16→8→4강·준결승→결승 경로를 추가한다. 안내는 준비 gate이며 hidden/locked/storageBlocked에서는 취소·복귀 후재시작한다. active복원deadline은 건드리지 않는다. 사진은 [별도 설계](CANDIDATE_VISUALS.md), 미구현이다.

2026-09-20 TD-55 제출 기준: 본문의 최초 통합 당시 엔진 동결/미배포 상태는 역사 기록이다. 현재 엔진 정책은 [DB 우선 엔진](CATALOG_ENGINE.md), 실제 제출 상태는 [1차 제출본](V1_SUBMISSION.md)을 따른다. A 상하 카드의 VS/스파크·승자/패자·진출·Champion·로딩 연출을 추가하되 진입350ms·선택피드백280ms·준비 후7000ms·줄인 움직임을 유지한다. 최초 위치 중앙 계산 대신 실제 두 카드 사이에 VS를 배치해 긴 이름/키보드 포커스에서도 가리지 않는다. 최신 장소·가격 확인 미지원 안내와32강 의미유사후보 한계를 표시하며 가짜진행률·API단계·품질보증은 넣지 않는다.

기준일: 2026-09-18. 목적: 기획·디자인·API·구현의 차이를 한 곳에서 정하고 A/B가 겹치지 않게 제품을 연결한다. 디자인 코드가 실행된다는 이유로 서비스 기능이 완성됐다고 보지 않는다.

## 1. 기준과 확인 범위

- 제품 규칙: [PRD](../PRD.md), [Design Spec](../DESIGN_SPEC.md), [AC](../ACCEPTANCE_CRITERIA.md). 정확한 wire 계약은 [OpenAPI](../contracts/openapi.json)가 우선한다.
- 새 디자인 원본: 사용자가 제공한 `AI World Cup 시안4.html`. SHA-256 `7eb384e9adaed8b25a0f96b4c6b7859c094a89f94a49f959f83facc420633743`. 원본 파일은 수정하거나 공개 저장소에 추가하지 않았다.
- 원본의 `__bundler/template` JSON을 읽어 화면·스타일·데모를 대조했다. 로컬 HTML 직접 열기는 브라우저 보안 정책에 막혔으므로 이 문서는 소스 기준 분석이다. 실제 렌더링·폰트·접근성·모바일 시각 검증은 별도 미완료다.
- 사용자 선택: 대결은 **A — SPLIT DECK, 상하 카드**를 기본으로 한다. B — CLASH를 별도 구현하거나 임의로 데스크톱 기본으로 바꾸지 않는다.
- 코드 기준: `feat/engine/ce-002-generation`의 `fac926c`에서 통합 작업을 시작한다. 새 작업 브랜치는 `feat/server/design-integration`이다. main 자동 병합·운영 배포는 하지 않는다.
- GitHub 확인: main `c1503cf`, 프론트 브랜치 `0ce51f1`, 서버 브랜치 `4e7e21a`, 엔진 `fac926c`. 프론트 코드는 모두 같은 초기 shell이며 서버 PR #1은 미병합이다. 이 HTML은 GitHub에서 발견한 파일이 아니라 사용자가 별도로 제공한 자료다.

시안의 설명·데모 코드가 기존 정책과 다르면 아래 조정표를 따른다. 시안에 포함된 번들 로더, React 18 UMD, `DCLogic`은 새 의존성으로 도입하지 않고 현재 React/TypeScript/Vite 구조로 구현한다.

## 2. 바꾸지 않는 제품 규칙

로그인 없이 고민 입력 → 8/16/32강 → 검증된 후보 전체 preview → 전체 성공 재생성 최대 1회 → 사용자가 시작하면 snapshot freeze → 경기당 7초 → timeout 균등 랜덤 → N−1번 선택 → Champion → 동일 후보·초기 순서·규칙의 공유/새 session.

- 시작 전 후보 개별 편집/교체, 시작 후 Undo/재생성, 자동 강수 축소를 넣지 않는다.
- 결승도 같은 카드·정보·시간 규칙이다. timeout 선택도 경기 기록으로 서버에 보내되 선호 집계에서만 제외한다.
- 후보 엔진은 현재 구현을 통합용 v0.1 기준으로 고정한다. 이번 작업에서 프롬프트/모델/검증 기준을 더 변경하거나 유료 실험을 하지 않는다. 실제 8/16/32 API 연결 증거와 운영 품질 승인은 별개다.
- 필수 환경을 확인하지 않고 후보를 승인한 문제 등 후보 품질 한계는 미해결로 남는다. 화면 완성으로 품질 검증을 대체하거나 dev 합성을 실제 추천으로 보이지 않는다.

## 3. 화면·데이터·구현 대응

모든 API 경로 앞에는 `/api/v1`을 붙인다. 모든 POST는 작업별 `Idempotency-Key`, private 데이터는 익명 cookie를 유지한다.

| 시안 화면 | 실제 데이터/행동 | 현재 구현과 A 작업 |
| --- | --- | --- |
| 01 고민 입력 | 빈 입력 금지, 최대 500자, 기본 locale/timezone; 강수 화면으로 이동할 때는 아직 생성하지 않음 | 시안의 div를 접근 가능한 label/textarea로 구현. 기존 shell에는 입력 없음 |
| 02 강수 | 8/16/32, 기본 16. 확인 시 `POST /generation-jobs` | 서버 구현됨. 강수에 따라 7/15/31번 선택 표시 |
| 03 생성 | job GET의 QUEUED/RUNNING/READY/FAILED; READY의 draftId로 preview GET | 중복 polling·자동 POST 금지. 예제나 미검증 후보 이름 노출 금지 |
| 04 전체 미리보기 | `GET /drafts/{id}`의 N개·version·regenerationsRemaining | 8개 고정 예제만 있음. 16/32개도 전부 탐색 가능하게 스크롤 처리 |
| E1 전체 재생성 중 | `POST /drafts/{id}/regenerations`에 expectedVersion → job 조회 | 기존 preview 보존, 시작/재생성 잠금. 실패 시 원본 유지, 성공 후 새 version 조회 |
| 05A 상하 대결 | `POST /drafts/{id}/start`의 sessionId/snapshot → 인접 pair 및 승자 순서 | 서버 freeze는 구현됨. A는 렌더링과 분리한 게임 상태·timer·RNG·복원 구현 |
| 06 Champion | N−1개 event를 selections API에 저장하고 `COMPLETED`/championId 확인 | 공유는 마지막 event까지 서버 수락 후 활성화. 로컬 우승과 저장 중/실패 구분 |
| 07 공유 진입 | `GET /shares/{token}` → 원본 champion/snapshot; `POST /shares/{token}/sessions` → 새 session | 서버 구현됨. 원문 고민/history 비공개, AI 재호출 없음. A가 공유 화면 구현 |
| E2 생성 제한 | 기본 익명 브라우저 하루2회/서울 자정, 심사 설정0은 일일cap만 해제. 접수 기록·단기actor/IP·비용 제한 유지. 429/RATE_LIMITED와 실제 Retry-After | 설정과 무관한 정책 안내. 시안의 고정04:12/58% 대신 실제 응답 대기 안내(긴 대기는 시·분·초). 만료 때 자동 생성하지 않음. 교체 잔여1회를 일일 잔여로 표시하지 않음 |
| E3 공유 실패 | 404/NOT_FOUND와 일시적 503/PROVIDER_UNAVAILABLE 구별 | 같은 공유 주소 GET 재시도. 없는 링크를 재생성하거나 다른 후보로 대체하지 않음 |
| E4 복귀·새로고침 | local checkpoint의 snapshot/session/events/current deadline | 서버에서 현재 경기 timer를 복원하는 API는 없음. A가 로컬 복원·전송 대기열을 관리 |

## 4. 시안에서 반드시 조정할 동작

| 시안 내용 | 확정 구현 기준 |
| --- | --- |
| 생성 `5/16`, `31%` | API에 후보별 진행률이 없다. 불확정 loading과 상태 문구로 표시; 단계/퍼센트 발명 금지 |
| `CANDIDATE_QUALITY`, `UPSTREAM_UNAVAILABLE` | 실제 enum인 `QUALITY_GATE_FAILED`, `PROVIDER_UNAVAILABLE` 사용. 사용자 화면에는 이해 가능한 문구, 내부 code는 진단용 |
| `matchSeconds` 2–15 조절·고정 8개 후보 | snapshot의 N·candidates·initialOrder·rules 사용. matchDurationMs=7000, seed 후보/가짜 이미지 교체 |
| 420ms 지나면 무조건 timer 시작 | 양쪽 카드·이미지 또는 fallback·진입 연출 준비 뒤 deadline 시작. 이미지 실패/2초 초과면 같은 크기 fallback |
| `pick()`이 phase만 확인 | 선택 순간 deadline도 검사. 6999ms direct / 7000ms 이상 timeout; 클릭·timeout 경쟁에서 승자와 event는 1개 |
| 숨김 상태에서도 interval로 다음 경기 진행 | 현재 deadline은 유지하지만 다음 경기는 숨겨진 채 시작하지 않음. 복귀 때 만료한 현재 경기 하나만 처리 |
| E4 별도 결과 확인·‘다음 경기 시작’ 버튼 | 기존 ‘별도 timeout 결과 화면/확인 없음’ 유지. 짧은 안내 후 보이는 다음 카드 준비로 이동; 추가 확인 단계로 바꾸려면 별도 결정 |
| 결과 대기 900ms/1400ms, 반복 충돌 연출 | 기존 진입 300–450ms, 선택 피드백 200–350ms, 라운드 전환 ≤500ms 기준. reduced-motion에도 같은 준비/결정 순서 |
| Champion의 ‘시간초과 선택은 기록 제외’ | UI에 직접 선택만 별도 요약할 수 있으나 서버에는 TIMEOUT_RANDOM 포함 전체 N−1 events 전송. 제외 대상은 선호 반영 |
| 375×780 고정 프레임·overflow hidden | 서비스에는 고정 캔버스를 복사하지 않음. 360px부터 가로 넘침 없이 긴 이름/32개 목록 접근, 터치 44px 이상 |
| 데모 초기화 ‘다시 하기’ | 플레이 중 reset/Undo 없음. 새 월드컵은 새 입력, 같은 대진 재플레이는 공유의 새 session 경로. 로컬 reset으로 기존 session 재사용 금지 |
| 입력 이탈/‘고민 고치기’ | polling 중단은 서버 취소가 아님. 현재 jobId를 보존하고 같은 요청의 중복 과금/생성을 만들지 않음 |

모델이 작성한 보충 질문 payload와 생성 취소 endpoint는 현재 계약에 없다. TD-42 후속 구현은 초기 생성의 `CLARIFICATION_REQUIRED`에 제품이 정한 질문 1개를 표시하고 원래 입력+명시적 답변을 기존 생성 API에 새 요청으로 보낸다. 강수와 원래 조건을 보존하며 총 500 Unicode 글자 제한, 흐름당 1회, 명시 제출만 POST, 반복 실패는 일반 수정 경로다. 새 접수는 하루 2회 제한에 포함되므로 이전 실패가 차감됐음을 안내한다. 새로고침은 접수된 job 조회 또는 동일 key의 수동 재시도로 복원한다. 재생성 실패에서는 질문 화면으로 바꾸지 않고 기존 preview를 유지한다. 서버가 실제 질문을 생성하거나 다회 대화를 지원하는 것으로 표시하지 않는다. 생성 취소는 여전히 미구현이다.

## 5. 시각 구현 기준

- 시안의 밝은 배경 `#f3f2f2`, 진한 글자/대결 카드 `#201e1d`, 주황 강조 `#ec3013`, 직각 카드·굵은 경계·큰 제목을 재사용한다.
- 기본 대결은 A 상하 카드. 7초 countdown bar, 승자/timeout 안내는 두 후보 중 하나를 고르는 행동을 방해하지 않게 한다.
- Archivo 지정은 디자인 의도이며 한국어 glyph/fallback과 폰트 재배포 조건은 A가 실제 자산을 적용할 때 확인한다. 첨부 번들의 폰트·서드파티 런타임을 검토 없이 저장소로 복사하지 않는다.
- 이미지가 없거나 늦어도 동일 크기 대체 카드를 사용한다. 현재 live 후보 이미지가 없는 경우도 있으므로 새 이미지 생성 서비스는 추가하지 않는다.
- 후보 전체 상태칩, 결승 상대, Champion 선택 요약은 기존 snapshot+로컬 events로 표시 가능하지만 32강 카드 영역·성능·접근성을 확인한 뒤 붙인다. 기본 완주보다 먼저 확장하지 않는다.
- `8명` 같은 사람 전용 단어는 일반 후보 단위에도 맞는 `후보 N개`로 바꾼다. ‘7초 안에 끝내자’는 전체 서비스 완료 시간으로 오해되지 않게 경기당 7초임을 설명한다.

## 6. 병렬 개발과 소유 경계

| 담당 | 바로 진행할 묶음 | 소유 / 완료 증거 |
| --- | --- | --- |
| A — 플레이 경험 | 시안 01–04/E1–E3 → 05A/E4 → 06/07 순서로 React 구현 | `frontend/**`, 생성 타입 제외. 실제 API client와 fixture adapter, 상태·timer/RNG 테스트, 360px/키보드/복원 완주 |
| B — 서버·통합 | 동일 origin 배포 산출물과 공유 deep-link 진입 연결, 기존 API 상태 설명 정합성 | `backend/**`, `scripts/**`, 루트 빌드. 기본 backend jar와 별도 app jar, 실제 HTTP route 및 API 404 보존 |
| 공동 | DTO/오류/추가 질문/취소 등 계약 변화가 필요한 경우만 paired review | `contracts/**`; 현재 공개 DTO·경로·규칙 변경 없음 |

2026-09-18 사용자가 프론트도 맡아 현재 작업에서 A/B를 모두 구현하도록 승인했다. 외부 프론트 인계 대기는 해제한다. `feat/product/split-deck-flow`에서 PM/통합·순수 게임/API·UI/디자인·서버 테스트를 파일별로 분리하고 루트 AGENTS의 경계를 따른다. 타인의 checkout과 HTML 원본은 변경하지 않는다. PR #1과 엔진 변경은 아직 main에 병합되지 않았으므로 main만 받으면 최신 API/엔진이 없다는 점을 합류 안내에서 확인한다.

## 7. 이번 B 구현: 동일 origin 진입

별도 frontend 서버 없이 빌드한 React 정적 자산을 Spring Boot app jar에 담는다. API가 만든 `/shares/{token}` 주소를 새 탭/새로고침으로 열면 React 진입 HTML을 내려준다. 이는 공유 화면의 구현 완료가 아니라 A가 붙일 수 있는 서버 진입 경로다.

- 공개 페이지 경로 `/`, `/shares/{token}`만 React 진입점으로 연결한다. `/api/**`, 없는 `/assets/**`, 임의 잘못된 경로를 HTML 200으로 바꾸지 않는다.
- frontend bundle이 없는 backend-only 실행에서는 페이지가 실제 404여야 한다. 테스트 HTML을 운영 jar에 넣지 않는다.
- `appJar`는 사전 빌드한 `frontend/dist`를 추가하는 별도 산출물이다. 일반 `bootJar`/test resources에는 frontend를 섞지 않는다. index가 없으면 명확히 실패한다.
- 데이터베이스·공개 API·AI 호출/비용 설정·보안 cookie/Origin 규칙은 그대로다. 같은 주소 실행 때도 PUBLIC_ORIGIN은 실제 브라우저 origin과 일치시킨다.

로컬 합성 모드로 확인할 때 Node 24 / Java 21 / Docker를 준비한다. 저장소 루트에서:

```sh
npm ci
npm run package:app
docker compose up -d --wait postgres
PUBLIC_ORIGIN=http://127.0.0.1:8080 java -jar backend/build/libs/worldcup-0.0.1-SNAPSHOT-app.jar --spring.profiles.active=dev
```

브라우저 주소는 `http://127.0.0.1:8080`이다. 개발 DB volume은 보존한다. Unix 명령 기준이며 Windows는 WSL/Git Bash와 기존 실행 안내를 따른다. 클라우드 배포·공개 도메인·HTTPS 사업자 설정은 이 작업에 포함하지 않는다. 현재 프론트가 shell이면 app jar도 shell을 표시한다.

## 8. 완료 판정

이번 서버 연결: 관련 HTTP 테스트 → 독립 코드 리뷰 → `./scripts/verify.sh`(계약/웹 build/Java/두 jar) → 실제 app jar의 HTML·자산·API route 확인. AI 키 없이 dev/격리 DB를 사용하고 유료 호출은 0회다. 검증 결과는 실행 후 아래에 적으며 계획을 완료 증거로 쓰지 않는다.

제품 완성: A의 실제 화면에서 8/16/32 입력→preview/재생성→N−1 경기→Champion 저장→공유→새 session까지 완주, 실패/숨김/복원/접근성 확인. 기존 서버 테스트나 디자인 데모만으로 AC-10~13/17·전체 제품 완성을 주장하지 않는다. 후보의 운영 품질과 배포 완료도 별도다.

2026-09-18 이번 B 구현 검증: 관련 HTTP 18개 통과 후 독립 리뷰 Critical 0 / High 0 / 남은 actionable 0. 이후 `./scripts/verify.sh` 통과 — Java 189개 실행, 실패/오류 0, opt-in 유료 1개 제외; handoff 6개, fixture 5개, 기존 HTTP 응답 102개/8 schemas, 웹 build와 기본/app 두 jar 성공. 별도 실제 app jar를 격리 PostgreSQL·dev·AI worker OFF로 실행해 14개 HTTP 점검을 통과했다. 두 페이지의 실제 빌드 HTML, JS/CSS 바이트 일치, HEAD/405, API 및 없는 자산/경로 404를 확인했다. 기본 jar에 웹 문서/테스트 fixture가 없고 app jar에만 정적 자산이 포함되는 것도 확인했다. bundle 누락 때 appJar가 실패하는 경로는 임시 격리 프로젝트에서 검증했다. 유료 호출 0회. 시안의 브라우저 렌더링과 실제 프론트 완주는 미검증이며 사용자 HTML 원본·frontend 소스는 변경하지 않았다.

## 9. A/B 통합 구현 규약

현재 통합 브랜치는 `feat/product/split-deck-flow`다. 기존 API/DB/후보 엔진을 그대로 사용하며 실제 화면과 게임을 React에 연결한다.

- `frontend/src/api`는 생성된 wire 타입과 동일 origin HTTP만 담당한다. 성공 DTO도 개수/ID/규칙/공유 우승 등 공개 불변 조건을 검사한다. dev 합성 후보는 화면에서 명시한다.
- `play`는 불변 snapshot과 순수 준비/활성/피드백/완료 상태를 담당한다. RNG/시각을 주입해 경계값을 검증한다. `ui`는 A 상하 레이아웃과 카드 준비·접근성만 담당한다.
- `app`은 생성 job, preview, key/body, 로컬 checkpoint, 전송 queue와 서버 ACK를 소유한다. 선택은 저장 실패로 되돌리지 않으며 실패한 batch를 보존한다. 새로고침 후 완료 ACK/공유의 캐시를 신뢰하지 않고 저장된 event를 서버에 재확인한다.
- 브라우저 저장소와 Web Locks가 가능한 최신 브라우저에서 동작한다. 같은 경로의 쓰기 탭은 하나만 허용한다. 저장 불가·다른 탭 충돌·지원 불가 때 조용히 진행하지 않는다. checkpoint가 손상/만료되면 이전 경기를 재시작하지 않고 안내한다. job/draft는 24시간, play는 30일 복원 기간을 서버 보존 정책에 맞춘다.
- 로그인이나 설정 화면을 추가하지 않는다. 이 기기의 진행을 위한 prompt/checkpoint만 로컬에 보관하고 쿠키·비밀키는 JS 저장소에 복사하지 않는다. 공유 페이지는 서버의 공개 snapshot만 사용한다.

`npm run test:web`는 Node 24 내장 테스트로 순수 상태/API/워크플로를 확인한다. 실제 브라우저 회귀를 재현하기 위해 Playwright를 **개발 의존성으로만** 추가했다. 설치된 Chrome과 별도로 띄운 합성 `dev` 서버·격리 DB에서 다음을 실행한다.

```sh
WORLDCUP_SYNTHETIC_E2E=1 npm run test:browser -- http://127.0.0.1:8080
```

이 명령은 생성 job 4개와 선택/공유 데이터를 만든다. live 서버에 실행하지 않는다. rate limit을 풀지 않으며 반복 검증은 별도의 임시 DB를 사용한다. 캡처는 ignore된 `reports/local/browser`에 저장한다. 숨김은 브라우저 visibility 이벤트 시뮬레이션이며 OS/모바일 절전 모드 자체를 검증했다는 뜻은 아니다. 전체 브라우저 결과와 독립 리뷰 수치는 실행 후 기록한다.

`npm run test:browser:states -- http://127.0.0.1:8080`은 API/이미지를 전부 가로채는 합성 UI 예외 테스트다. 생성 실패 후 새로고침·고민 수정, 이미지 2초 fallback, 360/1280의 긴 이름·상하 A·키보드 선택을 확인하며 서버 생성 요청을 하지 않는다. 본문/강조의 원안 주황은 유지하고 작은 흰 글자 버튼은 대비를 위해 `#d92b10`으로 어둡게 조정했다. 폰트는 재배포 불명확한 번들을 복사하지 않고 시스템 한글 fallback을 사용한다.

## 10. 2026-09-18 통합 검증 결과

- `./scripts/verify.sh`: 프론트 48개·handoff 6개, fixture 5개/생성 타입, 웹 build, Java **192개 실행/실패·오류 0**(opt-in 유료 1개 제외), 기본/app 두 jar 성공. 실제 HTTP 응답 159개/8개 schema 검사 통과.
- 최종 app jar + PostgreSQL 17 격리 DB + 명시적 dev: 브라우저 360px에서 8/16/32 preview와 7/15/31개 결정·서버 COMPLETED·공유 성공. 8강 전체 재생성 1회, active 새로고침 deadline 유지, 숨김 이벤트 후 만료 경기 1개 처리, 두 번째 탭 선택 잠금, 다른 익명 사용자의 동일 snapshot/새 session 완주를 확인했다. 생성 POST는 의도한 4개뿐, 공유 replay의 생성 POST 0개, 브라우저 예외 0개다.
- 합성 UI 예외: 생성 FAILED→reload에서도 수정 가능하며 자동 POST 0개. 늦은 이미지 동안 deadline null, 2초 fallback 뒤 7초 시작, 360/1280 긴 이름·상하 배치·44px 카드·키보드 선택, reduced-motion 설정을 확인했다. 추가 브라우저 점검에서 360px·reduced-motion으로 8강의 7경기를 모두 키보드 Enter로 완주하고 합성 ACK 완료를 확인했다. 캡처로 입력/미리보기/대결/Champion/공유를 점검했다.
- 독립 리뷰 1회차 Critical 0/High 1/Medium 1: terminal 생성 실패 복원 시 오류 UI가 사라지던 문제, 공유 화면의 새 월드컵 문구와 기존 진행 복귀 동작 불일치. 안전한 복원 안내와 명시적 복귀 문구로 수정하고 회귀 테스트 추가. **2회차 Critical 0/High 0/남은 actionable 0**, 이후 전체 verify 통과.
- 후보 엔진·OpenAPI/생성 schema·DB migration 변경 없음. API 키/live profile 없이 검증했으며 유료 호출 0회다. 실제 후보의 사람 평가 FAIL·외부 사실 eval·운영 품질 승인은 미해결이며 main 병합·공개 배포를 하지 않았다. 실제 휴대폰 OS 절전/백그라운드, 보조기술의 전체 수동 인수, 구체 보충 질문/생성 취소 계약은 별도 확인/후속 범위다.
