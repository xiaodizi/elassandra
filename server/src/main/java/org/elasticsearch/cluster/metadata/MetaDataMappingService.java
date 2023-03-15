/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.transport.Event;
import org.apache.cassandra.utils.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingClusterStateUpdateRequest;
import org.elasticsearch.cluster.AckedClusterStateTaskListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskConfig.SchemaUpdate;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperService.MergeReason;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidTypeNameException;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.elasticsearch.index.mapper.MapperService.isMappingSourceTyped;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.indices.cluster.IndicesClusterStateService.AllocatedIndices.IndexRemovalReason.NO_LONGER_ASSIGNED;

/**
 * Service responsible for submitting mapping changes
 */
public class MetaDataMappingService {

    private static final Logger logger = LogManager.getLogger(MetaDataMappingService.class);

    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final Settings settings;

    final RefreshTaskExecutor refreshExecutor = new RefreshTaskExecutor();
    final PutMappingExecutor putMappingExecutor = new PutMappingExecutor();
    final ClusterStateTaskExecutor<UpdateTask> updateExecutor = new UpdateTaskExecutor();

    @Inject
    public MetaDataMappingService(Settings settings, ClusterService clusterService, IndicesService indicesService) {
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.settings = settings;
    }

    public Settings settings() {
        return this.settings;
    }

    static class RefreshTask {
        final String index;
        final String indexUUID;

        RefreshTask(String index, final String indexUUID) {
            this.index = index;
            this.indexUUID = indexUUID;
        }

        @Override
        public String toString() {
            return "[" + index + "][" + indexUUID + "]";
        }
    }

    static class UpdateTask extends RefreshTask {
        final String type;
        final CompressedXContent mappingSource;
        final String nodeId; // null fr unknown
        final ClusterStateTaskListener listener;

        UpdateTask(String index, String indexUUID, String type, CompressedXContent mappingSource, String nodeId, ClusterStateTaskListener listener) {
            super(index, indexUUID);
            this.type = type;
            this.mappingSource = mappingSource;
            this.nodeId = nodeId;
            this.listener = listener;
        }
    }

    class RefreshTaskExecutor implements ClusterStateTaskExecutor<RefreshTask> {
        @Override
        public ClusterTasksResult<RefreshTask> execute(ClusterState currentState, List<RefreshTask> tasks) throws Exception {
            Collection<Mutation> mutations = new LinkedList<>();
            Collection<Event.SchemaChange> events = new LinkedList<>();
            ClusterState newClusterState = executeRefresh(currentState, tasks, mutations, events);
            return ClusterTasksResult.<RefreshTask>builder().successes(tasks).build(newClusterState, ClusterStateTaskConfig.SchemaUpdate.UPDATE, mutations, events);
        }
    }

    class UpdateTaskExecutor implements ClusterStateTaskExecutor<UpdateTask> {
        @Override
        public ClusterTasksResult<UpdateTask> execute(ClusterState currentState, List<UpdateTask> tasks) throws Exception {
            Collection<Mutation> mutations = new LinkedList<>();
            Collection<Event.SchemaChange> events = new LinkedList<>();
            ClusterState newClusterState = executeRefresh(currentState, tasks, mutations, events);
            return ClusterTasksResult.<UpdateTask>builder().successes(tasks).build(newClusterState, ClusterStateTaskConfig.SchemaUpdate.UPDATE, mutations, events);
        }
    }

    /**
     * Batch method to apply all the queued refresh operations. The idea is to try and batch as much
     * as possible so we won't create the same index all the time for example for the updates on the same mapping
     * and generate a single cluster change event out of all of those.
     */
    ClusterState executeRefresh(final ClusterState currentState, final List<? extends RefreshTask> allTasks,
            Collection<Mutation> mutations, Collection<Event.SchemaChange> events) throws Exception {
        // break down to tasks per index, so we can optimize the on demand index service creation
        // to only happen for the duration of a single index processing of its respective events
        Map<String, List<RefreshTask>> tasksPerIndex = new HashMap<>();
        for (RefreshTask task : allTasks) {
            if (task.index == null) {
                logger.debug("ignoring a mapping task of type [{}] with a null index.", task);
            }
            tasksPerIndex.computeIfAbsent(task.index, k -> new ArrayList<>()).add(task);
        }

        boolean dirty = false;
        MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());

        for (Map.Entry<String, List<RefreshTask>> entry : tasksPerIndex.entrySet()) {
            IndexMetaData indexMetaData = mdBuilder.get(entry.getKey());
            if (indexMetaData == null) {
                // index got deleted on us, ignore...
                logger.debug("[{}] ignoring tasks - index meta data doesn't exist", entry.getKey());
                continue;
            }
            final Index index = indexMetaData.getIndex();
            // the tasks lists to iterate over, filled with the list of mapping tasks, trying to keep
            // the latest (based on order) update mapping one per node
            List<RefreshTask> allIndexTasks = entry.getValue();
            boolean hasTaskWithRightUUID = false;
            for (RefreshTask task : allIndexTasks) {
                if (indexMetaData.isSameUUID(task.indexUUID)) {
                    hasTaskWithRightUUID = true;
                } else {
                    logger.debug("{} ignoring task [{}] - index meta data doesn't match task uuid", index, task);
                }
            }
            if (hasTaskWithRightUUID == false) {
                continue;
            }

            // construct the actual index if needed, and make sure the relevant mappings are there
            boolean removeIndex = false;
            IndexService indexService = indicesService.indexService(indexMetaData.getIndex());
            if (indexService == null) {
                // we need to create the index here, and add the current mapping to it, so we can merge
                indexService = indicesService.createIndex(indexMetaData, Collections.emptyList());
                //removeIndex = true;
                //indexService.mapperService().merge(indexMetaData, MergeReason.MAPPING_RECOVERY, true);
                KeyspaceMetadata ksm = clusterService.getSchemaManager().createOrUpdateKeyspace(
                        indexService.mapperService().keyspace(),
                        settings().getAsInt(SETTING_NUMBER_OF_REPLICAS, 0) +1,
                        indexService.mapperService().getIndexSettings().getIndexMetaData().replication(), mutations, events);
                for (ObjectCursor<MappingMetaData> metaData : indexMetaData.getMappings().values()) {

                    // build siblings indexMetaData to update table extensions in one schema mutation.
                    Map<Index, Pair<IndexMetaData, MapperService>> indicesMap = new HashMap<>();

                    for(ObjectCursor<IndexMetaData> imd : mdBuilder.indices()) {
                        if (indexMetaData.keyspace().equals(imd.value.keyspace()) && imd.value.getMappings().containsKey(metaData.value.type())) {
                            final IndexService indexService2 = indicesService.indexService(indexMetaData.getIndex());
                            final MapperService mapperService2 = indexService2.mapperService();
                            indicesMap.put(imd.value.getIndex(), Pair.create(imd.value, mapperService2));
                        }
                    }

                    // don't apply the default mapping, it has been applied when the mapping was created
                    DocumentMapper docMapper = indexService.mapperService().merge(metaData.value.type(), metaData.value.source(), MapperService.MergeReason.MAPPING_RECOVERY, true);
                    if (!metaData.value.type().equals(MapperService.DEFAULT_MAPPING)) {
                        ksm = clusterService.getSchemaManager().updateTableSchema(ksm, metaData.value.type(), indicesMap, mutations, events);
                    }
                }
            }

            IndexMetaData.Builder builder = IndexMetaData.builder(indexMetaData);
            try {
                boolean indexDirty = refreshIndexMapping(indexService, indexMetaData, builder, mutations, events);
                if (indexDirty) {
                    mdBuilder.put(builder);
                    dirty = true;
                }
            } finally {
                if (removeIndex) {
                    indicesService.removeIndex(index, NO_LONGER_ASSIGNED, "created for mapping processing");
                }
            }
        }

        if (!dirty) {
            return currentState;
        }
        return ClusterState.builder(currentState).metaData(mdBuilder).build();
    }

    private boolean refreshIndexMapping(IndexService indexService, IndexMetaData currentIndexMetaData,  IndexMetaData.Builder builder,
            Collection<Mutation> mutations, Collection<Event.SchemaChange> events) {
        boolean dirty = false;
        String index = indexService.index().getName();
        try {
            List<String> updatedTypes = new ArrayList<>();
            for (DocumentMapper mapper : indexService.mapperService().docMappers(true)) {
                final String type = mapper.type();
                if (!mapper.mappingSource().equals(builder.mapping(type).source())) {
                    updatedTypes.add(type);
                }
            }

            // if a single type is not up-to-date, re-send everything
            if (updatedTypes.isEmpty() == false) {
                logger.warn("[{}] re-syncing mappings with cluster state because of types [{}]", index, updatedTypes);
                dirty = true;
                KeyspaceMetadata ksm = clusterService.getSchemaManager().createOrUpdateKeyspace(
                        indexService.mapperService().keyspace(),
                        settings().getAsInt(SETTING_NUMBER_OF_REPLICAS, 0) +1,
                        indexService.mapperService().getIndexSettings().getIndexMetaData().replication(), mutations, events);
                for (DocumentMapper mapper : indexService.mapperService().docMappers(true)) {
                    MappingMetaData mappingMetaData2 = new MappingMetaData(mapper);
                    builder.putMapping(mappingMetaData2);

                    if (!mappingMetaData2.type().equals(MapperService.DEFAULT_MAPPING)) {
                        ksm = clusterService.getSchemaManager().updateTableSchema(ksm, mappingMetaData2.type(),
                                Collections.singletonMap(currentIndexMetaData.getIndex(), Pair.create(currentIndexMetaData, indexService.mapperService())), mutations, events);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn(() -> new ParameterizedMessage("[{}] failed to refresh-mapping in cluster state", index), e);
        }
        return dirty;
    }

    /**
     * Refreshes mappings if they are not the same between original and parsed version
     */
    public void refreshMapping(final String index, final String indexUUID) {
        final RefreshTask refreshTask = new RefreshTask(index, indexUUID);
        clusterService.submitStateUpdateTask("refresh-mapping",
            refreshTask,
            ClusterStateTaskConfig.build(Priority.HIGH),
            refreshExecutor,
                (source, e) -> logger.warn(() -> new ParameterizedMessage("failure during [{}]", source), e)
        );
    }

    class PutMappingExecutor implements ClusterStateTaskExecutor<PutMappingClusterStateUpdateRequest> {
        @Override
        public ClusterTasksResult<PutMappingClusterStateUpdateRequest>
        execute(ClusterState currentState, List<PutMappingClusterStateUpdateRequest> tasks) throws Exception {
            Map<Index, MapperService> indexMapperServices = new HashMap<>();
            ClusterTasksResult.Builder<PutMappingClusterStateUpdateRequest> builder = ClusterTasksResult.builder();
            try {
                Collection<Mutation> mutations = new LinkedList<>();
                Collection<Event.SchemaChange> events = new LinkedList<>();
                SchemaUpdate schemaUpdate = SchemaUpdate.NO_UPDATE;
                Map<String, KeyspaceMetadata> ksmMap = new HashMap<>();
                for (PutMappingClusterStateUpdateRequest request : tasks) {
                    try {
                        List<Index> effectiveIndices = new ArrayList<>();
                        for (Index index : request.indices()) {
                            final IndexMetaData indexMetaData = currentState.metaData().getIndexSafe(index);
                            if (indexMapperServices.containsKey(indexMetaData.getIndex()) == false) {
                                MapperService mapperService = indicesService.createIndexMapperService(indexMetaData);
                                indexMapperServices.put(index, mapperService);
                                // add mappings for all types, we need them for cross-type validation
                                mapperService.merge(indexMetaData, MergeReason.MAPPING_RECOVERY, request.updateAllTypes());
                            }

                            // add virtual index in the mapperServices map, and all depending on it
                            if (indexMetaData.virtualIndex() == null) {
				                effectiveIndices.add(index);
                            } else {
                                final IndexMetaData virtualIndexMetaData = currentState.metaData().index(indexMetaData.virtualIndex());
                                if (virtualIndexMetaData != null && indexMapperServices.containsKey(virtualIndexMetaData.getIndex()) == false) {
                                    MapperService mapperService = indicesService.createIndexMapperService(virtualIndexMetaData);
                                    indexMapperServices.put(virtualIndexMetaData.getIndex(), mapperService);
                                    // add mappings for all types, we need them for cross-type validation
                                    mapperService.merge(virtualIndexMetaData, MergeReason.MAPPING_RECOVERY, request.updateAllTypes());
                                }
                                effectiveIndices.add(virtualIndexMetaData.getIndex());
                            }
                        }
                        currentState = applyRequest(currentState, ksmMap, request, effectiveIndices, indexMapperServices, mutations, events);
                        builder.success(request);
                        schemaUpdate = (request.schemaUpdate().ordinal() > schemaUpdate.ordinal()) ? request.schemaUpdate() : schemaUpdate;
                    } catch (Exception e) {
                        builder.failure(request, e);
                    }
                }
                return builder.build(currentState, schemaUpdate, mutations, events);
            } finally {
                IOUtils.close(indexMapperServices.values());
            }
        }

        private ClusterState applyRequest(final ClusterState currentState,
                final Map<String, KeyspaceMetadata> ksmMap,
                final PutMappingClusterStateUpdateRequest request,
                                          List<Index> effectiveIndices,
                                          Map<Index, MapperService> indexMapperServices,
                                          Collection<Mutation> mutations, Collection<Event.SchemaChange> events) throws IOException {
            String mappingType = request.type();
            CompressedXContent mappingUpdateSource = new CompressedXContent(request.source());
            final MetaData metaData = currentState.metaData();
            final List<IndexMetaData> updateList = new ArrayList<>();
            for (Index index : effectiveIndices) {
                MapperService mapperService = indexMapperServices.get(index);
                // IMPORTANT: always get the metadata from the state since it get's batched
                // and if we pull it from the indexService we might miss an update etc.
                final IndexMetaData indexMetaData = currentState.getMetaData().getIndexSafe(index);

                // this is paranoia... just to be sure we use the exact same metadata tuple on the update that
                // we used for the validation, it makes this mechanism little less scary (a little)
                updateList.add(indexMetaData);
                // try and parse it (no need to add it here) so we can bail early in case of parsing exception
                DocumentMapper newMapper;
                DocumentMapper existingMapper = mapperService.documentMapper(mappingType);
                if (existingMapper == null && isMappingSourceTyped(request.type(), mappingUpdateSource) == false) {
                    existingMapper = mapperService.documentMapper(mapperService.resolveDocumentType(mappingType));
                }
                String typeForUpdate = existingMapper == null ? mappingType : existingMapper.type();

                if (MapperService.DEFAULT_MAPPING.equals(typeForUpdate)) {
                    // _default_ types do not go through merging, but we do test the new settings. Also don't apply the old default
                    newMapper = mapperService.parse(request.type(), mappingUpdateSource, false);
                } else {
                    newMapper = mapperService.parse(request.type(), mappingUpdateSource, existingMapper == null);
                    if (existingMapper != null) {
                        // first, simulate: just call merge and ignore the result
                        existingMapper.merge(newMapper.mapping(), request.updateAllTypes());
                    } else {
                        // TODO: can we find a better place for this validation?
                        // The reason this validation is here is that the mapper service doesn't learn about
                        // new types all at once , which can create a false error.

                        // For example in MapperService we can't distinguish between a create index api call
                        // and a put mapping api call, so we don't which type did exist before.
                        // Also the order of the mappings may be backwards.
                        if (newMapper.parentFieldMapper().active()) {
                            for (ObjectCursor<MappingMetaData> mapping : indexMetaData.getMappings().values()) {
                                String parentType = newMapper.parentFieldMapper().type();
                                if (parentType.equals(mapping.value.type()) &&
                                        mapperService.getParentTypes().contains(parentType) == false) {
                                    throw new IllegalArgumentException("can't add a _parent field that points to an " +
                                        "already existing type, that isn't already a parent");
                                }
                            }
                        }
                    }
                }
                if (mappingType == null) {
                    mappingType = newMapper.type();
                } else if (mappingType.equals(newMapper.type()) == false
                        && mapperService.resolveDocumentType(mappingType).equals(newMapper.type()) == false) {
                    throw new InvalidTypeNameException("Type name provided does not match type name within mapping definition");
                }
            }
            assert mappingType != null;

            if (MapperService.DEFAULT_MAPPING.equals(mappingType) == false
                    && MapperService.SINGLE_MAPPING_NAME.equals(mappingType) == false
                    && mappingType.charAt(0) == '_') {
                throw new InvalidTypeNameException("Document mapping type name can't start with '_', found: [" + mappingType + "]");
            }
            MetaData.Builder builder = MetaData.builder(metaData).setClusterUuid();
            int updateCount = 0;

            Map<Index, Pair<IndexMetaData, MapperService>> mapperServicesMap = new HashMap<>();

            for (IndexMetaData indexMetaData : updateList) {
                boolean updatedMapping = false;
                // do the actual merge here on the master, and update the mapping source
                // we use the exact same indexService and metadata we used to validate above here to actually apply the update
                final Index index = indexMetaData.getIndex();
                IndexService indexService = indicesService.indexService(index);
                if (indexService == null) {
                    continue;
                }
                final MapperService mapperService = indexMapperServices.get(index);

                // If the _type name is _doc and there is no _doc top-level key then this means that we
                // are handling a typeless call. In such a case, we override _doc with the actual type
                // name in the mappings. This allows to use typeless APIs on typed indices.
                String typeForUpdate = mappingType;
                CompressedXContent existingSource = null;
                DocumentMapper existingMapper = mapperService.documentMapper(mappingType);
                if (existingMapper == null && isMappingSourceTyped(request.type(), mappingUpdateSource) == false) {
                    existingMapper = mapperService.documentMapper(mapperService.resolveDocumentType(mappingType));
                }
                if (existingMapper != null) {
                    typeForUpdate = existingMapper.type();
                    existingSource = existingMapper.mappingSource();
                }
                DocumentMapper mergedMapper = mapperService.merge(typeForUpdate, mappingUpdateSource,
                    MergeReason.MAPPING_UPDATE, request.updateAllTypes());
                CompressedXContent updatedSource = mergedMapper.mappingSource();

                if (existingSource != null) {
                    if (existingSource.equals(updatedSource)) {
                        // same source, no changes, ignore it
                    } else {
                        updatedMapping = true;
                        // use the merged mapping source
                        if (logger.isDebugEnabled()) {
                            logger.debug("{} update_mapping [{}] with source [{}]", index, mergedMapper.type(), updatedSource);
                        } else if (logger.isInfoEnabled()) {
                            logger.info("{} update_mapping [{}]", index, mergedMapper.type());
                        }

                    }
                } else {
                    updatedMapping = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("{} create_mapping [{}] with source [{}]", index, mappingType, updatedSource);
                    } else if (logger.isInfoEnabled()) {
                        logger.info("{} create_mapping [{}]", index, mappingType);
                    }
                }

                IndexMetaData.Builder indexMetaDataBuilder = IndexMetaData.builder(indexMetaData);
                // Mapping updates on a single type may have side-effects on other types so we need to
                // update mapping metadata on all types
                KeyspaceMetadata ksm = clusterService.getSchemaManager().createOrUpdateKeyspace(mapperService.keyspace(),
                        settings().getAsInt(SETTING_NUMBER_OF_REPLICAS, 0) +1,
                        mapperService.getIndexSettings().getIndexMetaData().replication(), mutations, events);
                for (DocumentMapper mapper : mapperService.docMappers(true)) {
                    MappingMetaData mappingMd = new MappingMetaData(mapper.mappingSource());
                    indexMetaDataBuilder.putMapping(mappingMd);
                }
                if (updatedMapping) {
                    indexMetaDataBuilder.mappingVersion(1 + indexMetaDataBuilder.mappingVersion());
                    updateCount++;
                }
                IndexMetaData updatedIndexMetaData = indexMetaDataBuilder.build();
                builder.put(updatedIndexMetaData, true);
                mapperServicesMap.put(updatedIndexMetaData.getIndex(), Pair.create(updatedIndexMetaData, mapperService));
            }
            if (updateCount > 0) {
                MetaData newMetaData = builder.build();
                MetaData.Builder builder2 = MetaData.builder(newMetaData);
                for (IndexMetaData indexMetaData : mapperServicesMap.values().stream().map(p -> p.left).collect(Collectors.toList()) ) {
                    if (indexMetaData.isVirtual()) {
                        IndexMetaData virtualIndexMetaData = newMetaData.index(indexMetaData.getIndex().getName());
                    	// update the existing indices pointing to the virtual index
                        for(ObjectCursor<IndexMetaData> cursor : currentState.metaData().getIndices().values()) {
                            if (virtualIndexMetaData.getIndex().getName().equals(cursor.value.virtualIndex())) {
                                IndexMetaData.Builder imdBuilder = IndexMetaData.builder(cursor.value).version(virtualIndexMetaData.getVersion());
                                for(ObjectCursor<MappingMetaData> mappingCursor : virtualIndexMetaData.getMappings().values()) {
                                    imdBuilder.putMapping(mappingCursor.value);
                                }
                                IndexMetaData indexMetaData2 = imdBuilder.build();
                                builder2.put(indexMetaData2, false);
                                mapperServicesMap.put(indexMetaData2.getIndex(), Pair.create(indexMetaData2, indexMapperServices.get(virtualIndexMetaData.getIndex())));
                            }
                        }
                    }
                }

                // collect tables+IndexMetaData to update extensions
                Multimap<String, IndexMetaData> perTableIndexMetaData = ArrayListMultimap.create();
                for (IndexMetaData indexMetaData : mapperServicesMap.values().stream().map(p -> p.left).collect(Collectors.toList())) {
                    for(ObjectCursor<MappingMetaData> mmd : indexMetaData.getMappings().values()) {
                        if (!mmd.value.type().equals(MapperService.DEFAULT_MAPPING) && indexMetaData.virtualIndex() == null)
                            perTableIndexMetaData.put( indexMetaData.keyspace() + "." + mmd.value.type(), indexMetaData);
                    }
                }

                // update each table schema with related index metadata to store mapping in table extensions
                for(String ksTypeName : perTableIndexMetaData.keySet()) {
                    int i = ksTypeName.indexOf(".");
                    String ksName = ksTypeName.substring(0, i);
                    String type = ksTypeName.substring(i + 1);

                    IndexMetaData indexMetaData = perTableIndexMetaData.get(ksTypeName).iterator().next();
                    ksmMap.compute(ksName, (k, v) -> {
                        if (v == null)
                            v = clusterService.getSchemaManager().createOrUpdateKeyspace(ksName,
                                    settings().getAsInt(SETTING_NUMBER_OF_REPLICAS, 0) +1,
                                    indexMetaData.replication(), mutations, events);
                        return clusterService.getSchemaManager().updateTableSchema(v, type,
                                mapperServicesMap.entrySet().stream()
                                    .filter(e -> e.getValue().left.keyspace().equals(ksName) && e.getValue().left.getMappings().containsKey(type))
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                mutations, events);
                    });
                }

                return ClusterState.builder(currentState).metaData(builder2).build();
            } else {
                return currentState;
            }
        }

        @Override
        public String describeTasks(List<PutMappingClusterStateUpdateRequest> tasks) {
            return String.join(", ", tasks.stream().map(t -> (CharSequence)t.type())::iterator);
        }
    }

    /**
     * Refreshes mappings if they are not the same between original and parsed version
     */
    public void updateMapping(final String index, final String indexUUID, final String type, final CompressedXContent mappingSource, final String nodeId,
            ClusterStateTaskListener listener, final TimeValue timeout) {
        final UpdateTask updateTask = new UpdateTask(index, indexUUID, type, mappingSource, nodeId, listener);
        clusterService.submitStateUpdateTask("update-mapping [" + index + "]",
                updateTask,
                ClusterStateTaskConfig.build(Priority.HIGH, timeout, ClusterStateTaskConfig.SchemaUpdate.UPDATE),
            updateExecutor,
            listener);
    }

    public void putMapping(final PutMappingClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener, ClusterStateTaskConfig.SchemaUpdate schemaUpdate) {
        clusterService.submitStateUpdateTask("put-mapping",
                request,
                ClusterStateTaskConfig.build(Priority.HIGH, request.masterNodeTimeout(), schemaUpdate),
                putMappingExecutor,
                new AckedClusterStateTaskListener() {

                    @Override
                    public void onFailure(String source, Exception e) {
                        listener.onFailure(e);
                    }

                    @Override
                    public boolean mustAck(DiscoveryNode discoveryNode) {
                        return true;
                    }

                    @Override
                    public void onAllNodesAcked(@Nullable Exception e) {
                        listener.onResponse(new ClusterStateUpdateResponse(e == null));
                    }

                    @Override
                    public void onAckTimeout() {
                        listener.onResponse(new ClusterStateUpdateResponse(false));
                    }

                    @Override
                    public TimeValue ackTimeout() {
                        return request.ackTimeout();
                    }
                });
    }
}
