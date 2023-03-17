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
package org.elasticsearch.test;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.google.common.collect.Lists;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.ElassandraDaemon;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequestBuilder;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.MockEngineFactoryPlugin;
import org.elasticsearch.index.mapper.MockFieldFilterPlugin;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeMocksPlugin;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.MockSearchService;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.discovery.MockCassandraDiscovery;
import org.elasticsearch.test.discovery.TestZenDiscovery;
import org.elasticsearch.test.store.MockFSIndexStore;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * A test that keep a singleton node started for all tests that can be used to get
 * references to Guice injectors in unit tests.
 */
public abstract class ESSingleNodeTestCase extends ESTestCase {

    private static final Semaphore testMutex = new Semaphore(1);

    public static synchronized void initElassandraDeamon(Settings testSettings, Collection<Class<? extends Plugin>> classpathPlugins)  {
        if (ElassandraDaemon.instance == null) {
            System.out.println("working.dir="+System.getProperty("user.dir"));
            System.out.println("cassandra.home="+System.getProperty("cassandra.home"));
            System.out.println("cassandra.config.loader="+System.getProperty("cassandra.config.loader"));
            System.out.println("cassandra.config="+System.getProperty("cassandra.config"));
            System.out.println("cassandra.config.dir="+System.getProperty("cassandra.config.dir"));
            System.out.println("cassandra-rackdc.properties="+System.getProperty("cassandra-rackdc.properties"));
            System.out.println("cassandra.storagedir="+System.getProperty("cassandra.storagedir"));
            System.out.println("logback.configurationFile="+System.getProperty("logback.configurationFile"));

            DatabaseDescriptor.daemonInitialization();
            DatabaseDescriptor.createAllDirectories();

            CountDownLatch startLatch = new CountDownLatch(1);
            ElassandraDaemon.instance = new ElassandraDaemon(InternalSettingsPreparer.prepareEnvironment(Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), System.getProperty("cassandra.home"))
                .build(), null)) {
                @Override
                public Settings nodeSettings(Settings settings) {
                    return Settings.builder()
                        .put("discovery.type", MockCassandraDiscovery.MOCK_CASSANDRA)
                        .put(Environment.PATH_HOME_SETTING.getKey(), System.getProperty("cassandra.home"))
                        .put(Environment.PATH_DATA_SETTING.getKey(), DatabaseDescriptor.getAllDataFileLocations()[0] + File.separatorChar + "elasticsearch.data")
                        .put(Environment.PATH_REPO_SETTING.getKey(), System.getProperty("cassandra.home")+"/repo")
                        // TODO: use a consistent data path for custom paths
                        // This needs to tie into the ESIntegTestCase#indexSettings() method
                        .put(Environment.PATH_SHARED_DATA_SETTING.getKey(), DatabaseDescriptor.getAllDataFileLocations()[0] + File.separatorChar + "elasticsearch.data")
                        .put(NetworkModule.HTTP_ENABLED.getKey(), false)
                        .put(NetworkModule.TRANSPORT_TYPE_KEY, getTestTransportType())
                        .put(Node.NODE_DATA_SETTING.getKey(), true)
                        .put(NodeEnvironment.NODE_ID_SEED_SETTING.getKey(), random().nextLong())
                        .put("node.name", "127.0.0.1")
                        .put(ScriptService.SCRIPT_MAX_COMPILATIONS_RATE.getKey(), "1000/1m")
                        //.put(EsExecutors.PROCESSORS_SETTING.getKey(), 1) // limit the number of threads created
                        //.put("script.inline", "on")
                        //.put("script.indexed", "on")
                        //.put(EsExecutors.PROCESSORS, 1) // limit the number of threads created
                        .put("client.type", "node")
                        //.put(InternalSettingsPreparer.IGNORE_SYSTEM_PROPERTIES_SETTING, true)

                        .put(settings)
                        .build();
                }

                @Override
                public void ringReady() {
                    startLatch.countDown();
                }
            };

            Settings elassandraSettings = ElassandraDaemon.instance.nodeSettings(testSettings);
            Path confPath = Paths.get(System.getProperty("cassandra.config.dir"));
            ElassandraDaemon.instance.activate(false, false,  elassandraSettings, new Environment(elassandraSettings, confPath), classpathPlugins);

            // wait cassandra start.
            try {
                startLatch.await();
            } catch (InterruptedException e) {
            }
        }
    }

    public ESSingleNodeTestCase() {
        super();
        initElassandraDeamon(nodeSettings(1), getPlugins());
    }

    /**
     * Iff this returns true mock transport implementations are used for the test runs. Otherwise not mock transport impls are used.
     * The default is <tt>true</tt>
     */
    protected boolean addMockTransportService() {
        return false;
    }

    /**
     * A boolean value to enable or disable mock modules. This is useful to test the
     * system without asserting modules that to make sure they don't hide any bugs in
     * production.
     *
     * @see ESIntegTestCase
     */
    public static final String TESTS_ENABLE_MOCK_MODULES = "tests.enable_mock_modules";
    private static final boolean MOCK_MODULES_ENABLED = "true".equals(System.getProperty(TESTS_ENABLE_MOCK_MODULES, "true"));

    /** Return the mock plugins the cluster should use */
    protected Collection<Class<? extends Plugin>> getMockPlugins() {
        final ArrayList<Class<? extends Plugin>> mocks = new ArrayList<>();
        if (MOCK_MODULES_ENABLED && randomBoolean()) { // sometimes run without those completely
            if (randomBoolean() && addMockTransportService()) {
                mocks.add(MockTransportService.TestPlugin.class);
            }
            if (randomBoolean()) {
                mocks.add(MockFSIndexStore.TestPlugin.class);
            }
            if (randomBoolean()) {
                mocks.add(NodeMocksPlugin.class);
            }
            if (randomBoolean()) {
                mocks.add(MockEngineFactoryPlugin.class);
            }
            if (randomBoolean()) {
                mocks.add(MockSearchService.TestPlugin.class);
            }
            /*
            if (randomBoolean()) {
                mocks.add(AssertingTransportInterceptor.TestPlugin.class);
            }
            */
            if (randomBoolean()) {
                mocks.add(MockFieldFilterPlugin.class);
            }
        }

        if (addMockTransportService()) {
            mocks.add(getTestTransportPlugin());
        }

        mocks.add(ESIntegTestCase.TestSeedPlugin.class);
        mocks.add(MockCassandraDiscovery.TestPlugin.class);
        return Collections.unmodifiableList(mocks);
    }

    public MockCassandraDiscovery getMockCassandraDiscovery() {
        return (MockCassandraDiscovery) clusterService().getCassandraDiscovery();
    }

    // override this to initialize the single node cluster.
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.EMPTY;
    }

    static void reset() {
    }

    static void cleanup(boolean resetNode) {
        if (ElassandraDaemon.instance.node() != null) {
            DeleteIndexRequestBuilder builder = ElassandraDaemon.instance.node().client().admin().indices().prepareDelete("*");
            assertAcked(builder.get());
            if (resetNode) {
                reset();
            }
        }
    }
    public static String encodeBasicHeader(final String username, final String password) {
        return java.util.Base64.getEncoder().encodeToString((username + ":" + Objects.requireNonNull(password)).getBytes(StandardCharsets.UTF_8));
    }

    private Node newNode() {
        Collection<Class<? extends Plugin>> plugins = getPlugins();
        if (plugins.contains(getTestTransportPlugin()) == false) {
            plugins = new ArrayList<>(plugins);
            plugins.add(getTestTransportPlugin());
        }
        if (plugins.contains(MockCassandraDiscovery.TestPlugin.class) == false) {
            plugins = new ArrayList<>(plugins);
            plugins.add(MockCassandraDiscovery.TestPlugin.class);
        }
        logger.info("plugins={}", plugins);
        Node node = ElassandraDaemon.instance.newNode(ElassandraDaemon.instance.nodeSettings(nodeSettings()), plugins, forbidPrivateIndexSettings());
        try {
            node.activate();
            node.start();
        } catch (NodeValidationException e) {
            throw new RuntimeException(e);
        }
        // register NodeEnvironment to remove node.lock
        closeAfterTest(node.getNodeEnvironment());
        return node;
    }

    protected void startNode(long seed) throws Exception {
        ElassandraDaemon.instance.node(RandomizedContext.current().runWithPrivateRandomness(seed, this::newNode));
        // we must wait for the node to actually be up and running. otherwise the node might have started,
        // elected itself master but might not yet have removed the
        // SERVICE_UNAVAILABLE/1/state not recovered / initialized block
        ClusterAdminClient clusterAdminClient = client().admin().cluster();
        ClusterHealthRequestBuilder builder = clusterAdminClient.prepareHealth();
        ClusterHealthResponse clusterHealthResponse = builder.setWaitForGreenStatus().get();

        assertFalse(clusterHealthResponse.isTimedOut());
    }

    private static void stopNode() throws IOException {
        if (ElassandraDaemon.instance != null) {
            Node node = ElassandraDaemon.instance.node();
            if (node != null)
                node.stop();
            ElassandraDaemon.instance.node(null);
            IOUtils.close(node);
        }
    }

    @Before
    @Override
    public void setUp() throws Exception {
        logger.info("[{}#{}]: acquiring semaphore ={}", getTestClass().getSimpleName(), getTestName(), testMutex.toString());
        testMutex.acquireUninterruptibly();
        super.setUp();
        //the seed has to be created regardless of whether it will be used or not, for repeatability
        long seed = random().nextLong();
        // Create the node lazily, on the first test. This is ok because we do not randomize any settings,
        // only the cluster name. This allows us to have overridden properties for plugins and the version to use.
        if (ElassandraDaemon.instance.node() == null) {
            startNode(seed);
        }
    }

    @After
    @Override
    public void tearDown() throws Exception {
        logger.info("[{}#{}]: cleaning up after test", getTestClass().getSimpleName(), getTestName());
        super.tearDown();
        try {
            DeleteIndexRequestBuilder builder = ElassandraDaemon.instance.node().client().admin().indices().prepareDelete("*");
            assertAcked(builder.get());

            MetaData metaData = client().admin().cluster().prepareState().get().getState().getMetaData();
            assertThat("test leaves persistent cluster metadata behind: " + metaData.persistentSettings().getAsGroups(),
                metaData.persistentSettings().size(), equalTo(0));
            assertThat("test leaves transient cluster metadata behind: " + metaData.transientSettings().getAsGroups(),
                metaData.transientSettings().size(), equalTo(0));

            List<String> userKeyspaces = Lists.newArrayList(Schema.instance.getUserKeyspaces());
            userKeyspaces.remove(this.clusterService().getElasticAdminKeyspaceName());
            assertThat("test leaves a user keyspace behind:" + userKeyspaces, userKeyspaces.size(), equalTo(0));
        } catch(Exception e) {
            logger.warn("[{}#{}]: failed to clean indices and metadata: error="+e, getTestClass().getSimpleName(), getTestName());
            logger.warn("Exception:", e);
        } finally {
            testMutex.release();
            logger.info("[{}#{}]: released semaphore={}", getTestClass().getSimpleName(), getTestName(), testMutex.toString());
        }
        if (resetNodeAfterTest()) {
            assert ElassandraDaemon.instance != null;
            stopNode();
            //the seed can be created within this if as it will either be executed before every test method or will never be.
            startNode(random().nextLong());
        }
    }

    @BeforeClass
    public static synchronized void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws IOException {
        stopNode();
    }

    protected void ensureNoWarnings() throws IOException {
        super.ensureNoWarnings();
    }

    /**
     * This method returns <code>true</code> if the node that is used in the background should be reset
     * after each test. This is useful if the test changes the cluster state metadata etc. The default is
     * <code>false</code>.
     */
    protected boolean resetNodeAfterTest() {
        return false;
    }

    /** The plugin classes that should be added to the node. */
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.emptyList();
    }

    /** Helper method to create list of plugins without specifying generic types. */
    @SafeVarargs
    @SuppressWarnings("varargs") // due to type erasure, the varargs type is non-reifiable, which causes this warning
    protected final Collection<Class<? extends Plugin>> pluginList(Class<? extends Plugin>...plugins) {
        return Arrays.asList(plugins);
    }

    /** Additional settings to add when creating the node. Also allows overriding the default settings. */
    protected Settings nodeSettings() {
        return Settings.EMPTY;
    }

    /**
     * Returns a client to the single-node cluster.
     */
    public Client client() {
        return ElassandraDaemon.instance.node().client();
    }

    /**
     * Return a reference to the singleton node.
     */
    protected Node node() {
        return ElassandraDaemon.instance.node();
    }

    public ClusterService clusterService() {
        return ElassandraDaemon.instance.node().clusterService();
    }

    public UntypedResultSet process(ConsistencyLevel cl, String query) throws RequestExecutionException, RequestValidationException, InvalidRequestException {
        return clusterService().process(cl, query);
    }

    public UntypedResultSet process(ConsistencyLevel cl, ClientState clientState, String query) throws RequestExecutionException, RequestValidationException, InvalidRequestException {
        return clusterService().process(cl, clientState, query);
    }

    public UntypedResultSet process(ConsistencyLevel cl, String query, Object... values) throws RequestExecutionException, RequestValidationException, InvalidRequestException {
        return clusterService().process(cl, query, values);
    }

    public UntypedResultSet process(ConsistencyLevel cl, ClientState clientState, String query, Object... values) throws RequestExecutionException, RequestValidationException, InvalidRequestException {
        return clusterService().process(cl, clientState, query, values);
    }

    // wait for cassandra to rebuild indices on compaction manager threads.
    public boolean waitIndexRebuilt(String keyspace, List<String> types, long timeout) throws InterruptedException {
        for(int i = 0; i < timeout; i+=200) {
            if (types.stream().filter(t -> !SystemKeyspace.isIndexBuilt(keyspace, String.format(Locale.ROOT, "elastic_%s_idx", t))).count() == 0)
               return true;
            Thread.sleep(200);
        }
        return false;
    }

    public XContentBuilder discoverMapping(String type) throws IOException {
        return XContentFactory.jsonBuilder().startObject().startObject(type).field("discover", ".*").endObject().endObject();
    }

    /**
     * Get an instance for a particular class using the injector of the singleton node.
     */
    protected <T> T getInstanceFromNode(Class<T> clazz) {
        return ElassandraDaemon.instance.node().injector().getInstance(clazz);
    }

    /**
     * Create a new index on the singleton node with empty index settings.
     */
    protected IndexService createIndex(String index) {
        return createIndex(index, Settings.EMPTY);
    }

    /**
     * Create a new index on the singleton node with the provided index settings.
     */
    protected IndexService createIndex(String index, Settings settings) {
        return createIndex(index, settings, null, (XContentBuilder) null);
    }

    /**
     * Create a new index on the singleton node with the provided index settings.
     */
    protected IndexService createIndex(String index, Settings settings, String type, XContentBuilder mappings) {
        CreateIndexRequestBuilder createIndexRequestBuilder = client().admin().indices().prepareCreate(index).setSettings(settings);
        if (type != null && mappings != null) {
            createIndexRequestBuilder.addMapping(type, mappings);
        }
        return createIndex(index, createIndexRequestBuilder);
    }

    /**
     * Create a new index on the singleton node with the provided index settings.
     */
    protected IndexService createIndex(String index, Settings settings, String type, Object... mappings) {
        CreateIndexRequestBuilder createIndexRequestBuilder = client().admin().indices().prepareCreate(index).setSettings(settings);
        if (type != null) {
            createIndexRequestBuilder.addMapping(type, mappings);
        }
        return createIndex(index, createIndexRequestBuilder);
    }

    protected IndexService createIndex(String index, CreateIndexRequestBuilder createIndexRequestBuilder) {
        assertAcked(createIndexRequestBuilder.get());
        // Wait for the index to be allocated so that cluster state updates don't override
        // changes that would have been done locally
        ClusterHealthResponse health = client().admin().cluster()
                .health(Requests.clusterHealthRequest(index).waitForYellowStatus().waitForEvents(Priority.LANGUID)
                        .waitForNoRelocatingShards(true)).actionGet();
        assertThat(health.getStatus(), lessThanOrEqualTo(ClusterHealthStatus.YELLOW));
        assertThat("Cluster must be a single node cluster", health.getNumberOfDataNodes(), equalTo(1));
        IndicesService instanceFromNode = getInstanceFromNode(IndicesService.class);
        return instanceFromNode.indexServiceSafe(resolveIndex(index));
    }

    protected static org.elasticsearch.index.engine.Engine engine(IndexService service) {
        return service.getShard(0).getEngine();
    }

    public Index resolveIndex(String index) {
        GetIndexResponse getIndexResponse = client().admin().indices().prepareGetIndex().setIndices(index).get();
        assertTrue("index " + index + " not found", getIndexResponse.getSettings().containsKey(index));
        String uuid = getIndexResponse.getSettings().get(index).get(IndexMetaData.SETTING_INDEX_UUID);
        return new Index(index, uuid);
    }

    /**
     * Create a new search context.
     */
    protected SearchContext createSearchContext(IndexService indexService) {
        BigArrays bigArrays = indexService.getBigArrays();
        ThreadPool threadPool = indexService.getThreadPool();
        return new TestSearchContext(threadPool, bigArrays, indexService);
    }

    /**
     * Ensures the cluster has a green state via the cluster health API. This method will also wait for relocations.
     * It is useful to ensure that all action on the cluster have finished and all shards that were currently relocating
     * are now allocated and started.
     */
    public ClusterHealthStatus ensureGreen(String... indices) {
        return ensureGreen(TimeValue.timeValueSeconds(60), indices);
    }


    /**
     * Ensures the cluster has a green state via the cluster health API. This method will also wait for relocations.
     * It is useful to ensure that all action on the cluster have finished and all shards that were currently relocating
     * are now allocated and started.
     *
     * @param timeout time out value to set on {@link org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest}
     */
    public ClusterHealthStatus ensureGreen(TimeValue timeout, String... indices) {
        ClusterHealthResponse actionGet = client().admin().cluster()
                .health(Requests.clusterHealthRequest(indices).timeout(timeout).waitForGreenStatus().waitForEvents(Priority.LANGUID)
                        .waitForNoRelocatingShards(true)).actionGet();
        if (actionGet.isTimedOut()) {
            logger.info("ensureGreen timed out, cluster state:\n{}\n{}", client().admin().cluster().prepareState().get().getState(),
                client().admin().cluster().preparePendingClusterTasks().get());
            assertThat("timed out waiting for green state", actionGet.isTimedOut(), equalTo(false));
        }
        assertThat(actionGet.getStatus(), equalTo(ClusterHealthStatus.GREEN));
        logger.debug("indices {} are green", indices.length == 0 ? "[_all]" : indices);
        return actionGet.getStatus();
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return getInstanceFromNode(NamedXContentRegistry.class);
    }

    protected boolean forbidPrivateIndexSettings() {
        return true;
    }

}
