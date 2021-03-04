// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.yahoo.collections.Pair;
import com.yahoo.io.IOUtils;
import com.yahoo.test.ManualClock;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Tests the Quest metrics db.
 *
 * @author bratseth
 */
public class QuestMetricsDbTest {

    private static final double delta = 0.0000001;

    @Test
    public void testReadWrite() {
        String dataDir = "data/QuestMetricsDbReadWrite";
        IOUtils.recursiveDeleteDir(new File(dataDir));
        IOUtils.createDirectory(dataDir + "/metrics");
        ManualClock clock = new ManualClock("2020-10-01T00:00:00");
        QuestMetricsDb db = new QuestMetricsDb(dataDir, clock);
        Instant startTime = clock.instant();

        clock.advance(Duration.ofSeconds(1));
        db.add(timeseries(1000, Duration.ofSeconds(1), clock, "host1", "host2", "host3"));

        clock.advance(Duration.ofSeconds(1));

        // Read all of one host
        List<NodeTimeseries> nodeTimeSeries1 = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                                    Set.of("host1"));
        assertEquals(1, nodeTimeSeries1.size());
        assertEquals("host1", nodeTimeSeries1.get(0).hostname());
        assertEquals(1000, nodeTimeSeries1.get(0).size());
        MetricSnapshot snapshot = nodeTimeSeries1.get(0).asList().get(0);
        assertEquals(startTime.plus(Duration.ofSeconds(1)), snapshot.at());
        assertEquals(0.1, snapshot.cpu(), delta);
        assertEquals(0.2, snapshot.memory(), delta);
        assertEquals(0.4, snapshot.disk(), delta);
        assertEquals(1, snapshot.generation(), delta);
        assertEquals(30, snapshot.queryRate(), delta);

        // Read all from 2 hosts
        List<NodeTimeseries> nodeTimeSeries2 = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                                    Set.of("host2", "host3"));
        assertEquals(2, nodeTimeSeries2.size());
        assertEquals(Set.of("host2", "host3"), nodeTimeSeries2.stream().map(ts -> ts.hostname()).collect(Collectors.toSet()));
        assertEquals(1000, nodeTimeSeries2.get(0).size());
        assertEquals(1000, nodeTimeSeries2.get(1).size());

        // Read a short interval from 3 hosts
        List<NodeTimeseries> nodeTimeSeries3 = db.getNodeTimeseries(Duration.ofSeconds(3),
                                                                    Set.of("host1", "host2", "host3"));
        assertEquals(3, nodeTimeSeries3.size());
        assertEquals(Set.of("host1", "host2", "host3"), nodeTimeSeries3.stream().map(ts -> ts.hostname()).collect(Collectors.toSet()));
        assertEquals(2, nodeTimeSeries3.get(0).size());
        assertEquals(2, nodeTimeSeries3.get(1).size());
        assertEquals(2, nodeTimeSeries3.get(2).size());
    }

    @Test
    public void testWriteOldData() {
        String dataDir = "data/QuestMetricsDbWriteOldData";
        IOUtils.recursiveDeleteDir(new File(dataDir));
        IOUtils.createDirectory(dataDir + "/metrics");
        ManualClock clock = new ManualClock("2020-10-01T00:00:00");
        QuestMetricsDb db = new QuestMetricsDb(dataDir, clock);
        Instant startTime = clock.instant();
        clock.advance(Duration.ofSeconds(300));
        db.add(timeseriesAt(10, clock.instant(), "host1", "host2", "host3"));
        clock.advance(Duration.ofSeconds(1));

        List<NodeTimeseries> nodeTimeSeries1 = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                                    Set.of("host1"));
        assertEquals(10, nodeTimeSeries1.get(0).size());

        db.add(timeseriesAt(10, clock.instant().minus(Duration.ofSeconds(20)), "host1", "host2", "host3"));
        List<NodeTimeseries> nodeTimeSeries2 = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                                    Set.of("host1"));
        assertEquals("Recent data is accepted", 20, nodeTimeSeries2.get(0).size());

        db.add(timeseriesAt(10, clock.instant().minus(Duration.ofSeconds(200)), "host1", "host2", "host3"));
        List<NodeTimeseries> nodeTimeSeries3 = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                                    Set.of("host1"));
        assertEquals("Too old data is rejected", 20, nodeTimeSeries3.get(0).size());
    }

    @Test
    public void testGc() {
        String dataDir = "data/QuestMetricsDbGc";
        IOUtils.recursiveDeleteDir(new File(dataDir));
        IOUtils.createDirectory(dataDir + "/metrics");
        ManualClock clock = new ManualClock("2020-10-01T00:00:00");
        QuestMetricsDb db = new QuestMetricsDb(dataDir, clock);
        Instant startTime = clock.instant();
        int dayOffset = 3;
        clock.advance(Duration.ofHours(dayOffset));
        db.add(timeseries(24 * 10, Duration.ofHours(1), clock, "host1", "host2", "host3"));

        assertEquals(24 * 10, db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                   Set.of("host1")).get(0).size());
        db.gc();
        assertEquals(48 * 1 + dayOffset, db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                              Set.of("host1")).get(0).size());
        db.gc(); // no-op
        assertEquals(48 * 1 + dayOffset, db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                                              Set.of("host1")).get(0).size());
    }

    /** To manually test that we can read existing data */
    @Ignore
    @Test
    public void testReadingAndAppendingToExistingData() {
        String dataDir = "data/QuestMetricsDbExistingData";
        if ( ! new File(dataDir).exists()) {
            System.out.println("No existing data to check");
            return;
        }
        IOUtils.createDirectory(dataDir + "/metrics");
        ManualClock clock = new ManualClock("2020-10-01T00:00:00");
        clock.advance(Duration.ofSeconds(9)); // Adjust to last data written
        QuestMetricsDb db = new QuestMetricsDb(dataDir, clock);

        List<NodeTimeseries> timeseries = db.getNodeTimeseries(Duration.ofSeconds(9), Set.of("host1"));
        assertFalse("Could read existing data", timeseries.isEmpty());
        assertEquals(10, timeseries.get(0).size());

        System.out.println("Existing data read:");
        for (var snapshot : timeseries.get(0).asList())
            System.out.println("  " + snapshot);

        clock.advance(Duration.ofSeconds(1));
        db.add(timeseries(2, Duration.ofSeconds(1), clock, "host1"));
        System.out.println("New data written and read:");
        timeseries = db.getNodeTimeseries(Duration.ofSeconds(2), Set.of("host1"));
        for (var snapshot : timeseries.get(0).asList())
            System.out.println("  " + snapshot);
    }

    /** To update data for the manual test above */
    @Ignore
    @Test
    public void updateExistingData() {
        String dataDir = "data/QuestMetricsDbExistingData";
        IOUtils.recursiveDeleteDir(new File(dataDir));
        IOUtils.createDirectory(dataDir + "/metrics");
        ManualClock clock = new ManualClock("2020-10-01T00:00:00");
        QuestMetricsDb db = new QuestMetricsDb(dataDir, clock);
        Instant startTime = clock.instant();
        db.add(timeseries(10, Duration.ofSeconds(1), clock, "host1"));

        int added = db.getNodeTimeseries(Duration.between(startTime, clock.instant()),
                                         Set.of("host1")).get(0).asList().size();
        System.out.println("Added " + added + " rows of data");
        db.close();
    }

    private Collection<Pair<String, MetricSnapshot>> timeseries(int countPerHost, Duration sampleRate, ManualClock clock,
                                                                String ... hosts) {
        Collection<Pair<String, MetricSnapshot>> timeseries = new ArrayList<>();
        for (int i = 1; i <= countPerHost; i++) {
            for (String host : hosts)
                timeseries.add(new Pair<>(host, new MetricSnapshot(clock.instant(),
                                                                   i * 0.1,
                                                                   i * 0.2,
                                                                   i * 0.4,
                                                                   i % 100,
                                                                   true,
                                                                   true,
                                                                   30.0)));
            clock.advance(sampleRate);
        }
        return timeseries;
    }

    private Collection<Pair<String, MetricSnapshot>> timeseriesAt(int countPerHost, Instant at, String ... hosts) {
        Collection<Pair<String, MetricSnapshot>> timeseries = new ArrayList<>();
        for (int i = 1; i <= countPerHost; i++) {
            for (String host : hosts)
                timeseries.add(new Pair<>(host, new MetricSnapshot(at,
                                                                   i * 0.1,
                                                                   i * 0.2,
                                                                   i * 0.4,
                                                                   i % 100,
                                                                   true,
                                                                   false,
                                                                   0.0)));
        }
        return timeseries;
    }

}
