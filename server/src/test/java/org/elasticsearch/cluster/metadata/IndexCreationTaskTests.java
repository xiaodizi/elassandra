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

import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Sort;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.hamcrest.Matchers;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.test.hamcrest.CollectionAssertions.hasAllKeys;
import static org.elasticsearch.test.hamcrest.CollectionAssertions.hasKey;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.*;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.*;

public class IndexCreationTaskTests extends ESSingleNodeTestCase {

    private final IndicesService indicesService = mock(IndicesService.class);
    private final AliasValidator aliasValidator = mock(AliasValidator.class);
    private final NamedXContentRegistry xContentRegistry = mock(NamedXContentRegistry.class);
    private final CreateIndexClusterStateUpdateRequest request = mock(CreateIndexClusterStateUpdateRequest.class);
    private final Logger logger = mock(Logger.class);
    private final AllocationService allocationService = mock(AllocationService.class);
    private final MetaDataCreateIndexService.IndexValidator validator = mock(MetaDataCreateIndexService.IndexValidator.class);
    private final ActionListener listener = mock(ActionListener.class);
    private final ClusterState state = mock(ClusterState.class);
    private final Settings.Builder clusterStateSettings = Settings.builder();
    private final MapperService mapper = mock(MapperService.class);

    private final ImmutableOpenMap.Builder<String, IndexTemplateMetaData> tplBuilder = ImmutableOpenMap.builder();
    private final ImmutableOpenMap.Builder<String, MetaData.Custom> customBuilder = ImmutableOpenMap.builder();
    private final ImmutableOpenMap.Builder<String, IndexMetaData> idxBuilder = ImmutableOpenMap.builder();

    private final Settings.Builder reqSettings = Settings.builder();
    private final Set<ClusterBlock> reqBlocks = Sets.newHashSet();
    private final MetaData.Builder currentStateMetaDataBuilder = MetaData.builder();
    private final ClusterBlocks currentStateBlocks = mock(ClusterBlocks.class);
    private final RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
    private final DocumentMapper docMapper = mock(DocumentMapper.class);

    private ActiveShardCount waitForActiveShardsNum = ActiveShardCount.DEFAULT;

    public void setUp() throws Exception {
        super.setUp();
        setupIndicesService();
        setupClusterState();
    }

    public void testMatchTemplates() throws Exception {
        tplBuilder.put("template_1", createTemplateMetadata("template_1", "te*"));
        tplBuilder.put("template_2", createTemplateMetadata("template_2", "tes*"));
        tplBuilder.put("template_3", createTemplateMetadata("template_3", "zzz*"));

        final ClusterState result = executeTask();

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
         */
        assertThat(result.metaData().index("test").getAliases(), hasAllKeys("alias_from_template_1", "alias_from_template_2"));
        assertThat(result.metaData().index("test").getAliases(), not(hasKey("alias_from_template_3")));
    }

    public void testApplyDataFromTemplate() throws Exception {
        addMatchingTemplate(builder -> builder
                .putAlias(AliasMetaData.builder("alias1"))
                .putMapping("mapping1", createMapping())
                .settings(Settings.builder().put("key1", "value1"))
        );

        final ClusterState result = executeTask();

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
        */
        assertThat(result.metaData().index("test").getAliases(), hasKey("alias1"));
        assertThat(result.metaData().index("test").getSettings().get("key1"), equalTo("value1"));
        assertThat(getMappingsFromResponse(), Matchers.hasKey("mapping1"));
    }

    public void testApplyDataFromRequest() throws Exception {
        setupRequestAlias(new Alias("alias1"));
        setupRequestMapping("mapping1", createMapping());
        reqSettings.put("key1", "value1");

        final ClusterState result = executeTask();
        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
        */
        assertThat(result.metaData().index("test").getAliases(), hasKey("alias1"));
        assertThat(result.metaData().index("test").getSettings().get("key1"), equalTo("value1"));
        assertThat(getMappingsFromResponse(), Matchers.hasKey("mapping1"));
    }

    public void testRequestDataHavePriorityOverTemplateData() throws Exception {
        final CompressedXContent tplMapping = createMapping("text");
        final CompressedXContent reqMapping = createMapping("keyword");

        addMatchingTemplate(builder -> builder
                    .putAlias(AliasMetaData.builder("alias1").searchRouting("fromTpl").build())
                    .putMapping("mapping1", tplMapping)
                    .settings(Settings.builder().put("key1", "tplValue"))
        );

        setupRequestAlias(new Alias("alias1").searchRouting("fromReq"));
        setupRequestMapping("mapping1", reqMapping);
        reqSettings.put("key1", "reqValue");

        final ClusterState result = executeTask();

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");

         */
        assertThat(result.metaData().index("test").getAliases().get("alias1").getSearchRouting(), equalTo("fromReq"));
        assertThat(result.metaData().index("test").getSettings().get("key1"), equalTo("reqValue"));
        assertThat(getMappingsFromResponse().get("mapping1").toString(), equalTo("{type={properties={field={type=keyword}}}}"));
    }

    public void testDefaultSettings() throws Exception {
        final ClusterState result = executeTask();

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
        */
        assertThat(result.getMetaData().index("test").getSettings().get(SETTING_NUMBER_OF_SHARDS), equalTo("1"));
    }

    public void testSettingsFromClusterState() throws Exception {
        clusterStateSettings.put(SETTING_NUMBER_OF_SHARDS, 15);

        final ClusterState result = executeTask();

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
        */
        assertThat(result.getMetaData().index("test").getSettings().get(SETTING_NUMBER_OF_SHARDS), equalTo("1"));
    }

    public void testTemplateOrder() throws Exception {
        addMatchingTemplate(builder -> builder
            .order(1)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 10))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("1").build())
        );
        addMatchingTemplate(builder -> builder
            .order(2)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 11))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("2").build())
        );
        addMatchingTemplate(builder -> builder
            .order(3)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 12))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("3").build())
        );
        final ClusterState result = executeTask();

        assertThat(result.getMetaData().index("test").getSettings().get(SETTING_NUMBER_OF_SHARDS), equalTo("1"));
        assertThat(result.metaData().index("test").getAliases().get("alias1").getSearchRouting(), equalTo("3"));
    }

    public void testTemplateOrder2() throws Exception {
        addMatchingTemplate(builder -> builder
            .order(3)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("3").build())
        );
        addMatchingTemplate(builder -> builder
            .order(2)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("2").build())
        );
        addMatchingTemplate(builder -> builder
            .order(1)
            .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
            .putAlias(AliasMetaData.builder("alias1").searchRouting("1").build())
        );
        final ClusterState result = executeTask();

        assertThat(result.getMetaData().index("test").getSettings().get(SETTING_NUMBER_OF_SHARDS), equalTo("1"));
        assertThat(result.metaData().index("test").getAliases().get("alias1").getSearchRouting(), equalTo("3"));
    }

    /*
    public void testRequestStateOpen() throws Exception {
        when(request.state()).thenReturn(IndexMetaData.State.OPEN);

        executeTask();

        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");

        verify(allocationService, times(1)).reroute(anyObject(), anyObject());
    }
    */

    @SuppressWarnings("unchecked")
    public void testIndexRemovalOnFailure() throws Exception {
        doThrow(new RuntimeException("oops")).when(mapper).merge(anyMap(), anyObject(), anyBoolean());

        expectThrows(RuntimeException.class, this::executeTask);

        /* No such warning in elassandra
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");

        verify(indicesService, times(1)).removeIndex(anyObject(), anyObject(), anyObject());
         */
    }

    /*
    public void testShrinkIndexIgnoresTemplates() throws Exception {
        final Index source = new Index("source_idx", "aaa111bbb222");

        when(request.recoverFrom()).thenReturn(source);
        when(request.resizeType()).thenReturn(ResizeType.SHRINK);
        currentStateMetaDataBuilder.put(createIndexMetaDataBuilder("source_idx", "aaa111bbb222", 2, 2));

        routingTableBuilder.add(createIndexRoutingTableWithStartedShards(source));

        when(currentStateBlocks.indexBlocked(eq(ClusterBlockLevel.WRITE), eq("source_idx"))).thenReturn(true);
        reqSettings.put(SETTING_NUMBER_OF_SHARDS, 1);

        addMatchingTemplate(builder -> builder
            .putAlias(AliasMetaData.builder("alias1").searchRouting("fromTpl").build())
            .putMapping("mapping1", createMapping())
            .settings(Settings.builder().put("key1", "tplValue"))
        );

        final ClusterState result = executeTask();

        assertThat(result.metaData().index("test").getAliases(), not(hasKey("alias1")));
        assertThat(result.metaData().index("test").getCustomData(), not(hasKey("custom1")));
        assertThat(result.metaData().index("test").getSettings().keySet(), not(Matchers.contains("key1")));
        assertThat(getMappingsFromResponse(), not(Matchers.hasKey("mapping1")));
    }
    */

    public void testValidateWaitForActiveShardsFailure() throws Exception {
        waitForActiveShardsNum = ActiveShardCount.from(1000);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, this::executeTask);

        /* No such warning in Elassandra
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
                + "if you wish to continue using the default of [5] shards, "
                + "you must manage this on the create index request or with an index template");
        */
        assertThat(e.getMessage(), containsString("invalid wait_for_active_shards"));
    }

    public void testWriteIndex() throws Exception {
        Boolean writeIndex = randomBoolean() ? null : randomBoolean();
        setupRequestAlias(new Alias("alias1").writeIndex(writeIndex));
        setupRequestMapping("mapping1", createMapping());
        reqSettings.put("key1", "value1");

        final ClusterState result = executeTask();
        assertThat(result.metaData().index("test").getAliases(), hasKey("alias1"));
        assertThat(result.metaData().index("test").getAliases().get("alias1").writeIndex(), equalTo(writeIndex));

        /* No such warning in elassandra
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
            + "if you wish to continue using the default of [5] shards, "
            + "you must manage this on the create index request or with an index template");
         */
    }

    public void testWriteIndexValidationException() throws Exception {
        IndexMetaData existingWriteIndex = IndexMetaData.builder("test2")
            .settings(settings(Version.CURRENT)).putAlias(AliasMetaData.builder("alias1").writeIndex(true).build())
            .numberOfShards(1).numberOfReplicas(0).build();
        idxBuilder.put("test2", existingWriteIndex);
        setupRequestMapping("mapping1", createMapping());
        reqSettings.put("key1", "value1");
        setupRequestAlias(new Alias("alias1").writeIndex(true));

        Exception exception = expectThrows(IllegalStateException.class, () -> executeTask());
        assertThat(exception.getMessage(), startsWith("alias [alias1] has more than one write index ["));

        /*
        assertWarnings("the default number of shards will change from [5] to [1] in 7.0.0; "
            + "if you wish to continue using the default of [5] shards, "
            + "you must manage this on the create index request or with an index template");

         */
    }

    public void testTypelessTemplateWithTypedIndexCreation() throws Exception {
        reqSettings.put(SETTING_NUMBER_OF_SHARDS, 1);
        addMatchingTemplate(builder -> builder.putMapping("type", "{\"type\": {}}"));
        setupRequestMapping(MapperService.SINGLE_MAPPING_NAME, new CompressedXContent("{\"_doc\":{}}"));
        executeTask();
        assertThat(getMappingsFromResponse(), Matchers.hasKey(MapperService.SINGLE_MAPPING_NAME));
    }

    public void testTypedTemplateWithTypelessIndexCreation() throws Exception {
        reqSettings.put(SETTING_NUMBER_OF_SHARDS, 1);
        addMatchingTemplate(builder -> builder.putMapping(MapperService.SINGLE_MAPPING_NAME, "{\"_doc\": {}}"));
        setupRequestMapping("type", new CompressedXContent("{\"type\":{}}"));
        executeTask();
        assertThat(getMappingsFromResponse(), Matchers.hasKey("type"));
    }

    public void testTypedTemplate() throws Exception {
        reqSettings.put(SETTING_NUMBER_OF_SHARDS, 1);
        addMatchingTemplate(builder -> builder.putMapping("type", "{\"type\": {}}"));
        executeTask();
        assertThat(getMappingsFromResponse(), Matchers.hasKey("type"));
    }

    public void testTypelessTemplate() throws Exception {
        reqSettings.put(SETTING_NUMBER_OF_SHARDS, 1);
        addMatchingTemplate(builder -> builder.putMapping(MapperService.SINGLE_MAPPING_NAME, "{\"_doc\": {}}"));
        executeTask();
        assertThat(getMappingsFromResponse(), Matchers.hasKey(MapperService.SINGLE_MAPPING_NAME));
    }

    private IndexRoutingTable createIndexRoutingTableWithStartedShards(Index index) {
        final IndexRoutingTable idxRoutingTable = mock(IndexRoutingTable.class);

        when(idxRoutingTable.getIndex()).thenReturn(index);
        when(idxRoutingTable.shardsWithState(eq(ShardRoutingState.STARTED))).thenReturn(Arrays.asList(
            TestShardRouting.newShardRouting(index.getName(), 0, "1", randomBoolean(), ShardRoutingState.INITIALIZING).moveToStarted(),
            TestShardRouting.newShardRouting(index.getName(), 0, "1", randomBoolean(), ShardRoutingState.INITIALIZING).moveToStarted()

        ));

        return idxRoutingTable;
    }

    private IndexMetaData.Builder createIndexMetaDataBuilder(String name, String uuid, int numShards, int numReplicas) {
        return IndexMetaData
            .builder(name)
            .settings(Settings.builder()
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetaData.SETTING_INDEX_UUID, uuid))
            .putMapping(new MappingMetaData(docMapper))
            .numberOfShards(numShards)
            .numberOfReplicas(numReplicas);
    }

    private Map<String, String> createCustom() {
        return Collections.singletonMap("a", "b");
    }

    private interface MetaDataBuilderConfigurator {
        void configure(IndexTemplateMetaData.Builder builder) throws IOException;
    }

    private void addMatchingTemplate(MetaDataBuilderConfigurator configurator) throws IOException {
        final IndexTemplateMetaData.Builder builder = metaDataBuilder("template1", "te*");
        configurator.configure(builder);

        tplBuilder.put("template" + builder.hashCode(), builder.build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getMappingsFromResponse() {
        final ArgumentCaptor<Map> argument = ArgumentCaptor.forClass(Map.class);
        verify(mapper).merge(argument.capture(), anyObject(), anyBoolean());
        return argument.getValue();
    }

    private void setupRequestAlias(Alias alias) {
        when(request.aliases()).thenReturn(new HashSet<>(Collections.singletonList(alias)));
    }

    private void setupRequestMapping(String mappingKey, CompressedXContent mapping) throws IOException {
        when(request.mappings()).thenReturn(Collections.singletonMap(mappingKey, mapping.string()));
    }

    private CompressedXContent createMapping() throws IOException {
        return createMapping("text");
    }

    private CompressedXContent createMapping(String fieldType) throws IOException {
        final String mapping = Strings.toString(XContentFactory.jsonBuilder()
            .startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", fieldType)
                        .endObject()
                    .endObject()
                .endObject()
            .endObject());

        return new CompressedXContent(mapping);
    }

    private IndexTemplateMetaData.Builder metaDataBuilder(String name, String pattern) {
        return IndexTemplateMetaData
            .builder(name)
            .patterns(Collections.singletonList(pattern));
    }

    private IndexTemplateMetaData createTemplateMetadata(String name, String pattern) {
        return IndexTemplateMetaData
            .builder(name)
            .patterns(Collections.singletonList(pattern))
            .putAlias(AliasMetaData.builder("alias_from_" + name).build())
            .build();
    }

    @SuppressWarnings("unchecked")
    private ClusterState executeTask() throws Exception {
        setupState();
        setupRequest();
        final MetaDataCreateIndexService.IndexCreationTask task = new MetaDataCreateIndexService.IndexCreationTask(
            logger, allocationService, request, listener, indicesService, clusterService(), aliasValidator, xContentRegistry, clusterStateSettings.build(),
            validator, IndexScopedSettings.DEFAULT_SCOPED_SETTINGS) {

            @Override
            protected void checkShardLimit(final Settings settings, final ClusterState clusterState) {
                // we have to make this a no-op since we are not mocking enough for this method to be able to execute
            }

        };
        return task.execute(state);
    }

    private void setupState() {
        final ImmutableOpenMap.Builder<String, ClusterState.Custom> stateCustomsBuilder = ImmutableOpenMap.builder();

        currentStateMetaDataBuilder
            .customs(customBuilder.build())
            .templates(tplBuilder.build())
            .indices(idxBuilder.build());

        when(state.metaData()).thenReturn(currentStateMetaDataBuilder.build());

        final ImmutableOpenMap.Builder<String, Set<ClusterBlock>> blockIdxBuilder = ImmutableOpenMap.builder();

        when(currentStateBlocks.indices()).thenReturn(blockIdxBuilder.build());

        when(state.blocks()).thenReturn(currentStateBlocks);
        when(state.customs()).thenReturn(stateCustomsBuilder.build());
        when(state.routingTable()).thenReturn(routingTableBuilder.build());
    }

    private void setupRequest() {
        when(request.settings()).thenReturn(reqSettings.build());
        when(request.index()).thenReturn("test");
        when(request.waitForActiveShards()).thenReturn(waitForActiveShardsNum);
        when(request.blocks()).thenReturn(reqBlocks);
    }

    private void setupClusterState() {
        final DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(nodes.getSmallestNonClientNodeVersion()).thenReturn(Version.CURRENT);

        when(state.nodes()).thenReturn(nodes);
    }

    @SuppressWarnings("unchecked")
    private void setupIndicesService() throws Exception {
        final RoutingFieldMapper routingMapper = mock(RoutingFieldMapper.class);
        when(routingMapper.required()).thenReturn(false);

        when(docMapper.routingFieldMapper()).thenReturn(routingMapper);
        when(docMapper.parentFieldMapper()).thenReturn(mock(ParentFieldMapper.class));

        when(mapper.docMappers(anyBoolean())).thenReturn(Collections.singletonList(docMapper));

        final Index index = new Index("target", "tgt1234");
        final Supplier<Sort> supplier = mock(Supplier.class);
        final IndexService service = mock(IndexService.class);
        when(service.index()).thenReturn(index);
        when(service.mapperService()).thenReturn(mapper);
        when(service.getIndexSortSupplier()).thenReturn(supplier);
        when(service.getIndexEventListener()).thenReturn(mock(IndexEventListener.class));

        when(indicesService.createIndex(anyObject(), anyObject())).thenReturn(service);
    }
}