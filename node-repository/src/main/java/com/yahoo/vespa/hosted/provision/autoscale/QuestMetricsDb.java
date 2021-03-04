// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.google.inject.Inject;
import com.yahoo.collections.ListMap;
import com.yahoo.collections.Pair;
import com.yahoo.component.AbstractComponent;
import com.yahoo.io.IOUtils;
import com.yahoo.vespa.defaults.Defaults;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.DefaultCairoConfiguration;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.std.str.Path;

import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * An implementation of the metrics Db backed by Quest:
 * This provides local persistent storage of metrics with fast, multi-threaded lookup and write,
 * suitable for production.
 *
 * @author bratseth
 */
public class QuestMetricsDb extends AbstractComponent implements MetricsDb {

    private static final Logger log = Logger.getLogger(QuestMetricsDb.class.getName());
    private static final String table = "metrics";

    private final Clock clock;
    private final String dataDir;
    private CairoEngine engine;

    private long highestTimestampAdded = 0;

    @Inject
    public QuestMetricsDb() {
        this(Defaults.getDefaults().underVespaHome("var/db/vespa/autoscaling"), Clock.systemUTC());
    }

    public QuestMetricsDb(String dataDir, Clock clock) {
        this.clock = clock;

        if (dataDir.startsWith(Defaults.getDefaults().vespaHome())
            && ! new File(Defaults.getDefaults().vespaHome()).exists())
            dataDir = "data"; // We're injected, but not on a node with Vespa installed
        this.dataDir = dataDir;
        initializeDb();
    }

    private void initializeDb() {
        IOUtils.createDirectory(dataDir + "/" + table);

        // silence Questdb's custom logging system
        IOUtils.writeFile(new File(dataDir, "quest-log.conf"), new byte[0]);
        System.setProperty("questdbLog", dataDir + "/quest-log.conf");
        System.setProperty("org.jooq.no-logo", "true");

        CairoConfiguration configuration = new DefaultCairoConfiguration(dataDir);
        engine = new CairoEngine(configuration);
        ensureExists(table);
    }

    @Override
    public void add(Collection<Pair<String, MetricSnapshot>> snapshots) {
        try (TableWriter writer = engine.getWriter(newContext().getCairoSecurityContext(), table)) {
            add(snapshots, writer);
        }
        catch (CairoException e) {
            if (e.getMessage().contains("Cannot read offset")) {
                // This error seems non-recoverable
                repair(e);
                try (TableWriter writer = engine.getWriter(newContext().getCairoSecurityContext(), table)) {
                    add(snapshots, writer);
                }
            }
        }
    }

    private void add(Collection<Pair<String, MetricSnapshot>> snapshots, TableWriter writer) {
        for (var snapshot : snapshots) {
            long atMillis = adjustIfRecent(snapshot.getSecond().at().toEpochMilli(), highestTimestampAdded);
            if (atMillis < highestTimestampAdded) continue; // Ignore old data
            highestTimestampAdded = atMillis;
            TableWriter.Row row = writer.newRow(atMillis * 1000); // in microseconds
            row.putStr(0, snapshot.getFirst());
            row.putFloat(2, (float)snapshot.getSecond().cpu());
            row.putFloat(3, (float)snapshot.getSecond().memory());
            row.putFloat(4, (float)snapshot.getSecond().disk());
            row.putLong(5, snapshot.getSecond().generation());
            row.putBool(6, snapshot.getSecond().inService());
            row.putBool(7, snapshot.getSecond().stable());
            row.putFloat(8, (float)snapshot.getSecond().queryRate());
            row.append();
        }
        writer.commit();
    }

    @Override
    public List<NodeTimeseries> getNodeTimeseries(Duration period, Set<String> hostnames) {
        try (SqlCompiler compiler = new SqlCompiler(engine)) {
            SqlExecutionContext context = newContext();
            var snapshots = getSnapshots(clock.instant().minus(period), hostnames, compiler, context);
            return snapshots.entrySet().stream()
                            .map(entry -> new NodeTimeseries(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toList());
        }
        catch (SqlException e) {
            throw new IllegalStateException("Could not read timeseries data in Quest stored in " + dataDir, e);
        }
    }

    @Override
    public void gc() {
        // Since we remove full days at once we need to keep at least the scaling window + 1 day
        Instant oldestToKeep = clock.instant().minus(Autoscaler.maxScalingWindow().plus(Duration.ofDays(1)));
        SqlExecutionContext context = newContext();
        int partitions = 0;
        try (SqlCompiler compiler = new SqlCompiler(engine)) {
            File tableRoot = new File(dataDir, table);
            List<String> removeList = new ArrayList<>();
            for (String dirEntry : tableRoot.list()) {
                File partitionDir = new File(tableRoot, dirEntry);
                if ( ! partitionDir.isDirectory()) continue;

                partitions++;
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of("UTC"));
                Instant partitionDay = Instant.from(formatter.parse(dirEntry + "T00:00:00"));
                if (partitionDay.isBefore(oldestToKeep))
                    removeList.add(dirEntry);

            }
            // Remove unless all partitions are old: Removing all partitions "will be supported in the future"
            if ( removeList.size() < partitions && ! removeList.isEmpty())
                compiler.compile("alter table " + table + " drop partition " +
                                 removeList.stream().map(dir -> "'" + dir + "'").collect(Collectors.joining(",")),
                                 context);
        }
        catch (SqlException e) {
            log.log(Level.WARNING, "Failed to gc old metrics data in " + dataDir, e);
        }
    }

    @Override
    public void deconstruct() { close(); }

    @Override
    public void close() {
        if (engine != null)
            engine.close();
    }

    /**
     * Repairs this db on corruption.
     *
     * @param e the exception indicating corruption
     */
    private void repair(Exception e) {
        log.log(Level.WARNING, "QuestDb seems corrupted, wiping data and starting over", e);
        IOUtils.recursiveDeleteDir(new File(dataDir));
        initializeDb();
    }

    private void ensureExists(String table) {
        SqlExecutionContext context = newContext();
        if (0 == engine.getStatus(context.getCairoSecurityContext(), new Path(), table)) { // table exists
            ensureTableIsUpdated(table, context);
        } else {
            createTable(table, context);
        }
    }

    private void createTable(String table, SqlExecutionContext context) {
        try (SqlCompiler compiler = new SqlCompiler(engine)) {
            compiler.compile("create table " + table +
                             " (hostname string, at timestamp, cpu_util float, mem_total_util float, disk_util float," +
                             "  application_generation long, inService boolean, stable boolean, queries_rate float)" +
                             " timestamp(at)" +
                             "PARTITION BY DAY;",
                             context);
            // We should do this if we get a version where selecting on strings work embedded, see below
            // compiler.compile("alter table " + tableName + " alter column hostname add index", context);
        }
        catch (SqlException e) {
            throw new IllegalStateException("Could not create Quest db table '" + table + "'", e);
        }
    }

    private void ensureTableIsUpdated(String table, SqlExecutionContext context) {
        try (SqlCompiler compiler = new SqlCompiler(engine)) {
            if (0 == engine.getStatus(context.getCairoSecurityContext(), new Path(), table)) {
                ensureColumnExists("queries_rate", "float", table, compiler, context); // TODO: Remove after March 2021
            }
        } catch (SqlException e) {
            repair(e);
        }
    }

    private void ensureColumnExists(String column, String columnType,
                                    String table, SqlCompiler compiler, SqlExecutionContext context) throws SqlException {
        if (columnNamesOf(table, compiler, context).contains(column)) return;
        compiler.compile("alter table " + table + " add column " + column + " " + columnType, context);
    }

    private List<String> columnNamesOf(String tableName, SqlCompiler compiler, SqlExecutionContext context) throws SqlException {
        List<String> columns = new ArrayList<>();
        try (RecordCursorFactory factory = compiler.compile("show columns from " + tableName, context).getRecordCursorFactory()) {
            try (RecordCursor cursor = factory.getCursor(context)) {
                Record record = cursor.getRecord();
                while (cursor.hasNext()) {
                    columns.add(record.getStr(0).toString());
                }
            }
        }
        return columns;
    }

    private long adjustIfRecent(long timestamp, long highestTimestampAdded) {
        if (timestamp >= highestTimestampAdded) return timestamp;

        // We cannot add old data to QuestDb, but we want to use all recent information
        long oneMinute = 60 * 1000;
        if (timestamp >= highestTimestampAdded - oneMinute) return highestTimestampAdded;

        // Too old; discard
        return timestamp;
    }

    private ListMap<String, MetricSnapshot> getSnapshots(Instant startTime,
                                                         Set<String> hostnames,
                                                         SqlCompiler compiler,
                                                         SqlExecutionContext context)  throws SqlException {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of("UTC"));
        String from = formatter.format(startTime).substring(0, 19) + ".000000Z";
        String to = formatter.format(clock.instant()).substring(0, 19) + ".000000Z";
        String sql = "select * from " + table + " where at in('" + from + "', '" + to + "');";

        // WHERE clauses does not work:
        // String sql = "select * from " + tableName + " where hostname in('host1', 'host2', 'host3');";

        try (RecordCursorFactory factory = compiler.compile(sql, context).getRecordCursorFactory()) {
            ListMap<String, MetricSnapshot> snapshots = new ListMap<>();
            try (RecordCursor cursor = factory.getCursor(context)) {
                Record record = cursor.getRecord();
                while (cursor.hasNext()) {
                    String hostname = record.getStr(0).toString();
                    if (hostnames.contains(hostname)) {
                        snapshots.put(hostname,
                                      new MetricSnapshot(Instant.ofEpochMilli(record.getTimestamp(1) / 1000),
                                                         record.getFloat(2),
                                                         record.getFloat(3),
                                                         record.getFloat(4),
                                                         record.getLong(5),
                                                         record.getBool(6),
                                                         record.getBool(7),
                                                         record.getFloat(8)));
                    }
                }
            }
            return snapshots;
        }
    }

    private SqlExecutionContext newContext() {
        return new SqlExecutionContextImpl(engine, 1);
    }

}
