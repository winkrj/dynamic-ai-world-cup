# 검증 기록

## 2026-09-18 TD-47 — 제외 조건 분류 지침, 추가 유료 호출 없음

v20에서 비운동 제외가 soft로 내려간 경로를 read-only Explorer와 별도로 대조했다. 서버의 구조/원문 포함 검사와 해석 verdict 집행은 의미 오분류 자체를 판별하지 않는다는 한계를 확인했다. 공통 프롬프트에 직접 거부·상대적 선호·부정문의 대조 기준과 독립 검토의 CONSTRAINTS finding 지침만 추가하고 v21로 버전 관리했다. 정책 v3-engine-v21은 이전 기준의 DB 승인 세트를 자동 재사용하지 않도록 분리한다. 모델/schema/API/Repair/시간·비용 예약/프론트/게임 규칙 변경은 없다.

- 관련 검증: `CANDIDATE_LIVE_TEST=false`로 client 52개·engine 75개·reuse service 36개, **총 163개 PASS**. loopback 모의 provider만 사용했다.
- 신규 4개 회귀: 제외 조건이 선호로만 남은 모의 계획에 대해 사전/최종 해석 FAIL·UNKNOWN은 후보 전체 PASS와 별개로 종료하고 Repair를 호출하지 않는다. 기존 6단계 지침 전달 테스트도 대조 예시를 확인한다.
- 독립 read-only Reviewer cycle 1: **Critical 0 / High 0 / actionable 0**. 이후 production/test 코드 수정 없이 전체 verify.
- 최종 `CANDIDATE_LIVE_TEST=false ./scripts/verify.sh` 종료 0: **Java 313개 실행/실패·오류 0/유료 1개 제외(총 314)**, frontend 60개, handoff 6개, release 13개, fixture 5개·생성 TS, HTTP 166개/9 schemas, 웹 build·bootJar·appJar PASS.

실제 v21 provider 호출 0회이며 원시 장부 재합산은 $4.8498083/$6·총 시도 143회로 TD-46 이후 변하지 않았다. 자동 충전 OFF 설정을 변경하지 않았다. 새 실제 평가 승인/사람 품질 판정/32강 중복 해결은 없다. 보존된 v20 실패를 PASS로 덮지 않고 동일 실패 수정 루프를 버전명으로 초기화하지 않는다. v20 소스의 x86 runtime 검증 기록은 아래에 보존하며 이번 v21은 JAR까지 검증했고 새 runtime image·AWS 배포는 실행하지 않았다. 관련 문서의 diff/비밀값 노출 검사를 별도로 수행하며 코드 리뷰를 문서만 바뀐 뒤 반복하지 않는다.

## 2026-09-18 TD-46 — 실제 단일 평가와 추가 호출 없는 완주 검사

변경 범위는 test-only 공통 `PostPreviewHttpFlow` 및 이를 사용하는 기존 Worldcup/LiveEngine HTTP 테스트, 결과 문서다. production·OpenAPI·프롬프트·모델·비용 예약 정책은 변경하지 않았다. 실제 READY가 나오면 같은 preview의 freeze, N−1 선택 저장, Champion, 동일 snapshot 공유/새 익명 session을 검증하고 provider 장부/호출 수 및 생성 job 1개가 유지되는지 확인하도록 했다. 브라우저 timer/난수 품질 테스트는 아니다.

실행 전 focused 테스트에서 preview에 없는 initialOrder를 비교하던 새 테스트 오류가 8/16/32에서 드러났다. 공개 계약은 그대로 두고 frozen order가 유일한 후보 ID 전체와 일치하도록 검사 대상을 수정했다(동일 실패 수정–재검증 1회). 이후 WorldcupHttpTest 14개 PASS/유료 1개 제외, HTTP 166개/9 schemas PASS. read-only Reviewer 최초 1회 Critical 0/High 0/actionable 0, 그 뒤 전체 `CANDIDATE_LIVE_TEST=false ./scripts/verify.sh` 종료 0: Java 309개 실행/유료 1개 제외, 프론트 60개, handoff 6개, release 13개, fixture 5개·생성 TS, 웹/bootJar/appJar, HTTP 166개/9 schemas PASS.

사용자 승인 후 별도로 `LiveEngineHttpTest`만 실제 provider를 켜서 **1회 실행**했다. 이전 전체 장부를 다시 합산하고 격리 DB에 잔여 $1.2376317만 허용했다. `hobby-solo/32`, v20, Terra, 75.395초/4회 HTTP 200, 검색 0/사전 Repair 1회/$0.08744. Repair 재검토에서 그림 중복과 포괄적 학습 활동을 거절해 승인 30/32, **QUALITY_GATE_FAILED**로 종료했다. READY 기대 불충족으로 실제 테스트 **1개 FAIL**이며 사전 오프라인 회귀 PASS와 구별한다. 상세 생성/최종 feasibility/preview/완주·공유 검사는 실행되지 않았다. 원인 세부는 `CANDIDATE_ENGINE.md` TD-46 절에 기록했다.

실행의 QUEUED/FAILED HTTP 응답 2개는 기존 GenerationJob schema PASS다. 원시 응답과 비용 장부는 gitignored `reports/local/live-engine/2026-09-18T07-33-51.503430Z-size32/`에만 보존했다. provider 요청 4개 모두 tools 빈 배열이고 장부 search_calls=0을 대조했다. 현재 누적 장부 **$4.8498083/$6**(미확인 예약 $0.50 포함), 잔여 $1.1501917. 자동 충전 OFF를 실행 전에 다시 확인했고 변경하지 않았다. 승인 소진, 추가 유료 재시도 없음. 실패를 해결됐다고 보고하거나 기존 사람 FAIL을 변경하지 않는다. AWS 자원 생성/registry push/공개 배포도 하지 않았다.

## 2026-09-18 TD-45 후속 — 승인된 Docker 복구·x86 실행 검증

사용자가 강제 종료·재실행을 승인한 뒤 정확히 확인한 Docker Desktop 프로세스에만 종료 신호를 보냈다. 종료되지 않은 backend 프로세스 하나만 강제 종료했고 시스템 helper는 건드리지 않았다. 공식 시작 명령의 접수 응답만으로 성공을 판단하지 않았으며 앱 재실행 뒤 `_ping=OK`와 엔진 27.4.0 응답을 확인했다. 기존 프로젝트 DB와 재시작 정책이 있는 다른 프로젝트 컨테이너도 다시 실행 중임을 확인했다. 아래 이전 timeout 기록은 당시 결과로 보존한다.

기존 빌더의 ARM 이미지는 빌드가 끝났지만 `exec format error`로 시작하지 못했다. CPU는 image/engine 모두 ARM64였고 `/opt/java/openjdk/bin/java`가 **0바이트**였다. 기존 캐시를 지우지 않고 별도 docker-container 빌더로 다시 받아도 로컬 적재 이미지에서 같은 현상이 남았다. 공유된 로컬 이미지 계층 손상이 의심되지만 Docker 내부 원인을 확정하지 않았고 ARM 실행 문제를 해결됐다고 처리하지 않는다. Dockerfile·앱 코드를 바꾸지 않고 같은 소스를 `linux/amd64`로 빌드한 대안은 실제 실행까지 통과했다.

검증한 로컬 x86 이미지: `dynamic-ai-world-cup:verification-b73908a-amd64-20260918`, digest `sha256:f53233cc7924a81d0d56f5062f74f1801e8364c84ff43159cae0864895e8124a`. 소스는 `b73908a2d97329cb000217334e70f387dede1b6f`이며 변경 파일은 문서뿐이다. registry push나 AWS 아키텍처/사양 확정은 하지 않았다.

| 실제 재실행 | 결과와 범위 |
| --- | --- |
| 백엔드 `CANDIDATE_LIVE_TEST=false ./gradlew --no-daemon test --rerun-tasks` | Java 309개 실행, 실패·오류 0, 유료 1개 제외(총 310). 캐시 재사용이 아닌 실제 격리 PostgreSQL 테스트 재실행 |
| HTTP 계약 대조 | 생성 타입·fixture 5개, 실제 HTTP 응답 160개/9 schemas PASS |
| x86 runtime | Java 21.0.12, UID/GID 10001:10001, 읽기 전용 root·쓰기 전용 `/tmp`·capability 제거·no-new-privileges 조건에서 시작 PASS |
| `prod,live` 시작 | 임시 PostgreSQL 17.11에 V1~V3 적용, 웹 번들·worker·readiness PASS. 외부 통신 없는 internal network, 가짜 key, AI 예산 0 사용 |
| 읽기 전용 release smoke | `/`, share deep link, JS/CSS 2개, health·ready, 없는 API/asset 404 PASS. 생성·세션·공유 요청 없음 |
| 패키지 운영자 CLI | 같은 이미지의 `PropertiesLauncher`로 `CandidateReuseOperator pending` 실행·종료 0. 빈 목록이며 실제 후보 승인/사용은 하지 않음 |
| 같은 이미지의 dev API smoke | 합성 8강 생성·재생성 1회·선택 7개·완료 저장·새 익명 세션의 같은 snapshot 공유 PASS. 실제 AI·브라우저 timer 검증 아님 |
| 격리 DB 백업·복원 | 앱 중지 후 `pg_dump`를 새 빈 테스트 DB로 복원. snapshot 1개·share 1개·Flyway 3개·장부의 전체 행 hash 일치, snapshot UPDATE trigger 거절 확인 |

prod/dev smoke 직후 `provider_call`은 **0건**이었다. 복원 검증에만 가짜 `FAILED_UNKNOWN_COST` 예약 1건/$0.50을 직접 넣어 금액·상태 보존을 확인했다. 이는 실제 provider 호출·지출·기존 실험 장부 변경이 아니다. 기존 누적 $4.7623683/$6와 유료 승인 소진 상태는 그대로다. 원본/복원 DB 모두 이번에 생성한 메모리 기반 테스트 DB이며 실제 사용자·개발자 DB는 테스트하지 않았다.

관찰한 idle 메모리는 앱 약 342MiB, DB 약 99MiB(각 512MiB 제한)였다. Apple Silicon에서 x86 에뮬레이션 중의 단일 관찰이므로 운영 부하·AI 동시 생성·최소 AWS 사양의 근거로 일반화하지 않는다. 같은 호스트 내 논리 복원은 별도 호스트 장애 복구, 보관 백업, provider 장부 대조, RPO/RTO 달성을 대체하지 않는다.

검증 후 이번에 만든 앱/DB 컨테이너와 internal network만 정리했다. 합성 테스트 데이터는 영구 보관하지 않았으며 테스트로 재생성할 수 있다. 기존 데이터·컨테이너·이미지·volume은 삭제하지 않았다. 전용 빌더는 중지하고 이미지·빌드 cache는 보존했다(기본 빌더 미변경). 런타임 코드 변경이 없어 독립 코드 Reviewer를 다시 돌리지 않았으며 앞선 코드 리뷰 결과와 구분한다.

남은 사항: 로컬 ARM 계층 문제, AWS 무료 적용·자원 사양/HTTPS·trusted proxy/IP·운영 백업 구성, 실제 운영 호스트 검증과 공개 URL, 최신 v20 실제 후보 품질·사람 승인·운영 AI 예산. 로컬 x86 검증 성공은 공개 배포 완료나 Goal 전체 완료가 아니다.

## 2026-09-18 TD-45 — 무료 배포 범위 정정·Docker 복구 시도

실제 코드 기준은 `b73908a2d97329cb000217334e70f387dede1b6f`이며 이번 변경은 배포 비용·승인 범위와 실행 상태 문서뿐이다. AWS Free plan의 크레딧 소비와 실제 결제를 구분하고, 일반 가격표·월 $25 제안을 승인 예산으로 취급하지 않도록 AGENTS/Decision Log/배포 문서를 정리했다. AWS 자원 생성·유료 전환과 추가 AI 호출은 하지 않았다.

디스크 여유 공간은 약 **16GiB**로 확인했으나 Docker 상태 API는 5초 안에 아무 응답을 주지 않았다. 사용자가 기존 컨테이너의 일시 중단 가능성을 확인하고 Docker Desktop 재시작을 승인했다. 공식 `docker desktop restart --timeout 120`은 기존 프로세스를 종료하지 못해 `processes still running ... context deadline exceeded`로 실패했다. 앱 상태 확인도 제어 도구의 timeout으로 종료됐다. 이후 같은 Docker 프로세스가 남아 있고 상태 API가 계속 무응답인 것을 재확인했다. Docker가 정상 재시작됐다고 판단하지 않는다. 컨테이너·이미지·volume 삭제, 공장 초기화, 강제 종료는 수행하지 않았다.

Node 24로 `node --test scripts/smoke-release.test.mjs`를 재실행해 **13개 PASS**, `git diff --check`도 통과했다. 이는 읽기 전용 점검 도구의 합성 테스트이며 실제 컨테이너 기동·readiness smoke·운영자 CLI의 실제 DB 실행을 대신하지 않는다. 이번에는 런타임 코드 변경이 없어 별도 코드 Reviewer를 반복 실행하지 않았다. 최신 v20 실제 품질 평가·사람 승인·공개 배포는 미완료이며 추가 유료 승인도 없다.

## 2026-09-18 TD-42~44 — DB 재사용·서비스 보완·준비 가능성 기준

현재 로컬 변경: 승인된 complete set의 exact-context 재사용, 원본 certificate와 승인/사용 감사 기록(V3), 취소/만료·동시 완료·재생성 동일 구성 차단, 초기 생성 보충 질문 1회, v20 장비 준비/외부 환경 구분, readiness·prod 시작 검사와 배포 패키지다. 모든 합성 검증은 `CANDIDATE_LIVE_TEST=false`이며 실제 승인된 공용 후보 세트는 아직 없다.

전체 `npm run verify` PASS: **Java 309개 실행/실패·오류 0/유료 1개 제외(총 310)**, 프론트 **60개**, handoff **6개**, release 도구 **13개**, fixture **5개**, 실제 HTTP **160개/9 schemas**, TypeScript·웹 build·bootJar·appJar. Java/실제 격리 PostgreSQL 테스트는 이번 상태에서 실행했다. 재사용 관련 48개와 12개 실제 DB lifecycle 테스트가 포함되며 quota·snapshot·lease 회귀를 유지했다. 독립 read-only Reviewer 1회 **Critical 0 / High 0 / actionable 0**. Reviewer는 구현 파일을 변경하지 않았다. 이후 변경은 결과/운영 문서뿐이다.

프론트 합성 브라우저 점검 PASS: `frontend/tests/clarification.browser.mjs`에서 360/1280px, 원래 고민/32강/답변 복원, Unicode 500자 제한과 무단 잘라내기 없음, 키보드 제출, reduced motion, 429 대기/새로고침 뒤 자동 POST 없음, 수동 같은 key 재시도, 반복 질문 대신 수정 경로. 모든 API는 합성 응답으로 가로챘다. 실행 서버는 종료했고 기존 18086 preview는 수정하지 않았다.

배포 검증은 **부분 통과**다. Docker 웹 stage 빌드와 로컬 통합 JAR는 통과했고 JAR 안의 웹 문서와 운영자 launcher 존재를 확인했다. 전체 runtime 이미지 빌드는 기반 이미지 적재 중 Docker/containerd `input/output error`로 실패했다. 같은 시점 macOS Data 여유 공간은 **161MiB(100% 사용 표기)**였다. 기존 파일·이미지·volume 삭제나 Docker 재시작을 하지 않았다. 완성 runtime 이미지, 새 이미지의 실제 기동·readiness smoke·운영자 CLI 실제 DB 실행은 아직 검증하지 못했다. 디스크 공간 확보 후 해당 검증을 재개해야 한다.

별도 승인된 v19 실제 평가 1회는 위 합성 통과와 달리 **FAILED**였다: 176.932초/6회/$0.238354/검색 0/사전 Repair 1회, 최종 UNIT FAIL·베이킹 환경 UNKNOWN·preview 없음. 누적 장부 $4.7623683/$6(미확인 예약 $0.50 포함), 추가 승인 없음. v20은 사용자 기준을 반영했으나 실제 호출/사람 품질 검증은 하지 않았다. 실패 기록은 소급 승인하지 않았다.

미완료: v20 실제 후보 품질·사람 승인, 실제 승인 pool의 재사용/비용 절감, 최신 실제 AI와 프론트 완주, preview 준비 정보 충분성, 배포 사업자/월 운영비/도메인 선택, proxy/IP·TLS·DB backup/복원, 공개 배포. 이번 상태는 전체 Goal 완료가 아니다. 아래는 이전 시점별 검증 기록이다.

## 2026-09-18 TD-40 — 일일 생성 접수 제한

AC-19: 기본 익명 브라우저별 하루 2회, 서울 자정 초기화, 생성/재생성/접수 후 실패 합산. 기존 rate event/transaction을 사용하고 schema/공개 DTO/모델은 그대로다. 24시간 event 보존, 알려진 제한만 정확한 Retry-After, 프론트 정책 안내와 긴 대기 표시를 함께 변경했다.

새 실제 PostgreSQL/HTTP 테스트 12개 PASS: default 2회·8/16/32 동일 차감, idempotency 동시 재전송·마지막 슬롯 경합, 실패한 재생성/접수 rollback, 서울 자정·시간대 조작·DB 잠금 대기가 자정을 넘는 경우, retention 뒤 당일 기록, IP burst·자정 중첩, 소진 후 완주/공유/replay, 미상 제한의 대기시간 미발명. 최초 테스트 assertion 컴파일 오류 1건을 수정했고 동작 테스트 실패는 없었다.

전체 verify 실행 PASS: **Java 244개 실행/실패·오류 0/유료 1개 제외**, 프론트 **50개**, handoff **6개**, fixture **5개**, 실제 HTTP **159개/8 schemas**, 웹 build·bootJar·appJar. 독립 read-only 리뷰 1회 **Critical 0 / High 0 / actionable 0**. 코드 변경 없이 마지막 verify도 PASS(Gradle은 동일 입력 UP-TO-DATE, 웹 테스트/빌드와 계약 검사 재실행).

합성 브라우저: 360/1280px 생성 전·preview 정책 안내, 24시간 대기, 만료 자동 POST 0회, 수동 재시도 동일 key/body, 키보드/reduced motion/가로 넘침·브라우저 오류 없음. 기존 browser-states의 fallback·복구 회귀도 통과했다. 재현: Node 24로 `node frontend/tests/generation-policy.browser.mjs <loopback frontend origin>`. provider 호출은 가로채며 원래 18086 preview/DB는 변경하지 않았다.

이 quota 검증은 무료 합성 검증이다. 별도 승인한 실제 v18 32강 1회는 사전 해석 실패로 **live test 1개 실패**, $0.0728365, preview 없음이며 CANDIDATE_ENGINE에 따로 기록했다. 후보 품질/운영 배포 완료가 아니다. 누적 장부 $4.5240143/$6(미확인 예약 $0.50 포함), 자동 충전 OFF·추가 유료 호출 승인 없음. 후보 DB 재사용은 TECH_DESIGN 8절의 미구현 제안이다.

아래 첫 기록은 2026-09-12 저장소 기반 + CE-001이다. 이후 API Goal 검증은 문서 하단에 별도로 남긴다.

## 최종 로컬 검증

`./scripts/verify.sh` 최종 통과. 검증 환경은 macOS arm64, 임시 설치 Node 24.21.0, Java 21.0.5, Gradle 9.7.1. 시스템 기본 Node 23은 변경하지 않았다.

| 검증 | 결과 |
| --- | --- |
| OpenAPI → TS 타입 drift | 통과 |
| schema fixture | 5개 통과, 필수 필드 누락/잘못된 image scheme의 거절 확인 |
| TypeScript strict / Vite production build | 통과 |
| CandidateQualityGate JUnit | 38개 통과 |
| Java preview / 공용 fixture 일치 | 2개 통과 (이미지 null/값 존재) |
| 실제 HTTP health / Spring context | 각 1개 통과 |
| Java 전체 | 42개, 실패 0 / 오류 0 / skip 0 |
| Spring 실행 JAR 생성 | bootJar 통과 |
| 브라우저 360×800 | 후보 8개, document scrollWidth=360, 가로 넘침 없음 |
| 브라우저 1280×900 | 후보 8개, document scrollWidth=1280, 가로 넘침 없음 |
| 브라우저 console error | 0 |

화면 검증은 초기 고정 fixture shell에 한정된다. 사용자 완주 E2E나 최종 디자인 승인으로 해석하지 않는다. GitHub CI는 같은 verify 명령을 실행하도록 구성했으며 각 실행 결과는 저장소 Actions가 기준이다.

## 실패와 수정 기록

- 최초 Java contract test 컴파일에서 generic `valueToTree`와 AssertJ overload가 모호했다. JsonNode 타입을 명시해 수정했고 재검증 통과(동일 실패 수정 1회).
- sandbox 실행에서 npm 외부 다운로드, Gradle의 사용자 캐시 접근, 개발 서버 포트 열기가 제한됐다. 필요한 권한으로 재실행했고 최종 검증은 통과했다. 제한을 제품 코드 실패로 집계하지 않는다.
- 최초 독립 review: Critical 0 / High 0 / Medium 1 / Low 1. 특수 공백만 있는 이름/태그 허용과 대문자 HTTPS scheme의 schema 불일치 발견.
- normalized-empty 검사와 schema와 동일한 scheme 판정으로 수정. 회귀 테스트 3개와 non-null image 공용 fixture를 추가했다.
- 최종 독립 re-review(2회차): clean, Critical 0 / High 0, 이전 finding 모두 해결. 이후 변경은 이 검증 기록과 상태 문서뿐이다.

미실행: 실제 LLM/search candidate eval, PostgreSQL/Flyway, 전체 사용자 플로우 E2E, 실제 공유 링크, 배포. 이들은 아직 구현되지 않았다. synthetic fixtures와 대화 calibration은 모델 성능 지표가 아니다.

## 2026-09-14 백엔드 API Goal — 최종 검증

브랜치 `feat/server/backend-api`, 범위 B-002/003 및 B-004 서버 저장·검증. 이 기록은 위 초기 DB 미실행 상태를 갱신한다. 구현·최종 검증·독립 review 완료이며 main 병합·배포는 하지 않았다.

최초 전체 `./scripts/verify.sh` 통과: Java 73개(실패/오류/skip 0), 실제 HTTP 응답 96개/8개 schema, 기존 fixture 5개와 TS drift check, 웹 production build, bootJar. Node 24.21.0, Java 21.0.5, Gradle 9.7.1, Spring Boot 4.1.1, Docker Desktop 27.4.0, 공식 PostgreSQL 17-alpine(실행 버전 17.11)로 확인했다.

| 검증 | 범위 / 결과 |
| --- | --- |
| 기존 quality gate/preview | 40개, 기존 경계 회귀 통과 |
| 게임 순수 domain | 5개, 8/16/32 N−1 결정, 결승/시간 경계, 중복/순서 거절 |
| DB 통합 | 14개, 재생성 실패 보존, 동시 regen/start, idempotency, atomic batch, snapshot SQL 불변성, lease fencing/최종 실패 복원, quota, direct-only history, 정리 후 공유 존속 |
| 실제 HTTP | 제품 API 10개 테스트 + health 1개, cookie/hash/소유권, strict JSON/입력/enum/405, 8/16/32 완주 업로드·공유·재플레이, 102개 응답의 OpenAPI 대조 |
| profile / context | 엔진 profile 3개, 실제 서버 profile 설정 4개, Spring context 1개. dev 명시 표시/기본·prod 실제 엔진 미연결 실패, 혼용·보안 해제 거절 |
| 실제 개발 서버 smoke | 전용 compose DB migration → dev 서버 18080 → 자동 worker READY → 후보 8개 → 선택 7개 COMPLETED → 새 익명 공유 replay의 snapshot 동일 확인 |

DB 테스트는 Testcontainers가 만든 격리 DB만 초기화했다. smoke는 이 프로젝트 전용 compose DB(55432)를 사용했으며 기존 다른 프로젝트의 5432 DB는 변경하지 않았다. 실제 LLM/검색 호출·운영 배포·프론트 완주 화면 테스트는 하지 않았다.

실패/수정 경과:

- 첫 PostgreSQL 17 이미지 다운로드 뒤 Docker content digest 누락으로 컨테이너 생성 실패. 기존 데이터나 이미지를 삭제하지 않고 같은 PostgreSQL 17의 공식 Alpine 이미지를 받아 재검증 통과(환경 복구 1회).
- 신규 DB 테스트의 generic transaction 결과를 AssertJ에 바로 넘기면서 overload 모호성 발생. Boolean 변수를 명시해 컴파일/테스트 통과(수정 1회).
- 전체 스크립트의 sandbox 실행은 Gradle 사용자 캐시 접근 제한에서 중단됨. 필요한 권한으로 전체 검증을 재실행해 통과했다. 이를 제품 코드 실패로 집계하지 않는다.
- 독립 리뷰 1회차: Critical 0 / High 0 / Medium 2. 잘못된 GET/POST가 generic 500으로 변환되는 오류 매핑, dev/prod 혼용 시 개발 cookie 설정이 남는 문제를 확인했다.
- 405/Allow 명시 매핑, 실제 profile 설정을 읽는 혼용·Secure cookie 시작 차단, 관련 HTTP/profile 회귀 테스트를 추가했다. 숫자 enum 변환도 금지했다. 관련 테스트 통과 후 2회차 리뷰와 전체 verify를 다시 실행했다.
- 보완 중 Spring Allow builder의 Set/varargs 차이와 Jackson 3에서 이동한 EnumFeature 위치 때문에 컴파일 오류가 발생했다. 실제 API를 확인해 각각 수정하고 관련 테스트가 통과했다. 같은 수정안을 반복 실행하지 않았다.

smoke 서버와 이 프로젝트 전용 compose DB는 확인 후 중지했다. 로컬 합성 데이터가 담긴 전용 volume은 남겼으며 삭제하지 않았다.

최종 독립 review 2회차: clean, Critical 0 / High 0 / 남은 actionable finding 0. 이전 Medium 2개 해결 확인. 이후 코드 변경 없이 전체 verify 재실행: **Java 78개, 실패 0 / 오류 0 / skip 0; HTTP 102개/8개 schema; fixture 5개; TS/웹 build/bootJar 통과**. 문서 로컬 링크 16개와 diff whitespace도 확인했다. 마지막 변경은 이 결과를 반영한 상태/검증 문서다.

실제 provider 품질·Grounding·Repair·개인화 가중치 eval, 프론트 완주 E2E, production 배포는 이번 서버 Goal 범위 밖이다. GitHub Actions 결과는 PR의 실제 check를 기준으로 확인한다.

## 2026-09-14 프론트 API 인수인계

범위는 프론트 시작 안내, FE-001 상세 티켓, 기존 문서의 진입 링크, 로컬 연결 확인 도구다. backend 제품 코드·OpenAPI·생성 TS 타입·프론트 UI는 변경하지 않았다. PR #1이 미병합인 상태에서도 최신 API 브랜치에서 시작하도록 안내한다.

| 검증 | 결과 |
| --- | --- |
| `npm run test:handoff` | Node 기본 테스트 6개 통과: 전체 흐름, cookie/key 유지, body 생략, 비로컬 주소 거절, polling 상한, 실패 종료, snapshot 불일치, 안전한 오류 안내 |
| `./scripts/verify.sh` | 통과. fixture 5개·생성 타입 일치, 위 Node 테스트, 웹 production build, HTTP 응답 102개/8개 schema 대조 통과 |
| Java / bootJar | 이번에는 코드 변경이 없어 Gradle UP-TO-DATE. 위 API Goal의 78개 성공 결과를 재사용했으며 Java 테스트가 새로 실행됐다고 집계하지 않는다 |
| 실제 `npm run api:smoke` | 프로젝트 전용 compose DB 55432와 명시적 dev 서버 8080에서 통과. 후보 8개, 전체 재생성 1회, 선택 7개 저장, 공유 및 새 익명 replay의 동일 snapshot 확인 |

smoke는 로컬 DB에 개발용 생성 작업 2개와 경기/공유 기록을 만들었다. 실제 LLM/search는 호출하지 않았고 브라우저 timer/RNG·최종 디자인·실제 후보 품질은 검증하지 않았다. 공유 개발 서버 배포나 main 병합도 하지 않았다.

독립 리뷰 1회차: Critical 0 / High 0 / Medium 1. 연결된 Fork 안내가 다른 브랜치를 push하고 main을 PR base로 고정하는 불일치를 확인했다. 현재 브랜치 `HEAD` push와 PR #1 병합 전후 base 절차로 통일했다. 2회차 리뷰는 clean, Critical 0 / High 0 / 남은 actionable finding 0이다. 수정 후 전체 verify 재실행과 문서 로컬 링크 46개·diff whitespace 검사를 통과했다. Java 작업은 다시 UP-TO-DATE였다.

확인에 사용한 dev 서버와 이 프로젝트 전용 compose DB는 중지했고 데이터 volume은 유지했다. 다른 운영체제의 새 clone이나 실제 Fork UI 절차는 직접 실행하지 않았다. 이후 변경은 이 검증 기록뿐이다.
