package com.krickert.search.api.test.old.grpc.mapper.response;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.api.grpc.mapper.response.ToProtobuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToProtobufTest {

    @Test
    void testConvertToValue_NullValue() {
        Value result = ToProtobuf.convertToValue(null);
        assertEquals(NullValue.NULL_VALUE, result.getNullValue(), "Null value should be set correctly");
    }

    @Test
    void testConvertToValue_StringValue() {
        Value result = ToProtobuf.convertToValue("Test String");
        assertEquals("Test String", result.getStringValue(), "String value should match");
    }

    @Test
    void testConvertToValue_NumberValue() {
        Value result = ToProtobuf.convertToValue(123);
        assertEquals(123.0, result.getNumberValue(), "Number value should match");
    }

    @Test
    void testConvertToValue_BooleanValue() {
        Value result = ToProtobuf.convertToValue(true);
        assertTrue(result.getBoolValue(), "Boolean value should be true");
    }

    @Test
    void testConvertToValue_MapValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("key1", "value1");
        map.put("key2", 42);

        Value result = ToProtobuf.convertToValue(map);
        Struct structValue = result.getStructValue();

        assertEquals("value1", structValue.getFieldsMap().get("key1").getStringValue(), "Map key1 should match");
        assertEquals(42.0, structValue.getFieldsMap().get("key2").getNumberValue(), "Map key2 should match");
    }

    @Test
    void testConvertToValue_ListValue() {
        List<Object> list = Arrays.asList("item1", 123, true);

        Value result = ToProtobuf.convertToValue(list);
        ListValue listValue = result.getListValue();

        assertEquals(3, listValue.getValuesCount(), "List should have 3 items");
        assertEquals("item1", listValue.getValues(0).getStringValue(), "First item should match");
        assertEquals(123.0, listValue.getValues(1).getNumberValue(), "Second item should match");
        assertTrue(listValue.getValues(2).getBoolValue(), "Third item should be true");
    }

    @Test
    void testConvertToValue_ComplexMapValue() {
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put("nestedKey1", "nestedValue1");
        nestedMap.put("nestedKey2", Arrays.asList("listItem1", 456, false));

        Map<String, Object> map = new HashMap<>();
        map.put("key1", "value1");
        map.put("key2", nestedMap);

        Value result = ToProtobuf.convertToValue(map);
        Struct structValue = result.getStructValue();

        assertEquals("value1", structValue.getFieldsMap().get("key1").getStringValue(), "Map key1 should match");
        Struct nestedStruct = structValue.getFieldsMap().get("key2").getStructValue();
        assertEquals("nestedValue1", nestedStruct.getFieldsMap().get("nestedKey1").getStringValue(), "Nested key1 should match");

        ListValue nestedList = nestedStruct.getFieldsMap().get("nestedKey2").getListValue();
        assertEquals("listItem1", nestedList.getValues(0).getStringValue(), "First item in nested list should match");
        assertEquals(456.0, nestedList.getValues(1).getNumberValue(), "Second item in nested list should match");
        assertFalse(nestedList.getValues(2).getBoolValue(), "Third item in nested list should be false");
    }

    @Test
    void testConvertToValue_UnknownType() {
        Object unknownType = new Object();
        Value result = ToProtobuf.convertToValue(unknownType);
        assertEquals(unknownType.toString(), result.getStringValue(), "Unknown type should fallback to string representation");
    }
}