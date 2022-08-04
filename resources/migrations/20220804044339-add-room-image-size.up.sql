alter table rooms
  add column if not exists image_width bigint not null default 0,
  add column if not exists image_height bigint not null default 0;

--;;

comment on column rooms.image_width is 'Width in pixels of the images used to show blind positions.';

--;;

comment on column rooms.image_height is 'Height in pixels of the images used to show blind positions.';
