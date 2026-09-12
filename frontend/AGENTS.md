# A — 플레이 경험

루트 AGENTS와 DESIGN_SPEC, docs/COLLABORATION을 따른다. 책임은 입력부터 미리보기/대결/Champion/공유 진입까지 전체 경험이다. backend/evals 구현을 변경하지 않는다.

`src/api/schema.d.ts`는 공용 계약에서 생성한다. fixture adapter와 실제 client를 같은 interface에 둔다. fixture 모드/미구현 상태는 개발 화면에서 명확히 구분한다.

timer와 게임 로직은 React rendering에서 분리해 fake clock / 주입 RNG로 테스트한다. 준비 이후 deadline, 더블탭/timeout 경합, 숨김/복귀/새로고침, 8/16/32 완주를 검증한다. 실제 제품에 debug engine 용어를 보여주지 않는다.

`npm run build:web`와 관련 테스트를 실행한다. UI 변경은 360px와 desktop에서 직접 확인하고 reduced motion/keyboard/fallback 영향을 확인한다. 계약 변경이 필요하면 B가 변경할 수 있도록 정확한 필드/상태 차이를 제안한다.
