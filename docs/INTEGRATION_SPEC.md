# 제품 통합 Spec v1.0 — 시안 4

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
| E2 생성 제한 | 429/RATE_LIMITED와 Retry-After | 시안의 고정 04:12/58% 대신 응답 수신 기준 대기 안내. 만료 때 자동 생성하지 않음 |
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

구체적인 보충 질문 payload와 생성 취소 endpoint는 현재 계약에 없다. 당장은 명확한 오류·입력 수정 경로로 연결하며 후속 계약 논의로 남긴다. 이 기능을 이미 구현했거나 요구에서 삭제했다고 표시하지 않는다.

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

프론트 팀원의 checkout/브랜치나 HTML 원본은 변경하지 않는다. 새 프론트 브랜치는 현재 통합 기준에서 만들거나 필요한 서버 변경을 동료가 반영한다. PR #1과 엔진 변경은 아직 main에 병합되지 않았으므로 main만 받으면 최신 API/엔진이 없다는 점을 합류 안내에서 확인한다.

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
