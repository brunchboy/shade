-- :name list-session-ids :? :*
-- :doc retrieves all session ids
SELECT session_id from session_store

-- :name create-user! :! :n
-- :doc creates a new user record
INSERT INTO users (id, name, email, admin, pass, is_active)
VALUES (gen_random_uuid(), :name, :email, :admin, :pass, :is_active)

-- :name update-user! :! :n
-- :doc updates an existing user record
UPDATE users
   SET name = :name, email = :email, pass = :pass, admin = :admin, is_active = :is_active
 WHERE id = :id

-- :name list-users :? :*
-- :doc retrieves all user records
SELECT * FROM users
 ORDER by email;

-- :name get-user :? :1
-- :doc retrieves a user record given the id
SELECT * FROM users
 WHERE id = :id

-- :name get-user-by-email :? :1
-- :doc retrieves a user record given the email
SELECT * FROM users
 WHERE email = :email

-- :name update-user-login-timestamp! :! :n
UPDATE users
   SET last_login = now()
 WHERE id = :id

-- :name delete-user! :! :n
-- :doc deletes a user record given the id
DELETE FROM users
 WHERE id = :id

-- :name create-user-room! :! :n
-- :doc creates a new entry in the users_rooms join table
INSERT INTO users_rooms ("user", room)
VALUES (:user, :room);

-- :name delete-user-room! :! :n
-- :doc removes an entry from the users_rooms join table
DELETE FROM users_rooms
 WHERE "user" = :user
   AND room = :room;


-- :name create-room! :! :n
-- :doc creates a new room record
INSERT INTO rooms (id, name, sunrise_protect, image_width, image_height)
VALUES (gen_random_uuid(), :name, :sunrise_protect, 4032, 3024)

-- :name update-room! :! :n
-- :doc updates an existing room record
UPDATE rooms
   SET name = :name,
       sunrise_protect = :sunrise_protect
 WHERE id = :id

-- :name list-rooms :? :*
-- :doc retrieves all room records
SELECT * FROM rooms

-- :name list-rooms-for-user :? :*
-- :doc retrieves all room records available to the specified user
SELECT r.*
  FROM rooms r
  INNER JOIN users_rooms ur on ur.room = r.id
 WHERE ur.user = :user
 ORDER BY r.name

-- :name get-room :? :1
-- :doc retrieves a room record given the id
SELECT * FROM rooms
 WHERE id = :id

-- :name delete-room! :! :n
-- :doc deletes a room record given the id
DELETE FROM rooms
 WHERE id = :id


-- :name create-shade! :! :n
-- :doc creates a new shade record
INSERT INTO shades (id, name, kind, controller_id, room, parent_id)
VALUES (gen_random_uuid(), :name, :kind, :controller-id, :room, :parent-id)

-- :name update-shade! :! :n
-- :doc updates an existing shade record
UPDATE shades
   SET name = :name, kind = :kind, controller_id = :controller-id, room = :room, parent_id = :parent-id
 WHERE id = :id

-- :name list-shades :? :*
-- :doc retrieves all shade records, augmenting them with the name of the room the shade is found in
SELECT s.*, r.name as room_name
 FROM shades s
 INNER JOIN rooms r ON s.room = r.id
 ORDER BY room_name, s.kind, s.name

-- :name list-shades-by-room :? :*
-- :doc retrieves all shades in a given room
SELECT * FROM shades
 WHERE room = :room

-- :name list-shades-for-sunrise-protect :? ?*
select shades.* from shades
 inner join rooms on shades.room = rooms.id
   and rooms.sunrise_protect = 'true'
 where shades.kind = 'blackout';

-- :name get-shade :? :1
-- :doc retrieves a shade record given the id
SELECT * FROM shades
 WHERE id = :id

-- :name get-shades :? :*
-- :doc retrieves multiple shade records given a list of ids
SELECT * FROM shades
 WHERE id in (:v*:ids)

-- :name get-shade-by-controller-id :? :1
-- :doc retrieves a shade record given the controller id
SELECT * FROM shades
 WHERE controller_id = :id

-- :name delete-shade! :! :n
-- :doc deletes a shade record given the id
DELETE FROM shades
 WHERE id = :id


-- :name create-macro! :! :1
-- :doc creates a new macro record
INSERT INTO macros (id, name)
VALUES (gen_random_uuid(), :name)
RETURNING id;

-- :name get-macro :? :1
-- :doc gets a single macro record given the id
SELECT * from macros
 WHERE id = :id

-- :name get-macro-by-name :? :1
-- :doc gets a single macro record given the id
SELECT * from macros
 WHERE name = :name

-- :name update-macro! :! :n
-- :doc updates an existing macro record
UPDATE macros
   SET name = :name
 WHERE id = :id

-- :name list-macros :? :*
-- :doc retrieves all macro records
SELECT * FROM macros
 ORDER BY name

-- :name list-macros-for-user :? :*
-- :doc retrieves all macro records with any entries available to the specified user
SELECT m.*, count(s), exists (select * from users_macros um where um.user = :user and um.macro = m.id) as enabled
  FROM macros m
  INNER JOIN macro_entries me on m.id = me.macro
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
  INNER JOIN users_rooms ur on ur.room = r.id
 WHERE ur.user = :user
 GROUP BY m.id
 ORDER BY m.name

-- :name list-macros-enabled-for-user :? :*
-- :doc retrieves all macro records with any entries available to the specified user, if user has enabled the macro
SELECT m.*, count(s)
  FROM macros m
  INNER JOIN macro_entries me on m.id = me.macro
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
  INNER JOIN users_rooms ur on ur.room = r.id
  INNER JOIN users_macros um on um.macro = m.id
 WHERE ur.user = :user
   AND um.user = :user
 GROUP BY m.id
 ORDER BY m.name

-- :name list-macros-enabled-for-user-in-room :? :*
-- :doc retrieves all macro records with any entries available to the specified user which affect the specified room, if user has enabled the macro
SELECT m.*, count(s)
  FROM macros m
  INNER JOIN macro_entries me on m.id = me.macro
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN users_rooms ur on ur.room = s.room
  INNER JOIN users_macros um on um.macro = m.id
 WHERE ur.user = :user
   AND um.user = :user
 GROUP BY m.id
 ORDER BY m.name



-- :name create-user-macro! :! :n
-- :doc marks a user as having enabled the specified macro
INSERT INTO users_macros ("user", macro)
VALUES (:user, :macro)

-- :name delete-user-macro! :! :n
-- :doc marks a user as having disabled the specified macro
DELETE FROM users_macros
 WHERE "user" = :user
   AND macro = :macro

-- :name delete-macro! :! :n
-- :doc deletes a macro record given the id
DELETE FROM macros
 WHERE id = :id

-- :name delete-macro-entries! :! :n
-- :doc deletes all entries associated with a macro (for admin macro editing)
DELETE FROM macro_entries
 WHERE macro = :macro

-- :name get-all-macro-entries :? :*
-- :doc retrieves all macro entries for the specified macro (for admin macro editing)
SELECT me.*, s.controller_id, s.close_min, s.open_max, r.id as room, r.name as room_name
  FROM macro_entries me
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
 WHERE me.macro = :macro


-- :name get-macro-entries :? :*
-- :doc retrieves the entries available to the specified user for the macro with the specified id
SELECT me.*, s.controller_id, s.close_min, s.open_max, r.id as room, r.name as room_name
  FROM macro_entries me
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
  INNER JOIN users_rooms ur on ur.room = r.id
 WHERE me.macro = :macro
   AND ur.user = :user

-- :name create-macro-entry! :! :n
-- :doc creates a new macro entry record
INSERT INTO macro_entries (macro, shade, level)
VALUES (:macro, :shade, :level)

-- :name update-macro-entry! :! :n
-- :doc updates the level of an existing macro entry record
UPDATE macros
   SET level = :level
 WHERE id = :id


-- :name get-room-photo-boundaries :? :*
-- :doc retrieves the photo boundary information for all blinds in a room
select spb.id, kind, controller_id, open_max, close_min, s.id as shade_id,
       top_left_x, top_left_y, top_right_x, top_right_y, bottom_left_x, bottom_left_y, bottom_right_x, bottom_right_y
  from shade_photo_boundaries spb
 inner join shades s on spb.id = s.photo_boundaries_id
 where s.room = :room


-- :name get-event :? :1
SELECT * from events
 WHERE name = :name
   AND related_id = :related_id

-- :name update-event :! :n
INSERT INTO events (name, related_id, happened, details)
VALUES (:name, :related_id, now(), :details)
ON CONFLICT (name, related_id) DO UPDATE
  SET happened = EXCLUDED.happened, details = EXCLUDED.details;

-- :name list-events :? :*
SELECT * from events
ORDER BY name;


-- :name create-sunblock-group! :! :n
-- :doc creates a new sunblock group record
INSERT INTO sunblock_groups (id, name, azimuth, horizon, ceiling, "left", "right")
VALUES (gen_random_uuid(), :name, :azimuth, :horizon, :ceiling, :left, :right)

-- :name update-sunblock-group! :! :n
-- :doc updates an existing sunblock group record
UPDATE macros
   SET name = :name,
       azimuth = :azimuth,
       horizon = :horizon,
       ceiling = :ceiling,
       "left" = :left,
       "right" = :right
 WHERE id = :id

-- :name list-sunblock-groups :? :*
-- :doc retrieves all sunblock group records
SELECT * FROM sunblock_groups
 ORDER BY name

-- :name get-sunblock-group :? :1
-- :doc retrieves a single sunblock group record by ID
SELECT * FROM sunblock_groups
 WHERE id = :id

-- :name delete-sunblock-group! :! :n
-- :doc deletes a sunblock group record given the id
DELETE FROM sunblock_groups
 WHERE id = :id

-- :name get-sunblock-group-shades :? :*
-- :doc retrieves the shades which belong to the specified sunblock group
SELECT * from shades
 WHERE sunblock_group_id = :sunblock_group

-- :name get-sunblock-group-shades-in-state :? :*
-- :doc retrieves the shades which belong to the specified sunblock group
SELECT * from shades
 WHERE sunblock_group_id = :sunblock_group
   AND sunblock_state = :state

-- :name set-shade-sunblock-group! :! :n
-- :doc updates the sunblock group of an existing shade record
UPDATE shades
   SET sunblock_group_id = :sunblock_group
 WHERE id = :id

-- :name set-shade-sunblock-restore! :! :n
-- :doc updates the starting position of a shade as sun block is about to move it
UPDATE shades
   SET sunblock_restore = :sunblock_restore
 WHERE id = :id

-- :name set-shade-sunblock-state! :! :n
-- :doc updates the recorded sunblock state of a shade
UPDATE shades
   SET sunblock_restore = :sunblock_restore
 WHERE id = :id

-- :name clear-sunblock-group-shade-states! :! :n
-- :doc clears the sunblock sates and restore positions of all shades in a sunblock group
UPDATE shades
  SET sunblock_restore = null,
      sunblock_state = null
 WHERE sunblock_group_id = :sunblock_group


-- :name create-sunblock-obstacle! :! :n
-- :doc creates a new sunblock obstacle record
INSERT INTO sunblock_obstacles (id, shade_id, name, min_azimuth, max_azimuth, min_elevation, max_elevation)
VALUES (gen_random_uuid(), :shade_id, :name, :min_azimuth, :max_azimuth, :min_elevation, :max_elevation)

-- :name get-sunblock-obstacles-for-shade :? :*
SELECT * from sunblock_obstacles
 WHERE shade_id = :shade
