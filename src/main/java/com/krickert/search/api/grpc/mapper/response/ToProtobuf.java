package com.krickert.search.api.grpc.mapper.response;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.List;
import java.util.Map;

public class ToProtobuf {
    // Helper function to convert an Object to a google.protobuf.Value
    public static Value convertToValue(Object value) {
        Value.Builder valueBuilder = Value.newBuilder();

        if (value == null) {
            valueBuilder.setNullValue(NullValue.NULL_VALUE);
        } else if (value instanceof String) {
            valueBuilder.setStringValue((String) value);
        } else if (value instanceof Number) {
            valueBuilder.setNumberValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            valueBuilder.setBoolValue((Boolean) value);
        } else if (value instanceof Map) {
            // Convert Map to Struct
            Struct.Builder structBuilder = Struct.newBuilder();
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            mapValue.forEach((key, mapVal) -> structBuilder.putFields(key, convertToValue(mapVal)));
            valueBuilder.setStructValue(structBuilder);
        } else if (value instanceof List) {
            // Convert List to ListValue
            ListValue.Builder listBuilder = ListValue.newBuilder();
            @SuppressWarnings("unchecked")
            List<Object> listValue = (List<Object>) value;
            listValue.forEach(item -> listBuilder.addValues(convertToValue(item)));
            valueBuilder.setListValue(listBuilder);
        } else {
            // Fallback to String representation if type is not recognized
            valueBuilder.setStringValue(value.toString());
        }

        return valueBuilder.build();
    }
}
