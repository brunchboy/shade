CREATE TABLE users_rooms
("user" uuid NOT NULL,
 room uuid NOT NULL,
 PRIMARY KEY ("user", room)
)
--;;

ALTER TABLE ONLY users_rooms
  ADD CONSTRAINT users_rooms_user_fkey FOREIGN KEY ("user") REFERENCES users(id) ON DELETE CASCADE;

--;;

ALTER TABLE ONLY users_rooms
  ADD CONSTRAINT users_rooms_room_fkey FOREIGN KEY (room) REFERENCES rooms(id) ON DELETE CASCADE;

--;;
