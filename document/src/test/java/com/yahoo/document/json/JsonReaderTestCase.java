// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.google.common.base.Joiner;
import com.yahoo.collections.Tuple2;
import com.yahoo.document.ArrayDataType;
import com.yahoo.document.DataType;
import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentOperation;
import com.yahoo.document.DocumentPut;
import com.yahoo.document.DocumentRemove;
import com.yahoo.document.DocumentType;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.DocumentUpdate;
import com.yahoo.document.Field;
import com.yahoo.document.MapDataType;
import com.yahoo.document.PositionDataType;
import com.yahoo.document.StructDataType;
import com.yahoo.document.TensorDataType;
import com.yahoo.document.WeightedSetDataType;
import com.yahoo.document.datatypes.Array;
import com.yahoo.document.datatypes.BoolFieldValue;
import com.yahoo.document.datatypes.FieldValue;
import com.yahoo.document.datatypes.IntegerFieldValue;
import com.yahoo.document.datatypes.MapFieldValue;
import com.yahoo.document.datatypes.Raw;
import com.yahoo.document.datatypes.StringFieldValue;
import com.yahoo.document.datatypes.Struct;
import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.document.datatypes.WeightedSet;
import com.yahoo.document.json.document.DocumentParser;
import com.yahoo.document.json.readers.DocumentParseInfo;
import com.yahoo.document.json.readers.VespaJsonDocumentReader;
import com.yahoo.document.serialization.DocumentSerializer;
import com.yahoo.document.serialization.DocumentSerializerFactory;
import com.yahoo.document.update.AddValueUpdate;
import com.yahoo.document.update.ArithmeticValueUpdate;
import com.yahoo.document.update.ArithmeticValueUpdate.Operator;
import com.yahoo.document.update.AssignValueUpdate;
import com.yahoo.document.update.ClearValueUpdate;
import com.yahoo.document.update.FieldUpdate;
import com.yahoo.document.update.MapValueUpdate;
import com.yahoo.document.update.TensorModifyUpdate;
import com.yahoo.document.update.ValueUpdate;
import com.yahoo.io.GrowableByteBuffer;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.MappedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.text.Utf8;
import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.internal.matchers.Contains;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.yahoo.document.json.readers.SingleValueReader.*;
import static com.yahoo.test.json.JsonTestHelper.inputJson;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

/**
 * Basic test of JSON streams to Vespa document instances.
 *
 * @author Steinar Knutsen
 */
public class JsonReaderTestCase {

    private DocumentTypeManager types;
    private JsonFactory parserFactory;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        parserFactory = new JsonFactory();
        types = new DocumentTypeManager();
        {
            DocumentType x = new DocumentType("smoke");
            x.addField(new Field("something", DataType.STRING));
            x.addField(new Field("nalle", DataType.STRING));
            x.addField(new Field("int1", DataType.INT));
            x.addField(new Field("flag", DataType.BOOL));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("mirrors");
            StructDataType woo = new StructDataType("woo");
            woo.addField(new Field("sandra", DataType.STRING));
            woo.addField(new Field("cloud", DataType.STRING));
            x.addField(new Field("skuggsjaa", woo));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testarray");
            DataType d = new ArrayDataType(DataType.STRING);
            x.addField(new Field("actualarray", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testset");
            DataType d = new WeightedSetDataType(DataType.STRING, true, true);
            x.addField(new Field("actualset", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testmap");
            DataType d = new MapDataType(DataType.STRING, DataType.STRING);
            x.addField(new Field("actualmap", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testraw");
            DataType d = DataType.RAW;
            x.addField(new Field("actualraw", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testMapStringToArrayOfInt");
            DataType value = new ArrayDataType(DataType.INT);
            DataType d = new MapDataType(DataType.STRING, value);
            x.addField(new Field("actualMapStringToArrayOfInt", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testsinglepos");
            DataType d = PositionDataType.INSTANCE;
            x.addField(new Field("singlepos", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testtensor");
            x.addField(new Field("mappedtensorfield",
                                 new TensorDataType(new TensorType.Builder().mapped("x").mapped("y").build())));
            x.addField(new Field("indexedtensorfield",
                                 new TensorDataType(new TensorType.Builder().indexed("x").indexed("y").build())));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testpredicate");
            x.addField(new Field("boolean", DataType.PREDICATE));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testint");
            x.addField(new Field("integerfield", DataType.INT));
            types.registerDocumentType(x);
        }
    }

    @After
    public void tearDown() throws Exception {
        types = null;
        parserFactory = null;
        exception = ExpectedException.none();
    }

    private JsonReader createReader(String jsonInput) {
        InputStream input = new ByteArrayInputStream(Utf8.toBytes(jsonInput));
        return new JsonReader(types, input, parserFactory);
    }

    @Test
    public void readSingleDocumentPut() {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:smoke::doc1',",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse'",
                "  }",
                "}"));
        DocumentPut put = (DocumentPut) r.readSingleDocument(DocumentParser.SupportedOperation.PUT,
                                                             "id:unittest:smoke::doc1");
        smokeTestDoc(put.getDocument());
    }

    @Test
    public final void readSingleDocumentUpdate() {
        JsonReader r = createReader(inputJson("{ 'update': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'something': {",
                "      'assign': 'orOther' }}}"));
        DocumentUpdate doc = (DocumentUpdate) r.readSingleDocument(DocumentParser.SupportedOperation.UPDATE, "id:unittest:smoke::whee");
        FieldUpdate f = doc.getFieldUpdate("something");
        assertEquals(1, f.size());
        assertTrue(f.getValueUpdate(0) instanceof AssignValueUpdate);
    }

    @Test
    public void readClearField() {
        JsonReader r = createReader(inputJson("{ 'update': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'int1': {",
                "      'assign': null }}}"));
        DocumentUpdate doc = (DocumentUpdate) r.readSingleDocument(DocumentParser.SupportedOperation.UPDATE, "id:unittest:smoke::whee");
        FieldUpdate f = doc.getFieldUpdate("int1");
        assertEquals(1, f.size());
        assertTrue(f.getValueUpdate(0) instanceof ClearValueUpdate);
        assertNull(f.getValueUpdate(0).getValue());
    }


    @Test
    public void smokeTest() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:smoke::doc1',",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse'",
                "  }",
                "}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        smokeTestDoc(put.getDocument());
    }

    @Test
    public void docIdLookaheadTest() throws IOException {
        JsonReader r = createReader(inputJson(
                "{ 'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse'",
                "  },",
                "  'put': 'id:unittest:smoke::doc1'",
                "  }",
                "}"));

        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        smokeTestDoc(put.getDocument());
    }


    @Test
    public void emptyDocTest() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:smoke::whee', 'fields': {}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        assertEquals("id:unittest:smoke::whee", parseInfo.documentId.toString());
    }

    @Test
    public void testStruct() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:mirrors::whee',",
                "  'fields': {",
                "    'skuggsjaa': {",
                "      'sandra': 'person',",
                "      'cloud': 'another person' }}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("skuggsjaa"));
        assertSame(Struct.class, f.getClass());
        Struct s = (Struct) f;
        assertEquals("person", ((StringFieldValue) s.getFieldValue("sandra")).getString());
    }

    private DocumentUpdate parseUpdate(String json) throws IOException {
        InputStream rawDoc = new ByteArrayInputStream(Utf8.toBytes(json));
        JsonReader r = new JsonReader(types, rawDoc, parserFactory);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentUpdate update = new DocumentUpdate(docType, parseInfo.documentId);
        new VespaJsonDocumentReader().readUpdate(parseInfo.fieldsBuffer, update);
        return update;
    }

    @Test
    public void testStructUpdate() throws IOException {
        DocumentUpdate put = parseUpdate(inputJson("{ 'update': 'id:unittest:mirrors:g=test:whee',",
                "  'create': true,",
                "  'fields': {",
                "    'skuggsjaa': {",
                "      'assign': {",
                "        'sandra': 'person',",
                "        'cloud': 'another person' }}}}"));
        assertEquals(1, put.fieldUpdates().size());
        FieldUpdate fu = put.fieldUpdates().iterator().next();
        assertEquals(1, fu.getValueUpdates().size());
        ValueUpdate vu = fu.getValueUpdate(0);
        assertTrue(vu instanceof AssignValueUpdate);
        AssignValueUpdate avu = (AssignValueUpdate) vu;
        assertTrue(avu.getValue() instanceof Struct);
        Struct s = (Struct) avu.getValue();
        assertEquals(2, s.getFieldCount());
        assertEquals(new StringFieldValue("person"), s.getFieldValue(s.getField("sandra")));
        GrowableByteBuffer buf = new GrowableByteBuffer();
        DocumentSerializer serializer = DocumentSerializerFactory.create6(buf);
        put.serialize(serializer);
        assertEquals(107, buf.position());
    }

    @Test
    public final void testEmptyStructUpdate() throws IOException {
        DocumentUpdate put = parseUpdate(inputJson("{ 'update': 'id:unittest:mirrors:g=test:whee',",
                "  'create': true,",
                "  'fields': { ",
                "    'skuggsjaa': {",
                "      'assign': { } }}}"));
        assertEquals(1, put.fieldUpdates().size());
        FieldUpdate fu = put.fieldUpdates().iterator().next();
        assertEquals(1, fu.getValueUpdates().size());
        ValueUpdate vu = fu.getValueUpdate(0);
        assertTrue(vu instanceof AssignValueUpdate);
        AssignValueUpdate avu = (AssignValueUpdate) vu;
        assertTrue(avu.getValue() instanceof Struct);
        Struct s = (Struct) avu.getValue();
        assertEquals(0, s.getFieldCount());
        GrowableByteBuffer buf = new GrowableByteBuffer();
        DocumentSerializer serializer = DocumentSerializerFactory.create6(buf);
        put.serialize(serializer);
        assertEquals(69, buf.position());
    }

    @Test
    public void testUpdateArray() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testarray::whee',",
                "  'fields': {",
                "    'actualarray': {",
                "      'add': [",
                "        'person',",
                "        'another person' ]}}}"));
        checkSimpleArrayAdd(doc);
    }

    @Test
    public void testUpdateWeighted() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testset::whee',",
                "  'fields': {",
                "    'actualset': {",
                "      'add': {",
                "        'person': 37,",
                "        'another person': 41 }}}}"));

        Map<String, Integer> weights = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualset");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            AddValueUpdate adder = (AddValueUpdate) v;
            final String s = ((StringFieldValue) adder.getValue()).getString();
            weights.put(s, adder.getWeight());
        }
        assertEquals(2, weights.size());
        final String o = "person";
        final String o2 = "another person";
        assertTrue(weights.containsKey(o));
        assertTrue(weights.containsKey(o2));
        assertEquals(Integer.valueOf(37), weights.get(o));
        assertEquals(Integer.valueOf(41), weights.get(o2));
    }

    @Test
    public void testUpdateMatch() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testset::whee',",
                "  'fields': {",
                "    'actualset': {",
                "      'match': {",
                "        'element': 'person',",
                "        'increment': 13 }}}}"));

        Map<String, Tuple2<Number, String>> matches = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualset");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            MapValueUpdate adder = (MapValueUpdate) v;
            final String key = ((StringFieldValue) adder.getValue())
                    .getString();
            String op = ((ArithmeticValueUpdate) adder.getUpdate())
                    .getOperator().toString();
            Number n = ((ArithmeticValueUpdate) adder.getUpdate()).getOperand();
            matches.put(key, new Tuple2<>(n, op));
        }
        assertEquals(1, matches.size());
        final String o = "person";
        assertEquals("ADD", matches.get(o).second);
        assertEquals(Double.valueOf(13), matches.get(o).first);
    }

    @SuppressWarnings({ "cast", "unchecked", "rawtypes" })
    @Test
    public void testArithmeticOperators() throws IOException {
        Tuple2[] operations = new Tuple2[] {
                new Tuple2<String, Operator>(UPDATE_DECREMENT,
                        ArithmeticValueUpdate.Operator.SUB),
                new Tuple2<String, Operator>(UPDATE_DIVIDE,
                        ArithmeticValueUpdate.Operator.DIV),
                new Tuple2<String, Operator>(UPDATE_INCREMENT,
                        ArithmeticValueUpdate.Operator.ADD),
                new Tuple2<String, Operator>(UPDATE_MULTIPLY,
                        ArithmeticValueUpdate.Operator.MUL) };
        for (Tuple2<String, Operator> operator : operations) {
            DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testset::whee',",
                    "  'fields': {",
                    "    'actualset': {",
                    "      'match': {",
                    "        'element': 'person',",
                    "        '" + (String) operator.first + "': 13 }}}}"));

            Map<String, Tuple2<Number, Operator>> matches = new HashMap<>();
            FieldUpdate x = doc.getFieldUpdate("actualset");
            for (ValueUpdate v : x.getValueUpdates()) {
                MapValueUpdate adder = (MapValueUpdate) v;
                final String key = ((StringFieldValue) adder.getValue())
                        .getString();
                Operator op = ((ArithmeticValueUpdate) adder
                        .getUpdate()).getOperator();
                Number n = ((ArithmeticValueUpdate) adder.getUpdate())
                        .getOperand();
                matches.put(key, new Tuple2<>(n, op));
            }
            assertEquals(1, matches.size());
            final String o = "person";
            assertSame(operator.second, matches.get(o).second);
            assertEquals(Double.valueOf(13), matches.get(o).first);
        }
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testArrayIndexing() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testarray::whee',",
                "  'fields': {",
                "    'actualarray': {",
                "      'match': {",
                "        'element': 3,",
                "        'assign': 'nalle' }}}}"));

        Map<Number, String> matches = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualarray");
        for (ValueUpdate v : x.getValueUpdates()) {
            MapValueUpdate adder = (MapValueUpdate) v;
            final Number key = ((IntegerFieldValue) adder.getValue())
                    .getNumber();
            String op = ((StringFieldValue) ((AssignValueUpdate) adder.getUpdate())
                    .getValue()).getString();
            matches.put(key, op);
        }
        assertEquals(1, matches.size());
        Number n = Integer.valueOf(3);
        assertEquals("nalle", matches.get(n));
    }

    @Test
    public void testDocumentRemove() {
        JsonReader r = createReader(inputJson("{'remove': 'id:unittest:smoke::whee'}"));
        DocumentType docType = r.readDocumentType(new DocumentId("id:unittest:smoke::whee"));
        assertEquals("smoke", docType.getName());
    }

    @Test
    public void testWeightedSet() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testset::whee',",
                "  'fields': {",
                "    'actualset': {",
                "      'nalle': 2,",
                "      'tralle': 7 }}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("actualset"));
        assertSame(WeightedSet.class, f.getClass());
        WeightedSet<?> w = (WeightedSet<?>) f;
        assertEquals(2, w.size());
        assertEquals(Integer.valueOf(2), w.get(new StringFieldValue("nalle")));
        assertEquals(Integer.valueOf(7), w.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testArray() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testarray::whee',",
                "  'fields': {",
                "    'actualarray': [",
                "      'nalle',",
                "      'tralle' ]}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("actualarray"));
        assertSame(Array.class, f.getClass());
        Array<?> a = (Array<?>) f;
        assertEquals(2, a.size());
        assertEquals(new StringFieldValue("nalle"), a.get(0));
        assertEquals(new StringFieldValue("tralle"), a.get(1));
    }

    @Test
    public void testMap() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testmap::whee',",
                        "  'fields': {",
                        "    'actualmap': {",
                        "      'nalle': 'kalle',",
                        "      'tralle': 'skalle' }}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("actualmap"));
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        assertEquals(2, m.size());
        assertEquals(new StringFieldValue("kalle"), m.get(new StringFieldValue("nalle")));
        assertEquals(new StringFieldValue("skalle"), m.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testOldMap() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testmap::whee',",
                "  'fields': {",
                "    'actualmap': [",
                "      { 'key': 'nalle', 'value': 'kalle'},",
                "      { 'key': 'tralle', 'value': 'skalle'} ]}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("actualmap"));
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        assertEquals(2, m.size());
        assertEquals(new StringFieldValue("kalle"), m.get(new StringFieldValue("nalle")));
        assertEquals(new StringFieldValue("skalle"), m.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testPositionPositive() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testsinglepos::bamf',",
                "  'fields': {",
                "    'singlepos': 'N63.429722;E10.393333' }}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testPositionNegative() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testsinglepos::bamf',",
                        "  'fields': {",
                        "    'singlepos': 'W46.63;S23.55' }}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(-46630000, PositionDataType.getXValue(f).getInteger());
        assertEquals(-23550000, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testRaw() throws IOException {
        String stuff = new String(new JsonStringEncoder().quoteAsString(new Base64().encodeToString(Utf8.toBytes("smoketest"))));
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testraw::whee',",
                        "  'fields': {",
                        "    'actualraw': '" + stuff + "' }}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue(doc.getField("actualraw"));
        assertSame(Raw.class, f.getClass());
        Raw s = (Raw) f;
        ByteBuffer b = s.getByteBuffer();
        assertEquals("smoketest", Utf8.toString(b));
    }

    @Test
    public void testMapStringToArrayOfInt() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testMapStringToArrayOfInt::whee',",
                "  'fields': {",
                "    'actualMapStringToArrayOfInt': {",
                "      'bamse': [1, 2, 3] }}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue("actualMapStringToArrayOfInt");
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testOldMapStringToArrayOfInt() throws IOException {
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:testMapStringToArrayOfInt::whee',",
                "  'fields': {",
                "    'actualMapStringToArrayOfInt': [",
                "      { 'key': 'bamse', 'value': [1, 2, 3] } ]}}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        Document doc = put.getDocument();
        FieldValue f = doc.getFieldValue("actualMapStringToArrayOfInt");
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testAssignToString() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'something': {",
                "      'assign': 'orOther' }}}"));
        FieldUpdate f = doc.getFieldUpdate("something");
        assertEquals(1, f.size());
        AssignValueUpdate a = (AssignValueUpdate) f.getValueUpdate(0);
        assertEquals(new StringFieldValue("orOther"), a.getValue());
    }

    @Test
    public void testAssignToArray() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testMapStringToArrayOfInt::whee',",
                "  'fields': {",
                "    'actualMapStringToArrayOfInt': {",
                "      'assign': { 'bamse': [1, 2, 3] }}}}"));
        FieldUpdate f = doc.getFieldUpdate("actualMapStringToArrayOfInt");
        assertEquals(1, f.size());
        AssignValueUpdate assign = (AssignValueUpdate) f.getValueUpdate(0);
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) assign.getValue();
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testOldAssignToArray() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testMapStringToArrayOfInt::whee',",
                "  'fields': {",
                "    'actualMapStringToArrayOfInt': {",
                "      'assign': [",
                "        { 'key': 'bamse', 'value': [1, 2, 3] } ]}}}"));
        FieldUpdate f = doc.getFieldUpdate("actualMapStringToArrayOfInt");
        assertEquals(1, f.size());
        AssignValueUpdate assign = (AssignValueUpdate) f.getValueUpdate(0);
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) assign.getValue();
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testAssignToWeightedSet() throws IOException {
        DocumentUpdate doc = parseUpdate(inputJson("{ 'update': 'id:unittest:testset::whee',",
                "  'fields': {",
                "    'actualset': {",
                "      'assign': {",
                "        'person': 37,",
                "        'another person': 41 }}}}"));
        FieldUpdate x = doc.getFieldUpdate("actualset");
        assertEquals(1, x.size());
        AssignValueUpdate assign = (AssignValueUpdate) x.getValueUpdate(0);
        WeightedSet<?> w = (WeightedSet<?>) assign.getValue();
        assertEquals(2, w.size());
        assertEquals(Integer.valueOf(37), w.get(new StringFieldValue("person")));
        assertEquals(Integer.valueOf(41), w.get(new StringFieldValue("another person")));
    }


    @Test
    public void testCompleteFeed() {
        JsonReader r = createReader(inputJson("[",
                "{ 'put': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse' }},",
                "{ 'update': 'id:unittest:testarray::whee',",
                "  'fields': {",
                "    'actualarray': {",
                "      'add': [",
                "        'person',",
                "        'another person' ]}}},",
                "{ 'remove': 'id:unittest:smoke::whee' }]"));

        controlBasicFeed(r);
    }

    @Test
    public void testCompleteFeedWithCreateAndCondition() {
        JsonReader r = createReader(inputJson("[",
                "{ 'put': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse' }},",
                "{",
                "  'condition':'bla',",
                "  'update': 'id:unittest:testarray::whee',",
                "  'create':true,",
                "  'fields': {",
                "    'actualarray': {",
                "      'add': [",
                "        'person',",
                "        'another person' ]}}},",
                "{ 'remove': 'id:unittest:smoke::whee' }]"));

        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        smokeTestDoc(doc);

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        checkSimpleArrayAdd(update);
        assertThat(update.getCreateIfNonExistent(), is(true));
        assertThat(update.getCondition().getSelection(), is("bla"));

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());
    }

    @Test
    public void testUpdateWithConditionAndCreateInDifferentOrdering() {
        int  documentsCreated = 106;
        List<String> parts = Arrays.asList(
                "\"condition\":\"bla\"",
                "\"update\": \"id:unittest:testarray::whee\"",
                " \"fields\": { " + "\"actualarray\": { \"add\": [" + " \"person\",\"another person\"]}}",
                " \"create\":true");
        Random random = new Random(42);
        StringBuilder documents = new StringBuilder("[");
        for (int x = 0; x < documentsCreated; x++) {
            Collections.shuffle(parts, random);
            documents.append("{").append(Joiner.on(",").join(parts)).append("}");
            if (x < documentsCreated -1) {
                documents.append(",");
            }
        }
        documents.append("]");
        InputStream rawDoc = new ByteArrayInputStream(
                Utf8.toBytes(documents.toString()));

        JsonReader r = new JsonReader(types, rawDoc, parserFactory);

        for (int x = 0; x < documentsCreated; x++) {
            DocumentUpdate update = (DocumentUpdate) r.next();
            checkSimpleArrayAdd(update);
            assertThat(update.getCreateIfNonExistent(), is(true));
            assertThat(update.getCondition().getSelection(), is("bla"));

        }

        assertNull(r.next());
    }


    @Test(expected=RuntimeException.class)
    public void testCreateIfNonExistentInPut() {
        JsonReader r = createReader(inputJson("[{",
                "  'create':true,",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'nalle': 'bamse' },",
                "  'put': 'id:unittest:smoke::whee'",
                "}]"));
        r.next();
    }

    @Test
    public void testCompleteFeedWithIdAfterFields() {
        JsonReader r = createReader(inputJson("[",
                "{",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse' },",
                "  'put': 'id:unittest:smoke::whee'",
                "},",
                "{",
                "  'fields': {",
                "    'actualarray': {",
                "      'add': [",
                "        'person',",
                "        'another person' ]}},",
                "  'update': 'id:unittest:testarray::whee'",
                "},",
                "{ 'remove': 'id:unittest:smoke::whee' }]"));

        controlBasicFeed(r);
    }

    protected void controlBasicFeed(JsonReader r) {
        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        smokeTestDoc(doc);

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        checkSimpleArrayAdd(update);

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());
    }


    @Test
    public void testCompleteFeedWithEmptyDoc() {
        JsonReader r = createReader(inputJson("[",
                "{ 'put': 'id:unittest:smoke::whee', 'fields': {} },",
                "{ 'update': 'id:unittest:testarray::whee', 'fields': {} },",
                "{ 'remove': 'id:unittest:smoke::whee' }]"));

        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        assertEquals("smoke", doc.getId().getDocType());

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        assertEquals("testarray", update.getId().getDocType());

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());

    }

    private void checkSimpleArrayAdd(DocumentUpdate update) {
        Set<String> toAdd = new HashSet<>();
        FieldUpdate x = update.getFieldUpdate("actualarray");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            AddValueUpdate adder = (AddValueUpdate) v;
            toAdd.add(((StringFieldValue) adder.getValue()).getString());
        }
        assertEquals(2, toAdd.size());
        assertTrue(toAdd.contains("person"));
        assertTrue(toAdd.contains("another person"));
    }

    private void smokeTestDoc(Document doc) {
        FieldValue boolField = doc.getFieldValue(doc.getField("flag"));
        assertSame(BoolFieldValue.class, boolField.getClass());
        assertTrue((Boolean)boolField.getWrappedValue());

        FieldValue stringField = doc.getFieldValue(doc.getField("nalle"));
        assertSame(StringFieldValue.class, stringField.getClass());
        assertEquals("bamse", ((StringFieldValue) stringField).getString());
    }

    @Test
    public final void misspelledFieldTest()  throws IOException{
        JsonReader r = createReader(inputJson("{ 'put': 'id:unittest:smoke::whee',",
                "  'fields': {",
                "    'smething': 'smoketest',",
                "    'nalle': 'bamse' }}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        exception.expect(NullPointerException.class);
        exception.expectMessage("Could not get field \"smething\" in the structure of type \"smoke\".");
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
    }

    @Test
    public void feedWithBasicErrorTest() {
        JsonReader r = createReader(inputJson("[",
                "  { 'put': 'id:test:smoke::0', 'fields': { 'something': 'foo' } },",
                "  { 'put': 'id:test:smoke::1', 'fields': { 'something': 'foo' } },",
                "  { 'put': 'id:test:smoke::2', 'fields': { 'something': 'foo' } },",
                "]"));
        exception.expect(RuntimeException.class);
        exception.expectMessage("JsonParseException");
        while (r.next() != null);
    }

    @Test
    public void idAsAliasForPutTest()  throws IOException{
        JsonReader r = createReader(inputJson("{ 'id': 'id:unittest:smoke::doc1',",
                "  'fields': {",
                "    'something': 'smoketest',",
                "    'flag': true,",
                "    'nalle': 'bamse' }}"));
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader().readPut(parseInfo.fieldsBuffer, put);
        smokeTestDoc(put.getDocument());
    }

    private void testFeedWithTestAndSetCondition(String jsonDoc) {
        final ByteArrayInputStream parseInfoDoc = new ByteArrayInputStream(Utf8.toBytes(jsonDoc));
        final JsonReader reader = new JsonReader(types, parseInfoDoc, parserFactory);
        final int NUM_OPERATIONS_IN_FEED = 3;

        for (int i = 0; i < NUM_OPERATIONS_IN_FEED; i++) {
            DocumentOperation operation = reader.next();

            assertTrue("A test and set condition should be present",
                    operation.getCondition().isPresent());

            assertEquals("DocumentOperation's test and set condition should be equal to the one in the JSON feed",
                    "smoke.something == \"smoketest\"",
                    operation.getCondition().getSelection());
        }

        assertNull(reader.next());
    }

    @Test
    public void testFeedWithTestAndSetConditionOrderingOne() {
        testFeedWithTestAndSetCondition(
                inputJson("[",
                        "      {",
                        "          'put': 'id:unittest:smoke::whee',",
                        "          'condition': 'smoke.something == \\'smoketest\\'',",
                        "          'fields': {",
                        "              'something': 'smoketest',",
                        "              'nalle': 'bamse'",
                        "          }",
                        "      },",
                        "      {",
                        "          'update': 'id:unittest:testarray::whee',",
                        "          'condition': 'smoke.something == \\'smoketest\\'',",
                        "          'fields': {",
                        "              'actualarray': {",
                        "                  'add': [",
                        "                      'person',",
                        "                      'another person'",
                        "                   ]",
                        "              }",
                        "          }",
                        "      },",
                        "      {",
                        "          'remove': 'id:unittest:smoke::whee',",
                        "          'condition': 'smoke.something == \\'smoketest\\''",
                        "      }",
                        "]"
                ));
    }

    @Test
    public final void testFeedWithTestAndSetConditionOrderingTwo() {
        testFeedWithTestAndSetCondition(
                inputJson("[",
                        "      {",
                        "          'condition': 'smoke.something == \\'smoketest\\'',",
                        "          'put': 'id:unittest:smoke::whee',",
                        "          'fields': {",
                        "              'something': 'smoketest',",
                        "              'nalle': 'bamse'",
                        "          }",
                        "      },",
                        "      {",
                        "          'condition': 'smoke.something == \\'smoketest\\'',",
                        "          'update': 'id:unittest:testarray::whee',",
                        "          'fields': {",
                        "              'actualarray': {",
                        "                  'add': [",
                        "                      'person',",
                        "                      'another person'",
                        "                   ]",
                        "              }",
                        "          }",
                        "      },",
                        "      {",
                        "          'condition': 'smoke.something == \\'smoketest\\'',",
                        "          'remove': 'id:unittest:smoke::whee'",
                        "      }",
                        "]"
                ));
    }

    @Test
    public final void testFeedWithTestAndSetConditionOrderingThree() {
        testFeedWithTestAndSetCondition(
                inputJson("[",
                        "      {",
                        "          'put': 'id:unittest:smoke::whee',",
                        "          'fields': {",
                        "              'something': 'smoketest',",
                        "              'nalle': 'bamse'",
                        "          },",
                        "          'condition': 'smoke.something == \\'smoketest\\''",
                        "      },",
                        "      {",
                        "          'update': 'id:unittest:testarray::whee',",
                        "          'fields': {",
                        "              'actualarray': {",
                        "                  'add': [",
                        "                      'person',",
                        "                      'another person'",
                        "                   ]",
                        "              }",
                        "          },",
                        "          'condition': 'smoke.something == \\'smoketest\\''",
                        "      },",
                        "      {",
                        "          'remove': 'id:unittest:smoke::whee',",
                        "          'condition': 'smoke.something == \\'smoketest\\''",
                        "      }",
                        "]"
                ));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldAfterFieldsFieldShouldFailParse() {
        final String jsonData = inputJson(
                "[",
                "      {",
                "          'put': 'id:unittest:smoke::whee',",
                "          'fields': {",
                "              'something': 'smoketest',",
                "              'nalle': 'bamse'",
                "          },",
                "          'bjarne': 'stroustrup'",
                "      }",
                "]");

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldBeforeFieldsFieldShouldFailParse() {
        final String jsonData = inputJson(
                "[",
                "      {",
                "          'update': 'id:unittest:testarray::whee',",
                "          'what is this': 'nothing to see here',",
                "          'fields': {",
                "              'actualarray': {",
                "                  'add': [",
                "                      'person',",
                "                      'another person'",
                "                   ]",
                "              }",
                "          }",
                "      }",
                "]");

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldWithoutFieldsFieldShouldFailParse() {
        final String jsonData = inputJson(
                "[",
                "      {",
                "          'remove': 'id:unittest:smoke::whee',",
                "          'what is love': 'baby, do not hurt me... much'",
                "      }",
                "]");

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test
    public void testMissingOperation() {
        try {
            String jsonData = inputJson(
                    "[",
                    "      {",
                    "          'fields': {",
                    "              'actualarray': {",
                    "                  'add': [",
                    "                      'person',",
                    "                      'another person'",
                    "                   ]",
                    "              }",
                    "          }",
                    "      }",
                    "]");

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Missing a document operation ('put', 'update' or 'remove')", e.getMessage());
        }
    }

    @Test
    public void testMissingFieldsMapInPut() {
        try {
            String jsonData = inputJson(
                    "[",
                    "      {",
                    "          'put': 'id:unittest:smoke::whee'",
                    "      }",
                    "]");

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("put of document id:unittest:smoke::whee is missing a 'fields' map", e.getMessage());
        }
    }

    @Test
    public void testMissingFieldsMapInUpdate() {
        try {
            String jsonData = inputJson(
                    "[",
                    "      {",
                    "          'update': 'id:unittest:smoke::whee'",
                    "      }",
                    "]");

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("update of document id:unittest:smoke::whee is missing a 'fields' map", e.getMessage());
        }
    }

    static ByteArrayInputStream jsonToInputStream(String json) {
        return new ByteArrayInputStream(Utf8.toBytes(json));
    }

    @Test
    public void testParsingWithoutTensorField() {
        Document doc = createPutWithoutTensor().getDocument();
        assertEquals("testtensor", doc.getId().getDocType());
        assertEquals("id:unittest:testtensor::0", doc.getId().toString());
        TensorFieldValue fieldValue = (TensorFieldValue)doc.getFieldValue(doc.getField("mappedtensorfield"));
        assertNull(fieldValue);
    }

    @Test
    public void testParsingOfEmptyTensor() {
        assertMappedTensorField("tensor(x{},y{}):{}", createPutWithMappedTensor("{}"));
    }

    @Test
    public void testParsingOfTensorWithEmptyDimensions() {
        assertMappedTensorField("tensor(x{},y{}):{}",
                                createPutWithMappedTensor(inputJson("{ 'dimensions': [] }")));
    }

    @Test
    public void testParsingOfTensorWithEmptyCells() {
        assertMappedTensorField("tensor(x{},y{}):{}",
                                createPutWithMappedTensor(inputJson("{ 'cells': [] }")));
    }

    @Test
    public void testParsingOfMappedTensorWithCells() {
        Tensor tensor = assertMappedTensorField("{{x:a,y:b}:2.0,{x:c,y:b}:3.0}}",
                                createPutWithMappedTensor(inputJson("{",
                                                    "  'cells': [",
                                                    "    { 'address': { 'x': 'a', 'y': 'b' },",
                                                    "      'value': 2.0 },",
                                                    "    { 'address': { 'x': 'c', 'y': 'b' },",
                                                    "      'value': 3.0 }",
                                                    "  ]",
                                                    "}")));
        assertTrue(tensor instanceof MappedTensor); // any functional instance is fine
    }

    @Test
    public void testParsingOfIndexedTensorWithCells() {
        Tensor tensor = assertTensorField("{{x:0,y:0}:2.0,{x:1,y:0}:3.0}}",
                           createPutWithTensor(inputJson("{",
                                   "  'cells': [",
                                   "    { 'address': { 'x': '0', 'y': '0' },",
                                   "      'value': 2.0 },",
                                   "    { 'address': { 'x': '1', 'y': '0' },",
                                   "      'value': 3.0 }",
                                   "  ]",
                                   "}"), "indexedtensorfield"), "indexedtensorfield");
        assertTrue(tensor instanceof IndexedTensor); // this matters for performance
    }

    @Test
    public void testParsingOfTensorWithSingleCellInDifferentJsonOrder() {
        assertMappedTensorField("{{x:a,y:b}:2.0}",
                                createPutWithMappedTensor(inputJson("{",
                                        "  'cells': [",
                                        "    { 'value': 2.0,",
                                        "      'address': { 'x': 'a', 'y': 'b' } }",
                                        "  ]",
                                        "}")));
    }

    @Test
    public void testAssignUpdateOfEmptyMappedTensor() {
        assertTensorAssignUpdate("tensor(x{},y{}):{}", createAssignUpdateWithMappedTensor("{}"));
    }

    @Test
    public void testAssignUpdateOfEmptyIndexedTensor() {
        try {
            assertTensorAssignUpdate("tensor(x{},y{}):{}", createAssignUpdateWithTensor("{}", "indexedtensorfield"));
        }
        catch (IllegalArgumentException e) {
            assertEquals("An indexed tensor must have a value", "Tensor of type tensor(x[],y[]) has no values", e.getMessage());
        }
    }

    @Test
    public void testAssignUpdateOfNullTensor() {
        ClearValueUpdate clearUpdate = (ClearValueUpdate) getTensorField(createAssignUpdateWithMappedTensor(null)).getValueUpdate(0);
        assertTrue(clearUpdate != null);
        assertTrue(clearUpdate.getValue() == null);
    }

    @Test
    public void testAssignUpdateOfTensorWithCells() {
        assertTensorAssignUpdate("{{x:a,y:b}:2.0,{x:c,y:b}:3.0}}",
                createAssignUpdateWithMappedTensor(inputJson("{",
                        "  'cells': [",
                        "    { 'address': { 'x': 'a', 'y': 'b' },",
                        "      'value': 2.0 },",
                        "    { 'address': { 'x': 'c', 'y': 'b' },",
                        "      'value': 3.0 }",
                        "  ]",
                        "}")));
    }

    @Test
    public void tensor_modify_update_with_replace_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.REPLACE, "mappedtensorfield",
                inputJson("{",
                        "  'operation': 'replace',",
                        "  'cells': [",
                        "    { 'address': { 'x': 'a', 'y': 'b' }, 'value': 2.0 } ]}"));
    }

    @Test
    public void tensor_modify_update_with_add_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.ADD, "mappedtensorfield",
                inputJson("{",
                        "  'operation': 'add',",
                        "  'cells': [",
                        "    { 'address': { 'x': 'a', 'y': 'b' }, 'value': 2.0 } ]}"));
    }

    @Test
    public void tensor_modify_update_with_multiply_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.MULTIPLY, "mappedtensorfield",
                inputJson("{",
                        "  'operation': 'multiply',",
                        "  'cells': [",
                        "    { 'address': { 'x': 'a', 'y': 'b' }, 'value': 2.0 } ]}"));
    }

    @Test
    public void tensor_modify_update_on_non_tensor_field_throws() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("A modify update can only be applied to tensor fields. Field 'something' is of type 'string'");
        JsonReader reader = createReader(inputJson("{ 'update': 'id:unittest:smoke::doc1',",
                "  'fields': {",
                "    'something': {",
                "      'modify': {} }}}"));
        reader.readSingleDocument(DocumentParser.SupportedOperation.UPDATE, "id:unittest:smoke::doc1");
    }

    @Test
    public void tensor_modify_update_with_unknown_operation_throws() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Unknown operation 'unknown' in modify update for field 'mappedtensorfield'");
        createTensorModifyUpdate(inputJson("{",
                "  'operation': 'unknown',",
                "  'cells': [",
                "    { 'address': { 'x': 'a', 'y': 'b' }, 'value': 2.0 } ]}"), "mappedtensorfield");
    }

    @Test
    public void tensor_modify_update_without_operation_throws() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Modify update for field 'mappedtensorfield' does not contain an operation");
        createTensorModifyUpdate(inputJson("{",
                "  'cells': [] }"), "mappedtensorfield");
    }

    @Test
    public void tensor_modify_update_without_cells_throws() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Modify update for field 'mappedtensorfield' does not contain tensor cells");
        createTensorModifyUpdate(inputJson("{",
                "  'operation': 'replace' }"), "mappedtensorfield");
    }

    @Test
    public void tensor_modify_update_with_unknown_content_throws() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Unknown JSON string 'unknown' in modify update for field 'mappedtensorfield'");
        createTensorModifyUpdate(inputJson("{",
                "  'unknown': 'here' }"), "mappedtensorfield");
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_predicate() {
        assertParserErrorMatches(
                "Error in document 'id:unittest:testpredicate::0' - could not parse field 'boolean' of type 'predicate': " +
                        "line 1:10 no viable alternative at character '>'",

                "[",
                "      {",
                "          'fields': {",
                "              'boolean': 'timestamp > 9000'",
                "          },",
                "          'put': 'id:unittest:testpredicate::0'",
                "      }",
                "]"
        );
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_string_as_int() {
        assertParserErrorMatches(
                "Error in document 'id:unittest:testint::0' - could not parse field 'integerfield' of type 'int': " +
                        "For input string: \" 1\"",

                "[",
                "      {",
                "          'fields': {",
                "              'integerfield': ' 1'",
                "          },",
                "          'put': 'id:unittest:testint::0'",
                "      }",
                "]"
        );
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_overflowing_int() {
        assertParserErrorMatches(
                "Error in document 'id:unittest:testint::0' - could not parse field 'integerfield' of type 'int': " +
                        "For input string: \"281474976710656\"",

                "[",
                "      {",
                "          'fields': {",
                "              'integerfield': 281474976710656",
                "          },",
                "          'put': 'id:unittest:testint::0'",
                "      }",
                "]"
        );
    }

    @Test
    public void requireThatUnknownDocTypeThrowsIllegalArgumentException() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(new Contains("Document type walrus does not exist"));

        final String jsonData = inputJson(
                "[",
                "      {",
                "          'put': 'id:ns:walrus::walrus1',",
                "          'fields': {",
                "              'aField': 42",
                "          }",
                "      }",
                "]");

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    private static final String TENSOR_DOC_ID = "id:unittest:testtensor::0";

    private DocumentPut createPutWithoutTensor() {
        JsonReader reader = createReader(inputJson("[ { 'put': '" + TENSOR_DOC_ID + "', 'fields': { } } ]"));
        return (DocumentPut) reader.next();
    }

    private DocumentPut createPutWithMappedTensor(String inputTensor) {
        return createPutWithTensor(inputTensor, "mappedtensorfield");
    }
    private DocumentPut createPutWithTensor(String inputTensor, String tensorFieldName) {
        JsonReader reader = createReader(inputJson("[",
                "{ 'put': '" + TENSOR_DOC_ID + "',",
                "  'fields': {",
                "    '" + tensorFieldName + "': " + inputTensor + "  }}]"));
        return (DocumentPut) reader.next();
    }

    private DocumentUpdate createAssignUpdateWithMappedTensor(String inputTensor) {
        return createAssignUpdateWithTensor(inputTensor, "mappedtensorfield");
    }
    private DocumentUpdate createAssignUpdateWithTensor(String inputTensor, String tensorFieldName) {
        JsonReader reader = createReader(inputJson("[",
                "{ 'update': '" + TENSOR_DOC_ID + "',",
                "  'fields': {",
                "    '" + tensorFieldName + "': {",
                "      'assign': " + (inputTensor != null ? inputTensor : "null") + " } } } ]"));
        return (DocumentUpdate) reader.next();
    }

    private static Tensor assertMappedTensorField(String expectedTensor, DocumentPut put) {
        return assertTensorField(expectedTensor, put, "mappedtensorfield");
    }
    private static Tensor assertTensorField(String expectedTensor, DocumentPut put, String tensorFieldName) {
        final Document doc = put.getDocument();
        assertEquals("testtensor", doc.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, doc.getId().toString());
        TensorFieldValue fieldValue = (TensorFieldValue)doc.getFieldValue(doc.getField(tensorFieldName));
        assertEquals(Tensor.from(expectedTensor), fieldValue.getTensor().get());
        return fieldValue.getTensor().get();
    }

    private static void assertTensorAssignUpdate(String expectedTensor, DocumentUpdate update) {
        assertEquals("testtensor", update.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, update.getId().toString());
        AssignValueUpdate assignUpdate = (AssignValueUpdate) getTensorField(update).getValueUpdate(0);
        TensorFieldValue fieldValue = (TensorFieldValue) assignUpdate.getValue();
        assertEquals(Tensor.from(expectedTensor), fieldValue.getTensor().get());
    }

    private DocumentUpdate createTensorModifyUpdate(String modifyJson, String tensorFieldName) {
        JsonReader reader = createReader(inputJson("[",
                "{ 'update': '" + TENSOR_DOC_ID + "',",
                "  'fields': {",
                "    '" + tensorFieldName + "': {",
                "      'modify': " + modifyJson + " }}}]"));
        return (DocumentUpdate) reader.next();
    }

    private void assertTensorModifyUpdate(String expectedTensor, TensorModifyUpdate.Operation expectedOperation,
                                          String tensorFieldName, String modifyJson) {
        assertTensorModifyUpdate(expectedTensor, expectedOperation, tensorFieldName,
                createTensorModifyUpdate(modifyJson, tensorFieldName));
    }

    private static void assertTensorModifyUpdate(String expectedTensor, TensorModifyUpdate.Operation expectedOperation,
                                                 String tensorFieldName, DocumentUpdate update) {
        assertEquals("testtensor", update.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, update.getId().toString());
        assertEquals(1, update.fieldUpdates().size());
        FieldUpdate fieldUpdate = update.getFieldUpdate(tensorFieldName);
        assertEquals(1, fieldUpdate.size());
        TensorModifyUpdate modifyUpdate = (TensorModifyUpdate) fieldUpdate.getValueUpdate(0);
        assertEquals(expectedOperation, modifyUpdate.getOperation());
        assertEquals(Tensor.from(expectedTensor), modifyUpdate.getValue().getTensor().get());
    }

    private static FieldUpdate getTensorField(DocumentUpdate update) {
        FieldUpdate fieldUpdate = update.getFieldUpdate("mappedtensorfield");
        assertEquals(1, fieldUpdate.size());
        return fieldUpdate;
    }

    // NOTE: Do not call this method multiple times from a test method as it's using the ExpectedException rule
    private void assertParserErrorMatches(String expectedError, String... json) {
        exception.expect(JsonReaderException.class);
        exception.expectMessage(new Contains(expectedError));
        String jsonData = inputJson(json);
        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

}
