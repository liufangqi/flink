/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.core.io.InputSplitSource;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.scheduler.SchedulerBase;
import org.apache.flink.runtime.scheduler.strategy.ConsumedPartitionGroup;

import org.apache.flink.shaded.guava30.com.google.common.collect.Sets;

import org.junit.Test;
import org.mockito.Matchers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This class contains test concerning the correct conversion from {@link JobGraph} to {@link
 * ExecutionGraph} objects. It also tests that {@link EdgeManagerBuildUtil#connectVertexToResult}
 * builds {@link DistributionPattern#ALL_TO_ALL} connections correctly.
 */
public class DefaultExecutionGraphConstructionTest {

    private ExecutionGraph createDefaultExecutionGraph(List<JobVertex> vertices) throws Exception {
        return TestingDefaultExecutionGraphBuilder.newBuilder()
                .setVertexParallelismStore(SchedulerBase.computeVertexParallelismStore(vertices))
                .build();
    }

    @Test
    public void testExecutionAttemptIdInTwoIdenticalJobsIsNotSame() throws Exception {
        JobVertex v1 = new JobVertex("vertex1");
        JobVertex v2 = new JobVertex("vertex2");
        JobVertex v3 = new JobVertex("vertex3");

        v1.setParallelism(5);
        v2.setParallelism(7);
        v3.setParallelism(2);

        v1.setInvokableClass(AbstractInvokable.class);
        v2.setInvokableClass(AbstractInvokable.class);
        v3.setInvokableClass(AbstractInvokable.class);

        List<JobVertex> ordered = new ArrayList<>(Arrays.asList(v1, v2, v3));

        ExecutionGraph eg1 = createDefaultExecutionGraph(ordered);
        ExecutionGraph eg2 = createDefaultExecutionGraph(ordered);
        eg1.attachJobGraph(ordered);
        eg2.attachJobGraph(ordered);

        assertThat(
                Sets.intersection(
                        eg1.getRegisteredExecutions().keySet(),
                        eg2.getRegisteredExecutions().keySet()),
                is(empty()));
    }

    /**
     * Creates a JobGraph of the following form.
     *
     * <pre>
     *  v1--->v2-->\
     *              \
     *               v4 --->\
     *        ----->/        \
     *  v3-->/                v5
     *       \               /
     *        ------------->/
     * </pre>
     */
    @Test
    public void testCreateSimpleGraphBipartite() throws Exception {
        JobVertex v1 = new JobVertex("vertex1");
        JobVertex v2 = new JobVertex("vertex2");
        JobVertex v3 = new JobVertex("vertex3");
        JobVertex v4 = new JobVertex("vertex4");
        JobVertex v5 = new JobVertex("vertex5");

        v1.setParallelism(5);
        v2.setParallelism(7);
        v3.setParallelism(2);
        v4.setParallelism(11);
        v5.setParallelism(4);

        v1.setInvokableClass(AbstractInvokable.class);
        v2.setInvokableClass(AbstractInvokable.class);
        v3.setInvokableClass(AbstractInvokable.class);
        v4.setInvokableClass(AbstractInvokable.class);
        v5.setInvokableClass(AbstractInvokable.class);

        v2.connectNewDataSetAsInput(
                v1, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v4.connectNewDataSetAsInput(
                v2, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v4.connectNewDataSetAsInput(
                v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v5.connectNewDataSetAsInput(
                v4, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v5.connectNewDataSetAsInput(
                v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);

        List<JobVertex> ordered = new ArrayList<JobVertex>(Arrays.asList(v1, v2, v3, v4, v5));

        ExecutionGraph eg = createDefaultExecutionGraph(ordered);
        try {
            eg.attachJobGraph(ordered);
        } catch (JobException e) {
            e.printStackTrace();
            fail("Job failed with exception: " + e.getMessage());
        }

        verifyTestGraph(eg, v1, v2, v3, v4, v5);
    }

    private void verifyTestGraph(
            ExecutionGraph eg,
            JobVertex v1,
            JobVertex v2,
            JobVertex v3,
            JobVertex v4,
            JobVertex v5) {
        ExecutionGraphTestUtils.verifyGeneratedExecutionJobVertex(
                eg, v1, null, Collections.singletonList(v2));
        ExecutionGraphTestUtils.verifyGeneratedExecutionJobVertex(
                eg, v2, Collections.singletonList(v1), Collections.singletonList(v4));
        ExecutionGraphTestUtils.verifyGeneratedExecutionJobVertex(
                eg, v3, null, Arrays.asList(v4, v5));
        ExecutionGraphTestUtils.verifyGeneratedExecutionJobVertex(
                eg, v4, Arrays.asList(v2, v3), Collections.singletonList(v5));
        ExecutionGraphTestUtils.verifyGeneratedExecutionJobVertex(
                eg, v5, Arrays.asList(v4, v3), null);
    }

    @Test
    public void testCannotConnectWrongOrder() throws Exception {
        JobVertex v1 = new JobVertex("vertex1");
        JobVertex v2 = new JobVertex("vertex2");
        JobVertex v3 = new JobVertex("vertex3");
        JobVertex v4 = new JobVertex("vertex4");
        JobVertex v5 = new JobVertex("vertex5");

        v1.setParallelism(5);
        v2.setParallelism(7);
        v3.setParallelism(2);
        v4.setParallelism(11);
        v5.setParallelism(4);

        v1.setInvokableClass(AbstractInvokable.class);
        v2.setInvokableClass(AbstractInvokable.class);
        v3.setInvokableClass(AbstractInvokable.class);
        v4.setInvokableClass(AbstractInvokable.class);
        v5.setInvokableClass(AbstractInvokable.class);

        v2.connectNewDataSetAsInput(
                v1, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v4.connectNewDataSetAsInput(
                v2, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v4.connectNewDataSetAsInput(
                v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v5.connectNewDataSetAsInput(
                v4, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
        v5.connectNewDataSetAsInput(
                v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);

        List<JobVertex> ordered = new ArrayList<JobVertex>(Arrays.asList(v1, v2, v3, v5, v4));

        ExecutionGraph eg = createDefaultExecutionGraph(ordered);
        try {
            eg.attachJobGraph(ordered);
            fail("Attached wrong jobgraph");
        } catch (JobException e) {
            // expected
        }
    }

    @Test
    public void testSetupInputSplits() {
        try {
            final InputSplit[] emptySplits = new InputSplit[0];

            InputSplitAssigner assigner1 = mock(InputSplitAssigner.class);
            InputSplitAssigner assigner2 = mock(InputSplitAssigner.class);

            @SuppressWarnings("unchecked")
            InputSplitSource<InputSplit> source1 = mock(InputSplitSource.class);
            @SuppressWarnings("unchecked")
            InputSplitSource<InputSplit> source2 = mock(InputSplitSource.class);

            when(source1.createInputSplits(Matchers.anyInt())).thenReturn(emptySplits);
            when(source2.createInputSplits(Matchers.anyInt())).thenReturn(emptySplits);
            when(source1.getInputSplitAssigner(emptySplits)).thenReturn(assigner1);
            when(source2.getInputSplitAssigner(emptySplits)).thenReturn(assigner2);

            final JobID jobId = new JobID();
            final String jobName = "Test Job Sample Name";
            final Configuration cfg = new Configuration();

            JobVertex v1 = new JobVertex("vertex1");
            JobVertex v2 = new JobVertex("vertex2");
            JobVertex v3 = new JobVertex("vertex3");
            JobVertex v4 = new JobVertex("vertex4");
            JobVertex v5 = new JobVertex("vertex5");

            v1.setParallelism(5);
            v2.setParallelism(7);
            v3.setParallelism(2);
            v4.setParallelism(11);
            v5.setParallelism(4);

            v1.setInvokableClass(AbstractInvokable.class);
            v2.setInvokableClass(AbstractInvokable.class);
            v3.setInvokableClass(AbstractInvokable.class);
            v4.setInvokableClass(AbstractInvokable.class);
            v5.setInvokableClass(AbstractInvokable.class);

            v2.connectNewDataSetAsInput(
                    v1, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
            v4.connectNewDataSetAsInput(
                    v2, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
            v4.connectNewDataSetAsInput(
                    v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
            v5.connectNewDataSetAsInput(
                    v4, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);
            v5.connectNewDataSetAsInput(
                    v3, DistributionPattern.ALL_TO_ALL, ResultPartitionType.PIPELINED);

            v3.setInputSplitSource(source1);
            v5.setInputSplitSource(source2);

            List<JobVertex> ordered = new ArrayList<JobVertex>(Arrays.asList(v1, v2, v3, v4, v5));

            ExecutionGraph eg = createDefaultExecutionGraph(ordered);
            try {
                eg.attachJobGraph(ordered);
            } catch (JobException e) {
                e.printStackTrace();
                fail("Job failed with exception: " + e.getMessage());
            }

            assertEquals(assigner1, eg.getAllVertices().get(v3.getID()).getSplitAssigner());
            assertEquals(assigner2, eg.getAllVertices().get(v5.getID()).getSplitAssigner());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testRegisterConsumedPartitionGroupToEdgeManager() throws Exception {
        JobVertex v1 = new JobVertex("source");
        JobVertex v2 = new JobVertex("sink");

        v1.setParallelism(2);
        v2.setParallelism(2);

        v2.connectNewDataSetAsInput(
                v1, DistributionPattern.ALL_TO_ALL, ResultPartitionType.BLOCKING);

        List<JobVertex> ordered = new ArrayList<>(Arrays.asList(v1, v2));
        ExecutionGraph eg = createDefaultExecutionGraph(ordered);
        eg.attachJobGraph(ordered);

        IntermediateResult result =
                Objects.requireNonNull(eg.getJobVertex(v1.getID())).getProducedDataSets()[0];

        IntermediateResultPartition partition1 = result.getPartitions()[0];
        IntermediateResultPartition partition2 = result.getPartitions()[1];

        assertEquals(
                partition1.getConsumedPartitionGroups().get(0),
                partition2.getConsumedPartitionGroups().get(0));

        ConsumedPartitionGroup consumedPartitionGroup =
                partition1.getConsumedPartitionGroups().get(0);
        Set<IntermediateResultPartitionID> partitionIds = new HashSet<>();
        for (IntermediateResultPartitionID partitionId : consumedPartitionGroup) {
            partitionIds.add(partitionId);
        }
        assertThat(
                partitionIds,
                containsInAnyOrder(partition1.getPartitionId(), partition2.getPartitionId()));
    }
}
