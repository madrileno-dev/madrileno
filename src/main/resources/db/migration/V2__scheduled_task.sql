create table scheduled_task (
  task_name TEXT NOT NULL,
  task_instance TEXT NOT NULL,
  task_data JSONB NOT NULL,
  next_execution TIMESTAMPTZ NOT NULL,
  picked BOOLEAN NOT NULL,
  picked_by TEXT,
  last_success TIMESTAMPTZ,
  last_failure TIMESTAMPTZ,
  consecutive_failures INT,
  last_heartbeat TIMESTAMPTZ,
  version BIGINT NOT NULL,
  priority SMALLINT NOT NULL,
  trace_parent TEXT,
  PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX unpicked_priority_next_execution_idx ON scheduled_task (priority DESC, next_execution ASC) WHERE picked = false;
CREATE INDEX picked_last_heartbeat_idx ON scheduled_task (last_heartbeat) WHERE picked = true;