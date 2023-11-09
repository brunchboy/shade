alter table shades
  add column if not exists cell_count smallint null;

--;;

COMMENT ON COLUMN shades.cell_count IS 'The number of D cells that power this shade, for replacement planning.';
