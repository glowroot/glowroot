package org.glowroot.common2.repo;

public enum CassandraProfile {
    slow, //DDL
    collector, // data collection
    rollup, // rollup thread
    web // web
}
