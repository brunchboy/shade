CREATE TABLE IF NOT EXISTS sunblock_obstacles (
  id            uuid PRIMARY KEY,
  shade_id      uuid NOT NULL,
  name          character varying(100) NOT NULL,
  min_azimuth   double precision NOT NULL,
  max_azimuth   double precision NOT NULL,
  min_elevation double precision NOT NULL DEFAULT 0.0,
  max_elevation double precision NOT NULL
);

--;;

COMMENT ON TABLE sunblock_obstacles IS 'Identifies boundaries of buildings or other obstructions which can prevent the sun from hitting a specific shade.';

--;;

COMMENT ON COLUMN sunblock_obstacles.shade_id IS 'Identifies the shade affected by this obstacle.';

--;;

COMMENT ON COLUMN sunblock_obstacles.name IS 'Describes the nature of this obstacle.';

--;;

COMMENT ON COLUMN sunblock_obstacles.min_azimuth IS 'The compass direction at which this obstacle begins.';

--;;

COMMENT ON COLUMN sunblock_obstacles.max_azimuth IS 'The compass direction at which this obstacle ends.';

--;;

COMMENT ON COLUMN sunblock_obstacles.min_elevation IS 'The elevation at which this obstacle begins.';

--;;

COMMENT ON COLUMN sunblock_obstacles.max_elevation IS 'The elevation at which this obstacle ends.';

--;;

ALTER TABLE ONLY sunblock_obstacles
  ADD CONSTRAINT sunblock_obstacles_shade_fkey FOREIGN KEY (shade_id) REFERENCES shades(id) ON DELETE CASCADE;

--;;

CREATE INDEX "sunblock_obstacles_shade_idx" ON sunblock_obstacles(shade_id);
