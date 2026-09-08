INSERT INTO protected_link (short_code, original_url, title, password_hash, expires_at, recipient_names, max_views, view_count, created_at)
VALUES
('abc123', 'https://docs.google.com/presentation/d/example', '2026 브랜드 리뉴얼 최종 제안서', '$2a$10$dummyhashfordemopurposes', DATEADD('DAY', 3, CURRENT_TIMESTAMP), '김지수,박현우,이서윤', 10, 3, DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
('xyz789', 'https://figma.com/file/example-design', 'Q3 앱 리디자인 시안', NULL, DATEADD('DAY', 7, CURRENT_TIMESTAMP), '디자인팀', 0, 5, DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
('demo01', 'https://notion.so/internal-wiki/launch-plan', '신규 서비스 런칭 계획서', '$2a$10$dummyhashfordemopurposes', DATEADD('DAY', 14, CURRENT_TIMESTAMP), '경영지원팀,개발팀', 5, 1, DATEADD('DAY', -1, CURRENT_TIMESTAMP));

INSERT INTO link_view (link_id, viewer_name, viewed_at)
VALUES
(1, '김지수', DATEADD('HOUR', -5, CURRENT_TIMESTAMP)),
(1, '박현우', DATEADD('HOUR', -2, CURRENT_TIMESTAMP)),
(1, '이서윤', DATEADD('MINUTE', -30, CURRENT_TIMESTAMP)),
(2, '최민지', DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
(2, '정다은', DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(2, '한승우', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(2, '윤서현', DATEADD('HOUR', -12, CURRENT_TIMESTAMP)),
(2, '김태호', DATEADD('HOUR', -1, CURRENT_TIMESTAMP)),
(3, '이준혁', DATEADD('HOUR', -3, CURRENT_TIMESTAMP));
