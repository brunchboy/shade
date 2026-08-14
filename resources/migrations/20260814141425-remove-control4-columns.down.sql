ALTER TABLE "shades"
  ADD COLUMN if not exists "controller_id" bigint null,
  ADD UNIQUE ("controller_id");

--;;

COMMENT ON COLUMN "shades"."controller_id" IS 'If not null, this shade is controlled by Control4 rather than Home Assistant, and this is how to identify it.';

--;;

COMMENT ON COLUMN "shades"."home_assistant_entity" IS 'If not null, this shade is controlled by Home Assistant rather than Control4, and this is how to build entity IDs for its cover and battery sensor.';
