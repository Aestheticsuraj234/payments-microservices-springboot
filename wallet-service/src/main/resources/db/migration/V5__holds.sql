-- Ported from reference/go/db/wallet/migrations/000005_holds.up.sql (without frozen wallets).

-- Money set aside for a transfer saga, until it's captured or released.
CREATE TYPE hold_status AS ENUM ('held', 'captured', 'released');

CREATE TABLE holds (
    -- chosen by the caller (the transfer's id): it makes placing a hold idempotent
    id           uuid        PRIMARY KEY,
    wallet_id    uuid        NOT NULL REFERENCES accounts (id),
    amount       bigint      NOT NULL CHECK (amount > 0),
    currency     text        NOT NULL,
    status       hold_status NOT NULL DEFAULT 'held',
    hold_tx_id   uuid        NOT NULL REFERENCES transactions (id),
    -- where the money went in the end
    settled_to   uuid        REFERENCES accounts (id),
    settle_tx_id uuid        REFERENCES transactions (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    settled_at   timestamptz,
    CHECK ((status = 'held') = (settle_tx_id IS NULL))
);

-- Held money waits in a system account, one per currency. It is money we owe
-- someone, not yet decided whom: a liability, so its normal balance is credit.
INSERT INTO accounts (kind, name, currency, normal_balance) VALUES
    ('system', 'holds', 'EUR', 'credit'),
    ('system', 'holds', 'GBP', 'credit'),
    ('system', 'holds', 'JPY', 'credit'),
    ('system', 'holds', 'USD', 'credit');
