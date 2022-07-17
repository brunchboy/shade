create extension if not exists "uuid-ossp";

--;;

CREATE TABLE users
(id uuid PRIMARY KEY,
 name VARCHAR(60) NOT NULL,
 email VARCHAR(100) NOT NULL,
 admin BOOLEAN NOT NULL DEFAULT false,
 last_login TIMESTAMP WITH TIME ZONE,
 is_active BOOLEAN,
 pass VARCHAR(300));

--;;

CREATE UNIQUE INDEX "users_email_idx" ON users(email);
