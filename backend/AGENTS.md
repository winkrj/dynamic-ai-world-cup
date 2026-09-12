# B — 후보 품질과 서버

루트 AGENTS와 TECH_DESIGN, API_CONTRACT를 따른다. 책임은 Candidate Engine/grounding/eval/history, API/DB, snapshot/session/share 데이터, 운영이다. frontend 경험 내부를 변경하지 않는다.

Java 21 records와 순수 Java domain부터 시작한다. LLM/검색 adapter는 generation/assessment/grounding 책임을 구분하고 generated metadata를 독립 검증 결과로 간주하지 않는다. 첫 CE-001은 판정 집행이며 사실 검증기 구현 완료가 아니다.

HTTP DTO는 contracts와 맞춰 테스트하고 내부 plan/evidence를 그대로 반환하지 않는다. DB transaction과 unique constraint로 idempotency/regen/freeze를 보장한다. snapshot은 master candidate 참조가 아니라 display copy로 저장한다.

`./gradlew test bootJar`와 관련 API/DB 통합 테스트를 실행한다. 실제 provider 검증 전 모델 성능/latency/비용 성공 수치를 만들지 않는다. 유료 provider key는 환경에서만 주입하고 fixture/로그/프론트에 넣지 않는다.
