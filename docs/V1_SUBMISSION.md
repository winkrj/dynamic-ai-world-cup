# 1차 제출본 — Dynamic AI World Cup

기준일 2026-09-20. 목표는 아침에 팀원이 직접 실행·시연할 수 있는 서비스다. 이 문서에서 구현, 관측, 운영 적용을 구분한다.

## 제출 기준

| 항목 | 이번 버전의 선택 | 한계 |
| --- | --- | --- |
| 모델 | 기존 Terra, DB 후보 선택 + 부족분 한 번 보충 | 별도 모델 학습/파인튜닝 아님 |
| 속도 | 정확 preset은 AI 0회, 그 외 최대 1회. DB ≤2초/일반 16강 약10초 목표, AI 호출 제한30초 | 목표는 SLA/p95가 아님. 네트워크·대기열에 따라 지연 가능 |
| 품질 | 조건 우선·서버 구조/중복 검사·전체 미리보기. 평범함/호불호는 허용 | 의미 조건/유사성은 best-effort. 모든 입력의 완벽한 후보 보장 안 함 |
| 비용 | 일반 생성 건당 $0.05 이하 관측 목표. 운영 누적$5, 실험 누적$6, 자동충전 OFF. 심사 중 일일 횟수 해제, actor/IP5회/10분 유지 | 목표와 비용 예약은 최종 청구 hard cap이 아님. 쿠키 제한은 정확한 사람당 식별이 아님 |
| 화면 | A 상하 카드, 진입·승패·진출·우승·로딩, 마지막3초·라운드 첫500ms 안내·상시 강수 경로 | B 좌우 카드/실시간 그룹방은 미포함. 사진은 별도 설계만 |
| 게임 | 8/16/32, 16기본, 준비 후7초, timeout 균등랜덤, Undo없음, 동일 snapshot 공유 | 공유는 동일 대진의 새 세션이며 공동 투표가 아님 |

현재 장소·가격·영업 확인이 필요한 요청은 지원 범위가 아니다. 모델이 이를 인식하면 후보를 꾸며내지 않고 입력 수정 안내로 종료한다. 그 판단도 모델 기반이므로 실제 영업정보를 검증하는 검색 서비스로 소개하지 않는다. 생성 결과를 공개 공용 DB로 자동 학습하지 않는다.

## 전달할 링크와 실행 방법

- 서비스: [World Cup](https://dcti7vhb3wkpw.cloudfront.net)
- 코드: [작업 브랜치](https://github.com/winkrj/dynamic-ai-world-cup/tree/feat/engine/context-feasibility). main과 배포 소스가 같다고 가정하지 않는다.
- 설계/실측: [DB 우선 엔진](CATALOG_ENGINE.md), [화면 통합 Spec](INTEGRATION_SPEC.md), [공개 API](../contracts/openapi.json).

온라인 데모는 공개 서비스에서 `취미 추천해줘` → 16강 → 전체 후보 확인 → 시작 → 우승 → 공유 순서로 진행한다. 이 문구의 첫 생성은 개인 선택 이력이 없는 경우 DB preset이며, 조건을 추가하면 AI 1회 선택 경로로 간다. 공유 플레이는 AI 비용이나 생성 횟수를 추가로 쓰지 않는다. **심사 중 일일 횟수 제한은 해제했으며 단기 actor/IP5회/10분과 누적 예산은 유지한다.** 이전429 대기화면이 저장된 브라우저는 새로고침만으로 초기화되지 않을 수 있으므로 즉시 시연은 새 시크릿 창을 사용한다. 생성 없이 바로 시연하려면 [새 버전 16강 공유 예시](https://dcti7vhb3wkpw.cloudfront.net/shares/EvDA3r6utnyXgg4YJWpk-9CKTXhmqaIM_BDV8tIoOo4)를 사용한다. 로컬18089는 개발 합성 서버이며 제출용 운영 주소가 아니다.

로컬 개발에는 Node24·Java21·Docker가 필요하다. `git clone --branch feat/engine/context-feasibility https://github.com/winkrj/dynamic-ai-world-cup.git`로 최신 작업 브랜치를 받는다. 저장소 루트에서 `npm ci`, `docker compose up -d --wait postgres`, 백엔드 폴더에서 `./gradlew bootRun --args='--spring.profiles.active=dev'`, 별도 터미널 루트에서 `npm run dev:web`을 실행한다. dev 후보에는 개발용 표시가 붙고 실제 AI를 호출하지 않는다. 로컬 실행의 자세한 기준은 [프론트 연결 안내](FRONTEND_HANDOFF.md)를 따른다. 운영 키/환경 파일은 Git에 없으며 팀원에게 채팅으로 보내지 않는다.

## 완료 기록

### 최신 TD-57 — 심사 모드·배포 자동화

2026-09-20 `a08d5a3`를 공통 로컬 배포 명령으로 운영 적용했다. 같은익명actor의 DB16강3회 READY/추가AI0, 실행quota0·예산5·HTTPS/공개파일일치·기존snapshot/장부 보존을 확인했다. 현재 운영15건/$0.74506260이며 실험장부는 변화없다. S3백업71,555bytes/AES256/version 확인. 전체검증과 최종 독립리뷰2/2 Critical/High/actionable0을 통과했다.

현재 아키텍처와 한 명령 배포·실패 복구 기준은 [배포 파이프라인](DEPLOYMENT_PIPELINE.md)에 있다. 로컬 경로는 실제 검증 완료이며 GitHub OIDC/main 통합·버튼 활성화는 별도 권한 승인 대기다. 하루 제한 해제를 무제한 AI비용/모든 단기 제한 해제로 설명하지 않는다. 이전 탭의429 대기 취소 UX, 새 백업 격리 복원, 단일 호스트 한계는 남아 있다.

### 최신 TD-56 — 라운드 구분·긴박감 운영 반영

2026-09-20 소스 `521fbfe341264f0042f372ac06439be6c3b8a78c`를 기존 AWS에 배포했다. 이미지 `release-521fbfe-app`, linux/amd64 digest `sha256:efb46fcd3c31078bca86b318f96bb17dfa3298b38904c10223f4982c75b6619f`. GitHub 작업 브랜치에 반영됐으며 이후 기록 전용 커밋은 런타임을 바꾸지 않는다.

원본 HTML A와 재대조한 내부 압박/VS·승패, 마지막3초, 16→8→4→결승 안내를 추가했다. 실제7초·feedback280ms·이미지2초fallback·기존active deadline은 유지한다. 전체 verify(변경 없는 Java test는 up-to-date), 합성8강28경기/16·32강184경기, 독립 리뷰 Critical/High/actionable0. 공개 파일이 검증본과 바이트 일치하고 HTTPS smoke와 실제 공유16강 직접7/timeout8·Champion저장·console오류0을 확인했다.

앱만 교체했으며 DB/비밀설정/엔진/예산/인프라는 그대로다. 전후snapshot 전체hash·운영provider14건/$0.73137410·예약0가 동일하고 이번 추가AI0. 백업69,205bytes/AES256/version·timer active 확인, 새 백업 격리 복원은 미실행이다. 실제 모바일 Safari/스크린리더는 미검증이다. 상세는 [검증 기록](VERIFICATION.md), 사진은 수집·구현하지 않은 [별도 설계 제안](CANDIDATE_VISUALS.md)을 따른다.

### 이전 TD-55 — catalog 1차 제출

**2026-09-20 1차 제출본 개발·검증·AWS 배포 완료. 공개 서비스는 catalog 엔진과 새 애니메이션을 사용한다.**

릴리스 소스: `fcb6a5f8bea70fc90dd324bbceb30f22d3dc756d`, GitHub `feat/engine/context-feasibility`에 push 확인. 후속 문서 전용 커밋은 런타임 소스에 영향을 주지 않는다.

배포 이미지: `release-fcb6a5f-app`, `linux/amd64`, digest `sha256:6bcf312d9520d3fb0000f73215afa2b3a768b6c30b82966307ff42f252408809`. 로컬 검증 이미지와 ECR 업로드/실제 호스트 실행 digest·소스 label이 같다. 운영 `prod,live,proxy`, `catalog`, 호출 제한30초, 누적예산5를 확인했다. 기존 app.env·nginx·PostgreSQL 이미지·인프라는 바꾸지 않았다.

- 전체 `scripts/verify.sh` PASS: Java454중452실행/유료2제외, 프론트61, handoff6, release13, runtime20/AWS12, fixture5, 실제 HTTP166개/9schemas, TypeScript/Vite/bootJar/appJar. 마지막 실행의 변경 없는 Java 결과는 Gradle up-to-date로 재사용했다.
- 독립 read-only 리뷰1차: Critical0 / High0 / actionable0. 운영의 실제 이미지/전략/DB적용은 별도 확인 대상으로 남겼다.
- 최종 통합 JAR + 전용 로컬 DB: 브라우저360px에서8/16/32강7/15/31경기, 전체 재생성, deadline 새로고침복원, 숨김/복귀 timeout, 중복탭잠금, 완료저장, 동일 공유/새세션 PASS. 생성POST는 의도한4건, 공유재생성0, 브라우저오류0. 개발 합성 후보이며 실제AI 품질 증거는 아니다.
- 별도 합성 브라우저: 360/1280px × 일반/줄인 움직임, 양쪽 승패연출·결승·실제7초·280ms피드백·고정클릭영역·키보드·이미지2초fallback·긴이름·로딩·Champion·32강안내 PASS. 실제카드간격에 VS를 배치해 focus에서 가리는 문제를 수정했다.
- 통합JAR 읽기전용 smoke: 웹/공유진입/정적자산2개/health/ready/없는API·asset404 PASS.
- 실제 모델 관측/비용: [32강 후속 관측](CATALOG_ENGINE.md#제출-전-32강-관측--td-55). 최종v3 32강은1호출2.827초/$0.0167525지만 유사후보는 남음. 실험누적$5.4206158/$6과 운영 장부는 분리한다.

### 실제 운영 확인

| 확인 | 결과 |
| --- | --- |
| 기본 취미16강 | 접수→preview 0.767초, AI0 / 후속 DB 전용 확인0.613초 |
| 혼자·비운동·월10만원 취미16강 | 접수→preview5.357초, provider4.455초, COMPOSE1회/검색0/Repair0, $0.012404 |
| 저장/공유 | AI 후보15경기 API 저장 완료. DB 후보16강은 freeze→15경기→공유→새 익명 세션의 동일 snapshot 대조 PASS |
| 실제 브라우저 | 새 공유16강의 timeout15경기→Champion 저장→다시 공유 PASS, console warn/error0. 직접 클릭15회 증거로 세지 않음 |
| 데이터/백업 | 적용 전 DB dump 암호화·버전관리 S3 업로드 확인, V4·seed64, 기존 snapshot hash/공유·비용장부 보존, 한국03시 backup timer active |
| 비용 | 운영 누적$0.697184/$5, 미결 예약0, 잔여$4.302816(확인 시점). 실험 추가 비용0, 예산/자동충전 설정 변경0 |

초기 임시 HTTP 점검기가 본문이 없어야 하는 공유 요청에 `{}`를 보내400을 받았다. 제품은 계약대로 거절했고 기존 프론트는 빈 본문을 올바르게 보낸다. 그 실패를 지우지 않으며, 유료 AI 생성을 다시 하지 않고 DB preset으로 도구의 본문 처리만 고쳐 공유를 확인했다. 운영 앱 코드는 수정하지 않았다. 세 번의 생성 접수 중 실제 AI는 한 번뿐이며, 이후 공유/브라우저 플레이에서도 provider 총11건은 늘지 않았다. 5.357초는 한 사례의 실측으로 전체 입력의 평균/p95나 품질 보장이 아니다. 이번 새 V4 백업의 격리 복원은 재실행하지 않았으며, 이전V3의 실제 복원 증거와 구별한다.

## 출시 후 남길 과제

- 다양한 실제 입력에서 조건 적합성·중복·실패율/지연 분포를 더 수집한다. 소수 성공 사례로 정확도나 평균을 광고하지 않는다.
- 초기 공용 seed는64개다. 조건이 까다로우면 재사용0/전체 생성일 수 있다. 공개로 안전한 후보만 검토해 확장한다.
- 실제32강에서 그림/디지털 일러스트·십자수/자수 같은 의미 유사 후보가 남았다. 서버의 이름/family 동일성 검사가 모든 의미 중복을 잡는 것은 아니다. 16강을 기본 추천하며32강을 완벽히 검증된 추천으로 소개하지 않는다.
- 단일 호스트·수동 배포·백업 실패 자동 알림 부재는 초기 운영 한계다. AWS Free plan 종료 전에 계정 밖 백업/이전을 결정해야 한다.
- 전체 생성이 실패한 경우 원인을 성공으로 덮거나 비용 장부를 초기화하지 않는다. 운영 비용 긴급 차단은 기존 runtime 예산0, 엔진 정책 rollback은 staged다.

발표에서는 “LLM으로 완벽한 추천을 보장했다”보다 “조건 충족과 주관적 매력을 구별하고, 한 번의 선택/보충과 서버 불변성 검사를 결합해 비용·지연을 줄였다”고 설명한다. 실제 수치는 [검증 기록](VERIFICATION.md)의 관측 범위와 함께 전달한다.
