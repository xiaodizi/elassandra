/*
 * Copyright (c) 2017 Strapdata (http://www.strapdata.com)
 * Contains some code from Elasticsearch (http://www.elastic.co)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elassandra.shard;

import com.carrotsearch.hppc.cursors.ObjectCursor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateApplier;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardState;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Block cassandra until all local shards are started before playing commit logs or bootstrapping.
 * This is a lowPriorityApplier to relase boostrap when shards are started AND cluster state applied to 2i indices.
 * @author vroyer
 *
 */
public class CassandraShardStartedBarrier implements ClusterStateApplier  {

    final Logger logger = LogManager.getLogger(getClass());
    final CountDownLatch latch = new CountDownLatch(1);
    final ClusterService clusterService;

    public CassandraShardStartedBarrier(Settings settings, ClusterService clusterService) {
        this.clusterService = clusterService;
        clusterService.addLowPriorityApplier(this);
    }

    /**
     * Block until all local shards are started.
     */
    public void blockUntilShardsStarted() {
        try {
            logger.debug("Waiting latch={}", latch.getCount());
            if (latch.await(600, TimeUnit.SECONDS))
                logger.debug("All local shards ready to index.");
            else
                logger.error("Some local shards not ready to index, clusterState = {}", clusterService.state());
        } catch (InterruptedException e) {
            logger.error("Interrupred before all local shards are ready to index", e);
        }

    }

    /**
     * Called when a new cluster state ({@link ClusterChangedEvent#state()} needs to be applied
     */
    public void applyClusterState(ClusterChangedEvent event) {
        isReadyToIndex(event.state());
    }

    /**
     * Release the barrier if all local shards (for OPEN indices) are started.
     */
    private boolean isReadyToIndex(ClusterState clusterState) {
        boolean readyToIndex;
        if (clusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            readyToIndex = false;
        } else {
            readyToIndex = true;
            for(ObjectCursor<IndexMetaData> cursor : clusterState.metaData().indices().values()) {
                IndexMetaData indexMetaData = cursor.value;
                if (indexMetaData.getState() == IndexMetaData.State.OPEN) {
                    IndexService indexService = clusterService.getIndicesService().indexService(indexMetaData.getIndex());
                    if (indexService == null) {
                        readyToIndex = false;
                        break;
                    }
                    IndexShard localShard = indexService.getShardOrNull(0);
                    if (localShard == null) {
                        readyToIndex = false;
                        break;
                    }
                    if (localShard.state() != IndexShardState.STARTED) {
                        readyToIndex = false;
                        break;
                    }
                }
            }
        }
        if (readyToIndex && latch.getCount() > 0) {
            // ensure all elastic secondary index are correctly initialized
            /*
            for(ElasticSecondaryIndex esi : ElasticSecondaryIndex.elasticSecondayIndices.values()) {
                if (!esi.isReadyToIndex()) {
                    logger.info("Delayed initialization of ElasticSecondaryIndex={}", esi);
                    esi.initialize(this.clusterService);
                }
            }
            */
            clusterService.removeApplier(this);
            latch.countDown();
        }
        logger.debug("readyToIndex={} latch={} state={}", readyToIndex, latch.getCount(), clusterState);
        return readyToIndex;
    }

}
