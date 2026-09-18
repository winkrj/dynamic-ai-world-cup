# A — 플레이 경험

루트 AGENTS와 DESIGN_SPEC, docs/COLLABORATION을 따른다. 책임은 입력부터 미리보기/대결/Champion/공유 진입까지 전체 경험이다. backend/evals 구현을 변경하지 않는다.

`src/api/schema.d.ts`는 공용 계약에서 생성한다. fixture adapter와 실제 client를 같은 interface에 둔다. fixture 모드/미구현 상태는 개발 화면에서 명확히 구분한다.

timer와 게임 로직은 React rendering에서 분리해 fake clock / 주입 RNG로 테스트한다. 준비 이후 deadline, 더블탭/timeout 경합, 숨김/복귀/새로고침, 8/16/32 완주를 검증한다. 실제 제품에 debug engine 용어를 보여주지 않는다.

## 현재 구현 규약

- `app`은 워크플로·체크포인트·전송 대기열, `play`는 순수 결정/대진 상태, `api`는 wire 타입/HTTP, `ui`는 표시/카드 준비를 소유한다. 공용 view-model을 먼저 고정하고 각 담당은 자신의 경로만 수정한다.
- POST 전 operation key/body를 저장한다. 응답 유실·새로고침 재전송은 동일 key/body, 사용자가 명시적으로 새 작업을 시작할 때만 새 key다. effect/StrictMode가 생성 POST를 중복 시작하면 안 된다.
- 전송 중 selection batch/key는 불변이다. 새 event는 다음 batch에 넣으며 오래된 ACK가 nextSequence를 후퇴시키지 않는다. TIMEOUT도 전부 저장하고 COMPLETED/champion 확인 전 공유 금지.
- snapshot/session/events/current deadline을 로컬에 보관한다. 손상·저장 실패·다른 탭 충돌을 무시하고 처음 경기부터 자동 재시작하지 않는다. 숨김 중 다음 경기 준비/시작 금지.
- HTTP 실패와 job FAILED를 구별한다. 실제 Retry-After가 있을 때만 정확한 대기시간을 표시한다. 생성 취소 endpoint·상세 질문 payload는 아직 없으므로 없는 기능을 흉내 내지 않는다.
- 이미지 준비 또는 2초 fallback과 진입 완료 뒤에만 timer를 시작한다. 전체 카드 button, 새 화면/대진 focus, 360px 긴 이름/32개, reduced motion을 확인한다. 데스크톱에서도 A 상하 카드 기본이다.
- clipboard 실패가 공유 생성 실패는 아니다. 이미 받은 URL은 보존·표시하고 복사 재시도 때문에 POST를 반복하지 않는다.

`npm run build:web`와 관련 테스트를 실행한다. UI 변경은 360px와 desktop에서 직접 확인하고 reduced motion/keyboard/fallback 영향을 확인한다. 계약 변경이 필요하면 B가 변경할 수 있도록 정확한 필드/상태 차이를 제안한다.
