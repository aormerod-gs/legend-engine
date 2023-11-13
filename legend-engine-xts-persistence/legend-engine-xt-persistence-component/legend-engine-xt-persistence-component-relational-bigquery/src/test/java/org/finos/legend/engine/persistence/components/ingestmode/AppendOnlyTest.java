// Copyright 2023 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.persistence.components.ingestmode;

import org.finos.legend.engine.persistence.components.common.StatisticName;
import org.finos.legend.engine.persistence.components.relational.RelationalSink;
import org.finos.legend.engine.persistence.components.relational.api.DataSplitRange;
import org.finos.legend.engine.persistence.components.relational.api.GeneratorResult;
import org.finos.legend.engine.persistence.components.relational.bigquery.BigQuerySink;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;

public class AppendOnlyTest extends org.finos.legend.engine.persistence.components.ingestmode.nontemporal.AppendOnlyTest
{

    String incomingRecordCount = "SELECT COUNT(*) as `incomingRecordCount` FROM `mydb`.`staging` as stage";
    String rowsUpdated = "SELECT 0 as `rowsUpdated`";
    String rowsTerminated = "SELECT 0 as `rowsTerminated`";
    String rowsDeleted = "SELECT 0 as `rowsDeleted`";

    @Override
    public RelationalSink getRelationalSink()
    {
        return BigQuerySink.get();
    }

    @Override
    public void verifyAppendOnlyNoAuditingNoDedupNoVersioningNoFilterExistingRecordsDeriveMainSchema(GeneratorResult operations)
    {
        List<String> preActionsSqlList = operations.preActionsSql();
        List<String> milestoningSqlList = operations.ingestSql();

        String insertSql = "INSERT INTO `mydb`.`main` (`id`, `name`, `amount`, `biz_date`, `digest`) " +
                "(SELECT * FROM `mydb`.`staging` as stage)";
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTableCreateQueryWithNoPKs, preActionsSqlList.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedStagingTableCreateQueryWithNoPKs, preActionsSqlList.get(1));
        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));

        // Stats
        Assertions.assertEquals(incomingRecordCount, operations.postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsTerminated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
        Assertions.assertEquals(rowsDeleted, operations.postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertNull(operations.postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
    }


    @Override
    public void verifyAppendOnlyWithAuditingFailOnDuplicatesAllVersionNoFilterExistingRecords(List<GeneratorResult> generatorResults, List<DataSplitRange> dataSplitRanges)
    {
        String insertSql  = "INSERT INTO `mydb`.`main` " +
            "(`id`, `name`, `amount`, `biz_date`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`biz_date`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') " +
            "FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE (stage.`data_split` >= '{DATA_SPLIT_LOWER_BOUND_PLACEHOLDER}') AND (stage.`data_split` <= '{DATA_SPLIT_UPPER_BOUND_PLACEHOLDER}'))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, generatorResults.get(0).preActionsSql().get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTempStagingTablePlusDigestWithCountAndDataSplit, generatorResults.get(0).preActionsSql().get(1));

        Assertions.assertEquals(BigQueryTestArtifacts.expectedTempStagingCleanupQuery, generatorResults.get(0).deduplicationAndVersioningSql().get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedInsertIntoBaseTempStagingPlusDigestWithAllVersionAndFilterDuplicates, generatorResults.get(0).deduplicationAndVersioningSql().get(1));

        Assertions.assertEquals(enrichSqlWithDataSplits(insertSql, dataSplitRanges.get(0)), generatorResults.get(0).ingestSql().get(0));
        Assertions.assertEquals(enrichSqlWithDataSplits(insertSql, dataSplitRanges.get(1)), generatorResults.get(1).ingestSql().get(0));
        Assertions.assertEquals(2, generatorResults.size());

        // Stats
        String incomingRecordCount = "SELECT COALESCE(SUM(stage.`legend_persistence_count`),0) as `incomingRecordCount` FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE (stage.`data_split` >= '{DATA_SPLIT_LOWER_BOUND_PLACEHOLDER}') AND (stage.`data_split` <= '{DATA_SPLIT_UPPER_BOUND_PLACEHOLDER}')";
        String rowsInserted = "SELECT COUNT(*) as `rowsInserted` FROM `mydb`.`main` as sink WHERE sink.`batch_update_time` = (SELECT MAX(sink.`batch_update_time`) FROM `mydb`.`main` as sink)";

        Assertions.assertEquals(enrichSqlWithDataSplits(incomingRecordCount, dataSplitRanges.get(0)), generatorResults.get(0).postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(enrichSqlWithDataSplits(incomingRecordCount, dataSplitRanges.get(1)), generatorResults.get(1).postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, generatorResults.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsDeleted, generatorResults.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertEquals(enrichSqlWithDataSplits(rowsInserted, dataSplitRanges.get(0)), generatorResults.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
        Assertions.assertEquals(rowsTerminated, generatorResults.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
    }

    @Override
    public void verifyAppendOnlyWithAuditingFilterDuplicatesNoVersioningWithFilterExistingRecords(GeneratorResult queries)
    {
        List<String> preActionsSqlList = queries.preActionsSql();
        List<String> milestoningSqlList = queries.ingestSql();
        List<String> deduplicationAndVersioningSql = queries.deduplicationAndVersioningSql();

        String insertSql = "INSERT INTO `mydb`.`main` (`id`, `name`, `amount`, `biz_date`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`biz_date`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE NOT (EXISTS (SELECT * FROM `mydb`.`main` as sink WHERE ((sink.`id` = stage.`id`) AND " +
            "(sink.`name` = stage.`name`)) AND (sink.`digest` = stage.`digest`))))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, preActionsSqlList.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTempStagingTablePlusDigestWithCount, preActionsSqlList.get(1));

        Assertions.assertEquals(BigQueryTestArtifacts.expectedTempStagingCleanupQuery, deduplicationAndVersioningSql.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedInsertIntoBaseTempStagingPlusDigestWithFilterDuplicates, deduplicationAndVersioningSql.get(1));

        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));

        List<String> postActionsSql = queries.postActionsSql();
        List<String> expectedSQL = new ArrayList<>();
        expectedSQL.add(BigQueryTestArtifacts.expectedStagingCleanupQuery);
        assertIfListsAreSameIgnoringOrder(expectedSQL, postActionsSql);

        // Stats
        String incomingRecordCount = "SELECT COUNT(*) as `incomingRecordCount` FROM `mydb`.`staging` as stage";
        String rowsInserted = "SELECT COUNT(*) as `rowsInserted` FROM `mydb`.`main` as sink WHERE sink.`batch_update_time` = (SELECT MAX(sink.`batch_update_time`) FROM `mydb`.`main` as sink)";
        Assertions.assertEquals(incomingRecordCount, queries.postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, queries.postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsDeleted, queries.postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertEquals(rowsInserted, queries.postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
        Assertions.assertEquals(rowsTerminated, queries.postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
    }

    @Override
    public void verifyAppendOnlyWithAuditingFilterDuplicatesAllVersionWithFilterExistingRecords(List<GeneratorResult> operations, List<DataSplitRange> dataSplitRanges)
    {
        String insertSql = "INSERT INTO `mydb`.`main` " +
            "(`id`, `name`, `amount`, `biz_date`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`biz_date`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') " +
            "FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE ((stage.`data_split` >= '{DATA_SPLIT_LOWER_BOUND_PLACEHOLDER}') AND (stage.`data_split` <= '{DATA_SPLIT_UPPER_BOUND_PLACEHOLDER}')) AND " +
            "(NOT (EXISTS (SELECT * FROM `mydb`.`main` as sink " +
            "WHERE ((sink.`id` = stage.`id`) AND (sink.`name` = stage.`name`)) AND " +
            "(sink.`digest` = stage.`digest`)))))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, operations.get(0).preActionsSql().get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTempStagingTablePlusDigestWithCountAndDataSplit, operations.get(0).preActionsSql().get(1));

        Assertions.assertEquals(BigQueryTestArtifacts.expectedTempStagingCleanupQuery, operations.get(0).deduplicationAndVersioningSql().get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedInsertIntoBaseTempStagingPlusDigestWithAllVersionAndFilterDuplicates, operations.get(0).deduplicationAndVersioningSql().get(1));

        Assertions.assertEquals(enrichSqlWithDataSplits(insertSql, dataSplitRanges.get(0)), operations.get(0).ingestSql().get(0));
        Assertions.assertEquals(enrichSqlWithDataSplits(insertSql, dataSplitRanges.get(1)), operations.get(1).ingestSql().get(0));
        Assertions.assertEquals(2, operations.size());

        // Stats
        String incomingRecordCount = "SELECT COALESCE(SUM(stage.`legend_persistence_count`),0) as `incomingRecordCount` FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE (stage.`data_split` >= '{DATA_SPLIT_LOWER_BOUND_PLACEHOLDER}') AND (stage.`data_split` <= '{DATA_SPLIT_UPPER_BOUND_PLACEHOLDER}')";
        String rowsInserted = "SELECT COUNT(*) as `rowsInserted` FROM `mydb`.`main` as sink WHERE sink.`batch_update_time` = (SELECT MAX(sink.`batch_update_time`) FROM `mydb`.`main` as sink)";

        Assertions.assertEquals(enrichSqlWithDataSplits(incomingRecordCount, dataSplitRanges.get(0)), operations.get(0).postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(enrichSqlWithDataSplits(incomingRecordCount, dataSplitRanges.get(1)), operations.get(1).postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, operations.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsDeleted, operations.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertEquals(rowsInserted, operations.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
        Assertions.assertEquals(rowsTerminated, operations.get(0).postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
    }

    @Override
    public void verifyAppendOnlyWithUpperCaseOptimizer(GeneratorResult operations)
    {
        List<String> preActionsSqlList = operations.preActionsSql();
        List<String> milestoningSqlList = operations.ingestSql();

        String insertSql = "INSERT INTO `MYDB`.`MAIN` " +
            "(`ID`, `NAME`, `AMOUNT`, `BIZ_DATE`, `DIGEST`, `BATCH_UPDATE_TIME`) " +
            "(SELECT stage.`ID`,stage.`NAME`,stage.`AMOUNT`,stage.`BIZ_DATE`,stage.`DIGEST`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') FROM `MYDB`.`STAGING_LEGEND_PERSISTENCE_TEMP_STAGING` as stage " +
            "WHERE NOT (EXISTS " +
            "(SELECT * FROM `MYDB`.`MAIN` as sink WHERE ((sink.`ID` = stage.`ID`) AND (sink.`NAME` = stage.`NAME`)) AND (sink.`DIGEST` = stage.`DIGEST`))))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQueryUpperCase, preActionsSqlList.get(0));
        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));
    }

    @Override
    public void verifyAppendOnlyWithLessColumnsInStaging(GeneratorResult operations)
    {
        List<String> preActionsSqlList = operations.preActionsSql();
        List<String> milestoningSqlList = operations.ingestSql();

        String insertSql = "INSERT INTO `mydb`.`main` (`id`, `name`, `amount`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE NOT (EXISTS (SELECT * FROM `mydb`.`main` as sink WHERE ((sink.`id` = stage.`id`) AND " +
            "(sink.`name` = stage.`name`)) AND (sink.`digest` = stage.`digest`))))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, preActionsSqlList.get(0));
        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));
    }

    @Override
    public void verifyAppendOnlyWithAuditingFailOnDuplicatesMaxVersionWithFilterExistingRecords(GeneratorResult operations)
    {
        List<String> preActionsSqlList = operations.preActionsSql();
        List<String> milestoningSqlList = operations.ingestSql();
        List<String> deduplicationAndVersioningSql = operations.deduplicationAndVersioningSql();

        String insertSql = "INSERT INTO `mydb`.`main` (`id`, `name`, `amount`, `biz_date`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`biz_date`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') FROM `mydb`.`staging_legend_persistence_temp_staging` as stage " +
            "WHERE NOT (EXISTS (SELECT * FROM `mydb`.`main` as sink WHERE ((sink.`id` = stage.`id`) AND " +
            "(sink.`name` = stage.`name`)) AND (sink.`digest` = stage.`digest`))))";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, preActionsSqlList.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTempStagingTablePlusDigestWithCount, preActionsSqlList.get(1));

        Assertions.assertEquals(BigQueryTestArtifacts.expectedTempStagingCleanupQuery, deduplicationAndVersioningSql.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedInsertIntoBaseTempStagingPlusDigestWithMaxVersionAndFilterDuplicates, deduplicationAndVersioningSql.get(1));

        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));

        // Stats
        String incomingRecordCount = "SELECT COUNT(*) as `incomingRecordCount` FROM `mydb`.`staging` as stage";
        String rowsInserted = "SELECT COUNT(*) as `rowsInserted` FROM `mydb`.`main` as sink WHERE sink.`batch_update_time` = (SELECT MAX(sink.`batch_update_time`) FROM `mydb`.`main` as sink)";
        Assertions.assertEquals(incomingRecordCount, operations.postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsDeleted, operations.postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertEquals(rowsInserted, operations.postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
        Assertions.assertEquals(rowsTerminated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
    }

    @Override
    public void verifyAppendOnlyWithAuditingFilterDupsMaxVersionNoFilterExistingRecords(GeneratorResult operations)
    {
        List<String> preActionsSqlList = operations.preActionsSql();
        List<String> milestoningSqlList = operations.ingestSql();
        List<String> deduplicationAndVersioningSql = operations.deduplicationAndVersioningSql();

        String insertSql = "INSERT INTO `mydb`.`main` " +
            "(`id`, `name`, `amount`, `biz_date`, `digest`, `batch_update_time`) " +
            "(SELECT stage.`id`,stage.`name`,stage.`amount`,stage.`biz_date`,stage.`digest`,PARSE_DATETIME('%Y-%m-%d %H:%M:%S','2000-01-01 00:00:00.000000') FROM `mydb`.`staging_legend_persistence_temp_staging` as stage)";

        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTablePlusDigestPlusUpdateTimestampCreateQuery, preActionsSqlList.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedBaseTempStagingTablePlusDigestWithCount, preActionsSqlList.get(1));

        Assertions.assertEquals(BigQueryTestArtifacts.expectedTempStagingCleanupQuery, deduplicationAndVersioningSql.get(0));
        Assertions.assertEquals(BigQueryTestArtifacts.expectedInsertIntoBaseTempStagingPlusDigestWithMaxVersionAndFilterDuplicates, deduplicationAndVersioningSql.get(1));

        Assertions.assertEquals(insertSql, milestoningSqlList.get(0));

        // Stats
        String incomingRecordCount = "SELECT COUNT(*) as `incomingRecordCount` FROM `mydb`.`staging` as stage";
        String rowsInserted = "SELECT COUNT(*) as `rowsInserted` FROM `mydb`.`main` as sink WHERE sink.`batch_update_time` = (SELECT MAX(sink.`batch_update_time`) FROM `mydb`.`main` as sink)";
        Assertions.assertEquals(incomingRecordCount, operations.postIngestStatisticsSql().get(StatisticName.INCOMING_RECORD_COUNT));
        Assertions.assertEquals(rowsUpdated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_UPDATED));
        Assertions.assertEquals(rowsDeleted, operations.postIngestStatisticsSql().get(StatisticName.ROWS_DELETED));
        Assertions.assertEquals(rowsInserted, operations.postIngestStatisticsSql().get(StatisticName.ROWS_INSERTED));
        Assertions.assertEquals(rowsTerminated, operations.postIngestStatisticsSql().get(StatisticName.ROWS_TERMINATED));
    }
}
