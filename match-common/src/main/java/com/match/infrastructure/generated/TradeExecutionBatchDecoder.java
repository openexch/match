/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;


/**
 * Batch of individual trade executions with full details
 */
@SuppressWarnings("all")
public final class TradeExecutionBatchDecoder
{
    public static final int BLOCK_LENGTH = 12;
    public static final int TEMPLATE_ID = 26;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final TradeExecutionBatchDecoder parentMessage = this;
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

    public TradeExecutionBatchDecoder wrap(
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

    public TradeExecutionBatchDecoder wrapAndApplyHeader(
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

    public TradeExecutionBatchDecoder sbeRewind()
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

    public int marketId()
    {
        return buffer.getInt(offset + 0, BYTE_ORDER);
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

    public long timestamp()
    {
        return buffer.getLong(offset + 4, BYTE_ORDER);
    }


    private final TradesDecoder trades = new TradesDecoder(this);

    public static long tradesDecoderId()
    {
        return 10;
    }

    public static int tradesDecoderSinceVersion()
    {
        return 0;
    }

    public TradesDecoder trades()
    {
        trades.wrap(buffer);
        return trades;
    }

    public static final class TradesDecoder
        implements Iterable<TradesDecoder>, java.util.Iterator<TradesDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final TradeExecutionBatchDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        TradesDecoder(final TradeExecutionBatchDecoder parentMessage)
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

        public TradesDecoder next()
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
            return 73;
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

        public java.util.Iterator<TradesDecoder> iterator()
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

        public static int tradeIdId()
        {
            return 1;
        }

        public static int tradeIdSinceVersion()
        {
            return 0;
        }

        public static int tradeIdEncodingOffset()
        {
            return 0;
        }

        public static int tradeIdEncodingLength()
        {
            return 8;
        }

        public static String tradeIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long tradeIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long tradeIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long tradeIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long tradeId()
        {
            return buffer.getLong(offset + 0, BYTE_ORDER);
        }


        public static int takerOrderIdId()
        {
            return 2;
        }

        public static int takerOrderIdSinceVersion()
        {
            return 0;
        }

        public static int takerOrderIdEncodingOffset()
        {
            return 8;
        }

        public static int takerOrderIdEncodingLength()
        {
            return 8;
        }

        public static String takerOrderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long takerOrderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long takerOrderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long takerOrderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long takerOrderId()
        {
            return buffer.getLong(offset + 8, BYTE_ORDER);
        }


        public static int makerOrderIdId()
        {
            return 3;
        }

        public static int makerOrderIdSinceVersion()
        {
            return 0;
        }

        public static int makerOrderIdEncodingOffset()
        {
            return 16;
        }

        public static int makerOrderIdEncodingLength()
        {
            return 8;
        }

        public static String makerOrderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long makerOrderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long makerOrderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long makerOrderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long makerOrderId()
        {
            return buffer.getLong(offset + 16, BYTE_ORDER);
        }


        public static int takerUserIdId()
        {
            return 4;
        }

        public static int takerUserIdSinceVersion()
        {
            return 0;
        }

        public static int takerUserIdEncodingOffset()
        {
            return 24;
        }

        public static int takerUserIdEncodingLength()
        {
            return 8;
        }

        public static String takerUserIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long takerUserIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long takerUserIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long takerUserIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long takerUserId()
        {
            return buffer.getLong(offset + 24, BYTE_ORDER);
        }


        public static int makerUserIdId()
        {
            return 5;
        }

        public static int makerUserIdSinceVersion()
        {
            return 0;
        }

        public static int makerUserIdEncodingOffset()
        {
            return 32;
        }

        public static int makerUserIdEncodingLength()
        {
            return 8;
        }

        public static String makerUserIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long makerUserIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long makerUserIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long makerUserIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long makerUserId()
        {
            return buffer.getLong(offset + 32, BYTE_ORDER);
        }


        public static int priceId()
        {
            return 6;
        }

        public static int priceSinceVersion()
        {
            return 0;
        }

        public static int priceEncodingOffset()
        {
            return 40;
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

        public long price()
        {
            return buffer.getLong(offset + 40, BYTE_ORDER);
        }


        public static int quantityId()
        {
            return 7;
        }

        public static int quantitySinceVersion()
        {
            return 0;
        }

        public static int quantityEncodingOffset()
        {
            return 48;
        }

        public static int quantityEncodingLength()
        {
            return 8;
        }

        public static String quantityMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long quantityNullValue()
        {
            return -9223372036854775808L;
        }

        public static long quantityMinValue()
        {
            return -9223372036854775807L;
        }

        public static long quantityMaxValue()
        {
            return 9223372036854775807L;
        }

        public long quantity()
        {
            return buffer.getLong(offset + 48, BYTE_ORDER);
        }


        public static int takerSideId()
        {
            return 8;
        }

        public static int takerSideSinceVersion()
        {
            return 0;
        }

        public static int takerSideEncodingOffset()
        {
            return 56;
        }

        public static int takerSideEncodingLength()
        {
            return 1;
        }

        public static String takerSideMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public short takerSideRaw()
        {
            return ((short)(buffer.getByte(offset + 56) & 0xFF));
        }

        public OrderSide takerSide()
        {
            return OrderSide.get(((short)(buffer.getByte(offset + 56) & 0xFF)));
        }


        public static int takerOmsOrderIdId()
        {
            return 9;
        }

        public static int takerOmsOrderIdSinceVersion()
        {
            return 0;
        }

        public static int takerOmsOrderIdEncodingOffset()
        {
            return 57;
        }

        public static int takerOmsOrderIdEncodingLength()
        {
            return 8;
        }

        public static String takerOmsOrderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long takerOmsOrderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long takerOmsOrderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long takerOmsOrderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long takerOmsOrderId()
        {
            return buffer.getLong(offset + 57, BYTE_ORDER);
        }


        public static int makerOmsOrderIdId()
        {
            return 10;
        }

        public static int makerOmsOrderIdSinceVersion()
        {
            return 0;
        }

        public static int makerOmsOrderIdEncodingOffset()
        {
            return 65;
        }

        public static int makerOmsOrderIdEncodingLength()
        {
            return 8;
        }

        public static String makerOmsOrderIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long makerOmsOrderIdNullValue()
        {
            return -9223372036854775808L;
        }

        public static long makerOmsOrderIdMinValue()
        {
            return -9223372036854775807L;
        }

        public static long makerOmsOrderIdMaxValue()
        {
            return 9223372036854775807L;
        }

        public long makerOmsOrderId()
        {
            return buffer.getLong(offset + 65, BYTE_ORDER);
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("tradeId=");
            builder.append(this.tradeId());
            builder.append('|');
            builder.append("takerOrderId=");
            builder.append(this.takerOrderId());
            builder.append('|');
            builder.append("makerOrderId=");
            builder.append(this.makerOrderId());
            builder.append('|');
            builder.append("takerUserId=");
            builder.append(this.takerUserId());
            builder.append('|');
            builder.append("makerUserId=");
            builder.append(this.makerUserId());
            builder.append('|');
            builder.append("price=");
            builder.append(this.price());
            builder.append('|');
            builder.append("quantity=");
            builder.append(this.quantity());
            builder.append('|');
            builder.append("takerSide=");
            builder.append(this.takerSide());
            builder.append('|');
            builder.append("takerOmsOrderId=");
            builder.append(this.takerOmsOrderId());
            builder.append('|');
            builder.append("makerOmsOrderId=");
            builder.append(this.makerOmsOrderId());
            builder.append(')');

            return builder;
        }
        
        public TradesDecoder sbeSkip()
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

        final TradeExecutionBatchDecoder decoder = new TradeExecutionBatchDecoder();
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
        builder.append("[TradeExecutionBatch](sbeTemplateId=");
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
        builder.append("marketId=");
        builder.append(this.marketId());
        builder.append('|');
        builder.append("timestamp=");
        builder.append(this.timestamp());
        builder.append('|');
        builder.append("trades=[");
        final int tradesOriginalOffset = trades.offset;
        final int tradesOriginalIndex = trades.index;
        final TradesDecoder trades = this.trades();
        if (trades.count() > 0)
        {
            while (trades.hasNext())
            {
                trades.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        trades.offset = tradesOriginalOffset;
        trades.index = tradesOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public TradeExecutionBatchDecoder sbeSkip()
    {
        sbeRewind();
        TradesDecoder trades = this.trades();
        if (trades.count() > 0)
        {
            while (trades.hasNext())
            {
                trades.next();
                trades.sbeSkip();
            }
        }

        return this;
    }
}
