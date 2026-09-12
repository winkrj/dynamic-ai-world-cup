# 검증 기록

날짜: 2026-09-12. 범위: 저장소 기반 + CE-001.

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
