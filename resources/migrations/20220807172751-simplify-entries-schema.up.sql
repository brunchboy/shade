ALTER TABLE macro_entries DROP CONSTRAINT macro_entries_pkey;

--;;

ALTER TABLE macro_entries ADD PRIMARY KEY (macro, shade);

--;;

DROP INDEX macro_entries_macro_shade_idx;

--;;

ALTER TABLE macro_entries DROP COLUMN id;

--;;

ALTER TABLE sunblock_group_entries DROP CONSTRAINT sunblock_group_entries_pkey;

--;;

ALTER TABLE sunblock_group_entries ADD PRIMARY KEY (sunblock_group, shade);

--;;

DROP INDEX sunblock_group_entries_sunblock_group_shade_idx;

--;;

ALTER TABLE sunblock_group_entries DROP COLUMN id;
