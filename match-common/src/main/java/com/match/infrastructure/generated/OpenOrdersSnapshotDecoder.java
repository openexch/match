/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;


/**
 * Open-order membership snapshot for OMS reconciliation
 */
@SuppressWarnings("all")
public final class OpenOrdersSnapshotDecoder
{
    public static final int BLOCK_LENGTH = 21;
    public static final int TEMPLATE_ID = 27;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 7;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final OpenOrdersSnapshotDecoder parentMessage = this;
    private DirectBuffer buffer;
    private int offset;
    private int limit;
    int actingBlockLength;
    int actingVersion;

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

    public DirectBuffer buffer()
    {
        return buffer;
    }

    public int offset()
    {
        return offset;
    }

    public OpenOrdersSnapshotDecoder wrap(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        this.actingBlockLength = actingBlockLength;
        this.actingVersion = actingVersion;
        limit(offset + actingBlockLength);

        return this;
    }

    public OpenOrdersSnapshotDecoder wrapAndApplyHeader(
        final DirectBuffer buffer,
        final int offset,
        final MessageHeaderDecoder headerDecoder)
    {
        headerDecoder.wrap(buffer, offset);

        final int templateId = headerDecoder.templateId();
        if (TEMPLATE_ID != templateId)
        {
            throw new IllegalStateException("Invalid TEMPLATE_ID: " + templateId);
        }

        return wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(),
            headerDecoder.version());
    }

    public OpenOrdersSnapshotDecoder sbeRewind()
    {
        return wrap(buffer, offset, actingBlockLength, actingVersion);
    }

    public int sbeDecodedLength()
    {
        final int currentLimit = limit();
        sbeSkip();
        final int decodedLength = encodedLength();
        limit(currentLimit);

        return decodedLength;
    }

    public int actingVersion()
    {
        return actingVersion;
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

    public long requestId()
    {
        return buffer.getLong(offset + 0, BYTE_ORDER);
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

    public long snapshotMaxOrderId()
    {
        return buffer.getLong(offset + 8, BYTE_ORDER);
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

    public int chunkIndex()
    {
        return buffer.getInt(offset + 16, BYTE_ORDER);
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

    public short isLast()
    {
        return ((short)(buffer.getByte(offset + 20) & 0xFF));
    }


    private final OrdersDecoder orders = new OrdersDecoder(this);

    public static long ordersDecoderId()
    {
        return 10;
    }

    public static int ordersDecoderSinceVersion()
    {
        return 0;
    }

    public OrdersDecoder orders()
    {
        orders.wrap(buffer);
        return orders;
    }

    public static final class OrdersDecoder
        implements Iterable<OrdersDecoder>, java.util.Iterator<OrdersDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final OpenOrdersSnapshotDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        OrdersDecoder(final OpenOrdersSnapshotDecoder parentMessage)
        {
            this.parentMessage = parentMessage;
        }

        public void wrap(final DirectBuffer buffer)
        {
            if (buffer != this.buffer)
            {
                this.buffer = buffer;
            }

            index = 0;
            final int limit = parentMessage.limit();
            parentMessage.limit(limit + HEADER_SIZE);
            blockLength = (buffer.getShort(limit + 0, BYTE_ORDER) & 0xFFFF);
            count = (buffer.getShort(limit + 2, BYTE_ORDER) & 0xFFFF);
        }

        public OrdersDecoder next()
        {
            if (index >= count)
            {
                throw new java.util.NoSuchElementException();
            }

            offset = parentMessage.limit();
            parentMessage.limit(offset + blockLength);
            ++index;

            return this;
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

        public int actingBlockLength()
        {
            return blockLength;
        }

        public int actingVersion()
        {
            return parentMessage.actingVersion;
        }

        public int count()
        {
            return count;
        }

        public java.util.Iterator<OrdersDecoder> iterator()
        {
            return this;
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        public boolean hasNext()
        {
            return index < count;
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

        public long orderId()
        {
            return buffer.getLong(offset + 0, BYTE_ORDER);
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

        public long omsOrderId()
        {
            return buffer.getLong(offset + 8, BYTE_ORDER);
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("orderId=");
            builder.append(this.orderId());
            builder.append('|');
            builder.append("omsOrderId=");
            builder.append(this.omsOrderId());
            builder.append(')');

            return builder;
        }
        
        public OrdersDecoder sbeSkip()
        {

            return this;
        }
    }

    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final OpenOrdersSnapshotDecoder decoder = new OpenOrdersSnapshotDecoder();
        decoder.wrap(buffer, offset, actingBlockLength, actingVersion);

        return decoder.appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final int originalLimit = limit();
        limit(offset + actingBlockLength);
        builder.append("[OpenOrdersSnapshot](sbeTemplateId=");
        builder.append(TEMPLATE_ID);
        builder.append("|sbeSchemaId=");
        builder.append(SCHEMA_ID);
        builder.append("|sbeSchemaVersion=");
        if (parentMessage.actingVersion != SCHEMA_VERSION)
        {
            builder.append(parentMessage.actingVersion);
            builder.append('/');
        }
        builder.append(SCHEMA_VERSION);
        builder.append("|sbeBlockLength=");
        if (actingBlockLength != BLOCK_LENGTH)
        {
            builder.append(actingBlockLength);
            builder.append('/');
        }
        builder.append(BLOCK_LENGTH);
        builder.append("):");
        builder.append("requestId=");
        builder.append(this.requestId());
        builder.append('|');
        builder.append("snapshotMaxOrderId=");
        builder.append(this.snapshotMaxOrderId());
        builder.append('|');
        builder.append("chunkIndex=");
        builder.append(this.chunkIndex());
        builder.append('|');
        builder.append("isLast=");
        builder.append(this.isLast());
        builder.append('|');
        builder.append("orders=[");
        final int ordersOriginalOffset = orders.offset;
        final int ordersOriginalIndex = orders.index;
        final OrdersDecoder orders = this.orders();
        if (orders.count() > 0)
        {
            while (orders.hasNext())
            {
                orders.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        orders.offset = ordersOriginalOffset;
        orders.index = ordersOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public OpenOrdersSnapshotDecoder sbeSkip()
    {
        sbeRewind();
        OrdersDecoder orders = this.orders();
        if (orders.count() > 0)
        {
            while (orders.hasNext())
            {
                orders.next();
                orders.sbeSkip();
            }
        }

        return this;
    }
}
