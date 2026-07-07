/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;


/**
 * Open-order membership snapshot for OMS reconciliation
 */
@SuppressWarnings("all")
public final class OpenOrdersSnapshotEncoder
{
    public static final int BLOCK_LENGTH = 21;
    public static final int TEMPLATE_ID = 27;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 6;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final OpenOrdersSnapshotEncoder parentMessage = this;
    private MutableDirectBuffer buffer;
    private int offset;
    private int limit;

    public int sbeBlockLength()
    {
        return BLOCK_LENGTH;
    }

    public int sbeTemplateId()
    {
        return TEMPLATE_ID;
    }

    public int sbeSchemaId()
    {
        return SCHEMA_ID;
    }

    public int sbeSchemaVersion()
    {
        return SCHEMA_VERSION;
    }

    public String sbeSemanticType()
    {
        return "";
    }

    public MutableDirectBuffer buffer()
    {
        return buffer;
    }

    public int offset()
    {
        return offset;
    }

    public OpenOrdersSnapshotEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public OpenOrdersSnapshotEncoder wrapAndApplyHeader(
        final MutableDirectBuffer buffer, final int offset, final MessageHeaderEncoder headerEncoder)
    {
        headerEncoder
            .wrap(buffer, offset)
            .blockLength(BLOCK_LENGTH)
            .templateId(TEMPLATE_ID)
            .schemaId(SCHEMA_ID)
            .version(SCHEMA_VERSION);

        return wrap(buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH);
    }

    public int encodedLength()
    {
        return limit - offset;
    }

    public int limit()
    {
        return limit;
    }

    public void limit(final int limit)
    {
        this.limit = limit;
    }

    public static int requestIdId()
    {
        return 1;
    }

    public static int requestIdSinceVersion()
    {
        return 0;
    }

    public static int requestIdEncodingOffset()
    {
        return 0;
    }

    public static int requestIdEncodingLength()
    {
        return 8;
    }

    public static String requestIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long requestIdNullValue()
    {
        return -9223372036854775808L;
    }

    public static long requestIdMinValue()
    {
        return -9223372036854775807L;
    }

    public static long requestIdMaxValue()
    {
        return 9223372036854775807L;
    }

    public OpenOrdersSnapshotEncoder requestId(final long value)
    {
        buffer.putLong(offset + 0, value, BYTE_ORDER);
        return this;
    }


    public static int snapshotMaxOrderIdId()
    {
        return 2;
    }

    public static int snapshotMaxOrderIdSinceVersion()
    {
        return 0;
    }

    public static int snapshotMaxOrderIdEncodingOffset()
    {
        return 8;
    }

    public static int snapshotMaxOrderIdEncodingLength()
    {
        return 8;
    }

    public static String snapshotMaxOrderIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long snapshotMaxOrderIdNullValue()
    {
        return -9223372036854775808L;
    }

    public static long snapshotMaxOrderIdMinValue()
    {
        return -9223372036854775807L;
    }

    public static long snapshotMaxOrderIdMaxValue()
    {
        return 9223372036854775807L;
    }

    public OpenOrdersSnapshotEncoder snapshotMaxOrderId(final long value)
    {
        buffer.putLong(offset + 8, value, BYTE_ORDER);
        return this;
    }


    public static int chunkIndexId()
    {
        return 3;
    }

    public static int chunkIndexSinceVersion()
    {
        return 0;
    }

    public static int chunkIndexEncodingOffset()
    {
        return 16;
    }

    public static int chunkIndexEncodingLength()
    {
        return 4;
    }

    public static String chunkIndexMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int chunkIndexNullValue()
    {
        return -2147483648;
    }

    public static int chunkIndexMinValue()
    {
        return -2147483647;
    }

    public static int chunkIndexMaxValue()
    {
        return 2147483647;
    }

    public OpenOrdersSnapshotEncoder chunkIndex(final int value)
    {
        buffer.putInt(offset + 16, value, BYTE_ORDER);
        return this;
    }


    public static int isLastId()
    {
        return 4;
    }

    public static int isLastSinceVersion()
    {
        return 0;
    }

    public static int isLastEncodingOffset()
    {
        return 20;
    }

    public static int isLastEncodingLength()
    {
        return 1;
    }

    public static String isLastMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short isLastNullValue()
    {
        return (short)255;
    }

    public static short isLastMinValue()
    {
        return (short)0;
    }

    public static short isLastMaxValue()
    {
        return (short)254;
    }

    public OpenOrdersSnapshotEncoder isLast(final short value)
    {
        buffer.putByte(offset + 20, (byte)value);
        return this;
    }


    private final OrdersEncoder orders = new OrdersEncoder(this);

    public static long ordersId()
    {
        return 10;
    }

    public OrdersEncoder ordersCount(final int count)
    {
        orders.wrap(buffer, count);
        return orders;
    }

    public static final class OrdersEncoder
    {
        public static final int HEADER_SIZE = 4;
        private final OpenOrdersSnapshotEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        OrdersEncoder(final OpenOrdersSnapshotEncoder parentMessage)
        {
            this.parentMessage = parentMessage;
        }

        public void wrap(final MutableDirectBuffer buffer, final int count)
        {
            if (count < 0 || count > 65534)
            {
                throw new IllegalArgumentException("count outside allowed range: count=" + count);
            }

            if (buffer != this.buffer)
            {
                this.buffer = buffer;
            }

            index = 0;
            this.count = count;
            final int limit = parentMessage.limit();
            initialLimit = limit;
            parentMessage.limit(limit + HEADER_SIZE);
            buffer.putShort(limit + 0, (short)16, BYTE_ORDER);
            buffer.putShort(limit + 2, (short)count, BYTE_ORDER);
        }

        public OrdersEncoder next()
        {
            if (index >= count)
            {
                throw new java.util.NoSuchElementException();
            }

            offset = parentMessage.limit();
            parentMessage.limit(offset + sbeBlockLength());
            ++index;

            return this;
        }

        public int resetCountToIndex()
        {
            count = index;
            buffer.putShort(initialLimit + 2, (short)count, BYTE_ORDER);

            return count;
        }

        public static int countMinValue()
        {
            return 0;
        }

        public static int countMaxValue()
        {
            return 65534;
        }

        public static int sbeHeaderSize()
        {
            return HEADER_SIZE;
        }

        public static int sbeBlockLength()
        {
            return 16;
        }

        public static int orderIdId()
        {
            return 1;
        }

        public static int orderIdSinceVersion()
        {
            return 0;
        }

        public static int orderIdEncodingOffset()
        {
            return 0;
        }

        public static int orderIdEncodingLength()
        {
            return 8;
        }

        public static String orderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long orderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long orderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long orderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder orderId(final long value)
        {
            buffer.putLong(offset + 0, value, BYTE_ORDER);
            return this;
        }


        public static int omsOrderIdId()
        {
            return 2;
        }

        public static int omsOrderIdSinceVersion()
        {
            return 0;
        }

        public static int omsOrderIdEncodingOffset()
        {
            return 8;
        }

        public static int omsOrderIdEncodingLength()
        {
            return 8;
        }

        public static String omsOrderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long omsOrderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long omsOrderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long omsOrderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder omsOrderId(final long value)
        {
            buffer.putLong(offset + 8, value, BYTE_ORDER);
            return this;
        }

    }

    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        return appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final OpenOrdersSnapshotDecoder decoder = new OpenOrdersSnapshotDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
