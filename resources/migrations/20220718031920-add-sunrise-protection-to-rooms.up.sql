ALTER TABLE rooms
  ADD COLUMN IF NOT EXISTS "sunrise_protect" boolean NOT NULL DEFAULT 'false';
