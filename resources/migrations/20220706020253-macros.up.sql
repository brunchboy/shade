CREATE TABLE macros
(id uuid PRIMARY KEY,
 name VARCHAR(100) NOT NULL
)

--;;

CREATE UNIQUE INDEX "macros_name_idx" ON macros(name);

--;;

CREATE TABLE macro_entries
(id uuid PRIMARY KEY,
 macro uuid NOT NULL,
 shade uuid NOT NULL,
 level INTEGER NOT NULL
)

--;;

ALTER TABLE ONLY macro_entries
  ADD CONSTRAINT macro_entries_macro_fkey FOREIGN KEY (macro) REFERENCES macros(id) ON DELETE CASCADE;

--;;

ALTER TABLE ONLY macro_entries
  ADD CONSTRAINT macro_entries_shade_fkey FOREIGN KEY (shade) REFERENCES shades(id) ON DELETE CASCADE;

--;;

CREATE UNIQUE INDEX "macro_entries_macro_shade_idx" ON macro_entries(macro,shade);
