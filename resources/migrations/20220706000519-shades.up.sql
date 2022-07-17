CREATE TABLE shades
(id uuid PRIMARY KEY,
 name VARCHAR(60) not null,
 kind VARCHAR(20),
 room uuid not null,
 controller_id bigint not null,
 parent_id bigint not null,
 open_max smallint not null default '100'::smallint,
 close_min smallint not null default '0'::smallint
)

--;;

COMMENT ON COLUMN shades.name IS 'Descriptive name identifying the shade.';

--;;

COMMENT ON COLUMN shades.kind IS 'Currently either screen or blackout.';

--;;

COMMENT ON COLUMN shades.room IS 'The room in which this shade is found, for access control and sunrise protection.';

--;;

COMMENT ON COLUMN shades.controller_id IS 'Identifies the shade for queries and commands to the Control4 Director.';

--;;

COMMENT ON COLUMN shades.parent_id IS 'Identifies the device which supplies battery level information for this shade; blackout and screen shades share a parent.';

--;;

COMMENT ON COLUMN shades.open_max IS 'Calibration correction (0-100), can be a value less than 100 if the shade should never be instructed to fully open.';

--;;

COMMENT ON COLUMN shades.close_min IS 'Calibration collection (0-100), can be a value greater than 0 if the shade should never be instructed to fully close.';

--;;

ALTER TABLE ONLY shades
  ADD CONSTRAINT shades_room_fkey FOREIGN KEY (room) REFERENCES rooms(id) ON DELETE CASCADE;

--;;

CREATE UNIQUE INDEX shades_controller_id_idx ON shades(controller_id);
