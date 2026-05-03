-- 부하 테스트 후 좋아요 데이터 초기화
-- 대상: 시드 유저(test001~test1000)가 생성한 likes 행 및 feedbacks.like_count

DELETE FROM likes
WHERE member_id IN (
  SELECT member_id FROM members WHERE email LIKE 'test%@signal-buddy.com'
);

UPDATE feedbacks
SET like_count = 0
WHERE member_id IN (
  SELECT member_id FROM members WHERE email LIKE 'test%@signal-buddy.com'
);
