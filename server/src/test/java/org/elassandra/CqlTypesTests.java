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
package org.elassandra;

import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import com.google.common.net.InetAddresses;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.UntypedResultSet.Row;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.marshal.DoubleType;
import org.apache.cassandra.db.marshal.TupleType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.serializers.SimpleDateSerializer;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.lucene.search.join.ScoreMode;
import org.elassandra.cluster.Serializer;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.nested.InternalNested;
import org.elasticsearch.search.aggregations.metrics.sum.InternalSum;
import org.elasticsearch.search.sort.NestedSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 * Elassandra CQL types mapping tests.
 * @author vroyer
 *
 */
public class CqlTypesTests extends ESSingleNodeTestCase {

    @Test
    public void testTest() throws Exception {
        createIndex("cmdb");
        ensureGreen("cmdb");

        process(ConsistencyLevel.ONE,"CREATE TABLE cmdb.server ( name text, ip inet, netmask int, prod boolean, primary key (name))");
        XContentBuilder mapping1 = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", ".*")
                    .startObject("properties")
                        .startObject("name")
                            .field("type", "keyword")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().preparePutMapping("cmdb")
                .setType("server")
                .setSource(mapping1)
                .get());

        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('localhost','127.0.0.1',8,true)");
        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('my-server','123.45.67.78',24,true)");
        StorageService.instance.forceKeyspaceFlush("cmdb","server"); // flush to build the index correctly.

        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("my-server").get().isExists(), equalTo(true));
        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("localhost").get().isExists(), equalTo(true));

        assertEquals(client().prepareIndex("cmdb", "server", "bigserver234")
            .setSource("{\"ip\": \"22.22.22.22\", \"netmask\":32, \"prod\" : true, \"description\": \"my big server\" }", XContentType.JSON)
            .get().getResult(), DocWriteResponse.Result.CREATED);

        assertThat(client().prepareSearch().setIndices("cmdb").setTypes("server").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3L));
    }

    @Test
    public void testAllTypesTest() throws Exception {
        createIndex("ks1");
        ensureGreen("ks1");

        process(ConsistencyLevel.ONE,
                "CREATE TABLE ks1.natives (c1 text primary key, c2 text, c3 timestamp, c4 int, c5 bigint, c6 double, c7 float, c8 boolean, c9 blob, c10 uuid, c11 timeuuid, c12 smallint, c13 tinyint, c14 decimal)");
        assertAcked(client().admin().indices()
                .preparePutMapping("ks1")
                .setType("natives")
                .setSource("{ \"natives\" : { \"discover\" : \".*\", \"properties\": { \"c2\":{ \"type\":\"keyword\" }}}}", XContentType.JSON)
                .get());

        // {"c2": "toto", "c3" : "2016-10-10", "c4": 1, "c5":44, "c6":1.0, "c7":2.22, "c8": true, "c9":"U29tZSBiaW5hcnkgYmxvYg==" }
        assertThat(client().prepareIndex("ks1", "natives", "1")
                .setSource("{\"c2\": \"toto\", \"c3\" : \"2016-10-10T00:00:00.000Z\", \"c4\": 1, \"c5\":44, \"c6\":1.0, \"c7\":2.22, \"c8\": true, \"c9\":\"U29tZSBiaW5hcnkgYmxvYg==\", \"c10\":\"ae8c9260-dd02-11e6-b9d5-bbfb41c263ba\",\"c11\":\"ae8c9260-dd02-11e6-b9d5-bbfb41c263ba\", \"c12\":1, \"c13\":1, \"c14\":\"3.1416\"  }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        Map<String,Object> fields = client().prepareSearch("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c2:toto"))
                .get().getHits().getHits()[0]
                .getSourceAsMap();
        assertThat(fields.get("c2"),equalTo("toto"));
        assertThat(fields.get("c3").toString(),equalTo("2016-10-10T00:00:00.000Z"));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(44));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.22));
        assertThat(fields.get("c8"),equalTo(true));
        assertThat(fields.get("c9"),equalTo("U29tZSBiaW5hcnkgYmxvYg=="));
        assertThat(fields.get("c12"),equalTo(1));
        assertThat(fields.get("c13"),equalTo(1));
        assertThat(fields.get("c14"),equalTo("3.1416"));

        process(ConsistencyLevel.ONE,"insert into ks1.natives (c1,c2,c3,c4,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14) VALUES ('tutu', 'titi', '2016-11-11T00:00:00.000Z', 1, 45, 1.0, 2.23, false,textAsBlob('bdb14fbe076f6b94444c660e36a400151f26fc6f'),ae8c9260-dd02-11e6-b9d5-bbfb41c263ba,ae8c9260-dd02-11e6-b9d5-bbfb41c263ba, 1, 1, 3.1416)");
        assertThat(client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2L));

        fields = client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c5:45")).get().getHits().getHits()[0].getSourceAsMap();
        assertThat(fields.get("c2"), equalTo("titi"));
        assertThat(fields.get("c3"), equalTo("2016-11-11T00:00:00.000Z"));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(45));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.23));
        assertThat(fields.get("c8"),equalTo(false));
        assertThat(fields.get("c12"),equalTo(1));
        assertThat(fields.get("c13"),equalTo(1));
        assertThat(fields.get("c14"),equalTo("3.1416"));
    }

    @Test
    public void testSinglePkTypesTest() throws Exception {
        createIndex("ks");
        ensureGreen("ks");

        String[] types = new String[] { "text","int","smallint","tinyint","bigint","double","float","boolean","blob","timestamp","date","inet","uuid","decimal" };
        Object[] values = new Object[] { "foo", 1, (short)1, (byte)1, 2L, new Double(3.14), new Float(3.14), true, ByteBuffer.wrap("toto".getBytes("UTF-8")), new Date(), (int)LocalDate.now().toEpochDay(), InetAddresses.forString("127.0.0.1"), UUID.randomUUID(), new BigDecimal("3.1416") };
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            Object value = values[i];
            System.out.println("insert pk type="+type);
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"CREATE TABLE ks.t%s (pk%s %s PRIMARY KEY, v text)", type, type, type));
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"INSERT INTO ks.t%s (pk%s, v) VALUES (?, 'foobar')", type, type), value);
        }

        // flush for rebuild_index
        StorageService.instance.forceKeyspaceFlush("ks");
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("discover pk type="+type);
            CreateIndexRequestBuilder builder = client().admin().indices().prepareCreate("ks"+i);
            builder.request()
                .settings(Settings.builder().put("index.keyspace","ks"))
                .mapping(String.format(Locale.ROOT,"t%s",type), String.format(Locale.ROOT,"{ \"t%s\" : { \"discover\" : \".*\" }}",type), XContentType.JSON);
            assertAcked(builder.get());
        }

        assertTrue(waitIndexRebuilt("ks", Stream.of(types).map(t -> "t"+t).collect(Collectors.toList()), 10000));

        // search
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("search pk type="+type+" in index ks"+i);

            assertThat(client().prepareSearch()
                    .setIndices("ks"+i)
                    .setTypes(String.format(Locale.ROOT,"t%s",type))
                    .setQuery(QueryBuilders.matchAllQuery())
                    .storedFields("_id","_routing","_ttl","_timestamp","_source","v")
                    .get().getHits().getTotalHits(), equalTo(1L));
        }
    }

    @Test
    public void testCompoundPkTypesTest() throws Exception {
        createIndex("ks");
        ensureGreen("ks");

        Date now = new Date();
        String[] types = new String[] { "text", "int","smallint","tinyint", "bigint","double","float","boolean","blob","timestamp","date", "inet","uuid","timeuuid","timeuuid","decimal" };
        String[] names = new String[] { "text", "int","smallint","tinyint", "bigint","double","float","boolean","blob","timestamp","date2", "inet","uuid","timeuuid","timeuuid2","decimal"};
        Object[] values = new Object[] { "foo", 1, (short)1, (byte)1, 2L, new Double(3.14), new Float(3.14), true, ByteBuffer.wrap("toto".getBytes("UTF-8")), new Date(), (int)LocalDate.now().toEpochDay(), InetAddresses.forString("127.0.0.1"), UUID.randomUUID(), UUIDGen.getTimeUUID(now.getTime()), UUIDGen.getTimeUUID(now.getTime()), new BigDecimal("3.1416") };
        int randomCk = randomInt(types.length-1);
        int randomVal= randomInt(types.length-1);
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            String name = names[i];
            System.out.println("insert pk name="+name+" type="+type);
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"CREATE TABLE ks.t%s (pk%s %s, ck %s, v %s, PRIMARY KEY (pk%s,ck))", name, name, type, types[randomCk], types[randomVal], name));
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"INSERT INTO ks.t%s (pk%s, ck, v) VALUES (?, ?, ?)", name, name), values[i], values[randomCk], values[randomVal]);
        }

        // flush for rebuild_index
        StorageService.instance.forceKeyspaceFlush("ks");
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            String name = names[i];
            String mapping = name.equals("timeuuid2") ?
                    String.format(Locale.ROOT,"{ \"discover\" : \"^((?!pktimeuuid2).*)\", \"properties\":{ \"pktimeuuid2\":{ \"type\":\"date\", \"cql_collection\":\"singleton\",\"cql_partition_key\":true,\"cql_primary_key_order\":0}}}") :
                    String.format(Locale.ROOT,"{ \"discover\" : \".*\" }");
            System.out.println("discover index=ks"+i+" pk name="+name+" type="+type+" mapping="+mapping);
            CreateIndexRequestBuilder builder = client().admin().indices().prepareCreate("ks"+i);
            builder.request()
                .settings(Settings.builder().put("index.keyspace","ks"))
                .mapping("t"+name, mapping, XContentType.JSON);
            assertAcked(builder.get());
        }

        assertTrue(waitIndexRebuilt("ks", Stream.of(names).map(t -> "t"+t).collect(Collectors.toList()), 10000));

        // search for indexed documents
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            String name = names[i];
            System.out.println("search index=ks"+i+" pk name="+name+" type="+type);
            assertThat(client().prepareSearch()
                    .setIndices("ks"+i)
                    .setTypes(String.format(Locale.ROOT,"t%s", name))
                    .setQuery(QueryBuilders.matchAllQuery())
                    .storedFields("_id","_routing","_ttl","_timestamp","_source","ck","v")
                    .get().getHits().getTotalHits(), equalTo(1L));
        }

        // range delete to test delete by query
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            String name = names[i];
            if (!type.equals("blob") && !types[randomCk].equals("blob")) {
                System.out.println("delete pk name="+name+" type="+type+" value="+values[i]+" ck type="+types[randomCk]+" value="+values[randomCk]);
                process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"DELETE FROM ks.t%s WHERE pk%s = ? AND ck >= ?", name, name), values[i], values[randomCk]);
                // blob not supported for delete by query
                assertThat("search in ks"+i+" type="+String.format(Locale.ROOT,"t%s", name)+" cql_type="+type,
                    client().prepareSearch()
                    .setIndices("ks"+i)
                    .setTypes(String.format(Locale.ROOT,"t%s", name))
                    .setQuery(QueryBuilders.matchAllQuery())
                    .get().getHits().getTotalHits(), equalTo(0L));
            }
        }
    }

    @Test
    public void testTextGeohashMapping() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"create type test.geo_point (lat double, lon double);");
        process(ConsistencyLevel.ONE,"create table test.geoloc (geohash text, id uuid, coord frozen<geo_point>, comment text, primary key ((geohash),id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("geoloc")
                .setSource("{ \"geoloc\" : { \"discover\":\"^((?!geohash).*)\", \"properties\": { \"geohash\": { \"type\": \"geo_point\", \"cql_collection\":\"singleton\",\"cql_partition_key\" : true,\"cql_primary_key_order\" : 0 } }}}", XContentType.JSON).get());

        GeoPoint geo_point = new GeoPoint(-25.068403, 29.411767);
        ByteBuffer[] elements = new ByteBuffer[] {
                Serializer.serialize("test", "geoloc", DoubleType.instance, GeoUtils.LATITUDE, -25.068403, null),
                Serializer.serialize("test", "geoloc", DoubleType.instance, GeoUtils.LONGITUDE, 29.411767, null)
        };
        process(ConsistencyLevel.ONE,"INSERT INTO test.geoloc (geohash, id, coord, comment) VALUES (?,?,?,?)",
                geo_point.geohash(), UUID.randomUUID(), TupleType.buildValue(elements), "blabla");
        SearchResponse rsp = client().prepareSearch().setIndices("test").setTypes("geoloc")
                .setQuery(QueryBuilders.boolQuery()
                    .must(QueryBuilders.matchAllQuery())
                    .filter(QueryBuilders.geoDistanceQuery("geohash").distance("20km").point(-25.068403, 29.411767)))
                .get();
        assertThat(rsp.getHits().getTotalHits(),equalTo(1L));
    }

    // #282
    /* TODO: fix that
    @Test
    public void testGeoShapeMapping() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("_source").field("enabled", true).endObject()
                    .startObject("properties")
                        .startObject("geom").field("type", "geo_shape").field("cql_collection", "singleton").endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");
    }
    */


    // #74 test
    @Test
    public void testUUID() throws Exception {
        createIndex("test1");
        ensureGreen("test1");

        createIndex("test2");
        ensureGreen("test2");

        process(ConsistencyLevel.ONE,"create table test1.pk_uuid (pk_uuid uuid, column_not_uuid text, primary key(pk_uuid));");
        process(ConsistencyLevel.ONE,"create table test2.pk_not_uuid (pk_not_uuid text, column_uuid uuid, primary key(pk_not_uuid));");

        assertAcked(client().admin().indices().preparePutMapping("test1").setType("pk_uuid").setSource("{ \"pk_uuid\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());
        assertAcked(client().admin().indices().preparePutMapping("test2").setType("pk_not_uuid").setSource("{ \"pk_not_uuid\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());

        assertThat(client().prepareIndex("test1", "pk_uuid", "bacc6c75-91b8-4a86-a408-ff7bafac535d").setSource("{ \"column_not_uuid\": \"a value\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test1", "pk_uuid", "bacc6c75-91b8-4a86-a408-ff7bafac535d").setSource("{ \"column_not_uuid\": \"a value\", \"pk_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test2", "pk_not_uuid", "pk2").setSource("{ \"column_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test2", "pk_not_uuid", "pk2").setSource("{ \"column_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\", \"pk_not_uuid\":\"pk2\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
    }

    // #91 test
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testMapAsObject" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testMapAsObject() throws Exception {
        createIndex("test");
        ensureGreen("test");

        XContentBuilder mappingMapOpaque = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", ".*")
                .endObject();

        XContentBuilder mappingMap = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", "^((?!(strings|metrics)).*)")
                    .startObject("properties")
                        .startObject("strings")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_struct", "map")
                        .endObject()
                        .startObject("metrics")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_struct", "map")
                        .endObject()
                    .endObject()
                .endObject();

        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, x int, strings map<text, text>, metrics map<text, int>, PRIMARY KEY (id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource(mappingMapOpaque)
                .get());

        assertAcked(client().admin().indices().prepareCreate("test2")
                .setSettings(Settings.builder().put("index.keyspace", "test").build())
                .addMapping("event_test", mappingMap)
                .get());

        long N = 10;
        for(int i=0; i < N; i++) {
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id, x, strings, metrics) VALUES ('%d', %d, {'key%d':'b%d'}, {'k1':%d})", i, i, i, i, i));
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test")
                .setQuery(QueryBuilders.nestedQuery("strings",
                        QueryBuilders.termQuery("strings.key1", "b1"),
                        RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test")
                .setQuery(QueryBuilders.nestedQuery("strings",
                        QueryBuilders.termsQuery("strings.key1", "x", "b1"),
                        RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test")
                .setQuery(QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termsQuery("x", new int[] {1,2}))
                        .should(QueryBuilders.nestedQuery("strings", QueryBuilders.termsQuery("strings.key1", "x", "b1"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                        .should(QueryBuilders.nestedQuery("strings", QueryBuilders.termsQuery("strings.key2", "x", "b2"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                        .minimumShouldMatch(1))
                .get().getHits().getTotalHits(), equalTo(2L));

        // test2 support string query because mapping is available
        assertThat(client().prepareSearch().setIndices("test2").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test2").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings", QueryBuilders.termQuery("strings.key1", "b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test2").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings", QueryBuilders.queryStringQuery("strings.key1:b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));

        // aggregation on key
        SearchResponse rsp = client().prepareSearch().setIndices("test").setTypes("event_test")
                .setQuery(QueryBuilders.matchAllQuery())
                .addAggregation(AggregationBuilders
                        .nested("metrics_agg", "metrics")
                        .subAggregation(AggregationBuilders.sum("k1_total").field("metrics.k1")))
                .get();
        InternalNested metricsAgg = rsp.getAggregations().get("metrics_agg");
        InternalSum sumAgg = metricsAgg.getAggregations().get("k1_total");
        assertEquals(45.0, sumAgg.getValue(), 0.1);
    }

    @Test
    public void testMapAsObjectWithIndexCreation() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, foo text, strings map<text, text>, PRIMARY KEY (id));");

        XContentBuilder mappingMap = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", "^((?!strings).*)")
                    .startObject("properties")
                        .startObject("strings")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_struct", "map")
                        .endObject()
                    .endObject()
                .endObject();

        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,foo) VALUES ('%d','bar')", 1));
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource(mappingMap)
                .get());

        long N = 10;
        for(int i=0; i < N; i++)
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,strings) VALUES ('%d',{'key%d':'b%d'})", i, i, i));

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings", QueryBuilders.queryStringQuery("strings.key1:b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }

    @Test
    public void testNonEmptyMapAsObjectWithIndexCreation() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, foo text, strings map<text, text>, PRIMARY KEY (id));");
        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,foo) VALUES ('%d','bar')", 1));

        long N = 10;
        for(int i=0; i < N; i++)
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,strings) VALUES ('%d',{'key%d':'b%d'})", i, i, i));

        XContentBuilder mappingMap = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", "^((?!strings).*)")
                    .startObject("properties")
                        .startObject("strings")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_struct", "map")
                        .endObject()
                    .endObject()
                .endObject();

        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource(mappingMap)
                .get());

        // wait asynchronous index rebuild triggered by creating the index.
        waitIndexRebuilt("test", Collections.singletonList("event_test"), 10000L);

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings", QueryBuilders.queryStringQuery("strings.key1:b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }

    // #257 The index is still keeping dynamic update even that index mapping has configured dynamic: false
    @Test
    public void testMapDynamicFalse() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id")
                            .field("type", "keyword")
                            .field("cql_collection", "singleton")
                            .field("cql_primary_key_order", 0)
                            .field("cql_partition_key", true)
                        .endObject()
                        .startObject("name")
                            .field("type", "keyword")
                            .field("cql_collection", "singleton")
                        .endObject()
                        .startObject("counters")
                            .field("type", "nested")
                            .field("cql_struct", "map")
                            .field("cql_collection", "singleton")
                            .field("dynamic", false)
                            .startObject("properties")
                                .startObject("retry").field("type", "integer").endObject()
                                .startObject("fail").field("type", "integer").endObject()
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("mytest").addMapping("mymaptable", mapping));
        ensureGreen("mytest");

        process(ConsistencyLevel.ONE,"insert into mytest.mymaptable (id, name, counters) values ('john.d', 'john', {'tps':1000, 'retry':1});");
        process(ConsistencyLevel.ONE,"insert into mytest.mymaptable (id, name, counters) values ('Kelly.S', 'kelly', {'tps':1200, 'fail':2, 'pending':100}); ");

        assertThat(client().prepareSearch().setIndices("mytest").setTypes("mymaptable").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2L));
        assertThat(client().prepareSearch().setIndices("mytest").setTypes("mymaptable").setQuery(QueryBuilders.nestedQuery("counters", QueryBuilders.queryStringQuery("counters.retry:1"),
                RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("mytest").setTypes("mymaptable").setQuery(QueryBuilders.nestedQuery("counters", QueryBuilders.queryStringQuery("counters.fail:2"),
                RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("mytest").setTypes("mymaptable").setQuery(QueryBuilders.nestedQuery("counters", QueryBuilders.queryStringQuery("counters.tps:1200"),
                RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(0L));
    }

    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testMapAsObjectWithDynamicMapping" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testMapAsObjectWithDynamicMapping() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, strings map<text, text>, PRIMARY KEY (id));");

        XContentBuilder mappingMap = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("event_test")
                        .field("discover", "^((?!strings).*)")
                        .startObject("properties")
                            .startObject("strings")
                                .field("type", "nested")
                                .field("cql_collection", "singleton")
                                .field("cql_struct", "map")
                            .endObject()
                        .endObject()
                        .startArray("dynamic_templates")
                            .startObject()
                                .startObject("strings_template")
                                    .field("match", "strings.*")
                                    .startObject("mapping")
                                        .field("type", "text")
                                    .endObject()
                                .endObject()
                            .endObject()
                        .endArray()
                    .endObject()
                .endObject();

        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource(mappingMap)
                .get());

        long N = 10;
        for(int i=0; i < N; i++)
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,strings) VALUES ('%d',{'key%d':'test b%d'})", i, i, i));

        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings",QueryBuilders.matchQuery("strings.key1", "test b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }


    // ./gradlew :server:test -Dtests.seed=1EC932B209B2305F -Dtests.security.manager=false -Dtests.locale=fur-IT -Dtests.timezone=America/Whitehorse -Dtests.class=org.elassandra.CqlTypesTests  -Dtests.method="testCoercWithDynamicMapping"
    //#323
    @Test
    public void testCoercWithDynamicMapping() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TABLE test.ents_test (id text, int_coerce bigint, int_not_coerce bigint, PRIMARY KEY (id));");

        XContentBuilder mappingMap = XContentFactory.jsonBuilder()
            .startObject()
                .startObject("ents_test")
                    .field("discover", ".*")
                    .startArray("dynamic_templates")
                        .startObject()
                            .startObject("integer_fields")
                                .field("match", "int_*")
                                .startObject("mapping")
                                    .field("coerce", true)
                                    .field("type", "long")
                                .endObject()
                            .endObject()
                        .endObject()
                    .endArray()
                .endObject()
            .endObject();

        assertAcked(client().admin().indices().preparePutMapping("test").setType("ents_test")
            .setSource(mappingMap)
            .get());

        long N = 10;
        for(int i=0; i < N; i++) {
//            process(ConsistencyLevel.ONE, String.format(Locale.ROOT, "insert into test.ents_test (id, int_not_coerce) VALUES ('%d', %d)", i, i));
            client().prepareIndex("test", "ents_test").setSource("int_not_coerce", i).execute().actionGet();
        }
        for(int i=0; i < N; i++) {
            //process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.ents_test (id, int_coerce) VALUES ('%d', '%d')", i, i));
            client().prepareIndex("test", "ents_test").setSource("int_coerce", ""+i).execute().actionGet();
        }


        assertThat(client().prepareSearch().setIndices("test").setTypes("ents_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N*2));
        assertThat(client().prepareSearch().setIndices("test").setTypes("ents_test").setQuery(QueryBuilders.termQuery("int_coerce", 1)).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("ents_test").setQuery(QueryBuilders.termQuery("int_not_coerce", 1)).get().getHits().getTotalHits(), equalTo(1L));
    }


    // #91 test
    // see https://www.elastic.co/guide/en/elasticsearch/reference/2.4/null-value.html
    // see https://www.datastax.com/dev/blog/cql3_collections (empty list = null)
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testNullValue" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testNullValue() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id")
                            .field("type", "keyword")
                            .field("cql_collection", "singleton")
                            .field("cql_primary_key_order", 0)
                            .field("cql_partition_key", true)
                        .endObject()
                        .startObject("foo")
                            .field("type", "integer")
                            .field("cql_collection", "singleton")
                            .field("null_value", 0)
                        .endObject()
                        .startObject("status_code")
                            .field("type", "keyword")
                            .field("null_value", "NULL")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");

        assertThat(client().prepareIndex("test", "my_type", "1").setSource("{\"status_code\": \"OK\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "2").setSource("{\"status_code\": [ \"NOK\", \"OK\" ] }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "3").setSource("{\"status_code\": null }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "4").setSource("{\"status_code\": [] }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "5").setSource("{\"status_code\": \"NULL\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        assertThat(client().prepareSearch().setIndices("test").setTypes("my_type").setQuery(QueryBuilders.queryStringQuery("status_code:NULL")).get().getHits().getTotalHits(), equalTo(3L));
    }

    // #112 test
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testSets" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testSets() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
               .startObject()
                   .startObject("properties")
                       .startObject("items")
                           .field("type", "object")
                           .field("cql_collection", "set")
                           .field("cql_udt_name", "item")
                           .startObject("properties")
                               .startObject("name")
                                   .field("type", "keyword")
                                   .field("cql_collection", "singleton")
                               .endObject()
                           .endObject()
                        .endObject()
                        .startObject("item")
                           .field("type", "object")
                           .field("cql_collection", "singleton")
                           .field("cql_udt_name", "item")
                           .startObject("properties")
                               .startObject("name")
                                   .field("type", "keyword")
                                   .field("cql_collection", "singleton")
                                .endObject()
                           .endObject()
                        .endObject()
                        .startObject("attrs")
                            .field("type", "keyword")
                            .field("cql_collection", "set")
                         .endObject()
                  .endObject()
             .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("tab_set", mapping));
        ensureGreen("test");
        process(ConsistencyLevel.ONE,"insert into test.tab_set (\"_id\",item,items,attrs) values ('1',{name:'hello'},{{name:'world'},{name:'heaven'}},{'blue','red'})");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:world")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("red")).get().getHits().getTotalHits(), equalTo(1L));

        process(ConsistencyLevel.ONE,"insert into test.tab_set (\"_id\",item,items,attrs) values ('1',{name:'hello'},{{name:'heaven'}},{'blue'})");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("red")).get().getHits().getTotalHits(), equalTo(0L));

        process(ConsistencyLevel.ONE,"update test.tab_set set items = items + {{name:'world'}} where \"_id\" = '1'");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:world")).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE,"update test.tab_set set attrs = attrs + {'yellow'} where \"_id\" = '1'");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("yellow")).get().getHits().getTotalHits(), equalTo(1L));
    }

    // #161 Search over a nested set returns wrong inner_hits
    public void testNestedSets() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TYPE IF NOT EXISTS test.model (name VARCHAR, date TIMESTAMP);");
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.make_models (make VARCHAR, models SET<FROZEN<model>>, PRIMARY KEY (make));");

        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("make_models")
                        .field("discover", ".*")
                    .endObject()
                .endObject();
        assertTrue(client().admin().indices().preparePutMapping("test").setType("make_models").setSource(mapping).get().isAcknowledged());

        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'dart', date : '2018-01-29 11:53:00'}} WHERE make='dodge';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'polara', date : '2018-01-29 11:54:00'}} WHERE make='dodge';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'mustang', date : '2018-02-01 11:50:00'}} WHERE make='ford';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'galaxie', date : '2018-02-01 11:51:00'}} WHERE make='ford';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'camaro', date : '2018-02-01 12:50:00'}} WHERE make='gm';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'chevelle', date : '2018-02-01 12:52:00'}} WHERE make='gm';");

        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'mustang', date : '2018-02-01 11:50:00'}} WHERE make='ford';");
        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'galaxie', date : '2018-02-01 11:51:00'}} WHERE make='ford';");
        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }

    // #315 Nested objects' fields not indexed
    public void testNestedFieldMappingUpdate() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("nested")
                            .field("type", "nested")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test-issue-index").addMapping("test-issue", mapping));
        ensureGreen("test-issue-index");

        assertThat(client().prepareIndex("test-issue-index", "test-issue","0")
                .setSource("{\n" +
                        "\"fieldA\": \"A\",\n" +
                        "\"fieldB\": \"B\"" +
                        "}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        assertThat(client().prepareIndex("test-issue-index", "test-issue","1")
                .setSource("{\n" +
                        "\"fieldA\": \"A\",\n" +
                        "\"fieldB\": \"B\",\n" +
                        "\"nested\": {\n" +
                        "     \"fieldC\": \"C\",\n" +
                        "     \"fieldD\": \"D\",\n" +
                        "     \"fieldE\": \"E\",\n" +
                        "     \"fieldF\": \"F\"\n" +
                        "   }\n" +
                        "}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        assertThat(client().prepareIndex("test-issue-index", "test-issue","2")
                .setSource("{\n" +
                        "\"fieldA\": \"A\",\n" +
                        "\"fieldB\": \"B\",\n" +
                        "\"nested\": {\n" +
                        "    \"fieldG\": \"G\",\n" +
                        "    \"fieldH\": \"H\",\n" +
                        "    \"fieldI\": \"I\",\n" +
                        "    \"fieldJ\": \"J\"\n" +
                        "  }\n" +
                        "}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));


        SearchResponse resp = client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.matchAllQuery())
                .setFetchSource(true)
                .get();
        System.out.println("hits[0]="+resp.getHits().getHits()[0].getSourceAsString());
        System.out.println("hits[1]="+resp.getHits().getHits()[1].getSourceAsString());
        System.out.println("hits[2]="+resp.getHits().getHits()[2].getSourceAsString());

        assertThat(resp.getHits().getTotalHits(), equalTo(3L));

        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.termQuery("fieldA.keyword", "A"))
                .get().getHits().getTotalHits(), equalTo(3L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.termQuery("fieldB.keyword", "B"))
                .get().getHits().getTotalHits(), equalTo(3L));

        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldC.keyword", "C"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldD.keyword", "D"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldE.keyword", "E"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldF.keyword", "F"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldG.keyword", "G"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldH.keyword", "H"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldI.keyword", "I"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test-issue-index")
                .setTypes("test-issue")
                .setQuery(QueryBuilders.nestedQuery("nested", QueryBuilders.termQuery("nested.fieldJ.keyword", "J"), RandomPicks.randomFrom(random(), ScoreMode.values())))
                .get().getHits().getTotalHits(), equalTo(1L));

    }

    // #197 Deletion of a List element removes the document on ES
    public void testDeleteInUDTList() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE, "CREATE TYPE test.type_test (id text);");
        process(ConsistencyLevel.ONE, "CREATE TABLE test.table_test (" +
                "    id1 text," +
                "    id2 text," +
                "    list list<frozen<type_test>>," +
                "    PRIMARY KEY (id1, id2)" +
                ");");

        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("table_test")
                        .field("discover", ".*")
                    .endObject()
                .endObject();
        assertTrue(client().admin().indices().preparePutMapping("test").setType("table_test").setSource(mapping).get().isAcknowledged());
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list + [{ id:'foo'}] where id1='1' and id2='2';");
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list + [{ id:'bar'}] where id1='1' and id2='2';");

        SearchResponse resp = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        List<Object> list = (List<Object>) resp.getHits().getAt(0).getSourceAsMap().get("list");
        assertThat(list.size(), equalTo(2));
        Map<String, Object> map = (Map<String, Object>)list.get(0);
        assertThat(map.size(), equalTo(1));

        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list - [{ id:'bar'}] where id1='1' and id2='2';");
        SearchResponse resp2 = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp2.getHits().getTotalHits(), equalTo(1L));
        Map<String, Object> map2 = (Map<String, Object>) resp2.getHits().getAt(0).getSourceAsMap().get("list");
        assertThat(map2.size(), equalTo(1));

        // Row delete when all value updated to null, see https://issues.apache.org/jira/browse/CASSANDRA-11805
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list - [{ id:'foo'}] where id1='1' and id2='2';");
        SearchResponse resp3 = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp3.getHits().getTotalHits(), equalTo(0L));

        process(ConsistencyLevel.ONE, "INSERT INTO test.table_test (id1, id2, list) VALUES ('1', '2', null);");
        SearchResponse resp4 = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp4.getHits().getTotalHits(), equalTo(1L));
    }

    // #199 unit test
    public void testNullUpdate() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE, "CREATE TABLE test.t1 (" +
                "    id1 text," +
                "    id2 text," +
                "    id3 text," +
                "    id4 text," +
                "    PRIMARY KEY (id1, id2)" +
                ");");

        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("t1")
                        .field("discover", ".*")
                    .endObject()
                .endObject();
        assertTrue(client().admin().indices().preparePutMapping("test").setType("t1").setSource(mapping).get().isAcknowledged());
        process(ConsistencyLevel.ONE, "UPDATE test.t1 SET id3 = 'foo' where id1='1' and id2='2';");
        SearchResponse resp = client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));

        process(ConsistencyLevel.ONE, "UPDATE test.t1 SET id4 = 'foo' where id1='1' and id2='2';");
        resp = client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));

        process(ConsistencyLevel.ONE, "UPDATE test.t1 SET id4 = null where id1='1' and id2='2';");
        resp = client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));

        process(ConsistencyLevel.ONE, "UPDATE test.t1 SET id3 = null where id1='1' and id2='2';");
        resp = client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(0L));
    }

    // test CQL timeuuid, date and time mapping.
    @Test
    public void testTimes() throws Exception {
        createIndex("test");
        ensureGreen("test");

        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, start timeuuid, end timeuuid, day date, hour time, PRIMARY KEY (id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource("{ \"event_test\" : { \"discover\" : \"^((?!end).*)\", \"properties\":{ \"end\":{\"type\":\"date\",\"cql_collection\":\"singleton\"}}}}", XContentType.JSON).get());

        LocalDate localDate = LocalDate.parse("2010-10-10");
        Instant instant = Instant.from(localDate.atStartOfDay(ZoneId.of("GMT")));
        UUID end = UUIDGen.getTimeUUID(instant.toEpochMilli());
        UUID start = UUIDGen.getTimeUUID();

        process(ConsistencyLevel.ONE,"INSERT INTO test.event_test (id , start , end, day, hour) VALUES (?,?,?,?,?)",
                "1", start, end, SimpleDateSerializer.dateStringToDays("2010-10-10"), 10*3600*1000000000L);

        SearchResponse resp = client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.queryStringQuery("day:2010-10-10")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("day"), equalTo("2010-10-10T00:00:00.000Z"));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("hour"), equalTo(36000000000000L));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("start"), equalTo(start.toString()));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("end"), equalTo("2010-10-10T00:00:00.000Z"));
    }

    // see issue #128
    @Test
    public void testFetchMultipleTypes() throws Exception {
        createIndex("test");
        ensureGreen("test");

        assertThat(client().prepareIndex("test", "typeA", "1").setSource("{ \"a\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "typeA", "2").setSource("{ \"b\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "typeA", "3").setSource("{ \"c\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        SearchResponse resp = client().prepareSearch().setIndices("test").setQuery(QueryBuilders.queryStringQuery("q=aaa")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(3L));
    }

    // see issue #142
    @Test
    public void testNestedDate() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("event_timestamp")
                            .field("type", "date")
                            .field("format", "strict_date_hour_minute_second||epoch_millis")
                            .field("cql_collection", "singleton")
                        .endObject()
                        .startObject("event_info")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_udt_name", "event_info_udt")
                            .field("dynamic", "false")
                            .startObject("properties")
                               .startObject("event_timestamp")
                                .field("type", "date")
                                .field("format", "strict_date_hour_minute_second||epoch_millis")
                                .field("cql_collection", "singleton")
                            .endObject()
                        .endObject()
                    .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");


        assertThat(client().prepareIndex("test", "my_type", "1")
                .setSource("{\"event_info\": {},\"event_timestamp\": \"2017-11-21T16:30:00\"}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "2")
                .setSource("{\"event_info\": {\"event_timestamp\": \"2017-11-21T16:30:00\"},\"event_timestamp\": \"2017-11-21T16:30:00\"}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        SearchResponse resp = client().prepareSearch().setIndices("test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(2L));
        assertThat(resp.getFailedShards(), equalTo(0));
    }

    // #222 test
    @Test
    public void testDateInPartitionKey() throws Exception {
        createIndex("example");
        ensureGreen("example");

        process(ConsistencyLevel.ONE,"CREATE TABLE example.sessions ( id timeuuid, project_id uuid, day date, PRIMARY KEY ((project_id, day), id)) WITH CLUSTERING ORDER BY (id DESC);");
        assertAcked(client().admin().indices().preparePutMapping("example").setType("sessions").setSource("{ \"sessions\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());
        process(ConsistencyLevel.ONE,"INSERT INTO example.sessions (id, project_id, day) VALUES (now(), uuid(), toDate(now()));");

        SearchResponse resp = client().prepareSearch().setIndices("example").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
     }

    // #292 test
    @Test
    public void testStringMappedAsMultiFields() throws Exception {
        process(ConsistencyLevel.ONE, "CREATE KEYSPACE example WITH replication = {'class': 'NetworkTopologyStrategy', 'DC1': 1};");
        process(ConsistencyLevel.ONE,"CREATE TABLE example.sessions (id text, day text, PRIMARY KEY ((id)));");
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id")
                            .field("type", "keyword")
                            .field("cql_collection", "singleton")
                            .field("cql_primary_key_order", 0)
                            .field("cql_partition_key", true)
                        .endObject()
                        .startObject("day")
                            .field("type", "keyword").field("cql_collection", "singleton")
                             .startObject("fields")
                                .startObject("date").field("type", "date").field("format","yyyy-MM-dd").endObject()
                                .startObject("numeric").field("type", "double").endObject()
                                .startObject("addr").field("type", "ip").endObject()
                                .startObject("bool").field("type", "boolean").endObject()
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("example")
                .setSettings(Settings.builder().put("index.mapping.ignore_malformed", true).build())
                .addMapping("sessions", mapping));
        process(ConsistencyLevel.ONE,"INSERT INTO example.sessions (id, day) VALUES ('1', '2019-05-01');");
        SearchResponse resp = client().prepareSearch().setIndices("example").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
     }

    @Test
    public void testNestedMappingUpdate() throws Exception {
        createIndex("test");
        ensureGreen("test");

        assertThat(client().prepareIndex("test", "my_type", "1")
                .setSource("{\"event_info\": {}}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "2")
                .setSource("{\"event_info\": {\"foo\":\"bar\"}}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "3")
                .setSource("{\"event_info\": {\"foo2\":\"bar2\"}}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        UntypedResultSet rs = process(ConsistencyLevel.ONE,"select * from system_schema.types WHERE keyspace_name='test' and type_name='my_type_event_info';");
        assertThat(rs.size(), equalTo(1));
        Row row = rs.one();
        assertTrue(row.has("field_names"));
        List<String> filed_names = row.getList("field_names", UTF8Type.instance);
        System.out.println("filed_names=" + filed_names);
        assertTrue(filed_names.contains("foo"));
        assertTrue(filed_names.contains("foo2"));
    }

    @Test
    public void testClusteringOrderColumnDiscover() throws Exception {
        process(ConsistencyLevel.ONE, "CREATE KEYSPACE ks WITH replication = {'class': 'NetworkTopologyStrategy', 'DC1': 1};");
        process(ConsistencyLevel.ONE, "CREATE TABLE ks.test (id int, timestamp timestamp, PRIMARY KEY (id, timestamp)) WITH CLUSTERING ORDER BY (timestamp DESC)");
        assertAcked(client().admin().indices().prepareCreate("ks").addMapping("test", discoverMapping("test")));
    }

    // Cannot create indexes in case the table has materialized views #274
    @Test
    public void testElasticIndexWithMaterializedView() throws Exception {
        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "CREATE KEYSPACE test_keyspace WITH replication = {'class': 'NetworkTopologyStrategy', '%s': '1'}", DatabaseDescriptor.getLocalDataCenter()));
        process(ConsistencyLevel.ONE,"CREATE TABLE test_keyspace.numbers(number text, account_uuid timeuuid, type int, state text, country_code text, purchase_date timestamp, release_date timestamp, status int, PRIMARY KEY(number));");
        process(ConsistencyLevel.ONE,"CREATE MATERIALIZED VIEW test_keyspace.numbers_by_user AS SELECT * FROM numbers WHERE number IS NOT NULL AND account_uuid IS NOT NULL PRIMARY KEY (account_uuid, number);");

        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("numbers")
                        .startObject("_field_names").field("enabled", false).endObject()
                        .field("discover", "(number|type|state|contry_code|status)")
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test_numbers")
                .addMapping("numbers", mapping)
                .setSettings(Settings.builder().put("keyspace", "test_keyspace").build())
                .get());
        ensureGreen("test_numbers");
    }

    @Test
    public void testCopyTo() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("first_name")
                            .field("type", "text")
                            .field("cql_collection", "singleton")
                            .field("copy_to", "full_name")
                        .endObject()
                        .startObject("last_name")
                            .field("type", "text")
                            .field("cql_collection", "singleton")
                            .field("copy_to", "full_name")
                        .endObject()
                        .startObject("full_name")
                            .field("type", "text")
                            .field("cql_collection", "none")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");

        assertThat(client().prepareIndex("test", "my_type", "1")
                .setSource("{ \"first_name\": \"John\", \"last_name\": \"Smith\"}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        SearchResponse resp = client().prepareSearch().setIndices("test")
                .setQuery(QueryBuilders.matchQuery("first_name", "John")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));

        resp = client().prepareSearch().setIndices("test")
                .setQuery(QueryBuilders.matchQuery("full_name", "John Smith").operator(Operator.AND)).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
    }

    @Test
    public void testCopyToNested() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("nest")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .startObject("properties")
                                .startObject("first_name")
                                    .field("type", "text")
                                    .field("cql_collection", "singleton")
                                    .field("copy_to", "nest.full_name")
                                .endObject()
                                .startObject("last_name")
                                    .field("type", "text")
                                    .field("cql_collection", "singleton")
                                    .field("copy_to", "nest.full_name")
                                .endObject()
                                .startObject("full_name")
                                    .field("type", "text")
                                    .field("cql_collection", "none")
                                .endObject()
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("copytest").addMapping("my_type", mapping));
        ensureGreen("copytest");

        assertThat(client().prepareIndex("copytest", "my_type", "1")
                .setSource("{ \"nest\": { \"first_name\": \"John\", \"last_name\": \"Smith\"}}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        SearchResponse resp = client().prepareSearch().setIndices("copytest")
                .setQuery(QueryBuilders.nestedQuery("nest", QueryBuilders.matchQuery("nest.first_name", "John"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));

        resp = client().prepareSearch().setIndices("copytest")
                .setQuery(QueryBuilders.nestedQuery("nest", QueryBuilders.matchQuery("nest.full_name", "John Smith").operator(Operator.AND), RandomPicks.randomFrom(random(), ScoreMode.values()))).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
    }

    @Test
    public void testOutputFormat() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("_field_names").field("enabled", false).endObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("created_at").field("type","date").field("index", false).field("format","epoch_millis").field("cql_collection", "singleton").endObject()
                        .startObject("ip").field("type","ip").field("cql_collection", "singleton").endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test1").addMapping("my_type", mapping));
        ensureGreen("test1");

        assertThat(client().prepareIndex("test1", "my_type", "1")
                .setSource("{ \"created_at\": 1524002400000, \"ip\":\"127.0.0.1\" }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        SearchResponse resp = client().prepareSearch().setIndices("test1").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        String _source = resp.getHits().getHits()[0].getSourceAsString();
        System.out.println(_source);
        assertTrue(_source.contains("\"127.0.0.1\""));
        assertTrue(_source.contains("1524002400000"));
    }

    @Test
    public void insertFlattenDoc() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("us")
                           .field("type", "object")
                           .field("cql_collection", "singleton")
                           .startObject("properties")
                               .startObject("er")
                                   .field("cql_collection", "singleton")
                                   .field("type","keyword")
                               .endObject()
                               .startObject("form")
                                   .field("cql_collection", "singleton")
                                   .field("type","keyword")
                               .endObject()
                               .startObject("mail")
                                   .field("cql_collection", "singleton")
                                   .startObject("properties")
                                       .startObject("to")
                                           .field("cql_collection", "singleton")
                                           .field("type","keyword")
                                       .endObject()
                                   .endObject()
                               .endObject()
                           .endObject()
                        .endObject()
                        .startObject("postdate")
                            .field("type","date")
                            .field("cql_collection", "singleton")
                        .endObject()
                        .startObject("message")
                            .field("type","keyword")
                            .field("cql_collection", "singleton")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test1").addMapping("my_type", mapping));
        ensureGreen("test1");

        assertThat(client().prepareIndex("test1", "my_type", "1")
                .setSource("{ \"us.er\" : \"blabla\", \"postdate\" : \"2009-11-15T14:12:12\", \"message\" : \"Test123\" }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test1", "my_type", "2")
                .setSource("{ \"us.er\" : \"test\", \"postdate\" : \"2009-11-15T14:12:12\", \"us.form\":\"foo\", \"message\" : \"Test123\" }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test1", "my_type", "3")
                .setSource("{ \"us.er\" : \"test\", \"postdate\" : \"2009-11-15T14:12:12\", \"us.mail.to\":\"bob@foo.com\", \"message\" : \"Test123\" }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));

        SearchResponse resp = client().prepareSearch().setIndices("test1").setQuery(QueryBuilders.termQuery("us.er", "blabla")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        assertThat(((Map<String, Object>)resp.getHits().getAt(0).getSourceAsMap().get("us")).get("er"), equalTo("blabla"));

        resp = client().prepareSearch().setIndices("test1").setQuery(QueryBuilders.termQuery("us.form", "foo")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        assertThat(((Map<String, Object>)resp.getHits().getAt(0).getSourceAsMap().get("us")).get("form"), equalTo("foo"));

        resp = client().prepareSearch().setIndices("test1").setQuery(QueryBuilders.termQuery("us.mail.to", "bob@foo.com")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        assertThat(((Map<String, Object>)((Map<String, Object>)resp.getHits().getAt(0).getSourceAsMap().get("us")).get("mail")).get("to"), equalTo("bob@foo.com"));
    }

    @Test
    public void testSeqnoPresent() throws Exception {
        createIndex("test");
        ensureGreen("test");

        IndexResponse index = client().prepareIndex("test", "my_type", "1")
            .setSource("{\"foo\": \"bar\" }", XContentType.JSON)
            .get();
        assertThat(index.getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(index.getSeqNo(), equalTo(1L));
        assertThat(index.getPrimaryTerm(), equalTo(1L));

        UpdateResponse update = client().prepareUpdate("test", "my_type", "1")
            .setDoc("{\"foo\": \"bar2\" }", XContentType.JSON)
            .get();

        assertThat(update.getResult(), equalTo(DocWriteResponse.Result.UPDATED));
        assertThat(update.getSeqNo(), equalTo(1L));
        assertThat(update.getPrimaryTerm(), equalTo(1L));
    }
}

