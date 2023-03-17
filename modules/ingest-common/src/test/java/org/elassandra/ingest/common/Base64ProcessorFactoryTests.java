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

package org.elassandra.ingest.common;

import org.elasticsearch.ingest.TestTemplateService;
import org.elasticsearch.ingest.common.DateProcessor;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;

public class Base64ProcessorFactoryTests extends ESTestCase {

    private Base64Processor.Factory factory;

    @Before
    public void init() {
        factory = new Base64Processor.Factory(TestTemplateService.instance());
    }

    public void testCreateWithDefaults() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "ts");
        String processorTag = randomAlphaOfLength(10);

        Base64Processor processor = factory.create(null, processorTag, config);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getField(), equalTo("ts"));
        assertThat(processor.getTargetField(), equalTo("ts"));
    }

    public void testCreateWithTarget() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "timestamp");
        config.put("target_field", "ts");
        String processorTag = randomAlphaOfLength(10);

        Base64Processor processor = factory.create(null, processorTag, config);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getField(), equalTo("timestamp"));
        assertThat(processor.getTargetField(), equalTo("ts"));
    }
}
