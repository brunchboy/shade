CREATE TABLE IF NOT EXISTS events
(name VARCHAR(60) NOT NULL,
 related_id uuid NOT NULL,  -- Unfortunately required by primary key
 happened TIMESTAMP WITH TIME ZONE NOT NULL,
 details JSONB,
 PRIMARY KEY (name, related_id));

--;;

COMMENT ON TABLE events IS 'Records when periodic events have taken place, such as sun blocking, sunrise protection, and weather gathering (in which case the results are kept in the details column).';

--;;

COMMENT ON COLUMN events.name IS 'Identifies the type of event which this row records.';

--;;

COMMENT ON COLUMN events.related_id IS 'If the event is based on another table, records the ID of the row that it was executed on behalf of.';

--;;

COMMENT ON COLUMN events.happened IS 'When this event last took place.';

--;;

COMMENT ON COLUMN events.details IS 'Any additional information that describes the event.';
