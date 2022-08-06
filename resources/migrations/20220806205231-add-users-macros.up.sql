CREATE TABLE users_macros
("user" uuid NOT NULL,
 macro uuid NOT NULL,
 PRIMARY KEY ("user", macro)
)
--;;

ALTER TABLE ONLY users_macros
  ADD CONSTRAINT users_macros_user_fkey FOREIGN KEY ("user") REFERENCES users(id) ON DELETE CASCADE;

--;;

ALTER TABLE ONLY users_macros
  ADD CONSTRAINT users_macros_macro_fkey FOREIGN KEY (macro) REFERENCES macros(id) ON DELETE CASCADE;

--;;
