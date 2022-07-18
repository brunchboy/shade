CREATE TABLE IF NOT EXISTS sunblock_groups (
  id      uuid PRIMARY KEY,
  name    character varying(100) NOT NULL,
  azimuth double precision NOT NULL,
  horizon double precision NOT NULL DEFAULT 5.0,
  ceiling double precision NOT NULL DEFAULT 85.0,
  "left"  double precision NOT NULL DEFAULT 5.0,
  "right" double precision NOT NULL DEFAULT 175.0
);

--;;

CREATE UNIQUE INDEX "sunblock_groups_name_idx" ON sunblock_groups(name);

--;;

COMMENT ON TABLE sunblock_groups IS 'Identifies groups of blinds that should be closed when the sun is shining through them, as well as the parameters needed to calculate when that takes place.';

--;;

COMMENT ON COLUMN sunblock_groups.name IS 'Describes the nature of this group.';

--;;

COMMENT ON COLUMN sunblock_groups.azimuth IS 'The compass direction in which the windows of this group are facing.';

--;;

COMMENT ON COLUMN sunblock_groups.horizon IS 'The lowest elevation at which the sun shines through these windows.';

--;;

COMMENT ON COLUMN sunblock_groups.ceiling IS 'The highest elevation at which the sun shines through these windows.';

--;;

COMMENT ON COLUMN sunblock_groups.left IS 'If the windows were facing 90 degrees, the lowest azimuth at which the sun would shine through them.';

--;;

COMMENT ON COLUMN sunblock_groups.right IS 'If the windows were facing 90 degrees, the highest azimuth at which the sun would shine through them.';

--;;

CREATE TABLE sunblock_group_entries (id uuid PRIMARY KEY,
 sunblock_group uuid NOT NULL,
 shade uuid NOT NULL
)

--;;

COMMENT ON TABLE sunblock_group_entries IS 'Identifies the shades which belong to a sunblock group.';

--;;

ALTER TABLE ONLY sunblock_group_entries
  ADD CONSTRAINT sunblock_group_entries_sunblock_group_fkey
     FOREIGN KEY (sunblock_group) REFERENCES sunblock_groups(id) ON DELETE CASCADE;

--;;

ALTER TABLE ONLY sunblock_group_entries
  ADD CONSTRAINT sunblock_group_entries_shade_fkey FOREIGN KEY (shade) REFERENCES shades(id) ON DELETE CASCADE;

--;;

CREATE UNIQUE INDEX "sunblock_group_entries_sunblock_group_shade_idx" ON sunblock_group_entries(sunblock_group,shade);
