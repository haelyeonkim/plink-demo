-- Demo seed data (dev/test only). Production excludes this location via FLYWAY_LOCATIONS.
INSERT INTO protected_link (short_code, original_url, title, password_hash, expires_at, recipient_names, max_views, view_count, created_at)
VALUES
('abc123', 'https://docs.google.com/presentation/d/example', '2026 브랜드 리뉴얼 최종 제안서', '$2a$10$dummyhashfordemopurposes', CURRENT_TIMESTAMP + INTERVAL '3' DAY, '김지수,박현우,이서윤', 10, 3, CURRENT_TIMESTAMP - INTERVAL '2' DAY),
('xyz789', 'https://figma.com/file/example-design', 'Q3 앱 리디자인 시안', NULL, CURRENT_TIMESTAMP + INTERVAL '7' DAY, '디자인팀', 0, 5, CURRENT_TIMESTAMP - INTERVAL '5' DAY),
('demo01', 'https://notion.so/internal-wiki/launch-plan', '신규 서비스 런칭 계획서', '$2a$10$dummyhashfordemopurposes', CURRENT_TIMESTAMP + INTERVAL '14' DAY, '경영지원팀,개발팀', 5, 1, CURRENT_TIMESTAMP - INTERVAL '1' DAY);

INSERT INTO link_view (link_id, viewer_name, viewed_at)
VALUES
(1, '김지수', CURRENT_TIMESTAMP - INTERVAL '5' HOUR),
(1, '박현우', CURRENT_TIMESTAMP - INTERVAL '2' HOUR),
(1, '이서윤', CURRENT_TIMESTAMP - INTERVAL '30' MINUTE),
(2, '최민지', CURRENT_TIMESTAMP - INTERVAL '3' DAY),
(2, '정다은', CURRENT_TIMESTAMP - INTERVAL '2' DAY),
(2, '한승우', CURRENT_TIMESTAMP - INTERVAL '1' DAY),
(2, '윤서현', CURRENT_TIMESTAMP - INTERVAL '12' HOUR),
(2, '김태호', CURRENT_TIMESTAMP - INTERVAL '1' HOUR),
(3, '이준혁', CURRENT_TIMESTAMP - INTERVAL '3' HOUR);
