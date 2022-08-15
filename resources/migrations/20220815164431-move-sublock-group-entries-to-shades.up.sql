alter table shades
  add column if not exists sunblock_group_id UUID,
  add constraint shades_sunblock_group_fkey FOREIGN KEY (sunblock_group_id)
      references sunblock_groups(id) on delete set null;

--;;

COMMENT ON COLUMN shades.sunblock_group_id IS 'Identifies the sunblock group, if any, to which this shade belongs.';

--;;

update shades set sunblock_group_id = (select sunblock_group from sunblock_group_entries where shade = id);

--;;

drop table sunblock_group_entries;
