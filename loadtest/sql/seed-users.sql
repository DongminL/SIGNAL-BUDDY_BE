-- Signal Buddy load-test seed data
-- Creates: 10 crossroads, 4000 members, 4000 feedbacks
--
-- Password for all test members: "password"
-- BCrypt hash (cost 10): $2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi
--
-- If login fails, regenerate the hash with:
--   new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password")
-- and replace the value in the INSERT below.
--
-- Run against the local MariaDB instance:
--   mysql -u cklol -p signal-buddy < loadtest/sql/seed-users.sql

SET SESSION max_recursive_iterations = 4000;

-- ─────────────────────────────────────────────────────────────
-- 0. Clean previous seed data (idempotent re-run)
-- ─────────────────────────────────────────────────────────────
DELETE FROM feedbacks
  WHERE member_id IN (SELECT member_id FROM members WHERE email LIKE 'test%@signal-buddy.com');

DELETE FROM members WHERE email LIKE 'test%@signal-buddy.com';

DELETE FROM crossroads WHERE crossroad_api_id LIKE 'SEED-%';

-- ─────────────────────────────────────────────────────────────
-- 1. Crossroads (10 rows, central Seoul area)
--    coordinate = POINT(longitude latitude) with SRID 4326
-- ─────────────────────────────────────────────────────────────
INSERT INTO crossroads (crossroad_api_id, name, coordinate, status) VALUES
  ('SEED-001', '강남역 교차로',      ST_GeomFromText('POINT(127.027620 37.497942)', 4326), TRUE),
  ('SEED-002', '역삼역 교차로',      ST_GeomFromText('POINT(127.036595 37.500622)', 4326), TRUE),
  ('SEED-003', '선릉역 교차로',      ST_GeomFromText('POINT(127.049147 37.504503)', 4326), TRUE),
  ('SEED-004', '삼성역 교차로',      ST_GeomFromText('POINT(127.062913 37.508629)', 4326), TRUE),
  ('SEED-005', '종각역 교차로',      ST_GeomFromText('POINT(126.982943 37.570083)', 4326), TRUE),
  ('SEED-006', '광화문 교차로',      ST_GeomFromText('POINT(126.976913 37.575757)', 4326), TRUE),
  ('SEED-007', '홍대입구역 교차로',  ST_GeomFromText('POINT(126.923937 37.557414)', 4326), TRUE),
  ('SEED-008', '신촌역 교차로',      ST_GeomFromText('POINT(126.936562 37.555254)', 4326), TRUE),
  ('SEED-009', '이태원역 교차로',    ST_GeomFromText('POINT(126.994226 37.534658)', 4326), TRUE),
  ('SEED-010', '한남동 교차로',      ST_GeomFromText('POINT(127.002312 37.537631)', 4326), TRUE);

-- ─────────────────────────────────────────────────────────────
-- 2. Members (4000 rows via recursive CTE)
-- ─────────────────────────────────────────────────────────────
INSERT INTO members (email, password, nickname, notify_enabled, role, member_status)
WITH RECURSIVE gen (n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM gen WHERE n < 4000
)
SELECT
  CONCAT('test', LPAD(n, 4, '0'), '@signal-buddy.com'),
  '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi',
  CONCAT('TestUser', n),
  TRUE,
  'USER',
  'ACTIVITY'
FROM gen;

-- ─────────────────────────────────────────────────────────────
-- 3. Feedbacks (4000 rows, each assigned to a member + crossroad)
-- ─────────────────────────────────────────────────────────────
INSERT INTO feedbacks (subject, category, content, image_url, like_count, answer_status, secret, member_id, crossroad_id)
WITH RECURSIVE gen (n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM gen WHERE n < 4000
),
seed_members AS (
  SELECT member_id, ROW_NUMBER() OVER (ORDER BY member_id) AS rn
  FROM members WHERE email LIKE 'test%@signal-buddy.com'
),
seed_crossroads AS (
  SELECT crossroad_id, ROW_NUMBER() OVER (ORDER BY crossroad_id) AS rn
  FROM crossroads WHERE crossroad_api_id LIKE 'SEED-%'
)
SELECT
  CONCAT('테스트 피드백 #', n),
  ELT(MOD(n - 1, 4) + 1, 'ETC', 'DELAY', 'MALFUNCTION', 'ADD_SIGNAL'),
  CONCAT('부하 테스트용 피드백입니다. row=', n),
  NULL,
  0,
  'BEFORE',
  FALSE,
  (SELECT member_id FROM seed_members WHERE rn = MOD(n - 1, 4000) + 1),
  (SELECT crossroad_id FROM seed_crossroads WHERE rn = MOD(n - 1, 10) + 1)
FROM gen;
