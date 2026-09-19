# AWS 배포와 운영 검증

## 2026-09-19 공개 배포 완료 — TD-52/53

서비스: [Dynamic AI World Cup](https://dcti7vhb3wkpw.cloudfront.net). [실제 16강 공유 예시](https://dcti7vhb3wkpw.cloudfront.net/shares/6kx1tojTMZEfpyu51s_n3wpTxWn-94RdFw9M4w0DWLM)는 새 AI 호출 없이 같은 후보·대진으로 재생한다. 아래 준비 시점의 미완료 표시는 역사 기록이며 현재 실행 결과는 이 절과 [검증 기록](VERIFICATION.md)을 따른다.

- 실제 AWS x86 호스트의 PostgreSQL·Spring·nginx 시작 및 HTTPS 웹/자산/health/ready/없는 경로 404 smoke PASS. 앱 재시작 후에도 같은 공개 snapshot hash를 확인했다.
- 기본 16강을 실제 공개 화면에서 1회 생성했다. 78.396초, provider 4회·Repair 1회·검색 0, 비용 장부 **$0.146190/$5**다. 생성→전체 미리보기→freeze→15경기→Champion 저장→공유·새 익명 세션을 확인했다. 후속 유료 재생성은 실행하지 않았다.
- 운영 한도는 **전체 누적 $5**, 익명 브라우저당 하루 2회, 자동 충전 OFF다. 처음에는 예산 0으로 시작해 백업/복원을 확인한 뒤 5를 적용했다. 재시작·복원 검증에서 운영 비용 장부를 초기화하지 않았다.
- 키는 지정 Parameter Store SecureString과 호스트 root/0600 설정으로 분리했다. 정상 공유 재생의 Secure/HttpOnly/SameSite=Lax cookie, 다른 Origin 거절, origin token 없는 직접 요청 403, 앱/DB 미공개 포트를 확인했다.
- 암호화·버전 관리 S3 백업을 **실제로 다시 다운로드**해 별도 DB로 복원했다. 완주 데이터의 snapshot hash, 15경기, 공유 1개, 세션 2개, provider 4건/$0.146190, Flyway 1~3과 불변 trigger가 보존됐다. 운영 DB를 덮지 않았다.
- 매일 한국 03시 백업 timer active, 실제 service 실행 성공. RPO 24시간은 목표이며 실패 알림·자동 failover는 아직 없다. 초기/완주 복원 검증 DB와 private dump는 보존했다.
- 최종 앱 소스·이미지와 인프라 후속 수정은 아래 digest/revision 기록을 따른다. 필요한 추가 독립 리뷰는 명시 승인 뒤 완료했고 Critical/High/actionable 0, AWS 정적 12개 PASS였다. 이번 후속 실행에서는 앱 코드를 바꾸지 않았다.

남은 한계: 단일 서버 장애 지점, 크레딧/Free plan 종료 시 중단 가능성, 로컬 백업 용량 수동 관리, 일상 운영용 최소 권한 배포자 분리가 있다. 최신 실제 32강의 중복 FAIL과 일반 입력 성공률 미측정은 그대로다. 이번 16강의 서버 품질 gate 통과를 모든 후보의 사람 평가 완료로 일반화하지 않는다. 브라우저 검증 중 지연된 timeout 처리도 관측했으므로 백그라운드/기기 정지 상태의 정확한 벽시계 7초 화면 전환을 보장한다고 주장하지 않는다.

## 배포 준비 당시 기록 — TD-52

사용자가 실제 AWS 배포와 **운영 AI 누적 $5**를 별도로 승인했다. 월 예산/자동 갱신이 아니며 기존 실험 $6 장부와 분리한다. 자동 충전 OFF를 확인했고 서버의 진행 중·미확인 예약까지 합친 한도를 유지한다. AWS 계정의 Free plan ACTIVE와 t3.small의 무료 적용 적격성을 API로 확인했다. 무료 적용은 자원 소비가 없다는 뜻이 아니며 크레딧과 기간 종료 시 서비스가 중단될 수 있다. 계정 식별자·정확한 잔액은 공개 파일에 넣지 않는다.

배포 구성은 새 x86 t3.small 한 대에 nginx·Spring·PostgreSQL 17, 별도 암호화 EBS의 DB, private S3의 논리 백업이다. CloudFront VPC origin과 AWS 제공 도메인으로 HTTPS를 제공하며 ALB·NAT Gateway·RDS·도메인 구매는 추가하지 않는다. 기존 타 프로젝트 자원은 변경하지 않는다. EC2 public IPv4는 outbound 패키지·제공자 연결용이며 inbound는 CloudFront 원본 경로의 80만 허용하고 SSH/앱/DB 포트는 공개하지 않는다. SSM으로 관리하며 애플리케이션에는 호스트 IAM 자격을 전달하지 않는다.

앱은 `prod,live,proxy`를 사용한다. `proxy`는 정확한 nginx peer 주소 하나만 신뢰하고 기본/dev의 forwarding 무시는 유지한다. CloudFront가 X-Forwarded-For의 오른쪽에 실제 viewer IP를 추가하므로 nginx가 이를 보존하며 앱은 오른쪽의 첫 비신뢰 주소를 사용한다. nginx는 외부 Host/Forwarded를 신뢰하지 않고 고정 PUBLIC_HOST/HTTPS로 전달한다. 직접 앱 접근 차단과 별도 origin 검증값도 함께 적용해야 한다. 이 구성을 다른 프록시에 그대로 복사하지 않는다.

현재는 구현·검증·자원 설정 중이며 아직 공개 완료 기록이 아니다. 최종 URL·소스 revision·image digest·실제 호스트 smoke·첫 백업/격리 복원 결과는 실행 후 추가한다. 아래 2026-09-18 미배포 설명은 당시 기록이다.

### 실제 설정 진도 — 서비스 시작 전

후속 확인: HTTPS edge stack `CREATE_COMPLETE`, CloudFront `Deployed`. 준비 주소는 `https://dcti7vhb3wkpw.cloudfront.net`이며 실제 TLSv1.3 인증서 검증이 통과했다. HTTP→HTTPS, private VPC origin, 캐시 비활성화·전체 viewer 전달(Host 제외), origin 검증 헤더 존재를 실제 설정에서 대조했다. 이는 origin 앱 응답 검증이 아니다. 운영 metadata·신규 DB·origin 설정 3개를 root/0600으로 설치했고 예산은 준비 상태 `0`, app.env 없음·서비스 컨테이너 미시작을 확인했다. 기존 API 키 이전과 후속 수정 리뷰 승인은 계속 대기 중이다.

- 전용 foundation 생성 완료. 새 호스트 EC2 status checks/SSM Online, 별도 EBS의 ext4 mount, Docker의 data mount 의존성과 checksum 고정 Compose 설치를 확인했다. 기존 타 프로젝트 자원은 변경하지 않았다.
- 운영 앱 소스는 `a917a482534ae8c32461c463ac1971df4060e2a3`. 실행 이미지 `release-a917a48-app`의 digest는 `sha256:a7cf6219da1d034298db3ecf8bea4a873a6f11519520dd13c5ce7300edf88382`이며 실제 x86 호스트 pull·Java 21 실행을 확인했다. Spring 시작/READY 검증은 아직 아니다.
- 최초 `release-a917a48` 태그는 attestation manifest가 먼저 점유해 앱 manifest가 불변 태그 오류로 실패했다. 그 태그를 운영에 사용하지 않는다. 새 실행 태그는 부가 attestation 없이 패키징했으며 소스 label과 digest를 보존했다. 불변 정책은 완화하지 않았다.
- 앱 앞 nginx는 `nginx:stable-alpine@sha256:89956b8306b7db851b00cc78b32292cc32a848dcc0c48e65ebe20526c9a36019`, DB는 `postgres:17-alpine@sha256:aa90e97ee862e558111d34cfb8b2c4bec768c2b039fb791341686928560263b3`로 고정해 호스트에 pull했다.
- 새 호스트 SG의 ingress는 AWS 생성 CloudFront VPC-origin service SG의 TCP 80 하나로 좁혔다. 변경 계획이 해당 SG Modify/replacement false뿐인 것을 확인했고 실제 규칙도 대조했다. 공개 IPv4/IPv6 ingress와 앱/DB/SSH 포트는 허용하지 않았다.
- VPC-origin의 계정 미지원 선택 속성을 제거한 후속 `13e55a1`은 AWS template validation/정적 12개 PASS다. 기존 2차 리뷰 뒤 발생한 수정이므로 추가 독립 리뷰 1회 승인을 요청했다. 첫 실패 edge 스택은 자원 없음 확인 후 정리했고 두 번째 HTTPS/VPC-origin 생성은 진행 중이다.
- API 키를 AWS로 전달하는 명시 승인이 아직 없어 기존 키는 전송하지 않았다. app.env·서비스 시작·실제 백업/복원·공개 smoke·운영 AI 호출은 보류 중이며, 생성한 인프라는 대기 중에도 크레딧을 소비할 수 있다. 공개 서비스 완료나 운영 AI 사용액 발생으로 보고하지 않는다.

2026-09-18 기준, 저장소에는 웹을 포함한 실행 JAR와 컨테이너 빌드 경로가 있다. **외부 서비스는 아직 배포하지 않았고 공개 운영 주소도 없다.** 패키지 빌드·로컬 점검 통과와 실제 공개 배포, 실제 후보 품질 승인은 각각 다른 결과다. 최신 검증 결과는 [검증 기록](VERIFICATION.md)과 함께 확인한다.

로컬에서는 같은 소스의 **linux/amd64** 이미지로 prod 시작·readiness·웹 전달·패키지 운영자 CLI와 별도 dev 합성 API 흐름을 확인했다. 격리 DB의 논리 백업·별도 DB 복원도 통과했다. 로컬 ARM 이미지에서는 Java 파일 0바이트로 시작하지 못하는 계층 문제가 남았으므로, 단순한 `docker build` 종료 0을 실행 성공으로 해석하지 않는다. 실제 배포 호스트의 아키텍처에 맞는 이미지와 digest를 선택해 다시 시작 검증한다. 운영 백업/장애 복구나 AWS 설정 완료는 아니다.

사용자는 서비스 완성과 AWS 설정 진행을 요청했으며, 최신 비용 기준(TD-45)은 **AWS Free plan 안에서 실제 결제 $0를 우선**하는 것이다. 구체 자원·사양·도메인·프록시와 운영 AI 예산은 아직 확정하지 않았다. 이 문서는 새 자원을 생성하거나 유료 플랜으로 전환한 기록이 아니다. 기존 실험 예산을 공개 서비스의 반복 AI 호출 예산으로 해석하지 않는다.

## 무료 혜택과 실제 결제의 구분

AWS 일반 가격표의 월 요금은 크레딧 차감 전 자원 소비액이며 실제 카드 결제와 다르다. 앞서 제안한 월 $25는 확정 견적이나 승인된 지출 예산이 아니다. 계정에서 Free plan과 무료 적용을 확인한 뒤 그 범위 안의 자원만 검토한다. 계정 잔액·식별자 같은 비공개 정보는 공개 저장소에 옮기지 않는다.

Free plan은 유지 중 실제 결제가 발생하지 않지만 크레딧을 소비하며, 6개월 기간이나 크레딧 소진 중 먼저 도달한 시점에 종료된다. 유료 전환 없이 종료되면 계정이 닫히고 자원·데이터 접근을 잃으므로, 종료 전 백업·이전·종료 여부를 결정해야 한다. AWS Organizations 가입 등 일부 계정 변경은 유료 전환을 유발하므로 수행하지 않는다. [AWS 공식 plan 구분](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html), [무료 기간·종료 정책](https://aws.amazon.com/free/free-tier-faqs/) (확인: 2026-09-18).

기존 서비스와 크레딧을 공유하므로 방문자가 적다는 이유만으로 새 서버가 장기간 무료라고 보장하지 않는다. 생성 전 자원별 무료 적용, 켜 둔 시간·저장소·공개 IP·백업의 크레딧 소비와 잔여 기간을 대조한다. 메모리 사용량을 측정하지 않고 최소 사양을 확정하지 않는다. AWS 무료 혜택은 별도 provider의 AI API 비용을 포함하지 않으며, 최신 유료 평가 1회 승인은 이미 사용 완료됐다.

## 실행 구성

하나의 애플리케이션 인스턴스가 같은 origin에서 웹과 `/api/v1`을 제공하고 PostgreSQL 17에 연결한다. 웹 빌드는 Node 24, 서버 빌드·실행은 Java 21이다. 실행 컨테이너에는 JRE와 읽기 전용 통합 JAR를 넣고 UID/GID `10001:10001`로 실행한다. DB는 앱 컨테이너의 임시 파일시스템과 분리한다.

- `/`와 `/shares/{token}`은 같은 웹 문서를 제공한다. 그 밖의 없는 API·정적 파일에 웹 문서 200을 돌려주는 포괄적인 SPA fallback은 설정하지 않는다.
- 외부에는 HTTPS origin 하나만 공개한다. `PUBLIC_ORIGIN`은 경로·끝 슬래시·query 없는 실제 origin이다. 예: `https://worldcup.example.com`.
- 초기 replica 수는 **1**이다. HTTP와 작업 worker가 같은 프로세스에 있으므로 request가 없을 때 프로세스를 멈추거나 CPU를 중단하는 설정은 사용하지 않는다. 여러 replica·worker 자동 확장은 별도 검증 대상이다.
- 앱 포트 우선순위는 `SERVER_PORT` → 플랫폼의 `PORT` → 기본 `8080`이다. 컨테이너 이미지는 `SERVER_PORT=8080`을 기본 제공하므로 다른 포트를 요구하는 플랫폼에서는 `SERVER_PORT`도 명시적으로 맞춘다.
- 현재 DB pool 상한은 8이다. 플랫폼의 메모리·CPU·DB connection 예산은 실제 측정 후 정하며, 검증하지 않은 최소 사양이나 처리량을 보장하지 않는다.

## JAR / 이미지 만들기

저장소 루트에서 실행한다. 다음 빌드는 실제 AI 키가 필요하지 않으며 제공자를 호출하지 않는다.

```sh
npm ci
./scripts/verify.sh
npm run package:app
docker build -t dynamic-ai-world-cup:release .
```

통합 JAR는 `backend/build/libs/*-app.jar`다. `bootJar`만 만든 파일에는 웹 빌드가 포함되지 않는다. Dockerfile은 웹 빌드 후 `appJar`를 실행하므로 사전에 만든 로컬 `dist`/`build` 파일에 의존하지 않는다. 이미지 빌드는 테스트를 대신하지 않으므로 release 전에 `verify.sh` 결과를 확인한다.

`.dockerignore`는 빌드 입력을 허용 목록으로 제한한다. `.env*`, 키 파일, `reports/`, `evals/`, Git 이력, 로컬 dependency/cache/build 출력은 빌드 context에 넣지 않는다. 런타임 비밀값을 Dockerfile의 `ARG`/`ENV`나 웹의 `VITE_*` 변수에 넣지 않는다. 사용한 기반 이미지 태그는 보안 업데이트에 따라 바뀔 수 있으므로 공개 release에는 최종 이미지 digest와 소스 revision을 기록하고 그 digest로 재배포·rollback한다.

JAR를 직접 실행하는 환경에서도 아래 서버 환경변수를 비밀 저장소로 주입하고 Java 21로 실행한다. Spring은 `.env` 파일을 자동으로 읽지 않는다.

```sh
java -jar backend/build/libs/worldcup-0.0.1-SNAPSHOT-app.jar
```

파일명은 `backend/settings.gradle`과 실제 빌드 출력을 확인한다. 서비스 제공자 로그인·registry push·DNS 변경·리소스 생성은 이 패키지 빌드에 포함하지 않는다.

컨테이너 호스트에서는 읽기 전용 root filesystem, 쓰기 가능한 `/tmp`, Linux capability 제거와 `no-new-privileges`를 적용할 수 있다. 비밀 저장소가 아래 환경변수를 이미 주입한 환경의 실행 예시는 다음과 같다. DB 주소는 앱 컨테이너에서 접근 가능한 주소여야 하며, `localhost`는 DB가 아니라 앱 컨테이너 자신을 뜻한다.

```sh
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop=ALL --security-opt=no-new-privileges \
  -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE -e PUBLIC_ORIGIN \
  -e DATABASE_URL -e DATABASE_USERNAME -e DATABASE_PASSWORD \
  -e OPENAI_API_KEY -e CANDIDATE_BUDGET_USD \
  -e CANDIDATE_MODEL -e CANDIDATE_REVIEW_MODEL \
  dynamic-ai-world-cup:release
```

이 예시는 HTTP를 호스트 loopback에만 열므로 외부 HTTPS 연결은 선택한 edge/proxy에서 구성한다. 관리형 플랫폼은 같은 제약을 해당 runtime 설정에 반영하고 `SERVER_PORT`와 연결 포트를 맞춘다. 비밀값을 명령 문자열에 직접 적지 않는다.

## 서버 환경과 비용

| 변수 | 운영 값 / 의미 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod,live`. `dev`를 혼합하지 않는다. 합성 엔진을 실제 서비스로 노출하지 않는다. |
| `PUBLIC_ORIGIN` | 최종 HTTPS origin. 앱이 공유 링크와 mutation origin 검사에 사용한다. |
| `SERVER_PORT` | 플랫폼이 연결할 포트. JAR에서는 미설정 시 `PORT`, 그다음 `8080`. 이미지는 `8080` 기본값을 제공하므로 플랫폼 포트가 다르면 덮어쓴다. |
| `DATABASE_URL` | PostgreSQL 17 JDBC URL. 관리형 DB의 TLS 요구사항을 반영한다. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | 운영 전용 DB 사용자와 비밀값. 로컬 compose의 기본 계정·비밀번호를 사용하지 않는다. |
| `OPENAI_API_KEY` | 서버 비밀 저장소로만 주입. 이미지·Git·로그·브라우저에 노출하지 않는다. |
| `CANDIDATE_MODEL`, `CANDIDATE_REVIEW_MODEL` | 현재 개발 기본값 `gpt-5.6-terra`를 유지한다. 모델 변경은 별도 품질·비용 검증 대상이다. |
| `CANDIDATE_BUDGET_USD` | 명시적으로 승인한 **이 DB의 누적** AI 허용액. 기본 `0`으로 유료 호출 차단. 월 자동 초기화나 제공자 확정 청구 hard cap이 아니다. |
| `GENERATION_DAILY_LIMIT` | 초기 정책 `2`. 익명 브라우저의 서울 날짜 기준 생성·재생성 접수 합계이며 접수 후 실패도 포함한다. |

실제 운영에서 `live`를 활성화하기 전에 별도 운영 예산과 현재 계정 사용량을 대조한다. `provider_call` 장부의 `accounted_usd`에는 진행 중 예약과 확인되지 않은 비용도 들어 있다. DB를 새로 만들거나 복원해 장부가 줄어들었다고 계정 지출이 사라지는 것은 아니다. 장부 삭제·미확인 예약 해제·월별 자동 초기화로 한도를 우회하지 않는다. 인프라 요금과 AI 요금은 별도로 관리하며 자동 충전 상태도 기존 OFF를 임의 변경하지 않는다.

하루 2회 제한은 쿠키를 이용한 익명 브라우저 제한이다. 정확한 사람당 제한을 보장하지 않으며 기존 actor/IP 단기 제한과 DB 전체 비용 예약을 함께 유지한다. 서버가 시작되거나 health가 UP이라는 사실은 실제 후보 품질·예산·결제가 승인됐다는 뜻이 아니다.

## HTTPS 프록시와 IP 제한 — 공개 전 해결 사항

현재 `server.forward-headers-strategy=none`이고 IP 제한은 `request.getRemoteAddr()`를 사용한다. 일반적인 reverse proxy 뒤에서는 모든 요청이 proxy 주소로 보일 수 있어, 서로 다른 사용자가 IP별 10분 5회 제한을 공유하게 된다. **사업자와 proxy 경로를 선택한 뒤 이 주소 전달 구조를 해결하고 검증해야 공개할 수 있다.**

클라이언트가 보낸 `X-Forwarded-For`/`Forwarded`를 무조건 신뢰하거나 전역 옵션만 켜서 해결하지 않는다. 실제 접속 가능한 proxy 대역/단계, edge에서 기존 forwarding header를 제거하고 다시 쓰는 동작, 앱 직접 접근 차단을 확인한다. 그 구조에 맞는 제한된 trusted-proxy 설정 또는 실제 peer IP를 보존하는 경로를 선택한다. 앱이 서로 다른 client IP를 구분하고 공격자가 임의 header로 IP 제한을 우회하지 못하는지 검증한다. 전달 주소 수정은 선택한 인프라에 맞춰 별도 코드/설정 검토를 거친다.

HTTPS 종료는 edge에서 할 수 있지만 앱은 Secure 쿠키와 명시적 `PUBLIC_ORIGIN`을 유지한다. share URL을 Host/forwarding header에서 만들지 않는다. 프록시에서는 `/api/v1`, `/assets`, `/shares`를 같은 앱으로 전달하고 HTML 오류 페이지가 API 오류를 덮어쓰지 않도록 한다.

## DB 보존, 백업과 rollback

### 후보 재사용 승인

V3는 공개 snapshot과 별도로 재사용 certificate·승인·사용 감사 표를 추가한다. 최초 도입 시 과거 draft/FAILED 결과를 이관하거나 승인하지 않는다. 정상 생성의 모든 검증을 통과하고 개인 선택 이력이 없는 결과만 PENDING으로 저장하며, PENDING 자체는 다른 요청에 제공되지 않는다. 실제 승인 데이터는 아직 없다.

재사용은 정책 버전·전체 요청·8/16/32·locale·timezone이 일치하는 전체 세트에 한정한다. 의미가 비슷한 문장 검색이나 자동 학습은 하지 않는다. 조회 때 원본 근거를 재검증하고 공개 결과는 snapshot에 복사한다. 승인 취소는 이후 재사용을 막으며 이미 공유한 immutable bracket을 수정하지 않는다. 승인 유효기간은 최대 7일, 원본 근거는 최대 30일이다. PENDING/REJECTED/REVOKED payload는 마지막 변경 후 7일, 만료된 APPROVED payload는 정리하며 승인/사용 감사 기록은 90일 보존한다.

운영자는 공용 API가 아닌 아래 로컬/보호된 관리 콘솔을 사용한다. 먼저 대상 DB의 주소·권한을 확인한다. 이 프로그램은 웹·worker·retention·유료 예산을 끈 `reuse-operator` profile로 실행하지만, Spring 시작 시 Flyway migration은 실행되므로 DB 변경 권한과 migration 확인은 필요하다. 비밀값은 기존 비밀 환경으로 주입하고 명령에 직접 적지 않는다.

```sh
java -Dloader.main=dev.worldcup.generation.reuse.CandidateReuseOperator \
  -cp backend/build/libs/worldcup-0.0.1-SNAPSHOT-app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher pending

# 위 실행 명령의 마지막 인자를 다음 중 하나로 교체한다.
# inspect <set-id>
# approve <set-id> <operator-id> <expiry-UTC> <quality-review> <public-safety-review> --time-independent
# reject <set-id> <operator-id> <reason>
# revoke <set-id> <operator-id> <reason>
```

`inspect`는 내부 검증 자료다. 요청 원문/선택 이력/constraint sourceText는 저장하지 않지만 생성된 내용에 민감한 맥락이 남을 수 있으므로 출력과 DB를 공개하지 않는다. 검토자는 원래 요청의 명시 조건과 대조하고 실제 후보 품질, 공용 사용의 안전성, 시간 독립성을 각각 확인해야 한다. 원래 조건을 확인할 수 없거나 판단이 UNKNOWN이면 승인하지 않는다. hash는 익명화가 아니다. 승인 메모에도 개인정보를 복사하지 않는다. 현재 프로세스는 이 검토를 자동 승인으로 대체하지 않으며, 근거 삭제·판정 PASS 조작·만료 연장으로 재사용량을 늘리지 않는다.

PostgreSQL은 앱 재시작·새 이미지 배포와 무관하게 유지되는 저장소를 사용한다. 공개 snapshot/share, 비용 장부 `provider_call`, Flyway 이력을 포함하는 일관된 DB 백업과 별도 저장 위치를 마련하고 복원 연습까지 확인한다. 백업 주기·보관 기간·복구 목표는 사업자/비용 결정과 함께 확정한다. 현재 `compose.yaml`은 loopback 개발 DB용이며 운영 배포 manifest가 아니다.

Flyway는 시작 때 migration을 적용한다. 배포 전 backup과 새 migration을 검토하고 DB clean이나 snapshot 불변성 trigger 해제를 하지 않는다. 앱을 이전 digest로 되돌릴 때 해당 버전의 schema 호환성을 먼저 확인한다. DB 자체 복원이 필요하면 복원 시점 이후의 share/경기 기록 손실과 장부 차이를 계산하고, 유료 호출을 차단한 상태에서 제공자 사용 내역을 대조한다. 누락된 비용 예약을 0으로 취급하지 않는다.

초기 앱은 단일 인스턴스이므로 교체 중 짧은 중단이 생길 수 있다. 진행 중 job의 lease와 복구 정책을 유지하고, 배포 속도를 위해 timeout·Repair·비용 예약을 변경하지 않는다. 기존 이미지와 DB 백업을 남기고 새 release 확인 뒤 이전 이미지를 정리한다.

## 읽기 전용 배포 점검

Node 24 환경에서 운영 HTTPS origin 또는 로컬 HTTP loopback origin을 사용한다.

```sh
node --test scripts/smoke-release.test.mjs
node scripts/smoke-release.mjs https://worldcup.example.com
# 로컬 통합 JAR 확인
node scripts/smoke-release.mjs http://127.0.0.1:8080
```

점검은 GET으로 `/`, 존재하지 않는 token의 `/shares/{token}`, HTML에 직접 참조된 JS/CSS, `/api/v1/health`, `/api/v1/ready`, 존재하지 않는 API·asset의 404를 확인한다. HTML에서 JavaScript를 실행하거나 공유 데이터·새 session을 요청하지 않는다. 인증정보·쿠키를 보내거나 응답 쿠키를 재사용하지 않고 redirect도 따라가지 않는다. HTML에 원격 URL이나 API URL이 asset으로 들어 있으면 요청하기 전에 실패한다.

`/api/v1/health`는 현재 프로세스의 liveness 응답이다. `/api/v1/ready`는 DB `SELECT 1`, 번들된 웹 문서, worker 활성화와 엔진 profile 구성을 확인해 200 `READY` 또는 503 `NOT_READY`를 반환한다. 플랫폼의 readiness probe는 `/api/v1/ready`를 사용한다. 실제 worker 진행, 제공자 연결·잔액·후보 품질까지 검사하지 않으며 유료 호출도 없다. 이 점검은 실제 브라우저 선택/복원·Secure 쿠키의 mutation 동작·실제 모델 후보 품질·비용 통제 운영을 대체하지 않는다. `npm run api:smoke`는 로컬 합성 dev 전용으로 DB에 생성·경기 기록을 만드는 별도 점검이므로 운영에서 대신 실행하지 않는다.

최종 공개 완료 기록에는 소스 revision/이미지 digest, 실제 HTTPS 주소, DB backup/복원 결과, proxy/IP 제한 검증, 읽기 전용 smoke 결과, 별도 승인된 실제 후보 평가 결과와 남은 한계를 구분해 남긴다. 이 값들이 정해지거나 실행되기 전에는 배포 완료로 표기하지 않는다.
