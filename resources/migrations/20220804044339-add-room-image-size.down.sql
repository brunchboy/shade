alter table rooms
  drop column if exists image_width,
  drop column if exists image_height;
