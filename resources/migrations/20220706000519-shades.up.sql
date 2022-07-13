CREATE TABLE shades
(id uuid PRIMARY KEY,
 name VARCHAR(60) not null,
 kind VARCHAR(20),
 controller_id bigint not null,
 parent_id bigint not null,
 room uuid not null
)

--;;

ALTER TABLE ONLY shades
  ADD CONSTRAINT shades_room_fkey FOREIGN KEY (room) REFERENCES rooms(id) ON DELETE CASCADE;

--;;

CREATE UNIQUE INDEX shades_controller_id_idx ON shades(controller_id);
