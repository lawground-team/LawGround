-- 2026-11-09 결정: 부동산공법(PUBLIC_LAW)을 앱 분석 대상에 포함한다.
ALTER TABLE questions DROP CONSTRAINT questions_subject_check;
ALTER TABLE questions ADD CONSTRAINT questions_subject_check CHECK (subject IN ('BROKER_LAW', 'PUBLIC_LAW'));
