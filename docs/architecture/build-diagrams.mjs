// Editable diagram source. Run with Node; exported SVGs embed all icon assets.
import { readFileSync, writeFileSync } from 'node:fs';
const root = new URL('./', import.meta.url);
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
const icon = (name, x, y, size = 44) => {
  const type = name.endsWith('.svg') ? 'image/svg+xml' : 'image/png';
  const data = readFileSync(new URL(`icons/${name}`, root)).toString('base64');
  return `<image x="${x}" y="${y}" width="${size}" height="${size}" href="data:${type};base64,${data}"/>`;
};
const text = (x, y, value, size = 18, weight = 400, color = '#655C54') =>
  `<text x="${x}" y="${y}" font-size="${size}" font-weight="${weight}" fill="${color}">${escape(value)}</text>`;
const card = (x, y, width, title, lines, image, tint = '#ffffff') =>
  `<rect x="${x}" y="${y}" width="${width}" height="174" rx="22" fill="${tint}" stroke="#D8D1CA" stroke-width="2"/>`
  + (image ? icon(image, x + 24, y + 22, 38) : `<rect x="${x + 24}" y="${y + 25}" width="32" height="26" rx="5" fill="none" stroke="#655C54" stroke-width="2"/>`)
  + text(x + 24, y + 96, title, 24, 650)
  + lines.map((line, i) => text(x + 24, y + 128 + i * 25, line)).join('');
const arrow = (path, color = '#1689D8') => `<path d="${path}" fill="none" stroke="${color}" stroke-width="3" marker-end="url(#${color.slice(1)})"/>`;
const open = (name, desc, subtitle) => `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="860" viewBox="0 0 1600 860" role="img" aria-labelledby="title desc">
<title id="title">${escape(name)}</title><desc id="desc">${escape(desc)}</desc>
<defs>${['#1689D8','#47783F','#D97706'].map(c => `<marker id="${c.slice(1)}" markerWidth="9" markerHeight="9" refX="8" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8" fill="${c}"/></marker>`).join('')}</defs>
<rect width="1600" height="860" fill="#FAF8F5"/>
<g font-family="Apple SD Gothic Neo,Noto Sans CJK KR,Arial,sans-serif">
${text(56, 74, name, 36, 700)}${text(56, 116, subtitle, 21)}
`;
const close = '</g></svg>\n';

const runtime = open('Dynamic AI World Cup · 운영 아키텍처',
  '현재 운영 구조. 브라우저가 CloudFront HTTPS와 private VPC origin을 거쳐 단일 EC2의 nginx, Spring Boot 웹/API, PostgreSQL에 연결된다. 앱만 OpenAI API를 호출하며 DB는 암호화 EBS에 보존하고 S3에 매일 백업한다.',
  'CURRENT · 단일 EC2 / 통합 웹·API / DB 우선 후보 생성')
  + `<rect x="532" y="184" width="674" height="510" rx="28" fill="#F1F5ED" stroke="#A9BE9F" stroke-width="2"/>`
  + icon('ec2.png', 560, 203, 38) + text(612, 230, 'AWS EC2 · 1대 · t3.small', 22, 650)
  + card(56, 286, 176, 'Browser', ['React · 모바일', '같은 origin API'])
  + card(290, 286, 208, 'CloudFront', ['HTTPS :443', 'VPC origin'], 'cloudfront.png')
  + card(566, 286, 210, 'nginx', ['origin 검증', '호스트 :80'], 'nginx.svg')
  + card(850, 286, 306, 'Spring Boot', ['정적 웹 + API :8080', 'DB 후보 → AI 0~1회'], 'springboot.svg')
  + card(850, 496, 306, 'PostgreSQL 17', ['1대 · 내부망 :5432', '암호화 EBS · 대진/장부'], 'postgresql.svg')
  + card(1280, 286, 264, 'OpenAI API', ['서버에서만 호출', '누적 비용 예약·차단'])
  + card(1280, 496, 264, 'S3 Backup', ['비공개 · 암호화', '버전 보존 · 매일 03시'], 's3.png')
  + arrow('M232 368 H282') + arrow('M498 368 H558')
  + arrow('M776 368 H842') + arrow('M1156 368 H1272')
  + arrow('M1003 460 V488', '#47783F')
  + arrow('M1156 582 H1272', '#47783F')
  + text(1185, 353, 'HTTPS', 15) + text(1173, 561, 'pg_dump', 15)
  + text(56, 736, '공개 접점은 CloudFront. 앱·DB 포트는 인터넷에 노출하지 않습니다.', 23, 600)
  + text(56, 778, '단일 호스트라 짧은 배포 중단·서버 장애 지점이 남습니다. RDS / ALB / NAT / 실시간 그룹방은 없습니다.', 19)
  + close;

const delivery = open('Dynamic AI World Cup · 배포 흐름',
  '구성한 배포 흐름. 검증한 정확한 commit을 amd64 이미지로 빌드해 private ECR digest로 고정한다. 로컬 AWS 로그인 또는 별도 승인한 GitHub OIDC를 통해 SSM으로 기존 서버에 배포한다. 잠금, 유입 차단, 진행 작업 확인, DB 백업 뒤 교체하고 외부 HTTPS를 점검한다. DB schema가 같을 때만 앱과 runtime을 되돌린다. GitHub 원격 활성화 여부는 배포 문서에서 구분한다.',
  'CONFIGURED FLOW · GitHub 원격 연결은 승인 대기 / 로컬 명령과 공통 코드')
  + card(56, 250, 250, '1. Source', ['정확한 remote SHA', '깨끗한 작업 트리'], 'github.svg')
  + card(364, 250, 250, '2. Verify', ['Node24 · Java21', '계약·테스트·build'])
  + card(672, 250, 250, '3. Build → ECR', ['linux/amd64', 'immutable digest'])
  + card(980, 250, 250, '4. SSM Rollout', ['기존 EC2 1대만', 'lock · backup · 교체'], 'ec2.png')
  + card(1288, 250, 256, '5. HTTPS Check', ['웹·API·정적 자산', '생성·유료 호출 없음'])
  + arrow('M306 336 H356', '#D97706') + arrow('M614 336 H664', '#D97706')
  + arrow('M922 336 H972', '#D97706') + arrow('M1230 336 H1280', '#D97706')
  + `<rect x="56" y="492" width="698" height="194" rx="22" fill="#FFF2E1" stroke="#D97706" stroke-width="2"/>`
  + text(84, 531, '인증 경계', 25, 650)
  + text(84, 572, '로컬 AWS 로그인 또는 GitHub의 단기 OIDC 역할', 22)
  + text(84, 611, 'GitHub 실행은 main + production 환경만 허용', 20)
  + text(84, 651, '운영 OpenAI / DB 키는 서버에 유지 · CI로 복사하지 않음', 19)
  + `<rect x="802" y="492" width="742" height="194" rx="22" fill="#EEF4E9" stroke="#A9BE9F" stroke-width="2"/>`
  + text(830, 531, '배포 보호와 복구', 25, 650)
  + text(830, 572, '새 접수 일시 차단 → 진행 작업 확인 → DB 백업 → 교체', 21)
  + text(830, 611, '실패 시 schema가 같은 경우만 이전 앱/runtime 복귀', 20)
  + text(830, 651, 'DB 자동 복원·장부 초기화 없음 · 변경 schema는 사람 확인', 19)
  + text(56, 758, 'GitHub 수동 버튼과 로컬 명령은 같은 배포 코드를 사용합니다. 상시 자동배포·무중단 배포는 아닙니다.', 21, 600)
  + close;

for (const [name, svg] of [['runtime.svg', runtime], ['delivery.svg', delivery]]) {
  if (/<image[^>]+href="https?:/.test(svg)) throw new Error('Diagram must embed its assets.');
  writeFileSync(new URL(name, root), svg);
  console.log(`Generated ${name}`);
}
