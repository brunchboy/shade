-- :name list-session-ids :? :*
-- :doc retrieves all session ids
SELECT session_id from session_store

-- :name create-user! :! :n
-- :doc creates a new user record
INSERT INTO users (id, name, email, admin, pass)
VALUES (gen_random_uuid(), :name, :email, :admin, :pass)

-- :name update-user! :! :n
-- :doc updates an existing user record
UPDATE users
   SET name = :name, email = :email, pass = :pass
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


-- :name create-room! :! :n
-- :doc creates a new room record
INSERT INTO rooms (id, name sunrise_protect)
VALUES (gen_random_uuid(), :name, :sunrise_protect)

-- :name update-room! :! :n
-- :doc updates an existing room record
UPDATE rooms
   SET name = :name,
       sunrise_protect = :sunrise_protect
 WHERE id = :id

-- :name list-rooms :? :*
-- :doc retrieves all room records
SELECT * FROM rooms

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
INSERT INTO shades (id, name, kind, controller_id, room)
VALUES (gen_random_uuid(), :name, :kind, :controller-id, :room)

-- :name update-shade! :! :n
-- :doc updates an existing shade record
UPDATE shades
   SET name = :name, kind = :kind, controller_id = :controller-id, room = :room
 WHERE id = :id

-- :name list-shades :? :*
-- :doc retrieves all shade records
SELECT * FROM shades

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

-- :name get-shade-by-controller-id :? :1
-- :doc retrieves a shade record given the controller id
SELECT * FROM shades
 WHERE controller_id = :id

-- :name delete-shade! :! :n
-- :doc deletes a shade record given the id
DELETE FROM shades
 WHERE id = :id


-- :name create-macro! :! :n
-- :doc creates a new macro record
INSERT INTO macros (id, name)
VALUES (gen_random_uuid(), :name)

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
SELECT m.*, count(s)
  FROM macros m
  INNER JOIN macro_entries me on m.id = me.macro
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
  INNER JOIN users_rooms ur on ur.room = r.id
 WHERE ur.user = :user
 GROUP BY m.id
 ORDER BY m.name

-- :name get-macro-entries :? :*
-- :doc retrieves the entries available to the specified user for the macro with the specified id
SELECT me.*, s.controller_id, s.close_min, s.open_max, r.id as room, r.name as room_name
  FROM macro_entries me
  INNER JOIN shades s ON me.shade = s.id
  INNER JOIN rooms r ON s.room = r.id
  INNER JOIN users_rooms ur on ur.room = r.id
 WHERE me.macro = :macro
   AND ur.user = :user

-- :name delete-macro! :! :n
-- :doc deletes a macro record given the id
DELETE FROM macros
 WHERE id = :id


-- :name create-macro-entry! :! :n
-- :doc creates a new macro entry record
INSERT INTO macro_entries (id, macro, shade, level)
VALUES (gen_random_uuid(), :macro, :shade, :level)

-- :name update-macro-entry! :! :n
-- :doc updates the level of an existing macro entry record
UPDATE macros
   SET level = :level
 WHERE id = :id

-- :name delete-macro-entry! :! :n
-- :doc deletes a macro entry record given the id
DELETE FROM macro_entriess
 WHERE id = :id

-- :name get-event :? :1
SELECT * from events
 WHERE name = :name
   AND related_id = :related_id

-- :name update-event :! :n
INSERT INTO events (name, related_id, happened, details)
VALUES (:name, :related_id, now(), :details)
ON CONFLICT (name, related_id) DO UPDATE
  SET happened = EXCLUDED.happened, details = EXCLUDED.details;
