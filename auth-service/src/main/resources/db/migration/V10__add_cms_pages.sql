-- V10: Add CMS pages table
-- CMS Mock: Pages with protection levels

CREATE TABLE device_login.cms_pages (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    key              VARCHAR(8)   NOT NULL UNIQUE,
    protection_level VARCHAR(20)  NOT NULL DEFAULT 'public',
    content_path     VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP    DEFAULT NOW()
);

-- Seed data: 3 standard pages
INSERT INTO device_login.cms_pages (name, key, protection_level, content_path) VALUES
    ('public',  'pub001', 'public', '/cms-content/index.html'),
    ('premium', 'prm001', 'acr1',   '/cms-content/premium.html'),
    ('admin',   'adm001', 'acr2',   '/cms-content/admin.html');
