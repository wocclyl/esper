/*
 * *************************************************************************************
 *  Copyright (C) 2006-2015 EsperTech, Inc. All rights reserved.                       *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.avro.core;

import com.espertech.esper.client.ConfigurationEngineDefaults;
import com.espertech.esper.client.EPException;
import com.espertech.esper.client.EventType;
import com.espertech.esper.client.annotation.AvroField;
import com.espertech.esper.event.EventAdapterService;
import com.espertech.esper.event.EventTypeUtility;
import com.espertech.esper.util.JavaClassHelper;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.function.BiConsumer;

import static com.espertech.esper.avro.core.AvroConstant.PROP_JAVA_STRING_KEY;
import static com.espertech.esper.avro.core.AvroConstant.PROP_JAVA_STRING_VALUE;
import static org.apache.avro.SchemaBuilder.*;

public class AvroSchemaUtil {

    static String toSchemaStringSafe(Schema schema) {
        try {
            return schema.toString();
        }
        catch (Throwable t) {
            return "[Invalid schema: " + t.getClass().getName() + ": " + t.getMessage() + "]";
        }
    }

    public static Schema findUnionRecordSchemaSingle(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) {
            return null;
        }
        Schema found = null;
        for (Schema member : schema.getTypes()) {
            if (member.getType() == Schema.Type.RECORD) {
                if (found == null) {
                    found = member;
                }
                else {
                    return null;
                }
            }
        }
        return found;
    }

    static void assembleField(String propertyName, Object propertyType, SchemaBuilder.FieldAssembler<Schema> assembler, Annotation[] annotations, ConfigurationEngineDefaults.EventMeta.AvroSettings avroSettings, EventAdapterService eventAdapterService) {
        if (propertyName.contains(".")) {
            throw new EPException("Invalid property name as Avro does not allow dot '.' in field names (property '" + propertyName + "')");
        }
        Schema schema = getAnnotationSchema(propertyName, annotations);
        if (schema != null) {
            assembler.name(propertyName).type(schema).noDefault();
        }
        else if (propertyType == null) {
            assembler.name(propertyName).type("null");
        }
        else if (propertyType instanceof String) {
            String propertyTypeName = propertyType.toString();
            boolean isArray = EventTypeUtility.isPropertyArray(propertyTypeName);
            if (isArray) {
                propertyTypeName = EventTypeUtility.getPropertyRemoveArray(propertyTypeName);
            }

            // Add EventType itself as a property
            EventType eventType = eventAdapterService.getExistsTypeByName(propertyTypeName);
            if (!(eventType instanceof AvroEventType)) {
                throw new EPException("Type definition encountered an unexpected property type name '"
                        + propertyType + "' for property '" + propertyName + "', expected the name of a previously-declared Avro type");
            }
            schema = ((AvroEventType) eventType).getSchemaAvro();

            if (!isArray) {
                assembler.name(propertyName).type(schema).noDefault();
            }
            else {
                assembler.name(propertyName).type(array().items(schema)).noDefault();
            }
        }
        else if (propertyType instanceof EventType){
            EventType eventType = (EventType) propertyType;
            checkAvroType(eventType);
            schema = ((AvroEventType) eventType).getSchemaAvro();
            assembler.name(propertyName).type(schema).noDefault();
        }
        else if (propertyType instanceof EventType[]) {
            EventType eventType = ((EventType[]) propertyType)[0];
            checkAvroType(eventType);
            schema = ((AvroEventType) eventType).getSchemaAvro();
            assembler.name(propertyName).type(array().items(schema)).noDefault();
        }
        else if (propertyType instanceof Class) {
            Class propertyClass = (Class) propertyType;
            Class propertyClassBoxed = JavaClassHelper.getBoxedType(propertyClass);
            boolean nullable = propertyClass == propertyClassBoxed;
            boolean preferNonNull = avroSettings.isEnableSchemaDefaultNonNull();
            if (propertyClassBoxed == Boolean.class) {
                assemblePrimitive(nullable, REQ_BOOLEAN, OPT_BOOLEAN, assembler, propertyName, preferNonNull);
            }
            else if (propertyClassBoxed == Integer.class || propertyClassBoxed == Byte.class) {
                assemblePrimitive(nullable, REQ_INT, OPT_INT, assembler, propertyName, preferNonNull);
            }
            else if (propertyClassBoxed == Long.class) {
                assemblePrimitive(nullable, REQ_LONG, OPT_LONG, assembler, propertyName, preferNonNull);
            }
            else if (propertyClassBoxed == Float.class) {
                assemblePrimitive(nullable, REQ_FLOAT, OPT_FLOAT, assembler, propertyName, preferNonNull);
            }
            else if (propertyClassBoxed == Double.class) {
                assemblePrimitive(nullable, REQ_DOUBLE, OPT_DOUBLE, assembler, propertyName, preferNonNull);
            }
            else if (propertyClass == String.class || propertyClass == CharSequence.class) {
                if (avroSettings.isEnableNativeString()) {
                    if (preferNonNull) {
                        assembler.name(propertyName).type().stringBuilder().prop(PROP_JAVA_STRING_KEY, PROP_JAVA_STRING_VALUE).endString().noDefault();
                    }
                    else {
                        assembler.name(propertyName).type().unionOf().nullType().and().stringBuilder().prop(PROP_JAVA_STRING_KEY, PROP_JAVA_STRING_VALUE).endString().endUnion().noDefault();
                    }
                }
                else {
                    assemblePrimitive(nullable, REQ_STRING, OPT_STRING, assembler, propertyName, preferNonNull);
                }
            }
            else if (propertyClass == byte[].class) {
                if (preferNonNull) {
                    assembler.requiredBytes(propertyName);
                }
                else {
                    assembler.name(propertyName).type(unionOf().nullType().and().bytesType().endUnion()).noDefault();
                }
            }
            else if (propertyClass.isArray()) {
                Class componentType = propertyClass.getComponentType();
                Class componentTypeBoxed = JavaClassHelper.getBoxedType(componentType);
                boolean nullableElements = componentType == componentTypeBoxed;

                if (componentTypeBoxed == Boolean.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_BOOLEAN, ARRAY_OF_OPT_BOOLEAN, assembler, propertyName, preferNonNull);
                }
                else if (componentTypeBoxed == Integer.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_INT, ARRAY_OF_OPT_INT, assembler, propertyName, preferNonNull);
                }
                else if (componentTypeBoxed == Long.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_LONG, ARRAY_OF_OPT_LONG, assembler, propertyName, preferNonNull);
                }
                else if (componentTypeBoxed == Float.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_FLOAT, ARRAY_OF_OPT_FLOAT, assembler, propertyName, preferNonNull);
                }
                else if (componentTypeBoxed == Byte.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_INT, ARRAY_OF_OPT_INT, assembler, propertyName, preferNonNull);
                }
                else if (componentTypeBoxed == Double.class) {
                    assembleArray(nullableElements, ARRAY_OF_REQ_DOUBLE, ARRAY_OF_OPT_DOUBLE, assembler, propertyName, preferNonNull);
                }
                else if (propertyClass == String[].class || propertyClass == CharSequence[].class) {
                    Schema array;
                    if (avroSettings.isEnableNativeString()) {
                        array = array().items(builder().stringBuilder().prop(PROP_JAVA_STRING_KEY, PROP_JAVA_STRING_VALUE).endString());
                    }
                    else {
                        array = array().items(builder().stringBuilder().endString());
                    }

                    if (preferNonNull) {
                        assembler.name(propertyName).type(array).noDefault();
                    }
                    else {
                        assembler.name(propertyName).type(unionOf().nullType().and().type(array).endUnion()).noDefault();
                    }
                }
                else {
                    throw makeEPException(propertyName, propertyType);
                }
            }
            else if (JavaClassHelper.isImplementsInterface(propertyClass, Map.class)) {
                Schema value;
                if (avroSettings.isEnableNativeString()) {
                    value = builder().stringBuilder().prop(PROP_JAVA_STRING_KEY, PROP_JAVA_STRING_VALUE).endString();
                }
                else {
                    value = builder().stringBuilder().endString();
                }

                if (preferNonNull) {
                    assembler.name(propertyName).type(map().values(value)).noDefault();
                }
                else {
                    assembler.name(propertyName).type(unionOf().nullType().and().type(map().values(value)).endUnion()).noDefault();
                }
            }
            else {
                throw makeEPException(propertyName, propertyType);
            }
        }
        else {
            throw makeEPException(propertyName, propertyType);
        }
    }

    private static Schema getAnnotationSchema(String propertyName, Annotation[] annotations) {
        if (annotations == null) {
            return null;
        }
        for (Annotation annotation : annotations) {
            if (annotation instanceof AvroField) {
                AvroField avroField = (AvroField) annotation;
                if (avroField.name().equals(propertyName)) {
                    String schema = avroField.schema();
                    try {
                        return new Schema.Parser().parse(schema);
                    }
                    catch (RuntimeException ex) {
                        throw new EPException("Failed to parse Avro schema for property '" + propertyName + "': " + ex.getMessage(), ex);
                    }
                }
            }
        }
        return null;
    }

    private static void checkAvroType(EventType eventType) {
        if (!(eventType instanceof AvroEventType)) {
            throw new EPException("Property type cannot be an event type with an underlying of type '" + eventType.getUnderlyingType().getName() + "'");
        }
    }

    private static void assemblePrimitive(boolean nullable, BiConsumer<FieldAssembler<Schema>, String> reqAssemble, BiConsumer<FieldAssembler<Schema>, String> optAssemble, FieldAssembler<Schema> assembler, String propertyName, boolean preferNonNull) {
        if (preferNonNull) {
            reqAssemble.accept(assembler, propertyName);
        }
        else {
            if (nullable) {
                optAssemble.accept(assembler, propertyName);
            }
            else {
                reqAssemble.accept(assembler, propertyName);
            }
        }
    }

    private static void assembleArray(boolean nullableElements, Schema arrayOfReq, Schema arrayOfOpt,
                                      FieldAssembler<Schema> assembler, String propertyName, boolean preferNonNull) {
        if (preferNonNull) {
            if (!nullableElements) {
                assembler.name(propertyName).type(arrayOfReq).noDefault();
            }
            else {
                assembler.name(propertyName).type(arrayOfOpt).noDefault();
            }
        }
        else {
            if (!nullableElements) {
                Schema union = unionOf().nullType().and().type(arrayOfReq).endUnion();
                assembler.name(propertyName).type(union).noDefault();
            }
            else {
                Schema union = unionOf().nullType().and().type(arrayOfOpt).endUnion();
                assembler.name(propertyName).type(union).noDefault();
            }
        }
    }

    private static EPException makeEPException(String propertyName, Object propertyType) {
        return new EPException("Property '" + propertyName + "' type '" + propertyType + "' does not have a mapping to an Avro type (consider using the AvroField annotation)");
    }

    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_BOOLEAN = FieldAssembler::requiredBoolean;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_BOOLEAN = FieldAssembler::optionalBoolean;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_INT = FieldAssembler::requiredInt;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_INT = FieldAssembler::optionalInt;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_DOUBLE = FieldAssembler::requiredDouble;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_DOUBLE = FieldAssembler::optionalDouble;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_FLOAT = FieldAssembler::requiredFloat;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_FLOAT = FieldAssembler::optionalFloat;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_LONG = FieldAssembler::requiredLong;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_LONG = FieldAssembler::optionalLong;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> REQ_STRING = FieldAssembler::requiredString;
    private final static BiConsumer<SchemaBuilder.FieldAssembler<Schema>, String> OPT_STRING = FieldAssembler::optionalString;

    private final static Schema ARRAY_OF_REQ_BOOLEAN = array().items().booleanType();
    private final static Schema ARRAY_OF_OPT_BOOLEAN = array().items(unionOf().nullType().and().booleanType().endUnion());
    private final static Schema ARRAY_OF_REQ_INT = array().items().intType();
    private final static Schema ARRAY_OF_OPT_INT = array().items(unionOf().nullType().and().intType().endUnion());
    private final static Schema ARRAY_OF_REQ_LONG = array().items().longType();
    private final static Schema ARRAY_OF_OPT_LONG = array().items(unionOf().nullType().and().longType().endUnion());
    private final static Schema ARRAY_OF_REQ_DOUBLE = array().items().doubleType();
    private final static Schema ARRAY_OF_OPT_DOUBLE = array().items(unionOf().nullType().and().doubleType().endUnion());
    private final static Schema ARRAY_OF_REQ_FLOAT = array().items().floatType();
    private final static Schema ARRAY_OF_OPT_FLOAT = array().items(unionOf().nullType().and().floatType().endUnion());
}
