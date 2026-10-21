CREATE TABLE IF NOT EXISTS vehicles (
    id          TEXT PRIMARY KEY,
    nickname    TEXT NOT NULL,
    model       TEXT NOT NULL,
    lat         REAL NOT NULL,
    lon         REAL NOT NULL,
    heading     REAL NOT NULL DEFAULT 0,      -- degrees, 0 = north
    speed_kmh   REAL NOT NULL DEFAULT 0,
    gear        TEXT NOT NULL DEFAULT 'D',    -- P / R / N / D
    dest        TEXT,
    discoverable INTEGER NOT NULL DEFAULT 1,
    updated_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    from_id     TEXT NOT NULL,
    to_id       TEXT NOT NULL,
    preset      TEXT NOT NULL,
    arg         TEXT,
    auto        INTEGER NOT NULL DEFAULT 0,   -- 1 = answered by public-data fallback
    created_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS blocks (
    blocker_id  TEXT NOT NULL,
    blocked_id  TEXT NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE TABLE IF NOT EXISTS reports (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    reporter_id TEXT NOT NULL,
    target_id   TEXT NOT NULL,
    reason      TEXT NOT NULL,
    created_at  REAL NOT NULL
);

-- public-data fallback (rest areas, congestion, restaurants)
CREATE TABLE IF NOT EXISTS places (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind        TEXT NOT NULL,                -- restarea / restaurant
    name        TEXT NOT NULL,
    lat         REAL NOT NULL,
    lon         REAL NOT NULL,
    congestion  INTEGER,                      -- 0 free .. 2 crowded (restarea only)
    likes       INTEGER NOT NULL DEFAULT 0
);

-- one like per registered vehicle per place
CREATE TABLE IF NOT EXISTS place_likes (
    place_id    INTEGER NOT NULL,
    vehicle_id  TEXT NOT NULL,
    PRIMARY KEY (place_id, vehicle_id)
);
