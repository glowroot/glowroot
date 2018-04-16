CREATE TABLE trace_tn_error_count (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_error_summary_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_error_summary_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_error_summary_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_error_summary_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_throughput_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_throughput_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_throughput_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_throughput_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_aux_thread_profile_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_aux_thread_profile_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_aux_thread_profile_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_aux_thread_profile_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_error_summary_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tn_error_point (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    attributes blob,
    duration_nanos bigint,
    error_message text,
    headline text,
    user text,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_value_rollup_4 (
    agent_rollup text,
    gauge_name text,
    capture_time timestamp,
    value double,
    weight bigint,
    PRIMARY KEY ((agent_rollup, gauge_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tt_slow_point (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    attributes blob,
    duration_nanos bigint,
    error boolean,
    headline text,
    user text,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_summary_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_error_summary_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_summary_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_summary_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_name (
    agent_rollup text,
    gauge_name text,
    PRIMARY KEY (agent_rollup, gauge_name)
) WITH CLUSTERING ORDER BY (gauge_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_header (
    agent_id text,
    trace_id text,
    header blob,
    PRIMARY KEY (agent_id, trace_id)
) WITH CLUSTERING ORDER BY (trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE full_query_text_check (
    agent_rollup text,
    full_query_text_sha1 text,
    PRIMARY KEY (agent_rollup, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE triggered_alert (
    agent_rollup text,
    alert_config_version text,
    PRIMARY KEY (agent_rollup, alert_config_version)
) WITH CLUSTERING ORDER BY (alert_config_version ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_aux_thread_profile_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_aux_thread_profile_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_aux_thread_profile_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_aux_thread_profile_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    aux_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tt_error_point (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    attributes blob,
    duration_nanos bigint,
    error_message text,
    headline text,
    user text,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_service_call_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_service_call_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_service_call_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_service_call_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_overview_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_overview_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_overview_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_overview_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_summary_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_error_summary_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_error_summary_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    error_count bigint,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_aux_thread_profile (
    agent_id text,
    trace_id text,
    profile blob,
    PRIMARY KEY (agent_id, trace_id)
) WITH CLUSTERING ORDER BY (trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_histogram_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_histogram_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_histogram_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_histogram_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE transaction_type (
    one int,
    agent_rollup text,
    transaction_type text,
    PRIMARY KEY (one, agent_rollup, transaction_type)
) WITH CLUSTERING ORDER BY (agent_rollup ASC, transaction_type ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE full_query_text (
    full_query_text_sha1 text PRIMARY KEY,
    full_query_text text
) WITH bloom_filter_fp_chance = 0.01
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_main_thread_profile (
    agent_id text,
    trace_id text,
    profile blob,
    PRIMARY KEY (agent_id, trace_id)
) WITH CLUSTERING ORDER BY (trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tt_error_message (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    error_message text,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_main_thread_profile_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_main_thread_profile_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_main_thread_profile_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_main_thread_profile_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tn_slow_point (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    attributes blob,
    duration_nanos bigint,
    error boolean,
    headline text,
    user text,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_attribute_name (
    agent_rollup text,
    transaction_type text,
    trace_attribute_name text,
    PRIMARY KEY ((agent_rollup, transaction_type), trace_attribute_name)
) WITH CLUSTERING ORDER BY (trace_attribute_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_overview_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_overview_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_overview_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_overview_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    async_root_timers blob,
    async_transactions boolean,
    aux_thread_root_timers blob,
    aux_thread_total_allocated_bytes double,
    aux_thread_total_blocked_nanos double,
    aux_thread_total_cpu_nanos double,
    aux_thread_total_waited_nanos double,
    main_thread_root_timers blob,
    main_thread_total_allocated_bytes double,
    main_thread_total_blocked_nanos double,
    main_thread_total_cpu_nanos double,
    main_thread_total_waited_nanos double,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_query_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_query_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_query_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_query_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_entry (
    agent_id text,
    trace_id text,
    index_ int,
    active boolean,
    depth int,
    detail blob,
    duration_nanos bigint,
    error blob,
    location_stack_trace blob,
    message text,
    start_offset_nanos bigint,
    PRIMARY KEY (agent_id, trace_id, index_)
) WITH CLUSTERING ORDER BY (trace_id ASC, index_ ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_histogram_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_histogram_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_histogram_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_histogram_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    duration_nanos_histogram blob,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_throughput_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_summary_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_throughput_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_summary_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tt_slow_count (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_needs_rollup_1 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    gauge_names set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_needs_rollup_3 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    gauge_names set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_needs_rollup_2 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    gauge_names set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_needs_rollup_4 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    gauge_names set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE agent (
    agent_id text PRIMARY KEY,
    config blob,
    system_info blob
) WITH bloom_filter_fp_chance = 0.1
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_main_thread_profile_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_main_thread_profile_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_main_thread_profile_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_main_thread_profile_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    main_thread_profile blob,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE role (
    name text PRIMARY KEY,
    permissions set<text>
) WITH bloom_filter_fp_chance = 0.1
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_query_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_query_rollup_1 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_query_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tt_query_rollup_3 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    query_type text,
    truncated_query_text text,
    full_query_text_sha1 text,
    execution_count bigint,
    total_duration_nanos double,
    total_rows bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, query_type, truncated_query_text, full_query_text_sha1)
) WITH CLUSTERING ORDER BY (capture_time ASC, query_type ASC, truncated_query_text ASC, full_query_text_sha1 ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_value_rollup_1 (
    agent_rollup text,
    gauge_name text,
    capture_time timestamp,
    value double,
    weight bigint,
    PRIMARY KEY ((agent_rollup, gauge_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_value_rollup_0 (
    agent_rollup text,
    gauge_name text,
    capture_time timestamp,
    value double,
    weight bigint,
    PRIMARY KEY ((agent_rollup, gauge_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_value_rollup_3 (
    agent_rollup text,
    gauge_name text,
    capture_time timestamp,
    value double,
    weight bigint,
    PRIMARY KEY ((agent_rollup, gauge_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE gauge_value_rollup_2 (
    agent_rollup text,
    gauge_name text,
    capture_time timestamp,
    value double,
    weight bigint,
    PRIMARY KEY ((agent_rollup, gauge_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_needs_rollup_1 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    transaction_types set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_needs_rollup_3 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    transaction_types set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_needs_rollup_2 (
    agent_rollup text,
    capture_time timestamp,
    uniqueness timeuuid,
    transaction_types set<text>,
    PRIMARY KEY (agent_rollup, capture_time, uniqueness)
) WITH CLUSTERING ORDER BY (capture_time ASC, uniqueness ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_service_call_rollup_1 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_service_call_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_service_call_rollup_3 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_service_call_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    service_call_type text,
    service_call_text text,
    execution_count bigint,
    total_duration_nanos double,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, service_call_type, service_call_text)
) WITH CLUSTERING ORDER BY (capture_time ASC, service_call_type ASC, service_call_text ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE agent_rollup (
    one int,
    agent_rollup text,
    leaf boolean,
    PRIMARY KEY (one, agent_rollup)
) WITH CLUSTERING ORDER BY (agent_rollup ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE user (
    username text PRIMARY KEY,
    ldap boolean,
    password_hash text,
    roles set<text>
) WITH bloom_filter_fp_chance = 0.1
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tt_error_count (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tn_slow_count (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_summary_rollup_0 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_throughput_rollup_0 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_summary_rollup_2 (
    agent_rollup text,
    transaction_type text,
    capture_time timestamp,
    transaction_name text,
    total_duration_nanos double,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type), capture_time, transaction_name)
) WITH CLUSTERING ORDER BY (capture_time ASC, transaction_name ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE aggregate_tn_throughput_rollup_2 (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    transaction_count bigint,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time)
) WITH CLUSTERING ORDER BY (capture_time ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE server_config (
    key text PRIMARY KEY,
    value text
) WITH bloom_filter_fp_chance = 0.1
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

CREATE TABLE trace_tn_error_message (
    agent_rollup text,
    transaction_type text,
    transaction_name text,
    capture_time timestamp,
    agent_id text,
    trace_id text,
    error_message text,
    PRIMARY KEY ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id, trace_id)
) WITH CLUSTERING ORDER BY (capture_time ASC, agent_id ASC, trace_id ASC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.DateTieredCompactionStrategy'}
    AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND gc_grace_seconds = 864000;

