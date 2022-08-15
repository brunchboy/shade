CREATE TABLE sunblock_group_entries (
    sunblock_group uuid REFERENCES sunblock_groups(id) ON DELETE CASCADE,
    shade uuid REFERENCES shades(id) ON DELETE CASCADE,
    CONSTRAINT sunblock_group_entries_pkey PRIMARY KEY (sunblock_group, shade)
);

--;;

COMMENT ON TABLE sunblock_group_entries IS 'Identifies the shades which belong to a sunblock group.';

--;;

CREATE UNIQUE INDEX sunblock_group_entries_pkey ON sunblock_group_entries(sunblock_group, shade);

--;;

insert into sunblock_group_entries(shade, sunblock_group)
            select id, sunblock_group_id from shades where sunblock_group_id is not null;

--;;

alter table shades
  drop column if exists sunblock_group_id;
