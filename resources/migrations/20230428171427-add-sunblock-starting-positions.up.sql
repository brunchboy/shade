alter table shades
  add column if not exists sunblock_restore smallint null;

--;;

COMMENT ON COLUMN shades.sunblock_restore IS 'Identifies the position to which the blind should be returned, if any, when sun block ends.';
