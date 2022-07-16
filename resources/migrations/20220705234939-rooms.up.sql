CREATE TABLE rooms
(id uuid PRIMARY KEY,
 name VARCHAR(60) NOT NULL)

--;;

CREATE UNIQUE INDEX "rooms_name_idx" ON rooms(name);
