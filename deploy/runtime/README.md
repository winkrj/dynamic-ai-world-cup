# 단일 호스트 운영 runtime

Amazon Linux 2023 / `linux/amd64` / 초기 2 GiB 호스트용이다. CloudFront HTTPS → VPC origin → nginx → 통합 웹·API → PostgreSQL 17 순서다. CloudFormation·계정 무료 적용·이미지 생성·운영 키 주입은 이 폴더 밖의 배포 담당자가 처리한다. 이 파일의 존재나 정적 테스트 통과는 실제 배포 성공을 뜻하지 않는다.

## 연결 계약

| 위치 | 배포 담당자가 준비할 내용 |
| --- | --- |
| `/opt/worldcup/runtime` | 이 폴더의 배포 파일. root 소유, 다른 사용자 쓰기 금지. `init-worldcup.sh` 실행 비트 보존 |
| `/etc/worldcup` | root 소유·0700. 아래 네 파일은 root 소유·0600, symlink 금지 |
| `runtime.env` | 필수: `APP_IMAGE`, `NGINX_IMAGE`, `POSTGRES_IMAGE`, `PUBLIC_HOST`, `BACKUP_BUCKET`, `AWS_REGION`, `CANDIDATE_BUDGET_USD`. 선택: `CANDIDATE_ENGINE_STRATEGY`, `CANDIDATE_FAST_TIMEOUT_SECONDS`, `GENERATION_DAILY_LIMIT` |
| `app.env` | `DATABASE_PASSWORD`, `OPENAI_API_KEY`만 |
| `postgres.env` | `POSTGRES_PASSWORD`(관리자), `WORLDCUP_PASSWORD`(앱 암호와 동일)만 |
| `proxy.env` | CloudFront origin의 `X-Origin-Verify`와 동일한 `ORIGIN_VERIFY_TOKEN`만 |
| `/var/lib/worldcup` | 별도 암호화·retained EBS를 **먼저 mount**. root disk에 조용히 DB를 만들지 않도록 시작 전 mount 여부 검사 |
| `/var/lib/worldcup/postgres` | PostgreSQL 데이터. UID/GID 70:70,0700. 시작 스크립트가 최상위 디렉터리만 준비 |
| `/var/lib/worldcup/backups` | 완료/미완료 DB 백업. root:root,0700. 백업 파일 0600 |

환경 파일은 `KEY=literal-value` 한 줄씩이며 shell 스크립트가 아니다. 따옴표·변수 확장·`export`를 넣지 않는다. DB 관리자/앱 암호와 origin token은 **각각 독립적인 32-byte 난수의 64자리 소문자 hex**로 주입한다. OpenAI 키는 별도로 발급된 서버용 키다. 예제에는 실제 값이 없으며 그대로 실행하면 실패한다. 검증기는 중복·누락·미승인 key, mode/owner, mutable 이미지, placeholder, 잘못된 호스트, 예산 증가를 차단한다.

Docker Engine, Compose **2.30 이상**(raw env file 지원), Bash, AWS CLI, util-linux(`mountpoint`,`flock`)가 필요하다. Compose plugin은 `/usr/local/lib/docker/cli-plugins` 등 시스템 경로에 설치한다. 임시 Docker 설정을 사용하는 시작 과정에서 사용자 `.docker/cli-plugins`에만 둔 plugin은 찾지 못할 수 있다. 호스트 instance role만으로 이미지 pull/정해진 bucket의 `backups/` prefix 업로드에 접근한다. AWS access key를 파일에 추가하지 않는다. 호스트 스크립트는 root로 실행한다. `run.sh check`만 컨테이너/네트워크/유료 호출 없이 현재 소유자의 설정을 검사한다.

이미지는 `APP_IMAGE=<12자리계정>.dkr.ecr.ap-northeast-2.amazonaws.com/<repo>@sha256:<digest>`, `NGINX_IMAGE=nginx:stable-alpine@sha256:<digest>`, `POSTGRES_IMAGE=postgres:17-alpine@sha256:<digest>`다. 앱 이미지는 서울 private ECR만 허용한다. 후자의 두 태그 표기는 UID/major 계약을 드러내며 실제 고정 기준은 검증한 digest다. PostgreSQL 18이나 다른 배포판 digest로 바꾸지 않는다. 최종 배포 기록에 세 이미지 digest·소스 revision을 별도 남긴다.

## 시작과 비용 경계

```sh
bash /opt/worldcup/runtime/run.sh check
bash /opt/worldcup/runtime/run.sh start
bash /opt/worldcup/runtime/run.sh status
```

`start`는 매번 호스트 역할로 ECR 인증 갱신 → 고정 digest pull → PostgreSQL TCP 준비 → 앱 `/api/v1/ready` 200 → nginx 순서로 기다린다. AWS CLI 토큰은 `docker login --password-stdin`에 파이프로만 전달한다. 0700 임시 `DOCKER_CONFIG`와 0600 token 설정을 사용하고 성공/실패 종료 시 해당 파일을 삭제한다. 원래 사용자 Docker 인증 설정은 덮어쓰거나 지우지 않는다. 이전 로그인 만료 후 재배포도 새 인증으로 시작한다. 실패하면 배포를 성공으로 표시하지 않고 상태와 비밀 없는 오류를 확인한다. 자동 DB 삭제·major 업그레이드·복원·무한 재시도는 없다. `stop`은 이 프로젝트의 컨테이너만 정지하며 volume을 삭제하지 않는다.

앱은 `prod,live,proxy`, 같은 기존 Terra 모델, **DB 전체 운영 누적 $5**다. 일/월 자동 재설정이 아니며 API 잔액 소진만을 안전장치로 삼지 않는다. 복구/비용 대조 동안은 `CANDIDATE_BUDGET_USD=0`으로 시작한다. 런타임 validator는 예산에 `0` 또는 승인한 `5`만 허용한다. 예산을 바꾸면 컨테이너를 다시 생성해야 하며 파일 변경만으로 실행 중 프로세스가 바뀌지 않는다. 초기 메모리 상한은 앱768MiB(`-Xmx384m`), DB384MiB, nginx64MiB다. 이는 검증할 시작 설정이며 처리량/무장애를 보장하지 않는다.

TD-57 심사 모드: `GENERATION_DAILY_LIMIT=0`은 일일 횟수만 해제하며 `2`는 일반 모드다. 운영 validator는0/2만 허용하고 누락 시2를 유지한다. 호출 shell의 동명 환경을 상속하지 않는다. cap0에서도 actor/IP5회/10분·기록·전체 재생성1회·누적 AI예산은 그대로다. 음수/매우 큰 수로 ‘무제한’을 흉내 내지 않는다. 호환 앱 이미지와 이 runtime을 함께 배포한 뒤 적용하고, 종료 시2로 재배포한다. 기존 일일 기록/비용 장부를 지우지 않으므로 같은 날 다시2로 바꾸면 이전 접수가 계속 집계된다.

후보 엔진은 `runtime.env`에 `CANDIDATE_ENGINE_STRATEGY=catalog`, `CANDIDATE_FAST_TIMEOUT_SECONDS=30`을 명시해 DB 우선·최대 1회 AI 경로로 전환한다. 해당 코드와 V4 migration을 포함한 이미지가 선행해야 한다. 전략은 `catalog` 또는 `staged`만, 운영 fast timeout은 `30`만 허용한다. 기존 설정 파일에 두 키가 없으면 `staged`/`30`을 유지하며 호출한 shell의 동명 변수는 덮어쓴다. `app.env`에 추가하지 않는다. 30초는 fast engine 실행 상한이지 대기열·네트워크를 포함한 응답 SLA가 아니다.

배포 시 새 image digest와 두 설정을 외부 `runtime.env`에 적용한 뒤 `check` → `start` → HTTPS readiness/생성/공유 점검을 수행한다. `start`가 변경 환경으로 앱을 재생성한다. 긴급 비용 중단은 예산 `0`, 엔진만 되돌릴 때는 **같은 새 이미지에서** 전략 `staged`로 설정 후 다시 `check` → `start`한다. staged는 기존 다단계 호출로 돌아가므로 더 느리고 비용이 높을 수 있다. V4/기존 snapshot/비용 장부를 지우지 않으며 DB rollback이나 예산 초기화는 하지 않는다. 이전 이미지로 되돌리는 작업은 Flyway 호환성을 따로 검증해야 하므로 엔진 설정 rollback과 구분한다.

앱 UID10001, nginx UID101, DB UID70으로 실행한다. 모두 read-only root, capability 제거, no-new-privileges와 제한된 tmpfs를 쓴다. DB만 EBS에 쓴다. Docker 로그는 서비스당 10 MiB ×3으로 제한하고 nginx access log는 끈다. `docker inspect`, `docker compose config`, `nginx -T`는 비밀을 보여줄 수 있으므로 채팅·SSM 출력·CI 로그에 출력하지 않는다. 스크립트의 Compose 확인은 `config --quiet`뿐이다. 설정 파일은 어떤 백업/릴리스 archive에도 넣지 않는다.

## 프록시 경계

외부 publish는 nginx의 호스트 `80` → 컨테이너 `8080` 하나다. app/DB는 포트를 publish하지 않는다. DB network는 internal이고 nginx가 참가하지 않는다. edge network에서 nginx는 `172.30.52.2`, app은 `.3`; DB network `172.30.53.0/24`에서 DB는 `.2`, app은 `.3`다.

호스트 80의 인바운드는 배포 담당자가 **해당 CloudFront VPC origin의 service security group**으로 제한한다. VPC origin의 SG 제한과 origin token은 함께 필요하다. 다른 기존 EC2/서비스의 security group을 바꾸거나 EC2 80을 인터넷 전체에 열지 않는다. 앱은 `172.30.52.2` 한 peer만 신뢰한다.

CloudFront가 X-Forwarded-For 오른쪽에 viewer IP를 붙인 값을 nginx가 **그대로** 넘긴다. nginx 자신/CloudFront peer를 다시 append하거나 임의 `real_ip` 설정을 추가하면 안 된다. Tomcat은 nginx만 신뢰하므로 마지막 비신뢰 viewer IP를 선택한다. nginx는 `Forwarded`, `X-Forwarded-Host`, `X-Forwarded-Port`, `X-Real-IP`, origin token을 upstream에서 제거하고 `Host`를 배포 CF hostname, `X-Forwarded-Proto`를 https로 고정한다. 토큰 누락/불일치 또는 XFF 누락은 403이다. Origin 검사는 기존 앱이 수행하고 Secure cookie를 유지한다.

## 백업과 수동 복원

```sh
bash /opt/worldcup/runtime/backup.sh
```

worldcup DB만 관리자 역할로 `pg_dump -Fc`한다. 암호는 CLI 인자에 넣지 않으며 DB 컨테이너 안의 로컬 socket으로 접속한다. 비공개 임시 파일 → `pg_restore --list`로 archive 확인 → 원자적 rename으로 완료 표시 → 암호화된 S3 `backups/` 업로드 순서다. 실패한 미완료 파일이나 업로드 실패한 완료 파일은 남긴다. 성공 메시지는 업로드 완료 뒤에만 출력한다. 복원 가능한 데이터/장부까지 보장하려면 별도 격리 DB 복원 연습이 필요하며 archive list 검사는 이를 대체하지 않는다.

배포 담당자는 `/var/lib/worldcup/backups`를 먼저 root:root/0700으로 준비한 뒤 제공된 systemd service/timer를 설치·활성화할 수 있다. 예정 시각은 매일 18:00 UTC(한국 03:00), 목표 RPO 24시간이다. `Persistent=true`는 호스트가 꺼진 동안 빠진 실행을 부팅 뒤 보완한다. 실제 마지막 성공/실패와 timer 상태를 확인해야 하며 `OnFailure` 알림은 아직 없다. bucket versioning·암호화·public 차단·S3 보존 정책은 CloudFormation 소유다. **로컬 백업 자동 삭제는 하지 않는다.** 디스크 사용량을 확인하고 S3 검증 후 보관/정리 여부를 운영자가 결정한다. 무료 계정 접근 종료 전에 계정 밖의 안전한 백업과 이전/종료 계획이 필요하다.

복원은 자동 스크립트를 제공하지 않는다.

1. 외부 새 생성을 중단하고 운영 예산 0을 적용한다. 현재 DB와 비용 장부를 먼저 별도 보존한다. S3 object version·암호화·archive list를 확인한다.
2. 원본 데이터 디렉터리에 덮어쓰지 않고 격리된 새 PostgreSQL 17 DB에 복원한다. 앱 역할은 초기화 계약대로 미리 만들며 운영자 role/password를 dump에 넣지 않는다.
3. Flyway 버전, immutable snapshot/share, 경기 저장, `provider_call`과 미확인 예약을 검증한다. 백업 시점 이후 손실 범위를 기록한다. 앱은 계속 예산 0이다.
4. 제공자 사용 내역 및 현 DB 보존 장부와 복원 장부를 대조한다. 백업 이후 사용액을 0으로 취급하거나 예약을 지워 $5 한도를 다시 얻지 않는다. 비용 상태를 재구성할 때까지 유료 생성을 재개하지 않는다.
5. 사람의 대상/손실 확인 후에만 복원 DB로 전환하고 HTTPS/프록시/readiness/공유 점검을 다시 한다. 같은 데이터와 migration 호환성 확인 없이 앱 digest만 rollback하지 않는다.

## 검증 근거와 한계

저장소 루트에서 `node --test scripts/check-deployment-runtime.test.mjs`는 비밀 없는 설정 fixture, 실제 `docker compose config --format json`, shell parse를 검사한다. catalog/staged 전달·구 설정 기본값·잘못된 설정 차단·비밀 파일 미변경도 확인한다. ECR 인증의 성공/실패·토큰 stdin 전달·임시 설정 제거는 가짜 CLI만 사용하는 회귀 테스트로 확인한다. Docker daemon/실제 AWS/paid provider는 호출하지 않는다. 실제 컨테이너 시작, 비root 파일 권한, DB 역할/Flyway, nginx token/XFF 전달, 공개 HTTPS, S3 dump 업로드/격리 복원, 실제 2 GiB 메모리 사용은 배포 통합 검증으로 따로 확인해야 한다.

공식 참고: [Compose 환경 파일](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/), [Compose 서비스 제약](https://docs.docker.com/reference/compose-file/services/), [nginx 전달 헤더](https://nginx.org/en/docs/http/ngx_http_proxy_module.html), [PostgreSQL 공식 이미지 초기화 동작](https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh). 템플릿 치환은 nginx 변수까지 치환하지 않도록 두 허용 변수만 지정한다.
