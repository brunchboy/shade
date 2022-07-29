CREATE TABLE IF NOT EXISTS shade_photo_boundaries (
  id      uuid PRIMARY KEY,
  "top_left_x" bigint NOT NULL,
  "top_left_y" bigint NOT NULL,
  "top_right_x" bigint NOT NULL,
  "top_right_y" bigint NOT NULL,
  "bottom_left_x" bigint NOT NULL,
  "bottom_left_y" bigint NOT NULL,
  "bottom_right_x" bigint NOT NULL,
  "bottom_right_y" bigint NOT NULL
);
--;;

COMMENT ON TABLE shade_photo_boundaries IS 'Identifies the boundary of a shade within the room photo, and groups shades that are in the same window.';

--;;

ALTER TABLE shades
  ADD COLUMN IF NOT EXISTS "photo_boundaries_id" UUID;

--;;

COMMENT ON COLUMN shades.photo_boundaries_id IS 'Identifies the boundary of the shade within the room photo, and groups shades that are in the same window.';

--;;

ALTER TABLE ONLY shades
  ADD CONSTRAINT shades_photo_boundaries_fkey FOREIGN KEY (photo_boundaries_id)
      REFERENCES shade_photo_boundaries(id) ON DELETE SET NULL;
