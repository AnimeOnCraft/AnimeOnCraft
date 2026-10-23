CREATE INDEX clans_public_page ON clans(created_at,id) WHERE enabled = true AND archived_at IS NULL;
CREATE INDEX news_public_page ON news(created_at,id) WHERE published_at IS NOT NULL AND archived_at IS NULL;
