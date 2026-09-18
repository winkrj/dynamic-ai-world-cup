# 검증 기록

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
