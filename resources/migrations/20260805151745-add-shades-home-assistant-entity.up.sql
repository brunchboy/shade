ALTER TABLE "shades"
  ADD COLUMN if not exists "home_assistant_entity" text null,
  ADD UNIQUE ("home_assistant_entity");

--;;

COMMENT ON COLUMN "shades"."home_assistant_entity" IS 'If not null, this shade is controlled by Home Assistant rather than Control4, and this is how to build entity IDs for its cover and battery sensor.';
