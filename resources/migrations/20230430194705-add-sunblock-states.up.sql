alter table shades
  add column if not exists sunblock_state character varying(20) null;

--;;

COMMENT ON COLUMN shades.sunblock_state IS 'Tracks participation of individual shades in an active sunblock event, can be "delayed", "active", or "ended".';
