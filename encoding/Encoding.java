/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.encoding;

import com.vaticle.typedb.common.collection.Pair;
import com.vaticle.typedb.core.common.collection.ByteArray;
import com.vaticle.typedb.core.common.collection.Bytes;
import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.parameters.Label;
import com.vaticle.typeql.lang.common.TypeQLArg;

import javax.annotation.Nullable;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.vaticle.typedb.common.collection.Collections.map;
import static com.vaticle.typedb.common.collection.Collections.pair;
import static com.vaticle.typedb.common.collection.Collections.set;
import static com.vaticle.typedb.common.util.Objects.className;
import static com.vaticle.typedb.core.common.collection.Bytes.SHORT_SIZE;
import static com.vaticle.typedb.core.common.collection.Bytes.SHORT_UNSIGNED_MAX_VALUE;
import static com.vaticle.typedb.core.common.collection.Bytes.signedByte;
import static com.vaticle.typedb.core.common.collection.Bytes.unsignedByte;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.UNRECOGNISED_VALUE;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.ThingRead.VALUES_NOT_COMPARABLE;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Encoding {

    public static final String ROCKS_DATA = "data";
    public static final String ROCKS_SCHEMA = "schema";
    public static final int ENCODING_VERSION = 2;

    public enum Partition {
        DEFAULT(0),
        VARIABLE_START_EDGE(1),
        FIXED_START_EDGE(2),
        OPTIMISATION_EDGE(3),
        METADATA(4);

        private final byte ID;

        Partition(int ID) {
            this.ID = Bytes.unsignedByte(ID);
        }

        public byte ID() {
            return ID;
        }
    }

    public enum Key {
        PERSISTED(0, true),
        BUFFERED(-1, false);
        private final int initialValue;

        private final boolean isIncrement;

        Key(int initialValue, boolean isIncrement) {
            this.initialValue = initialValue;
            this.isIncrement = isIncrement;
        }

        public int initialValue() {
            return initialValue;
        }

        public boolean isIncrement() {
            return isIncrement;
        }
    }

    public enum Status {
        BUFFERED(0),
        COMMITTED(1),
        PERSISTED(2),
        EPHEMERAL(3);

        private final int status;

        Status(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public interface Direction {

        enum Edge implements Direction {
            FORWARD(true),
            BACKWARD(false);

            private final boolean isForward;

            Edge(boolean isForward) {
                this.isForward = isForward;
            }

            public boolean isForward() {
                return isForward;
            }

            public boolean isBackward() {
                return !isForward;
            }
        }
    }

    public enum PrefixType {
        SYSTEM,
        INDEX,
        METADATA,
        TYPE,
        THING,
        VALUE,
        RULE
    }

    /**
     * The values in this class will be used as 'prefixes' within an IID for every database object,
     * and must not overlap with each other.
     * <p>
     * A prefix is 1 unsigned byte, up to the value of 179. Values 180-255 are reserved for TypeDB Cloud.
     */
    public enum Prefix {
        SYSTEM(0, PrefixType.SYSTEM),
        // leave large open range for future indices
        INDEX_TYPE(20, PrefixType.INDEX),
        INDEX_RULE(21, PrefixType.INDEX),
        METADATA_STATISTICS(60, PrefixType.METADATA),
        VERTEX_THING_TYPE(100, PrefixType.TYPE),
        VERTEX_ENTITY_TYPE(110, PrefixType.TYPE),
        VERTEX_ATTRIBUTE_TYPE(111, PrefixType.TYPE),
        VERTEX_RELATION_TYPE(112, PrefixType.TYPE),
        VERTEX_ROLE_TYPE(113, PrefixType.TYPE),
        VERTEX_ENTITY(130, PrefixType.THING),
        VERTEX_ATTRIBUTE(131, PrefixType.THING),
        VERTEX_RELATION(132, PrefixType.THING),
        VERTEX_ROLE(133, PrefixType.THING),
        VERTEX_VALUE(150, PrefixType.VALUE),
        STRUCTURE_RULE(160, PrefixType.RULE);

        private static final ByteMap<Prefix> prefixByKey = ByteMap.create(
                pair(SYSTEM.key, SYSTEM),
                pair(INDEX_TYPE.key, INDEX_TYPE),
                pair(INDEX_RULE.key, INDEX_RULE),
                pair(METADATA_STATISTICS.key, METADATA_STATISTICS),
                pair(VERTEX_THING_TYPE.key, VERTEX_THING_TYPE),
                pair(VERTEX_ENTITY_TYPE.key, VERTEX_ENTITY_TYPE),
                pair(VERTEX_ATTRIBUTE_TYPE.key, VERTEX_ATTRIBUTE_TYPE),
                pair(VERTEX_RELATION_TYPE.key, VERTEX_RELATION_TYPE),
                pair(VERTEX_ROLE_TYPE.key, VERTEX_ROLE_TYPE),
                pair(VERTEX_ENTITY.key, VERTEX_ENTITY),
                pair(VERTEX_ATTRIBUTE.key, VERTEX_ATTRIBUTE),
                pair(VERTEX_RELATION.key, VERTEX_RELATION),
                pair(VERTEX_ROLE.key, VERTEX_ROLE),
                pair(STRUCTURE_RULE.key, STRUCTURE_RULE)
        );

        private final byte key;
        private final PrefixType type;
        private final ByteArray bytes;

        Prefix(int key, PrefixType type) {
            assert key < 180 : "The encoding range >= 180 is reserved for TypeDB Cloud.";
            this.key = unsignedByte(key);
            this.type = type;
            this.bytes = ByteArray.of(new byte[]{this.key});
        }

        public static Prefix of(byte key) {
            Prefix prefix = prefixByKey.get(key);
            if (prefix == null) throw TypeDBException.of(UNRECOGNISED_VALUE);
            else return prefix;
        }

        public byte key() {
            return key;
        }

        public ByteArray bytes() {
            return bytes;
        }

        public PrefixType type() {
            return type;
        }

        public boolean isIndex() {
            return type.equals(PrefixType.INDEX);
        }

        public boolean isMetadata() {
            return type.equals(PrefixType.METADATA);
        }

        public boolean isType() {
            return type.equals(PrefixType.TYPE);
        }

        public boolean isThing() {
            return type.equals(PrefixType.THING);
        }

        public boolean isRule() {
            return type.equals(PrefixType.RULE);
        }

        public boolean isSystem() {
            return type.equals(PrefixType.SYSTEM);
        }

    }

    /**
     * The values in this class will be used as 'infixes' between two IIDs of
     * two objects in the database, and must not overlap with each other.
     * <p>
     * The size of a prefix is 1 signed byte; i.e. min-value = -128 and max-value = 127.
     * <p>
     * TODO: these should be scoped to be within a section of the encoding.
     * For example, Edge and Property should have their own Infixes.
     */
    public enum Infix {
        PROPERTY_LABEL(0),
        PROPERTY_SCOPE(1),
        PROPERTY_ABSTRACT(2),
        PROPERTY_VALUE_TYPE(3),
        PROPERTY_REGEX(4),
        PROPERTY_WHEN(5),
        PROPERTY_THEN(6),
        PROPERTY_VALUE(7),
        PROPERTY_VALUE_REF(8),
        EDGE_OWNS_PROPERTY_ANNOTATION_UNIQUE(20),
        EDGE_ISA_BACKWARD(-40), // EDGE_ISA_FORWARD does not exist by design
        EDGE_SUB_FORWARD(50),
        EDGE_SUB_BACKWARD(-50),
        EDGE_OWNS_FORWARD(51),
        EDGE_OWNS_BACKWARD(-51),
        EDGE_OWNS_KEY_FORWARD(52),
        EDGE_OWNS_KEY_BACKWARD(-52),
        EDGE_PLAYS_FORWARD(53),
        EDGE_PLAYS_BACKWARD(-53),
        EDGE_RELATES_FORWARD(54),
        EDGE_RELATES_BACKWARD(-54),
        EDGE_HAS_FORWARD(70),
        EDGE_HAS_BACKWARD(-70),
        EDGE_PLAYING_FORWARD(71),
        EDGE_PLAYING_BACKWARD(-71),
        EDGE_RELATING_FORWARD(72),
        EDGE_RELATING_BACKWARD(-72),
        EDGE_ROLEPLAYER_FORWARD(73, true),
        EDGE_ROLEPLAYER_BACKWARD(-73, true);

        private static final ByteMap<Infix> infixByKey = ByteMap.create(
                pair(PROPERTY_LABEL.key, PROPERTY_LABEL),
                pair(PROPERTY_SCOPE.key, PROPERTY_SCOPE),
                pair(PROPERTY_ABSTRACT.key, PROPERTY_ABSTRACT),
                pair(PROPERTY_VALUE_TYPE.key, PROPERTY_VALUE_TYPE),
                pair(PROPERTY_REGEX.key, PROPERTY_REGEX),
                pair(PROPERTY_WHEN.key, PROPERTY_WHEN),
                pair(PROPERTY_THEN.key, PROPERTY_THEN),
                pair(PROPERTY_VALUE.key, PROPERTY_VALUE),
                pair(PROPERTY_VALUE_REF.key, PROPERTY_VALUE_REF),
                pair(EDGE_ISA_BACKWARD.key, EDGE_ISA_BACKWARD),
                pair(EDGE_SUB_FORWARD.key, EDGE_SUB_FORWARD),
                pair(EDGE_SUB_BACKWARD.key, EDGE_SUB_BACKWARD),
                pair(EDGE_OWNS_FORWARD.key, EDGE_OWNS_FORWARD),
                pair(EDGE_OWNS_BACKWARD.key, EDGE_OWNS_BACKWARD),
                pair(EDGE_OWNS_KEY_FORWARD.key, EDGE_OWNS_KEY_FORWARD),
                pair(EDGE_OWNS_KEY_BACKWARD.key, EDGE_OWNS_KEY_BACKWARD),
                pair(EDGE_PLAYS_FORWARD.key, EDGE_PLAYS_FORWARD),
                pair(EDGE_PLAYS_BACKWARD.key, EDGE_PLAYS_BACKWARD),
                pair(EDGE_RELATES_FORWARD.key, EDGE_RELATES_FORWARD),
                pair(EDGE_RELATES_BACKWARD.key, EDGE_RELATES_BACKWARD),
                pair(EDGE_HAS_FORWARD.key, EDGE_HAS_FORWARD),
                pair(EDGE_HAS_BACKWARD.key, EDGE_HAS_BACKWARD),
                pair(EDGE_PLAYING_FORWARD.key, EDGE_PLAYING_FORWARD),
                pair(EDGE_PLAYING_BACKWARD.key, EDGE_PLAYING_BACKWARD),
                pair(EDGE_RELATING_FORWARD.key, EDGE_RELATING_FORWARD),
                pair(EDGE_RELATING_BACKWARD.key, EDGE_RELATING_BACKWARD),
                pair(EDGE_ROLEPLAYER_FORWARD.key, EDGE_ROLEPLAYER_FORWARD),
                pair(EDGE_ROLEPLAYER_BACKWARD.key, EDGE_ROLEPLAYER_BACKWARD)
        );

        public static final int LENGTH = 1;

        private final byte key;
        private final boolean isOptimisation;
        private final ByteArray bytes;

        Infix(int key) {
            this(key, false);
        }

        Infix(int key, boolean isOptimisation) {
            this.key = signedByte(key);
            this.isOptimisation = isOptimisation;
            this.bytes = ByteArray.of(new byte[]{this.key});
        }

        public static Infix of(byte key) {
            Infix infix = infixByKey.get(key); // already unsigned??
            if (infix == null) throw TypeDBException.of(UNRECOGNISED_VALUE);
            else return infix;
        }

        public byte key() {
            return key;
        }

        public ByteArray bytes() {
            return bytes;
        }

        public boolean isOptimisation() {
            return isOptimisation;
        }

    }

    public static class Property {

        public enum Vertex {
            LABEL(Infix.PROPERTY_LABEL),
            SCOPE(Infix.PROPERTY_SCOPE),
            ABSTRACT(Infix.PROPERTY_ABSTRACT),
            REGEX(Infix.PROPERTY_REGEX),
            VALUE_TYPE(Infix.PROPERTY_VALUE_TYPE),
            VALUE_REF(Infix.PROPERTY_VALUE_REF),
            VALUE(Infix.PROPERTY_VALUE);

            private final Infix infix;

            Vertex(Infix infix) {
                this.infix = infix;
            }

            public Infix infix() {
                return infix;
            }
        }

        public enum Edge {
            OWNS_PROPERTY_ANNOTATION_UNIQUE(Infix.EDGE_OWNS_PROPERTY_ANNOTATION_UNIQUE, true);

            private final Infix infix;
            private final boolean isAnnotation;

            Edge(Infix infix, boolean isAnnotation) {
                this.infix = infix;
                this.isAnnotation = isAnnotation;
            }

            public Infix infix() {
                return infix;
            }

            public boolean isAnnotation() {
                return isAnnotation;
            }
        }

        public enum Structure {
            LABEL(Infix.PROPERTY_LABEL),
            WHEN(Infix.PROPERTY_WHEN),
            THEN(Infix.PROPERTY_THEN);

            private final Infix infix;

            Structure(Infix infix) {
                this.infix = infix;
            }

            public Infix infix() {
                return infix;
            }
        }
    }

    /**
     * The size of a prefix is 1 unsigned byte; i.e. min-value = 0 and max-value = 255.
     */
    public static class ValueType<T> {

        public static final ZoneId TIME_ZONE_ID = ZoneOffset.UTC;
        public static final Charset STRING_ENCODING = UTF_8;
        public static final int STRING_SIZE_ENCODING = SHORT_SIZE;
        public static final int STRING_MAX_SIZE = SHORT_UNSIGNED_MAX_VALUE;
        public static final double DOUBLE_PRECISION = 0.000001d;

        public static final ValueType<Object> OBJECT = new ValueType<>(
                0, "Object", Object.class, false, false, false, null, null
        );
        public static final ValueType<Boolean> BOOLEAN = new ValueType<>(
                10, "Boolean", Boolean.class, true, true, true, TypeQLArg.ValueType.BOOLEAN, Boolean::compareTo
        );
        public static final ValueType<Long> LONG = new ValueType<>(
                20, "Long", Long.class, true, true, true, TypeQLArg.ValueType.LONG, Long::compareTo
        );
        public static final ValueType<Double> DOUBLE = new ValueType<>(
                30, "Double", Double.class, true, false, true, TypeQLArg.ValueType.DOUBLE, (a, b) -> {
            int res = Double.compare(a, b);
            if (res == 0 || Math.abs(a - b) < DOUBLE_PRECISION) return 0;
            else return res;
        }
        );
        public static final ValueType<String> STRING = new ValueType<>(
                40, "String", String.class, true, true, false, TypeQLArg.ValueType.STRING, String::compareTo
        );
        public static final ValueType<LocalDateTime> DATETIME = new ValueType<>(
                50, "DateTime", LocalDateTime.class, true, true, true, TypeQLArg.ValueType.DATETIME, LocalDateTime::compareTo
        );

        private static final ByteMap<ValueType<?>> valueTypeByKey = ByteMap.create(
                pair(OBJECT.key, OBJECT),
                pair(BOOLEAN.key, BOOLEAN),
                pair(LONG.key, LONG),
                pair(DOUBLE.key, DOUBLE),
                pair(STRING.key, STRING),
                pair(DATETIME.key, DATETIME)
        );

        private static final Map<ValueType<?>, Set<ValueType<?>>> ASSIGNABLES = map(
                pair(OBJECT, set()),
                pair(BOOLEAN, set(BOOLEAN)),
                pair(LONG, set(LONG, DOUBLE)),
                pair(DOUBLE, set(DOUBLE)),
                pair(STRING, set(STRING)),
                pair(DATETIME, set(DATETIME))
        );

        private static final Map<ValueType<?>, Set<ValueType<?>>> COMPARABLES = map(
                pair(OBJECT, set()),
                pair(BOOLEAN, set(BOOLEAN)),
                pair(LONG, set(LONG, DOUBLE)),
                pair(DOUBLE, set(LONG, DOUBLE)),
                pair(STRING, set(STRING)),
                pair(DATETIME, set(DATETIME))
        );

        private final String name;
        private final byte key;
        private final ByteArray bytes;
        private final boolean hasExactEquality;
        private final boolean isWritable;
        private final boolean isSorted; // TODO: once strings are encoded in the correct order, we can remove this

        private final Class<T> valueClass;
        private final TypeQLArg.ValueType typeQLValueType;
        private final Comparator<T> comparator;

        ValueType(int key, String name, Class<T> valueClass, boolean isWritable, boolean hasExactEquality, boolean isSorted,
                  @Nullable TypeQLArg.ValueType typeQLValueType, @Nullable Comparator<T> comparator) {
            this.key = unsignedByte(key);
            this.name = name;
            this.comparator = comparator;
            this.bytes = ByteArray.of(new byte[]{this.key});
            this.valueClass = valueClass;
            this.isWritable = isWritable;
            this.hasExactEquality = hasExactEquality;
            this.isSorted = isSorted;
            this.typeQLValueType = typeQLValueType;
        }

        public static ValueType<?> of(byte value) {
            ValueType<?> valueType = valueTypeByKey.get(value);
            if (valueType == null) throw TypeDBException.of(UNRECOGNISED_VALUE);
            else return valueType;
        }

        public static ValueType<?> of(Class<?> valueClass) {
            if (valueClass == OBJECT.valueClass) return OBJECT;
            else if (valueClass == BOOLEAN.valueClass) return BOOLEAN;
            else if (valueClass == LONG.valueClass) return LONG;
            else if (valueClass == DOUBLE.valueClass) return DOUBLE;
            else if (valueClass == STRING.valueClass) return STRING;
            else if (valueClass == DATETIME.valueClass) return DATETIME;
            else throw TypeDBException.of(UNRECOGNISED_VALUE);
        }

        public static ValueType<?> of(TypeQLArg.ValueType typeQLValueType) {
            if (typeQLValueType == OBJECT.typeQLValueType) return OBJECT;
            else if (typeQLValueType == BOOLEAN.typeQLValueType) return BOOLEAN;
            else if (typeQLValueType == LONG.typeQLValueType) return LONG;
            else if (typeQLValueType == DOUBLE.typeQLValueType) return DOUBLE;
            else if (typeQLValueType == STRING.typeQLValueType) return STRING;
            else if (typeQLValueType == DATETIME.typeQLValueType) return DATETIME;
            else throw TypeDBException.of(UNRECOGNISED_VALUE);
        }

        public String name() {
            return name;
        }

        public byte key() {
            return key;
        }

        public ByteArray bytes() {
            return bytes;
        }

        public Class<T> valueClass() {
            return valueClass;
        }

        public boolean isWritable() {
            return isWritable;
        }

        public boolean hasExactEquality() {
            return hasExactEquality;
        }

        public boolean isSorted() {
            return isSorted;
        }

        public Set<ValueType<?>> assignables() {
            return ASSIGNABLES.get(this);
        }

        public Set<ValueType<?>> comparables() {
            return COMPARABLES.get(this);
        }

        public boolean comparableTo(ValueType<?> valueType) {
            return COMPARABLES.get(this).contains(valueType);
        }

        public TypeQLArg.ValueType typeQLValueType() {
            return typeQLValueType;
        }

        public Comparator<T> comparator() {
            return comparator;
        }

        public static <T, U> int compare(ValueType<T> firstType, T firstValue, ValueType<U> secondType, U secondValue) {
            if (!firstType.comparableTo(secondType)) {
                throw TypeDBException.of(VALUES_NOT_COMPARABLE, firstType, firstValue, secondType, secondValue);
            }

            if (firstType == BOOLEAN) {
                assert secondType == BOOLEAN;
                return BOOLEAN.comparator().compare((Boolean) firstValue, (Boolean) secondValue);
            } else if (firstType == LONG) {
                if (secondType == LONG) return LONG.comparator().compare((Long) firstValue, (Long) secondValue);
                else if (secondType == DOUBLE) {
                    return DOUBLE.comparator().compare(((Long) firstValue).doubleValue(), (Double) secondValue);
                } else throw TypeDBException.of(ILLEGAL_STATE);
            } else if (firstType == DOUBLE) {
                if (secondType == LONG) {
                    return DOUBLE.comparator().compare((Double) firstValue, ((Long) secondValue).doubleValue());
                } else if (secondType == DOUBLE) {
                    return DOUBLE.comparator().compare((Double) firstValue, (Double) secondValue);
                } else throw TypeDBException.of(ILLEGAL_STATE);
            } else if (firstType == STRING) {
                assert secondType == STRING;
                return STRING.comparator().compare((String) firstValue, (String) secondValue);
            } else if (firstType == DATETIME) {
                assert secondType == DATETIME;
                return DATETIME.comparator().compare((LocalDateTime) firstValue, (LocalDateTime) secondValue);
            } else throw TypeDBException.of(ILLEGAL_STATE);
        }

        public static <SOURCE_VALUE> Boolean convertToBoolean(
                ValueType<SOURCE_VALUE> sourceEncoding, SOURCE_VALUE sourceValue
        ) {
            assert BOOLEAN.comparableTo(sourceEncoding);
            if (sourceEncoding.equals(BOOLEAN)) return (Boolean) sourceValue;
            else throw TypeDBException.of(ILLEGAL_STATE);
        }

        public static <SOURCE_VALUE> Long convertToLong(
                ValueType<SOURCE_VALUE> sourceEncoding, SOURCE_VALUE sourceValue, boolean roundDown
        ) {
            assert LONG.comparableTo(sourceEncoding);
            if (sourceEncoding.equals(LONG)) return (Long) sourceValue;
            else if (sourceEncoding.equals(DOUBLE)) {
                return roundDown ? ((Double) sourceValue).longValue() : (long) (((Double) sourceValue) + 1);
            } else throw TypeDBException.of(ILLEGAL_STATE);
        }

        public static <SOURCE_VALUE> Double convertToDouble(
                ValueType<SOURCE_VALUE> sourceEncoding, SOURCE_VALUE sourceValue
        ) {
            assert DOUBLE.comparableTo(sourceEncoding);
            if (sourceEncoding.equals(LONG)) return ((Long) sourceValue).doubleValue();
            else if (sourceEncoding.equals(DOUBLE)) return (Double) sourceValue;
            else throw TypeDBException.of(ILLEGAL_STATE);
        }

        public static <SOURCE_VALUE> String convertToString(
                ValueType<SOURCE_VALUE> sourceEncoding, SOURCE_VALUE sourceValue
        ) {
            assert STRING.comparableTo(sourceEncoding);
            if (sourceEncoding.equals(STRING)) return (String) sourceValue;
            else throw TypeDBException.of(ILLEGAL_STATE);
        }

        public static <SOURCE_VALUE> LocalDateTime convertToDateTime(
                ValueType<SOURCE_VALUE> sourceEncoding, SOURCE_VALUE sourceValue
        ) {
            assert DATETIME.comparableTo(sourceEncoding);
            if (sourceEncoding.equals(DATETIME)) return (LocalDateTime) sourceValue;
            else throw TypeDBException.of(ILLEGAL_STATE);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public interface Structure {

        Prefix prefix();

        interface Rule extends Structure {

            String label();
        }

        Structure.Rule RULE = new Structure.Rule() {
            @Override
            public Prefix prefix() {
                return Prefix.STRUCTURE_RULE;
            }

            @Override
            public String label() {
                return "rule";
            }
        };
    }

    public interface Vertex {

        Prefix prefix();

        enum Type implements Vertex {
            THING_TYPE(Prefix.VERTEX_THING_TYPE, Root.THING, null),
            ENTITY_TYPE(Prefix.VERTEX_ENTITY_TYPE, Root.ENTITY, Thing.ENTITY),
            ATTRIBUTE_TYPE(Prefix.VERTEX_ATTRIBUTE_TYPE, Root.ATTRIBUTE, Thing.ATTRIBUTE),
            RELATION_TYPE(Prefix.VERTEX_RELATION_TYPE, Root.RELATION, Thing.RELATION),
            ROLE_TYPE(Prefix.VERTEX_ROLE_TYPE, Root.ROLE, Thing.ROLE);

            private static final ByteMap<Type> typeVertexByKey = ByteMap.create(
                    pair(THING_TYPE.prefix.key, THING_TYPE),
                    pair(ENTITY_TYPE.prefix.key, ENTITY_TYPE),
                    pair(ATTRIBUTE_TYPE.prefix.key, ATTRIBUTE_TYPE),
                    pair(RELATION_TYPE.prefix.key, RELATION_TYPE),
                    pair(ROLE_TYPE.prefix.key, ROLE_TYPE)
            );

            private final Prefix prefix;
            private final Root root;
            private final Thing instance;

            Type(Prefix prefix, Root root, Thing instance) {
                this.prefix = prefix;
                this.root = root;
                this.instance = instance;
            }

            public static Type of(byte prefix) {
                Type type = typeVertexByKey.get(prefix);
                if (type == null) throw TypeDBException.of(UNRECOGNISED_VALUE);
                else return type;
            }

            public static Type of(Thing thing) {
                if (Objects.equals(thing, THING_TYPE.instance)) return THING_TYPE;
                else if (Objects.equals(thing, ENTITY_TYPE.instance)) return ENTITY_TYPE;
                else if (Objects.equals(thing, ATTRIBUTE_TYPE.instance)) return ATTRIBUTE_TYPE;
                else if (Objects.equals(thing, RELATION_TYPE.instance)) return RELATION_TYPE;
                else if (Objects.equals(thing, ROLE_TYPE.instance)) return ROLE_TYPE;
                else throw TypeDBException.of(UNRECOGNISED_VALUE);
            }

            /**
             * Returns the fully scoped label for a given {@code TypeVertex}
             *
             * @param label the unscoped label of the {@code TypeVertex}
             * @param scope the scope label of the {@code TypeVertex}
             * @return the fully scoped label for a given {@code TypeVertex} as a string
             */
            public static String scopedLabel(String label, @Nullable String scope) {
                if (scope == null) return label;
                else return scope + ":" + label;
            }

            @Override
            public Prefix prefix() {
                return prefix;
            }

            public Root root() {
                return root;
            }

            public Thing instance() {
                return instance;
            }

            public enum Root {
                THING("thing"),
                ENTITY("entity"),
                ATTRIBUTE("attribute"),
                RELATION("relation"),
                ROLE("role", "relation");

                private final String label;
                private final String scope;
                private final Label properLabel;

                Root(String label) {
                    this(label, null);
                }

                Root(String label, @Nullable String scope) {
                    this.label = label;
                    this.scope = scope;
                    this.properLabel = Label.of(label, scope);
                }

                public String label() {
                    return label;
                }

                public String scope() {
                    return scope;
                }

                public Label properLabel() {
                    return properLabel;
                }
            }
        }

        enum Thing implements Vertex {
            ENTITY(Prefix.VERTEX_ENTITY),
            ATTRIBUTE(Prefix.VERTEX_ATTRIBUTE),
            RELATION(Prefix.VERTEX_RELATION),
            ROLE(Prefix.VERTEX_ROLE);

            private static final ByteMap<Thing> thingVertexByKey = ByteMap.create(
                    pair(ENTITY.prefix.key, ENTITY),
                    pair(ATTRIBUTE.prefix.key, ATTRIBUTE),
                    pair(RELATION.prefix.key, RELATION),
                    pair(ROLE.prefix.key, ROLE)
            );

            private final Prefix prefix;

            Thing(Prefix prefix) {
                this.prefix = prefix;
            }

            public static Thing of(byte prefix) {
                Thing thing = thingVertexByKey.get(prefix);
                if (thing == null) throw TypeDBException.of(UNRECOGNISED_VALUE);
                else return thing;
            }

            @Override
            public Prefix prefix() {
                return prefix;
            }
        }

        enum Value implements Vertex {
            VALUE(Prefix.VERTEX_VALUE);

            private final Prefix prefix;

            Value(Prefix prefix) {
                this.prefix = prefix;
            }

            public static Value of(byte prefix) {
                if (VALUE.prefix.key == prefix) return VALUE;
                else throw TypeDBException.of(UNRECOGNISED_VALUE);
            }

            @Override
            public Prefix prefix() {
                return prefix;
            }
        }
    }

    public interface Edge {

        static boolean isForward(byte infix) {
            return infix > 0;
        }

        Infix forward();

        Infix backward();

        String name();

        default boolean isOptimisation() {
            return false;
        }

        default boolean isType() {
            return false;
        }

        default boolean isThing() {
            return false;
        }

        default Type asType() {
            throw TypeDBException.of(ILLEGAL_CAST, className(this.getClass()), className(Type.class));
        }

        default Thing asThing() {
            throw TypeDBException.of(ILLEGAL_CAST, className(this.getClass()), className(Thing.class));
        }

        Edge ISA = new Edge() {

            @Override
            public Infix forward() {
                return null;
            }

            @Override
            public Infix backward() {
                return Infix.EDGE_ISA_BACKWARD;
            }

            @Override
            public String name() {
                return "ISA";
            }

            @Override
            public String toString() {
                return name();
            }
        };

        enum Type implements Edge {
            SUB(Infix.EDGE_SUB_FORWARD, Infix.EDGE_SUB_BACKWARD),
            OWNS(Infix.EDGE_OWNS_FORWARD, Infix.EDGE_OWNS_BACKWARD),
            OWNS_KEY(Infix.EDGE_OWNS_KEY_FORWARD, Infix.EDGE_OWNS_KEY_BACKWARD),
            PLAYS(Infix.EDGE_PLAYS_FORWARD, Infix.EDGE_PLAYS_BACKWARD),
            RELATES(Infix.EDGE_RELATES_FORWARD, Infix.EDGE_RELATES_BACKWARD);

            private final Infix forward;
            private final Infix backward;

            Type(Infix forward, Infix backward) {
                this.forward = forward;
                this.backward = backward;
            }

            public static Type of(byte infix) {
                if (infix == SUB.backward.key || infix == SUB.forward.key) return SUB;
                else if (infix == OWNS.backward.key || infix == OWNS.forward.key) return OWNS;
                else if (infix == OWNS_KEY.backward.key || infix == OWNS_KEY.forward.key) return OWNS_KEY;
                else if (infix == PLAYS.backward.key || infix == PLAYS.forward.key) return PLAYS;
                else if (infix == RELATES.backward.key || infix == RELATES.forward.key) return RELATES;
                else throw TypeDBException.of(UNRECOGNISED_VALUE);
            }

            @Override
            public Infix forward() {
                return forward;
            }

            @Override
            public Infix backward() {
                return backward;
            }

            @Override
            public boolean isType() {
                return true;
            }

            @Override
            public Type asType() {
                return this;
            }
        }

        interface Thing extends Edge {

            static Thing of(Infix infix) {
                return of(infix.key);
            }

            static Thing of(byte infix) {
                if (Base.HAS.forward.key == infix || Base.HAS.backward.key == infix) {
                    return Base.HAS;
                } else if (Base.PLAYING.forward.key == infix || Base.PLAYING.backward.key == infix) {
                    return Base.PLAYING;
                } else if (Base.RELATING.forward.key == infix || Base.RELATING.backward.key == infix) {
                    return Base.RELATING;
                } else if (Optimised.ROLEPLAYER.forward.key == infix || Optimised.ROLEPLAYER.backward.key == infix) {
                    return Optimised.ROLEPLAYER;
                } else {
                    throw TypeDBException.of(UNRECOGNISED_VALUE);
                }
            }

            String name();

            Infix backward();

            Infix forward();

            boolean isOptimisation();

            int tailSize();

            default int lookAhead() {
                return tailSize() + 2;
            }

            @Override
            default boolean isThing() {
                return true;
            }

            @Override
            default Thing asThing() {
                return this;
            }

            enum Base implements Thing { // TODO: name could be improved
                HAS(Infix.EDGE_HAS_FORWARD, Infix.EDGE_HAS_BACKWARD),
                PLAYING(Infix.EDGE_PLAYING_FORWARD, Infix.EDGE_PLAYING_BACKWARD),
                RELATING(Infix.EDGE_RELATING_FORWARD, Infix.EDGE_RELATING_BACKWARD);

                private final Infix forward;
                private final Infix backward;

                Base(Infix forward, Infix backward) {
                    this.forward = forward;
                    this.backward = backward;
                }

                @Override
                public Infix backward() {
                    return backward;
                }

                @Override
                public Infix forward() {
                    return forward;
                }

                @Override
                public boolean isOptimisation() {
                    return false;
                }

                @Override
                public int tailSize() {
                    return 0;
                }
            }

            enum Optimised implements Thing {
                ROLEPLAYER(Infix.EDGE_ROLEPLAYER_FORWARD, Infix.EDGE_ROLEPLAYER_BACKWARD, 2);

                private final Infix forward;
                private final Infix backward;
                private final int tailSize;

                Optimised(Infix forward, Infix backward, int tailSize) {
                    this.forward = forward;
                    this.backward = backward;
                    this.tailSize = tailSize;
                }

                @Override
                public Infix backward() {
                    return backward;
                }

                @Override
                public Infix forward() {
                    return forward;
                }

                @Override
                public boolean isOptimisation() {
                    return true;
                }

                @Override
                public int tailSize() {
                    return tailSize;
                }
            }
        }
    }

    public interface Index {

        enum Prefix {
            TYPE(Encoding.Prefix.INDEX_TYPE),
            RULE(Encoding.Prefix.INDEX_RULE);

            private final Encoding.Prefix prefix;

            Prefix(Encoding.Prefix prefix) {
                this.prefix = prefix;
            }

            public Encoding.Prefix prefix() {
                return prefix;
            }

            public ByteArray bytes() {
                return prefix.bytes();
            }
        }

        /**
         * The size of a prefix is 1 unsigned byte; i.e. min-value = 0 and max-value = 255.
         */
        enum Infix {
            CONTAINED_TYPE(0),
            CONCLUDED_VERTEX(10),
            CONCLUDED_EDGE_TO(11);

            public static final int LENGTH = 1;
            private final byte key;
            private final ByteArray bytes;

            Infix(int key) {
                this.key = unsignedByte(key);
                bytes = ByteArray.of(new byte[]{this.key});
            }

            public static Infix of(ByteArray key) {
                if (key.get(0) == CONTAINED_TYPE.key) return CONTAINED_TYPE;
                else if (key.get(0) == CONCLUDED_VERTEX.key) return CONCLUDED_VERTEX;
                else if (key.get(0) == CONCLUDED_EDGE_TO.key) return CONCLUDED_EDGE_TO;
                else throw TypeDBException.of(UNRECOGNISED_VALUE);
            }

            public ByteArray bytes() {
                return bytes;
            }
        }
    }

    public interface Metadata {

        interface Statistics {

            enum Prefix {

                SNAPSHOT(0),
                VERTEX_COUNT(10),
                HAS_EDGE_COUNT(20),
                MISCOUNTABLE(50),
                TXN_COMMITTED_ID(60);

                public static final int LENGTH = 2;

                private final ByteArray bytes;

                Prefix(int key) {
                    this.bytes = ByteArray.join(
                            Encoding.Prefix.METADATA_STATISTICS.bytes(),
                            ByteArray.of(new byte[]{unsignedByte(key)})
                    );
                }

                public ByteArray bytes() {
                    return this.bytes;
                }
            }

            enum Infix {
                ATTRIBUTE_OVERCOUNT(0),
                ATTRIBUTE_UNDERCOUNT(1),
                HAS_EDGE_OVERCOUNT(10),
                HAS_EDGE_UNDERCOUNT(11);

                public static final int LENGTH = 1;

                private final byte key;
                private final ByteArray bytes;

                Infix(int key) {
                    this.key = unsignedByte(key);
                    this.bytes = ByteArray.of(new byte[]{this.key});
                }

                public ByteArray bytes() {
                    return bytes;
                }

                public byte key() {
                    return key;
                }
            }
        }
    }

    public enum System {

        // WARNING: do not change encoding version key, or compatibility checks may break
        ENCODING_VERSION_KEY(0),
        TRANSACTION_DUMMY_WRITE(1);

        private final ByteArray bytes;

        System(int key) {
            byte b = unsignedByte(key);
            this.bytes = ByteArray.join(Prefix.SYSTEM.bytes(), ByteArray.of(new byte[]{b}));
        }

        public ByteArray bytes() {
            return bytes;
        }

    }

    private static class ByteMap<T> {

        private final ArrayList<T> listOrderedByByteValue;

        private ByteMap(ArrayList<T> listOrderedByByteValue) {
            this.listOrderedByByteValue = listOrderedByByteValue;
        }

        @SafeVarargs
        static <T> ByteMap<T> create(Pair<Byte, T>... byteIndices) {
            ArrayList<T> indexList = new ArrayList<>(Collections.nCopies(255, null));
            for (Pair<Byte, T> index : byteIndices) indexList.set(index.first() + 128, index.second());
            return new ByteMap<>(indexList);
        }

        T get(byte b) {
            return listOrderedByByteValue.get(b + 128);
        }
    }
}
