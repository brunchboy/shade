alter table shades
 add column if not exists cells_installed timestamp with time zone null;

--;;

COMMENT ON COLUMN shades.cells_installed IS 'When cells were last installed in this shade, for replacement planning.';
