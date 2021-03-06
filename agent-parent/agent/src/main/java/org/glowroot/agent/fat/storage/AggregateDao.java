/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.agent.fat.storage.util.CappedDatabase;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcQuery;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcUpdate;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.ImmutableIndex;
import org.glowroot.agent.fat.storage.util.RowMappers;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;
import org.glowroot.agent.fat.storage.util.Schemas.Index;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.config.ConfigDefaults;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.ServiceCallsByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Timer;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castUntainted;

class AggregateDao implements AggregateRepository {

    private static final String AGENT_ID = "";

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_duration_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("async_transactions", ColumnType.BOOLEAN),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("service_calls_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("async_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("main_thread_stats", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_stats", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("duration_nanos_histogram", ColumnType.VARBINARY)); // protobuf

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_duration_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("async_transactions", ColumnType.BOOLEAN),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("service_calls_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("async_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("main_thread_stats", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_stats", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("duration_nanos_histogram", ColumnType.VARBINARY)); // protobuf

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "total_duration_nanos",
                    "transaction_count", "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name",
                    "total_duration_nanos", "transaction_count", "error_count");

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final ConfigRepository configRepository;
    private final TransactionTypeDao transactionTypeDao;

    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            ConfigRepository configRepository, TransactionTypeDao transactionTypeDao)
            throws Exception {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.configRepository = configRepository;
        this.transactionTypeDao = transactionTypeDao;

        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            String overallTableName = "aggregate_tt_rollup_" + castUntainted(i);
            dataSource.renameColumn(overallTableName, "async_thread_profile_capped_id",
                    "aux_thread_profile_capped_id");
            dataSource.syncTable(overallTableName, overallAggregatePointColumns);
            dataSource.syncIndexes(overallTableName, ImmutableList.<Index>of(
                    ImmutableIndex.of(overallTableName + "_idx", overallAggregateIndexColumns)));
            String transactionTableName = "aggregate_tn_rollup_" + castUntainted(i);
            dataSource.renameColumn(transactionTableName, "async_thread_profile_capped_id",
                    "aux_thread_profile_capped_id");
            dataSource.syncTable(transactionTableName, transactionAggregateColumns);
            dataSource.syncIndexes(transactionTableName, ImmutableList.<Index>of(ImmutableIndex
                    .of(transactionTableName + "_idx", transactionAggregateIndexColumns)));
        }

        // don't need last_rollup_times table like in GaugeValueDao since there is already index
        // on capture_time so these queries are relatively fast
        long[] lastRollupTimes = new long[rollupConfigs.size()];
        lastRollupTimes[0] = 0;
        for (int i = 1; i < lastRollupTimes.length; i++) {
            lastRollupTimes[i] = dataSource.queryForLong("select ifnull(max(capture_time), 0)"
                    + " from aggregate_tt_rollup_" + castUntainted(i));
        }
        this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public void store(String agentId, long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        for (AggregatesByType aggregatesByType1 : aggregatesByType) {
            String transactionType = aggregatesByType1.getTransactionType();

            dataSource.update(new AggregateInsert(transactionType, null, captureTime,
                    aggregatesByType1.getOverallAggregate(), 0));
            transactionTypeDao.updateLastCaptureTime(transactionType, captureTime);

            for (TransactionAggregate transactionAggregate : aggregatesByType1
                    .getTransactionAggregateList()) {
                dataSource.update(new AggregateInsert(transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), 0));
            }
        }
        synchronized (rollupLock) {
            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                RollupConfig rollupConfig = rollupConfigs.get(i);
                long safeRollupTime = RollupLevelService.getSafeRollupTime(captureTime,
                        rollupConfig.intervalMillis());
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, rollupConfig.intervalMillis(), i, i - 1);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInOverallSummary(String agentRollup, OverallQuery query,
            OverallSummaryCollector collector) throws Exception {
        dataSource.query(new OverallSummaryQuery(collector, query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionSummaries(String agentRollup, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector)
            throws Exception {
        dataSource.query(
                new TransactionSummaryQuery(query, sortOrder, limit, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInOverallErrorSummary(String agentRollup, OverallQuery query,
            OverallErrorSummaryCollector collector) throws Exception {
        dataSource.query(new OverallErrorSummaryQuery(collector, query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionErrorSummaries(String agentRollup, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit, TransactionErrorSummaryCollector collector)
            throws Exception {
        dataSource.query(new TransactionErrorSummaryQuery(query, sortOrder, limit,
                collector));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollup,
            TransactionQuery query) throws Exception {
        return dataSource.query(new OverviewAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollup,
            TransactionQuery query) throws Exception {
        return dataSource.query(new PercentileAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollup,
            TransactionQuery query) throws Exception {
        return dataSource.query(new ThroughputAggregateQuery(query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInQueries(String agentRollup, TransactionQuery query, QueryCollector collector)
            throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery("queries_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessages(cappedId.cappedId(), Aggregate.QueriesByType.parser());
            if (queries != null) {
                collector.mergeQueries(queries);
                collector.updateLastCaptureTime(captureTime);
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInServiceCalls(String agentRollup, TransactionQuery query,
            ServiceCallCollector collector) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds =
                dataSource.query(new CappedIdQuery("service_calls_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Aggregate.ServiceCallsByType> queries =
                    rollupCappedDatabases.get(query.rollupLevel()).readMessages(cappedId.cappedId(),
                            Aggregate.ServiceCallsByType.parser());
            if (queries != null) {
                collector.mergeServiceCalls(queries);
                collector.updateLastCaptureTime(captureTime);
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInMainThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        mergeInProfiles(collector, query, "main_thread_profile_capped_id");
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInAuxThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        mergeInProfiles(collector, query, "aux_thread_profile_capped_id");
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception {
        return !dataSource.query(new CappedIdQuery("aux_thread_profile_capped_id", query))
                .isEmpty();
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception {
        return dataSource
                .query(new ShouldHaveSomethingQuery(query, "main_thread_profile_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception {
        return dataSource
                .query(new ShouldHaveSomethingQuery(query, "aux_thread_profile_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollup, TransactionQuery query) throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "queries_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollup, TransactionQuery query)
            throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "service_calls_capped_id"));
    }

    void deleteBefore(long captureTime, int rollupLevel) throws Exception {
        dataSource.deleteBefore("aggregate_tt_rollup_" + castUntainted(rollupLevel), captureTime);
        dataSource.deleteBefore("aggregate_tn_rollup_" + castUntainted(rollupLevel), captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        List<Long> rollupTimes = dataSource.query(new RollupTimeRowMapper(fromRollupLevel,
                fixedIntervalMillis, lastRollupTime, curentRollupTime));
        for (Long rollupTime : rollupTimes) {
            dataSource.query(new RollupOverallAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
            dataSource.query(new RollupTransactionAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
        }
    }

    private void mergeInProfiles(ProfileCollector collector, TransactionQuery query,
            @Untainted String cappedIdColumnName) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery(cappedIdColumnName, query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            Profile profile = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessage(cappedId.cappedId(), Profile.parser());
            if (profile != null) {
                collector.mergeProfile(profile);
                collector.updateLastCaptureTime(captureTime);
            }
        }
    }

    private void merge(MutableAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex,
            int fromRollupLevel) throws Exception {
        int i = startColumnIndex;
        double totalDurationNanos = resultSet.getDouble(i++);
        long transactionCount = resultSet.getLong(i++);
        long errorCount = resultSet.getLong(i++);
        boolean asyncTransactions = resultSet.getBoolean(i++);
        Long queriesCappedId = RowMappers.getLong(resultSet, i++);
        Long serviceCallsCappedId = RowMappers.getLong(resultSet, i++);
        Long mainThreadProfileCappedId = RowMappers.getLong(resultSet, i++);
        Long auxThreadProfileCappedId = RowMappers.getLong(resultSet, i++);
        byte[] mainThreadRootTimers = resultSet.getBytes(i++);
        byte[] auxThreadRootTimers = resultSet.getBytes(i++);
        byte[] asyncTimers = resultSet.getBytes(i++);
        byte[] mainThreadStats = resultSet.getBytes(i++);
        byte[] auxThreadStats = resultSet.getBytes(i++);
        byte[] durationNanosHistogram = checkNotNull(resultSet.getBytes(i++));

        mergedAggregate.addTotalDurationNanos(totalDurationNanos);
        mergedAggregate.addTransactionCount(transactionCount);
        mergedAggregate.addErrorCount(errorCount);
        mergedAggregate.addAsyncTransactions(asyncTransactions);
        if (mainThreadRootTimers != null) {
            mergedAggregate.mergeMainThreadRootTimers(
                    readMessages(mainThreadRootTimers, Aggregate.Timer.parser()));
        }
        if (auxThreadRootTimers != null) {
            mergedAggregate.mergeAuxThreadRootTimers(
                    readMessages(auxThreadRootTimers, Aggregate.Timer.parser()));
        }
        if (asyncTimers != null) {
            mergedAggregate.mergeAsyncTimers(readMessages(asyncTimers, Aggregate.Timer.parser()));
        }
        if (mainThreadStats == null) {
            mergedAggregate.mergeMainThreadStats(null);
        } else {
            mergedAggregate.mergeMainThreadStats(Aggregate.ThreadStats.parseFrom(mainThreadStats));
        }
        if (auxThreadStats == null) {
            mergedAggregate.mergeAuxThreadStats(null);
        } else {
            mergedAggregate.mergeAuxThreadStats(Aggregate.ThreadStats.parseFrom(auxThreadStats));
        }
        mergedAggregate
                .mergeDurationNanosHistogram(Aggregate.Histogram.parseFrom(durationNanosHistogram));
        if (queriesCappedId != null) {
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessages(queriesCappedId, Aggregate.QueriesByType.parser());
            if (queries != null) {
                mergedAggregate.mergeQueries(queries);
            }
        }
        if (serviceCallsCappedId != null) {
            List<Aggregate.ServiceCallsByType> serviceCalls =
                    rollupCappedDatabases.get(fromRollupLevel).readMessages(serviceCallsCappedId,
                            Aggregate.ServiceCallsByType.parser());
            if (serviceCalls != null) {
                mergedAggregate.mergeServiceCalls(serviceCalls);
            }
        }
        if (mainThreadProfileCappedId != null) {
            Profile mainThreadProfile = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(mainThreadProfileCappedId, Profile.parser());
            if (mainThreadProfile != null) {
                mergedAggregate.mergeMainThreadProfile(mainThreadProfile);
            }
        }
        if (auxThreadProfileCappedId != null) {
            Profile auxThreadProfile = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(auxThreadProfileCappedId, Profile.parser());
            if (auxThreadProfile != null) {
                mergedAggregate.mergeAuxThreadProfile(auxThreadProfile);
            }
        }
    }

    private int getMaxAggregateQueriesPerType() throws IOException {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(AGENT_ID);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateQueriesPerType()) {
            return advancedConfig.getMaxAggregateQueriesPerType().getValue();
        } else {
            return ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_TYPE;
        }
    }

    private int getMaxAggregateServiceCallsPerType() throws IOException {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(AGENT_ID);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            return advancedConfig.getMaxAggregateServiceCallsPerType().getValue();
        } else {
            return ConfigDefaults.MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE;
        }
    }

    private static @Untainted String getTableName(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "aggregate_tt_rollup_" + castUntainted(query.rollupLevel());
        } else {
            return "aggregate_tn_rollup_" + castUntainted(query.rollupLevel());
        }
    }

    private static @Untainted String getTransactionNameCriteria(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "";
        } else {
            return " and transaction_name = ?";
        }
    }

    private static int bindQuery(PreparedStatement preparedStatement, TransactionQuery query)
            throws SQLException {
        int i = 1;
        preparedStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            preparedStatement.setString(i++, transactionName);
        }
        preparedStatement.setLong(i++, query.from());
        preparedStatement.setLong(i++, query.to());
        return i;
    }

    private static <T extends /*@NonNull*/ Object> List<T> readMessages(byte[] bytes,
            Parser<T> parser) throws InvalidProtocolBufferException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        List<T> messages = Lists.newArrayList();
        T message;
        while ((message = parser.parseDelimitedFrom(bais)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private class AggregateInsert implements JdbcUpdate {

        private final String transactionType;
        private final @Nullable String transactionName;
        private final long captureTime;
        private final Aggregate aggregate;
        private final @Nullable Long queriesCappedId;
        private final @Nullable Long serviceCallsCappedId;
        private final @Nullable Long mainThreadProfileCappedId;
        private final @Nullable Long auxThreadProfileCappedId;
        private final byte /*@Nullable*/[] mainThreadRootTimers;
        private final byte /*@Nullable*/[] auxThreadRootTimers;
        private final byte /*@Nullable*/[] asyncTimers;
        private final byte /*@Nullable*/[] mainThreadStats;
        private final byte /*@Nullable*/[] auxThreadStats;
        private final byte[] durationNanosHistogramBytes;

        private final int rollupLevel;

        private AggregateInsert(String transactionType, @Nullable String transactionName,
                long captureTime, Aggregate aggregate, int rollupLevel) throws IOException {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            this.captureTime = captureTime;
            this.aggregate = aggregate;
            this.rollupLevel = rollupLevel;

            List<QueriesByType> queries = aggregate.getQueriesByTypeList();
            if (queries.isEmpty()) {
                queriesCappedId = null;
            } else {
                queriesCappedId = rollupCappedDatabases.get(rollupLevel).writeMessages(queries,
                        RollupCappedDatabaseStats.AGGREGATE_QUERIES);
            }
            List<ServiceCallsByType> serviceCalls = aggregate.getServiceCallsByTypeList();
            if (serviceCalls.isEmpty()) {
                serviceCallsCappedId = null;
            } else {
                serviceCallsCappedId = rollupCappedDatabases.get(rollupLevel).writeMessages(
                        serviceCalls, RollupCappedDatabaseStats.AGGREGATE_SERVICE_CALLS);
            }
            if (aggregate.hasMainThreadProfile()) {
                mainThreadProfileCappedId = rollupCappedDatabases.get(rollupLevel).writeMessage(
                        aggregate.getMainThreadProfile(),
                        RollupCappedDatabaseStats.AGGREGATE_PROFILES);
            } else {
                mainThreadProfileCappedId = null;
            }
            if (aggregate.hasAuxThreadProfile()) {
                auxThreadProfileCappedId = rollupCappedDatabases.get(rollupLevel).writeMessage(
                        aggregate.getAuxThreadProfile(),
                        RollupCappedDatabaseStats.AGGREGATE_PROFILES);
            } else {
                auxThreadProfileCappedId = null;
            }
            List<Timer> mainThreadRootTimers = aggregate.getMainThreadRootTimerList();
            if (mainThreadRootTimers.isEmpty()) {
                this.mainThreadRootTimers = null;
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                for (Timer message : mainThreadRootTimers) {
                    message.writeDelimitedTo(baos);
                }
                this.mainThreadRootTimers = baos.toByteArray();
            }
            List<Timer> auxThreadRootTimers = aggregate.getAuxThreadRootTimerList();
            if (auxThreadRootTimers.isEmpty()) {
                this.auxThreadRootTimers = null;
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                for (Timer message : auxThreadRootTimers) {
                    message.writeDelimitedTo(baos);
                }
                this.auxThreadRootTimers = baos.toByteArray();
            }
            List<Aggregate.Timer> asyncTimers = aggregate.getAsyncTimerList();
            if (asyncTimers.isEmpty()) {
                this.asyncTimers = null;
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                for (Timer message : asyncTimers) {
                    message.writeDelimitedTo(baos);
                }
                this.asyncTimers = baos.toByteArray();
            }
            if (aggregate.hasMainThreadStats()) {
                this.mainThreadStats = aggregate.getMainThreadStats().toByteArray();
            } else {
                this.mainThreadStats = null;
            }
            if (aggregate.hasAuxThreadStats()) {
                this.auxThreadStats = aggregate.getAuxThreadStats().toByteArray();
            } else {
                this.auxThreadStats = null;
            }
            durationNanosHistogramBytes = aggregate.getDurationNanosHistogram().toByteArray();
        }

        @Override
        public @Untainted String getSql() {
            StringBuilder sb = new StringBuilder();
            sb.append("merge into aggregate_");
            if (transactionName != null) {
                sb.append("tn_");
            } else {
                sb.append("tt_");
            }
            sb.append("rollup_");
            sb.append(castUntainted(rollupLevel));
            sb.append(" (transaction_type,");
            if (transactionName != null) {
                sb.append(" transaction_name,");
            }
            sb.append(" capture_time, total_duration_nanos, transaction_count, error_count,"
                    + " async_transactions, queries_capped_id, service_calls_capped_id,"
                    + " main_thread_profile_capped_id, aux_thread_profile_capped_id,"
                    + " main_thread_root_timers, aux_thread_root_timers, async_root_timers,"
                    + " main_thread_stats, aux_thread_stats, duration_nanos_histogram) key"
                    + " (transaction_type");
            if (transactionName != null) {
                sb.append(", transaction_name");
            }
            sb.append(", capture_time) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
            if (transactionName != null) {
                sb.append(", ?");
            }
            sb.append(")");
            return castUntainted(sb.toString());
        }

        // minimal work inside this method as it is called with active connection
        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setString(i++, transactionType);
            if (transactionName != null) {
                preparedStatement.setString(i++, transactionName);
            }
            preparedStatement.setLong(i++, captureTime);
            preparedStatement.setDouble(i++, aggregate.getTotalDurationNanos());
            preparedStatement.setLong(i++, aggregate.getTransactionCount());
            preparedStatement.setLong(i++, aggregate.getErrorCount());
            preparedStatement.setBoolean(i++, aggregate.getAsyncTransactions());
            RowMappers.setLong(preparedStatement, i++, queriesCappedId);
            RowMappers.setLong(preparedStatement, i++, serviceCallsCappedId);
            RowMappers.setLong(preparedStatement, i++, mainThreadProfileCappedId);
            RowMappers.setLong(preparedStatement, i++, auxThreadProfileCappedId);
            if (mainThreadRootTimers == null) {
                preparedStatement.setNull(i++, Types.VARBINARY);
            } else {
                preparedStatement.setBytes(i++, mainThreadRootTimers);
            }
            if (auxThreadRootTimers == null) {
                preparedStatement.setNull(i++, Types.VARBINARY);
            } else {
                preparedStatement.setBytes(i++, auxThreadRootTimers);
            }
            if (asyncTimers == null) {
                preparedStatement.setNull(i++, Types.VARBINARY);
            } else {
                preparedStatement.setBytes(i++, asyncTimers);
            }
            if (mainThreadStats == null) {
                preparedStatement.setNull(i++, Types.VARBINARY);
            } else {
                preparedStatement.setBytes(i++, mainThreadStats);
            }
            if (auxThreadStats == null) {
                preparedStatement.setNull(i++, Types.VARBINARY);
            } else {
                preparedStatement.setBytes(i++, auxThreadStats);
            }
            preparedStatement.setBytes(i++, durationNanosHistogramBytes);
        }
    }

    private static class OverallSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallSummaryCollector collector;
        private final OverallQuery query;

        private OverallSummaryQuery(OverallSummaryCollector collector, OverallQuery query) {
            this.collector = collector;
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            return "select sum(total_duration_nanos), sum(transaction_count), max(capture_time)"
                    + " from aggregate_tt_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            double totalDurationNanos = resultSet.getDouble(1);
            long transactionCount = resultSet.getLong(2);
            long captureTime = resultSet.getLong(3);
            collector.mergeSummary(totalDurationNanos, transactionCount, captureTime);
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class TransactionSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallQuery query;
        private final SummarySortOrder sortOrder;
        private final int limit;

        private final TransactionSummaryCollector collector;

        private TransactionSummaryQuery(OverallQuery query, SummarySortOrder sortOrder, int limit,
                TransactionSummaryCollector collector) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.collector = collector;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(total_duration_nanos), sum(transaction_count),"
                    + " max(capture_time) from aggregate_tn_rollup_");
            sb.append(query.rollupLevel());
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(4, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                String transactionName = checkNotNull(resultSet.getString(1));
                double totalDurationNanos = resultSet.getDouble(2);
                long transactionCount = resultSet.getLong(3);
                long maxCaptureTime = resultSet.getLong(4);
                collector.collect(transactionName, totalDurationNanos,
                        transactionCount, maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }

        private @Untainted String getSortClause(SummarySortOrder sortOrder) {
            switch (sortOrder) {
                case TOTAL_TIME:
                    return "sum(total_duration_nanos) desc";
                case AVERAGE_TIME:
                    return "sum(total_duration_nanos) / sum(transaction_count) desc";
                case THROUGHPUT:
                    return "sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }
    }

    private static class OverallErrorSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallErrorSummaryCollector collector;
        private final OverallQuery query;

        private OverallErrorSummaryQuery(OverallErrorSummaryCollector collector,
                OverallQuery query) {
            this.collector = collector;
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            return "select sum(error_count), sum(transaction_count), max(capture_time)"
                    + " from aggregate_tt_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            long errorCount = resultSet.getLong(1);
            long transactionCount = resultSet.getLong(2);
            long captureTime = resultSet.getLong(3);
            collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class TransactionErrorSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallQuery query;
        private final ErrorSummarySortOrder sortOrder;
        private final int limit;

        private final TransactionErrorSummaryCollector collector;

        private TransactionErrorSummaryQuery(OverallQuery query, ErrorSummarySortOrder sortOrder,
                int limit, TransactionErrorSummaryCollector collector) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.collector = collector;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(error_count), sum(transaction_count),");
            sb.append(" max(capture_time) from aggregate_tn_rollup_");
            sb.append(castUntainted(query.rollupLevel()));
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name having sum(error_count) > 0 order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(4, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                String transactionName = checkNotNull(resultSet.getString(1));
                long errorCount = resultSet.getLong(2);
                long transactionCount = resultSet.getLong(3);
                long maxCaptureTime = resultSet.getLong(4);
                collector.collect(transactionName, errorCount,
                        transactionCount, maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }

        private @Untainted String getSortClause(ErrorSummarySortOrder sortOrder) {
            switch (sortOrder) {
                case ERROR_COUNT:
                    return "sum(error_count) desc";
                case ERROR_RATE:
                    return "sum(error_count) / sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }

    }

    private static class OverviewAggregateQuery implements JdbcRowQuery<OverviewAggregate> {

        private final TransactionQuery query;

        private OverviewAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_duration_nanos, transaction_count,"
                    + " async_transactions, main_thread_root_timers, aux_thread_root_timers,"
                    + " async_root_timers, main_thread_stats, aux_thread_stats from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public OverviewAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalDurationNanos(resultSet.getDouble(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .asyncTransactions(resultSet.getBoolean(i++));
            byte[] mainThreadRootTimers = resultSet.getBytes(i++);
            if (mainThreadRootTimers != null) {
                builder.mainThreadRootTimers(
                        readMessages(mainThreadRootTimers, Aggregate.Timer.parser()));
            }
            byte[] auxThreadRootTimers = resultSet.getBytes(i++);
            if (auxThreadRootTimers != null) {
                builder.auxThreadRootTimers(
                        readMessages(auxThreadRootTimers, Aggregate.Timer.parser()));
            }
            byte[] asyncTimers = resultSet.getBytes(i++);
            if (asyncTimers != null) {
                builder.asyncTimers(readMessages(asyncTimers, Aggregate.Timer.parser()));
            }
            byte[] mainThreadStats = resultSet.getBytes(i++);
            if (mainThreadStats != null) {
                builder.mainThreadStats(Aggregate.ThreadStats.parseFrom(mainThreadStats));
            }
            byte[] auxThreadStats = resultSet.getBytes(i++);
            if (auxThreadStats != null) {
                builder.auxThreadStats(Aggregate.ThreadStats.parseFrom(auxThreadStats));
            }
            return builder.build();
        }
    }

    private static class PercentileAggregateQuery implements JdbcRowQuery<PercentileAggregate> {

        private final TransactionQuery query;

        private PercentileAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_duration_nanos, transaction_count,"
                    + " duration_nanos_histogram from " + tableName + " where transaction_type = ?"
                    + transactionNameCriteria + " and capture_time >= ? and capture_time <= ?"
                    + " order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public PercentileAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutablePercentileAggregate.Builder builder = ImmutablePercentileAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalDurationNanos(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++));
            byte[] durationNanosHistogram = checkNotNull(resultSet.getBytes(i++));
            builder.durationNanosHistogram(
                    Aggregate.Histogram.parser().parseFrom(durationNanosHistogram));
            return builder.build();
        }
    }

    private static class ThroughputAggregateQuery implements JdbcRowQuery<ThroughputAggregate> {

        private final TransactionQuery query;

        private ThroughputAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, transaction_count from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public ThroughputAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            return ImmutableThroughputAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .build();
        }
    }

    private class RollupOverallAggregates implements JdbcQuery</*@Nullable*/ Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupOverallAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, total_duration_nanos, transaction_count, error_count,"
                    + " async_transactions, queries_capped_id, service_calls_capped_id,"
                    + " main_thread_profile_capped_id, aux_thread_profile_capped_id,"
                    + " main_thread_root_timers, aux_thread_root_timers, async_root_timers,"
                    + " main_thread_stats, aux_thread_stats, duration_nanos_histogram"
                    + " from aggregate_tt_rollup_" + castUntainted(fromRollupLevel)
                    + " where capture_time > ? and capture_time <= ? order by transaction_type";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType();
            int maxAggregateServiceCallsPerType = getMaxAggregateServiceCallsPerType();
            MutableOverallAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                if (curr == null || !transactionType.equals(curr.transactionType())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(), null,
                                rollupCaptureTime, curr.aggregate().toAggregate(scratchBuffer),
                                toRollupLevel));
                    }
                    curr = ImmutableMutableOverallAggregate.of(transactionType,
                            new MutableAggregate(maxAggregateQueriesPerType,
                                    maxAggregateServiceCallsPerType));
                }
                merge(curr.aggregate(), resultSet, 2, fromRollupLevel);
            }
            if (curr != null) {
                dataSource
                        .update(new AggregateInsert(curr.transactionType(), null, rollupCaptureTime,
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class RollupTransactionAggregates implements JdbcQuery</*@Nullable*/ Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupTransactionAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, transaction_name, total_duration_nanos,"
                    + " transaction_count, error_count, async_transactions, queries_capped_id,"
                    + " service_calls_capped_id, main_thread_profile_capped_id,"
                    + " aux_thread_profile_capped_id, main_thread_root_timers,"
                    + " aux_thread_root_timers, async_root_timers, main_thread_stats,"
                    + " aux_thread_stats, duration_nanos_histogram from aggregate_tn_rollup_"
                    + castUntainted(fromRollupLevel) + " where capture_time > ?"
                    + " and capture_time <= ? order by transaction_type, transaction_name";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType();
            int maxAggregateServiceCallsPerType = getMaxAggregateServiceCallsPerType();
            MutableTransactionAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                String transactionName = checkNotNull(resultSet.getString(2));
                if (curr == null || !transactionType.equals(curr.transactionType())
                        || !transactionName.equals(curr.transactionName())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(),
                                curr.transactionName(), rollupCaptureTime,
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
                    }
                    curr = ImmutableMutableTransactionAggregate.of(transactionType, transactionName,
                            new MutableAggregate(maxAggregateQueriesPerType,
                                    maxAggregateServiceCallsPerType));
                }
                merge(curr.aggregate(), resultSet, 3, fromRollupLevel);
            }
            if (curr != null) {
                dataSource.update(new AggregateInsert(curr.transactionType(),
                        curr.transactionName(), rollupCaptureTime,
                        curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class CappedIdQuery implements JdbcQuery<List<CappedId>> {

        private final @Untainted String cappedIdColumnName;
        private final TransactionQuery query;
        private final long smallestNonExpiredCappedId;

        private CappedIdQuery(@Untainted String cappedIdColumnName, TransactionQuery query) {
            this.cappedIdColumnName = cappedIdColumnName;
            this.query = query;
            smallestNonExpiredCappedId =
                    rollupCappedDatabases.get(query.rollupLevel()).getSmallestNonExpiredId();
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, " + cappedIdColumnName + " from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time > ? and capture_time <= ? and " + cappedIdColumnName
                    + " >= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = bindQuery(preparedStatement, query);
            preparedStatement.setLong(i++, smallestNonExpiredCappedId);
        }

        @Override
        public List<CappedId> processResultSet(ResultSet resultSet) throws Exception {
            List<CappedId> cappedIds = Lists.newArrayList();
            while (resultSet.next()) {
                cappedIds.add(ImmutableCappedId.of(resultSet.getLong(1), resultSet.getLong(2)));
            }
            return cappedIds;
        }

        @Override
        public List<CappedId> valueIfDataSourceClosing() {
            return ImmutableList.of();
        }
    }

    private static class ShouldHaveSomethingQuery implements JdbcQuery<Boolean> {

        private final TransactionQuery query;
        private final @Untainted String cappedIdColumnName;

        private ShouldHaveSomethingQuery(TransactionQuery query,
                @Untainted String cappedIdColumnName) {
            this.query = query;
            this.cappedIdColumnName = cappedIdColumnName;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select 1 from " + tableName + " where transaction_type = ?"
                    + transactionNameCriteria + " and capture_time > ? and capture_time <= ?"
                    + " and " + cappedIdColumnName + " is not null limit 1";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            String transactionName = query.transactionName();
            if (transactionName != null) {
                preparedStatement.setString(i++, transactionName);
            }
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
        }

        @Override
        public Boolean processResultSet(ResultSet resultSet) throws Exception {
            return resultSet.next();
        }

        @Override
        public Boolean valueIfDataSourceClosing() {
            return false;
        }
    }

    private static class RollupTimeRowMapper implements JdbcRowQuery<Long> {

        private final int rollupLevel;
        private final long fixedIntervalMillis;
        private final long lastRollupTime;
        private final long curentRollupTime;

        private RollupTimeRowMapper(int rollupLevel, long fixedIntervalMillis, long lastRollupTime,
                long curentRollupTime) {
            this.rollupLevel = rollupLevel;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.lastRollupTime = lastRollupTime;
            this.curentRollupTime = curentRollupTime;
        }

        @Override
        public @Untainted String getSql() {
            // need ".0" to force double result
            String captureTimeSql = castUntainted(
                    "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
            return "select distinct " + captureTimeSql + " from aggregate_tt_rollup_"
                    + castUntainted(rollupLevel) + " where capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, lastRollupTime);
            preparedStatement.setLong(2, curentRollupTime);
        }

        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CappedId {
        long captureTime();
        long cappedId();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableOverallAggregate {
        String transactionType();
        MutableAggregate aggregate();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableTransactionAggregate {
        String transactionType();
        String transactionName();
        MutableAggregate aggregate();
    }
}
