ALTER TABLE shades DROP COLUMN IF EXISTS "controller_id";

--;;

ALTER TABLE shades DROP COLUMN IF EXISTS "parent_id";

--;;

ALTER TABLE shades ALTER COLUMN "home_assistant_entity" SET NOT NULL;

--;;

COMMENT ON COLUMN "shades"."home_assistant_entity" IS 'Used to build Home Assistant entity IDs for its cover and battery sensor.';
