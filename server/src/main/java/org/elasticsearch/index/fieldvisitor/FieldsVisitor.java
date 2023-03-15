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
package org.elasticsearch.index.fieldvisitor;

import com.google.common.collect.ImmutableSet;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.IgnoredFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableSet;
import static org.elasticsearch.common.util.set.Sets.newHashSet;

/**
 * Base {@link StoredFieldVisitor} that retrieves all non-redundant metadata.
 */
public class FieldsVisitor extends StoredFieldVisitor {
    private static final Set<String> BASE_REQUIRED_FIELDS = unmodifiableSet(newHashSet(
            UidFieldMapper.NAME,
            IdFieldMapper.NAME,
            RoutingFieldMapper.NAME,
            ParentFieldMapper.NAME));

    private final boolean loadSource;
    private final String sourceFieldName;
    private final Set<String> requiredFields;
    protected BytesReference source;
    protected String type, id;
    protected Map<String, List<Object>> fieldsValues;
    protected List<ByteBuffer> values;

    public FieldsVisitor(boolean loadSource) {
        this(loadSource, SourceFieldMapper.NAME);
    }

    public FieldsVisitor(boolean loadSource, String sourceFieldName) {
        this.loadSource = loadSource;
        this.sourceFieldName = sourceFieldName;
        requiredFields = new HashSet<>();
        reset();
    }

    @Override
    public Status needsField(FieldInfo fieldInfo) throws IOException {
        if (requiredFields.remove(fieldInfo.name)) {
            return Status.YES;
        }
        // Always load _ignored to be explicit about ignored fields
        // This works because _ignored is added as the first metadata mapper,
        // so its stored fields always appear first in the list.
        if (IgnoredFieldMapper.NAME.equals(fieldInfo.name)) {
            return Status.YES;
        }
        // All these fields are single-valued so we can stop when the set is
        // empty
        return requiredFields.isEmpty()
                ? Status.STOP
                : Status.NO;
    }

    public Set<String> requestedFields() {
        return ImmutableSet.of();
    }

    public NavigableSet<String> requiredColumns(SearchContext searchContext) throws IOException {
        List<String> requiredColumns =  new ArrayList<String>();
        if (requestedFields() != null) {
            for(String fieldExp : requestedFields()) {
                for(String field : searchContext.mapperService().simpleMatchToFullName(fieldExp)) {
                    int i = field.indexOf('.');
                    String columnName = (i > 0) ? field.substring(0, i) : field;
                    requiredColumns.add(columnName);
                }
            }
        }
        if (loadSource()) {
            for(String columnName : searchContext.mapperService().documentMapper(type).getColumnDefinitions().keySet())
                requiredColumns.add( columnName );
        }
        return new TreeSet<String>(requiredColumns);
    }

    public boolean loadSource() {
        return this.loadSource;
    }

    public void postProcess(MapperService mapperService) {
        if (mapperService.getIndexSettings().isSingleType()) {
            final Collection<String> types = mapperService.types();
            assert types.size() <= 1 : types;
            if (types.isEmpty() == false) {
                type = types.iterator().next();
            }
        }
        for (Map.Entry<String, List<Object>> entry : fields().entrySet()) {
            MappedFieldType fieldType = mapperService.fullName(entry.getKey());
            if (fieldType == null) {
                throw new IllegalStateException("Field [" + entry.getKey()
                    + "] exists in the index but not in mappings");
            }
            List<Object> fieldValues = entry.getValue();
            for (int i = 0; i < fieldValues.size(); i++) {
                fieldValues.set(i, fieldType.valueForDisplay(fieldValues.get(i)));
            }
        }
    }

    @Override
    public void binaryField(FieldInfo fieldInfo, byte[] value) throws IOException {
        if (sourceFieldName.equals(fieldInfo.name)) {
            source = new BytesArray(value);
        } else if (IdFieldMapper.NAME.equals(fieldInfo.name)) {
            id = Uid.decodeId(value);
        } else {
            addValue(fieldInfo.name, new BytesRef(value));
        }
    }

    @Override
    public void stringField(FieldInfo fieldInfo, byte[] bytes) throws IOException {
        final String value = new String(bytes, StandardCharsets.UTF_8);
        if (UidFieldMapper.NAME.equals(fieldInfo.name)) {
            // 5.x-only
            // TODO: Remove when we are on 7.x
            Uid uid = Uid.createUid(value);
            type = uid.type();
            id = uid.id();
        } else if (IdFieldMapper.NAME.equals(fieldInfo.name)) {
            // only applies to 5.x indices that have single_type = true
            // TODO: Remove when we are on 7.x
            id = value;
        } else {
            addValue(fieldInfo.name, value);
        }
    }

    @Override
    public void intField(FieldInfo fieldInfo, int value) throws IOException {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void longField(FieldInfo fieldInfo, long value) throws IOException {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void floatField(FieldInfo fieldInfo, float value) throws IOException {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void doubleField(FieldInfo fieldInfo, double value) throws IOException {
        addValue(fieldInfo.name, value);
    }

    public BytesReference source() {
        return this.source;
    }


    public FieldsVisitor source(byte[] _source) {
        this.source = new BytesArray(_source);
        return this;
    }

    public FieldsVisitor source(BytesReference _source) {
        this.source = _source;
        return this;
    }

    public Uid uid() {
        if (id == null) {
            return null;
        } else if (type == null) {
            throw new IllegalStateException("Call postProcess before getting the uid");
        }
        return new Uid(type, id);
    }

    public String routing() {
        if (fieldsValues == null) {
            return null;
        }
        List<Object> values = fieldsValues.get(RoutingFieldMapper.NAME);
        if (values == null || values.isEmpty()) {
            return null;
        }
        assert values.size() == 1;
        return values.get(0).toString();
    }

    public Map<String, List<Object>> fields() {
        return fieldsValues != null ? fieldsValues : emptyMap();
    }

    public void reset() {
        if (fieldsValues != null) fieldsValues.clear();
        source = null;
        type = null;
        id = null;

        requiredFields.addAll(BASE_REQUIRED_FIELDS);
        if (loadSource) {
            requiredFields.add(sourceFieldName);
        }
    }

    void addValue(String name, Object value) {
        if (fieldsValues == null) {
            fieldsValues = new HashMap<>();
        }

        List<Object> values = fieldsValues.get(name);
        if (values == null) {
            values = new ArrayList<>(2);
            fieldsValues.put(name, values);
        }
        values.add(value);
    }


    public void setValues(String name, List<Object> values) {
        if (fieldsValues == null) {
            fieldsValues = new HashMap<>();
        }
        this.fieldsValues.put(name, values);
    }

    public List<ByteBuffer> getValues() {
        return values;
    }

    public void setValues(List<ByteBuffer> values) {
        this.values = values;
    }
}
