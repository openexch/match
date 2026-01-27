/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;


/**
 * Batch of order status updates
 */
@SuppressWarnings("all")
public final class OrderStatusBatchEncoder
{
    public static final int BLOCK_LENGTH = 12;
    public static final int TEMPLATE_ID = 24;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final OrderStatusBatchEncoder parentMessage = this;
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

    public OrderStatusBatchEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public OrderStatusBatchEncoder wrapAndApplyHeader(
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

    public static int marketIdId()
    {
        return 1;
    }

    public static int marketIdSinceVersion()
    {
        return 0;
    }

    public static int marketIdEncodingOffset()
    {
        return 0;
    }

    public static int marketIdEncodingLength()
    {
        return 4;
    }

    public static String marketIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int marketIdNullValue()
    {
        return -2147483648;
    }

    public static int marketIdMinValue()
    {
        return -2147483647;
    }

    public static int marketIdMaxValue()
    {
        return 2147483647;
    }

    public OrderStatusBatchEncoder marketId(final int value)
    {
        buffer.putInt(offset + 0, value, BYTE_ORDER);
        return this;
    }


    public static int timestampId()
    {
        return 2;
    }

    public static int timestampSinceVersion()
    {
        return 0;
    }

    public static int timestampEncodingOffset()
    {
        return 4;
    }

    public static int timestampEncodingLength()
    {
        return 8;
    }

    public static String timestampMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long timestampNullValue()
    {
        return -9223372036854775808L;
    }

    public static long timestampMinValue()
    {
        return -9223372036854775807L;
    }

    public static long timestampMaxValue()
    {
        return 9223372036854775807L;
    }

    public OrderStatusBatchEncoder timestamp(final long value)
    {
        buffer.putLong(offset + 4, value, BYTE_ORDER);
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
        private final OrderStatusBatchEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        OrdersEncoder(final OrderStatusBatchEncoder parentMessage)
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
            buffer.putShort(limit + 0, (short)50, BYTE_ORDER);
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
            return 50;
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


        public static int userIdId()
        {
            return 2;
        }

        public static int userIdSinceVersion()
        {
            return 0;
        }

        public static int userIdEncodingOffset()
        {
            return 8;
        }

        public static int userIdEncodingLength()
        {
            return 8;
        }

        public static String userIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long userIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long userIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long userIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder userId(final long value)
        {
            buffer.putLong(offset + 8, value, BYTE_ORDER);
            return this;
        }


        public static int statusId()
        {
            return 3;
        }

        public static int statusSinceVersion()
        {
            return 0;
        }

        public static int statusEncodingOffset()
        {
            return 16;
        }

        public static int statusEncodingLength()
        {
            return 1;
        }

        public static String statusMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public OrdersEncoder status(final OrderStatus value)
        {
            buffer.putByte(offset + 16, (byte)value.value());
            return this;
        }

        public static int priceId()
        {
            return 4;
        }

        public static int priceSinceVersion()
        {
            return 0;
        }

        public static int priceEncodingOffset()
        {
            return 17;
        }

        public static int priceEncodingLength()
        {
            return 8;
        }

        public static String priceMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long priceNullValue()
        {
            return -9223372036854775808L;
        }

        public static long priceMinValue()
        {
            return -9223372036854775807L;
        }

        public static long priceMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder price(final long value)
        {
            buffer.putLong(offset + 17, value, BYTE_ORDER);
            return this;
        }


        public static int remainingQtyId()
        {
            return 5;
        }

        public static int remainingQtySinceVersion()
        {
            return 0;
        }

        public static int remainingQtyEncodingOffset()
        {
            return 25;
        }

        public static int remainingQtyEncodingLength()
        {
            return 8;
        }

        public static String remainingQtyMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long remainingQtyNullValue()
        {
            return -9223372036854775808L;
        }

        public static long remainingQtyMinValue()
        {
            return -9223372036854775807L;
        }

        public static long remainingQtyMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder remainingQty(final long value)
        {
            buffer.putLong(offset + 25, value, BYTE_ORDER);
            return this;
        }


        public static int filledQtyId()
        {
            return 6;
        }

        public static int filledQtySinceVersion()
        {
            return 0;
        }

        public static int filledQtyEncodingOffset()
        {
            return 33;
        }

        public static int filledQtyEncodingLength()
        {
            return 8;
        }

        public static String filledQtyMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long filledQtyNullValue()
        {
            return -9223372036854775808L;
        }

        public static long filledQtyMinValue()
        {
            return -9223372036854775807L;
        }

        public static long filledQtyMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder filledQty(final long value)
        {
            buffer.putLong(offset + 33, value, BYTE_ORDER);
            return this;
        }


        public static int sideId()
        {
            return 7;
        }

        public static int sideSinceVersion()
        {
            return 0;
        }

        public static int sideEncodingOffset()
        {
            return 41;
        }

        public static int sideEncodingLength()
        {
            return 1;
        }

        public static String sideMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public OrdersEncoder side(final OrderSide value)
        {
            buffer.putByte(offset + 41, (byte)value.value());
            return this;
        }

        public static int timestampId()
        {
            return 8;
        }

        public static int timestampSinceVersion()
        {
            return 0;
        }

        public static int timestampEncodingOffset()
        {
            return 42;
        }

        public static int timestampEncodingLength()
        {
            return 8;
        }

        public static String timestampMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long timestampNullValue()
        {
            return -9223372036854775808L;
        }

        public static long timestampMinValue()
        {
            return -9223372036854775807L;
        }

        public static long timestampMaxValue()
        {
            return 9223372036854775807L;
        }

        public OrdersEncoder timestamp(final long value)
        {
            buffer.putLong(offset + 42, value, BYTE_ORDER);
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

        final OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
