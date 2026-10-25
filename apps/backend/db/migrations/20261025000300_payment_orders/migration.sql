-- Real-money crystal purchases. One row per checkout; crystals are credited once, by the ledger ref 'order:<id>'.
-- Amounts are stored in kopecks (rubles) or stars so no float ever touches money. No existing data is changed.
CREATE TABLE payment_orders (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 provider varchar(12) NOT NULL CHECK (provider IN ('YOOKASSA','TELEGRAM')),
 pack varchar(16) NOT NULL,
 crystals integer NOT NULL CHECK (crystals > 0),
 amount integer NOT NULL CHECK (amount > 0),
 currency varchar(3) NOT NULL CHECK (currency IN ('RUB','XTR')),
 status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUCCEEDED','CANCELED','REFUNDED')),
 provider_payment_id varchar(64) UNIQUE,
 confirmation_url varchar(512),
 created_at timestamptz(3) NOT NULL DEFAULT now(),
 paid_at timestamptz(3),
 refunded_at timestamptz(3)
);
CREATE INDEX payment_orders_user_created ON payment_orders(user_id,created_at DESC);
GRANT SELECT,INSERT,UPDATE ON payment_orders TO anime_app;
