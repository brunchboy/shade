-- :name create-user! :! :n
-- :doc creates a new user record
INSERT INTO users
(id, name, email, admin, pass)
VALUES (gen_random_uuid(), :name, :email, :admin, :pass)

-- :name update-user! :! :n
-- :doc updates an existing user record
UPDATE users
SET name = :name, email = :email, pass = :pass
WHERE id = :id

-- :name get-user :? :1
-- :doc retrieves a user record given the id
SELECT * FROM users
WHERE id = :id

-- :name get-user-by-email :? :1
-- :doc retrieves a user record given the email
SELECT * FROM users
WHERE email = :email

-- :name delete-user! :! :n
-- :doc deletes a user record given the id
DELETE FROM users
WHERE id = :id
