create extension if not exists "uuid-ossp";

--;;

CREATE TABLE users
(id uuid PRIMARY KEY,
 name VARCHAR(60),
 email VARCHAR(30),
 admin BOOLEAN,
 last_login TIMESTAMP,
 is_active BOOLEAN,
 pass VARCHAR(300));

--;;

CREATE UNIQUE INDEX "users_email_idx" ON users(email);
