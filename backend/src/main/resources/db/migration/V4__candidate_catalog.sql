-- Public generic editorial seed, curated with AI for the new catalog policy.
-- Not human-rated, independently model-reviewed, or promoted from prior FAILED/PENDING generations.
-- Contains activity/menu ideas only: no venues, current availability, prices, or personal prompts.
-- Existing frozen snapshots and approved-set evidence remain untouched.
CREATE TABLE candidate_catalog_entry (
    id text PRIMARY KEY,
    topic text NOT NULL,
    unit text NOT NULL,
    family text NOT NULL,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    tags text[] NOT NULL CHECK (cardinality(tags) BETWEEN 1 AND 2),
    ordinal integer NOT NULL CHECK (ordinal > 0),
    active boolean NOT NULL DEFAULT true,
    provenance text NOT NULL,
    time_independent boolean NOT NULL DEFAULT false,
    human_rated boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (topic, family),
    UNIQUE (topic, name)
);
CREATE INDEX candidate_catalog_active_topic ON candidate_catalog_entry(topic, ordinal, id) WHERE active;

CREATE TABLE candidate_catalog_preset (
    id text PRIMARY KEY,
    topic text NOT NULL,
    unit text NOT NULL,
    locale text NOT NULL,
    timezone text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    provenance text NOT NULL
);
CREATE TABLE candidate_catalog_preset_alias (
    normalized_prompt text PRIMARY KEY,
    preset_id text NOT NULL REFERENCES candidate_catalog_preset(id)
);
CREATE TABLE candidate_catalog_preset_entry (
    preset_id text NOT NULL REFERENCES candidate_catalog_preset(id),
    candidate_id text NOT NULL REFERENCES candidate_catalog_entry(id),
    position integer NOT NULL CHECK (position > 0),
    PRIMARY KEY (preset_id, candidate_id),
    UNIQUE (preset_id, position)
);

INSERT INTO candidate_catalog_entry(id, topic, unit, family, name, tags, ordinal, provenance, time_independent)
SELECT 'hobby-' || family, 'hobby', '지속 가능한 취미', family, name, tags, ordinal,
       'AI_CURATED_EDITORIAL_V1', true
FROM (VALUES
    ('reading', '독서', ARRAY['이야기','몰입'], 1),
    ('drawing', '그림 그리기', ARRAY['표현','손작업'], 2),
    ('baking', '베이킹', ARRAY['만들기','먹는 즐거움'], 3),
    ('running', '달리기', ARRAY['운동','야외'], 4),
    ('language', '외국어 학습', ARRAY['배움','꾸준함'], 5),
    ('knitting', '뜨개질', ARRAY['손작업','실용'], 6),
    ('photography', '사진 촬영', ARRAY['관찰','기록'], 7),
    ('boardgames', '보드게임', ARRAY['전략','함께'], 8),
    ('composition', '디지털 작곡', ARRAY['음악','창작'], 9),
    ('indoor-gardening', '실내 식물 가꾸기', ARRAY['돌봄','성장'], 10),
    ('creative-writing', '창작 글쓰기', ARRAY['상상','표현'], 11),
    ('swimming', '수영', ARRAY['운동','물'], 12),
    ('magic', '마술 연습', ARRAY['손기술','공연'], 13),
    ('woodworking', '목공', ARRAY['제작','도구'], 14),
    ('coding', '코딩', ARRAY['문제 해결','만들기'], 15),
    ('dance', '댄스', ARRAY['리듬','운동'], 16),
    ('pottery', '도예', ARRAY['흙','손작업'], 17),
    ('hiking', '등산', ARRAY['자연','운동'], 18),
    ('video-editing', '영상 편집', ARRAY['이야기','디지털'], 19),
    ('model-building', '레고 조립', ARRAY['구조','몰입'], 20),
    ('coffee-brewing', '핸드드립 커피', ARRAY['향미','실험'], 21),
    ('cycling', '자전거 타기', ARRAY['이동','운동'], 22),
    ('leathercraft', '가죽 공예', ARRAY['손작업','실용'], 23),
    ('jigsaw', '직소 퍼즐', ARRAY['집중','완성'], 24),
    ('juggling', '저글링', ARRAY['손기술','도전'], 25),
    ('table-tennis', '탁구', ARRAY['운동','함께'], 26),
    ('origami', '종이접기', ARRAY['구조','손작업'], 27),
    ('perfumery', '향수 조향', ARRAY['향','창작'], 28),
    ('stamp-collecting', '우표 수집', ARRAY['수집','탐구'], 29),
    ('bowling', '볼링', ARRAY['운동','정확성'], 30),
    ('meditation', '명상', ARRAY['집중','고요함'], 31),
    ('climbing', '클라이밍', ARRAY['운동','문제 해결'], 32)
) AS seed(family, name, tags, ordinal);

INSERT INTO candidate_catalog_entry(id, topic, unit, family, name, tags, ordinal, provenance, time_independent)
SELECT 'date-' || family, 'date', '함께 할 데이트 활동', family, name, tags, ordinal,
       'AI_CURATED_EDITORIAL_V1', true
FROM (VALUES
    ('art-exhibition', '미술 전시 보기', ARRAY['감상','대화'], 1),
    ('cinema', '영화관에서 영화 보기', ARRAY['이야기','몰입'], 2),
    ('park-walk', '공원 산책', ARRAY['야외','대화'], 3),
    ('boardgames', '보드게임 카페 가기', ARRAY['놀이','전략'], 4),
    ('escape-room', '방탈출 게임', ARRAY['협동','추리'], 5),
    ('pottery-class', '도예 원데이 클래스', ARRAY['체험','만들기'], 6),
    ('bowling', '함께 볼링 치기', ARRAY['운동','승부'], 7),
    ('karaoke', '노래방 가기', ARRAY['음악','놀이'], 8),
    ('bookstore', '서점에서 서로 책 골라주기', ARRAY['취향','대화'], 9),
    ('home-cooking', '집에서 함께 요리하기', ARRAY['협동','식사'], 10),
    ('picnic', '도시락 피크닉', ARRAY['야외','휴식'], 11),
    ('live-performance', '라이브 공연 보기', ARRAY['공연','현장감'], 12),
    ('aquarium', '아쿠아리움 구경', ARRAY['관찰','실내'], 13),
    ('arcade', '오락실에서 게임하기', ARRAY['놀이','승부'], 14),
    ('cycling', '함께 자전거 타기', ARRAY['운동','야외'], 15),
    ('market-food', '시장에서 먹거리 둘러보기', ARRAY['먹거리','구경'], 16)
) AS seed(family, name, tags, ordinal);

INSERT INTO candidate_catalog_entry(id, topic, unit, family, name, tags, ordinal, provenance, time_independent)
SELECT 'dinner-' || family, 'dinner', '저녁 식사 메뉴', family, name, tags, ordinal,
       'AI_CURATED_EDITORIAL_V1', true
FROM (VALUES
    ('kimchi-stew', '김치찌개', ARRAY['한식','국물'], 1),
    ('bibimbap', '비빔밥', ARRAY['한식','비벼먹기'], 2),
    ('pork-cutlet', '돈가스', ARRAY['튀김','바삭함'], 3),
    ('pho', '쌀국수', ARRAY['면','국물'], 4),
    ('malatang', '마라탕', ARRAY['중식','얼얼함'], 5),
    ('salmon-rice', '연어덮밥', ARRAY['생선','덮밥'], 6),
    ('bulgogi', '불고기', ARRAY['한식','고기'], 7),
    ('fried-chicken', '치킨', ARRAY['닭고기','튀김'], 8),
    ('jajangmyeon', '짜장면', ARRAY['중식','면'], 9),
    ('pizza', '피자', ARRAY['치즈','오븐 요리'], 10),
    ('tomato-pasta', '토마토 파스타', ARRAY['양식','면'], 11),
    ('shabu-shabu', '샤부샤부', ARRAY['국물','채소'], 12),
    ('curry', '카레라이스', ARRAY['향신료','밥'], 13),
    ('tacos', '타코', ARRAY['멕시코식','한 손 음식'], 14),
    ('grilled-mackerel', '고등어구이', ARRAY['한식','생선'], 15),
    ('hamburger', '햄버거', ARRAY['고기','빵'], 16)
) AS seed(family, name, tags, ordinal);

INSERT INTO candidate_catalog_preset(id, topic, unit, locale, timezone, provenance) VALUES
    ('hobby-basic', 'hobby', '지속 가능한 취미', 'ko-KR', 'Asia/Seoul', 'AI_CURATED_EDITORIAL_V1'),
    ('date-basic', 'date', '함께 할 데이트 활동', 'ko-KR', 'Asia/Seoul', 'AI_CURATED_EDITORIAL_V1'),
    ('dinner-basic', 'dinner', '저녁 식사 메뉴', 'ko-KR', 'Asia/Seoul', 'AI_CURATED_EDITORIAL_V1');

-- Deliberately few aliases. Additional words or punctuation do not match these exact requests.
INSERT INTO candidate_catalog_preset_alias(normalized_prompt, preset_id) VALUES
    ('취미 추천해줘', 'hobby-basic'),
    ('데이트 활동 추천해줘', 'date-basic'),
    ('저녁 메뉴 추천해줘', 'dinner-basic');

INSERT INTO candidate_catalog_preset_entry(preset_id, candidate_id, position)
SELECT p.id, e.id, e.ordinal FROM candidate_catalog_preset p
JOIN candidate_catalog_entry e ON e.topic = p.topic AND e.unit = p.unit;
