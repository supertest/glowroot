/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.storage.repo;

import java.io.IOException;
import java.util.List;
import java.util.zip.DataFormatException;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

@Styles.Private
public class MutableAggregate {

    private double totalDurationNanos;
    private long transactionCount;
    private long errorCount;
    private boolean asyncTransactions;
    private final List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
    private final List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
    private final List<MutableTimer> asyncTimers = Lists.newArrayList();
    private final MutableThreadStats mainThreadStats = new MutableThreadStats();
    private final MutableThreadStats auxThreadStats = new MutableThreadStats();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram durationNanosHistogram = new LazyHistogram();
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull QueryCollector queries;
    private @MonotonicNonNull ServiceCallCollector serviceCalls;
    private @MonotonicNonNull MutableProfile mainThreadProfile;
    private @MonotonicNonNull MutableProfile auxThreadProfile;

    private final int maxAggregateQueriesPerType;
    private final int maxAggregateServiceCallsPerType;

    public MutableAggregate(int maxAggregateQueriesPerType, int maxAggregateServiceCallsPerType) {
        this.maxAggregateQueriesPerType = maxAggregateQueriesPerType;
        this.maxAggregateServiceCallsPerType = maxAggregateServiceCallsPerType;
    }

    public boolean isEmpty() {
        return transactionCount == 0;
    }

    public void addTotalDurationNanos(double totalDurationNanos) {
        this.totalDurationNanos += totalDurationNanos;
    }

    public void addTransactionCount(long transactionCount) {
        this.transactionCount += transactionCount;
    }

    public void addErrorCount(long errorCount) {
        this.errorCount += errorCount;
    }

    public void addAsyncTransactions(boolean asyncTransactions) {
        if (asyncTransactions) {
            this.asyncTransactions = true;
        }
    }

    public void mergeMainThreadRootTimers(List<Aggregate.Timer> toBeMergedRootTimers) {
        mergeRootTimers(toBeMergedRootTimers, mainThreadRootTimers);
    }

    public void mergeAuxThreadRootTimers(List<Aggregate.Timer> toBeMergedRootTimers) {
        mergeRootTimers(toBeMergedRootTimers, auxThreadRootTimers);
    }

    public void mergeAsyncTimers(List<Aggregate.Timer> toBeMergedRootTimers) {
        mergeRootTimers(toBeMergedRootTimers, asyncTimers);
    }

    public void mergeMainThreadStats(@Nullable Aggregate.ThreadStats threadStats) {
        mainThreadStats.addThreadStats(threadStats);
    }

    public void mergeAuxThreadStats(@Nullable Aggregate.ThreadStats threadStats) {
        auxThreadStats.addThreadStats(threadStats);
    }

    public void mergeDurationNanosHistogram(Aggregate.Histogram toBeMergedDurationNanosHistogram)
            throws DataFormatException {
        durationNanosHistogram.merge(toBeMergedDurationNanosHistogram);
    }

    public Aggregate toAggregate(ScratchBuffer scratchBuffer) throws IOException {
        Aggregate.Builder builder = Aggregate.newBuilder()
                .setTotalDurationNanos(totalDurationNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setAsyncTransactions(asyncTransactions)
                .addAllMainThreadRootTimer(toProto(mainThreadRootTimers))
                .addAllAuxThreadRootTimer(toProto(auxThreadRootTimers))
                .addAllAsyncTimer(toProto(asyncTimers))
                .setDurationNanosHistogram(durationNanosHistogram.toProto(scratchBuffer));
        if (!mainThreadStats.isNA()) {
            builder.setMainThreadStats(mainThreadStats.toProto());
        }
        if (!auxThreadStats.isNA()) {
            builder.setAuxThreadStats(auxThreadStats.toProto());
        }
        if (queries != null) {
            builder.addAllQueriesByType(queries.toProto());
        }
        if (serviceCalls != null) {
            builder.addAllServiceCallsByType(serviceCalls.toProto());
        }
        if (mainThreadProfile != null) {
            builder.setMainThreadProfile(mainThreadProfile.toProto());
        }
        if (auxThreadProfile != null) {
            builder.setAuxThreadProfile(auxThreadProfile.toProto());
        }
        return builder.build();
    }

    public OverviewAggregate toOverviewAggregate(long captureTime) throws IOException {
        ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .asyncTransactions(asyncTransactions)
                .mainThreadRootTimers(toProto(mainThreadRootTimers))
                .auxThreadRootTimers(toProto(auxThreadRootTimers))
                .asyncTimers(toProto(asyncTimers));
        if (!mainThreadStats.isNA()) {
            builder.mainThreadStats(mainThreadStats.toProto());
        }
        if (!auxThreadStats.isNA()) {
            builder.auxThreadStats(auxThreadStats.toProto());
        }
        return builder.build();
    }

    public PercentileAggregate toPercentileAggregate(long captureTime) throws IOException {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .durationNanosHistogram(durationNanosHistogram.toProto(new ScratchBuffer()))
                .build();
    }

    public void mergeQueries(List<Aggregate.QueriesByType> toBeMergedQueries) throws IOException {
        if (queries == null) {
            queries = new QueryCollector(maxAggregateQueriesPerType, 0);
        }
        queries.mergeQueries(toBeMergedQueries);
    }

    public void mergeServiceCalls(List<Aggregate.ServiceCallsByType> toBeMergedServiceCalls)
            throws IOException {
        if (serviceCalls == null) {
            serviceCalls = new ServiceCallCollector(maxAggregateServiceCallsPerType, 0);
        }
        serviceCalls.mergeServiceCalls(toBeMergedServiceCalls);
    }

    public void mergeMainThreadProfile(Profile toBeMergedProfile) throws IOException {
        if (mainThreadProfile == null) {
            mainThreadProfile = new MutableProfile();
        }
        mainThreadProfile.merge(toBeMergedProfile);
    }

    public void mergeAuxThreadProfile(Profile toBeMergedProfile) throws IOException {
        if (auxThreadProfile == null) {
            auxThreadProfile = new MutableProfile();
        }
        auxThreadProfile.merge(toBeMergedProfile);
    }

    public static void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers,
            List<MutableTimer> rootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer, rootTimers);
        }
    }

    public static List<Aggregate.Timer> toProto(List<MutableTimer> rootTimers) {
        List<Aggregate.Timer> protobufRootTimers =
                Lists.newArrayListWithCapacity(rootTimers.size());
        for (MutableTimer rootTimer : rootTimers) {
            protobufRootTimers.add(rootTimer.toProto());
        }
        return protobufRootTimers;
    }

    private static void mergeRootTimer(Aggregate.Timer toBeMergedRootTimer,
            List<MutableTimer> rootTimers) {
        for (MutableTimer rootTimer : rootTimers) {
            if (toBeMergedRootTimer.getName().equals(rootTimer.getName())) {
                rootTimer.merge(toBeMergedRootTimer);
                return;
            }
        }
        MutableTimer rootTimer = MutableTimer.createRootTimer(toBeMergedRootTimer.getName(),
                toBeMergedRootTimer.getExtended());
        rootTimer.merge(toBeMergedRootTimer);
        rootTimers.add(rootTimer);
    }
}
