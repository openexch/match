/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;


/**
 * Batch of individual trade executions with full details
 */
@SuppressWarnings("all")
public final class TradeExecutionBatchEncoder
{
    public static final int BLOCK_LENGTH = 12;
    public static final int TEMPLATE_ID = 26;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final TradeExecutionBatchEncoder parentMessage = this;
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

    public TradeExecutionBatchEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public TradeExecutionBatchEncoder wrapAndApplyHeader(
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

    public TradeExecutionBatchEncoder marketId(final int value)
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

    public TradeExecutionBatchEncoder timestamp(final long value)
    {
        buffer.putLong(offset + 4, value, BYTE_ORDER);
        return this;
    }


    private final TradesEncoder trades = new TradesEncoder(this);

    public static long tradesId()
    {
        return 10;
    }

    public TradesEncoder tradesCount(final int count)
    {
        trades.wrap(buffer, count);
        return trades;
    }

    public static final class TradesEncoder
    {
        public static final int HEADER_SIZE = 4;
        private final TradeExecutionBatchEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        TradesEncoder(final TradeExecutionBatchEncoder parentMessage)
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
            buffer.putShort(limit + 0, (short)73, BYTE_ORDER);
            buffer.putShort(limit + 2, (short)count, BYTE_ORDER);
        }

        public TradesEncoder next()
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
            return 73;
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

        public TradesEncoder tradeId(final long value)
        {
            buffer.putLong(offset + 0, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder takerOrderId(final long value)
        {
            buffer.putLong(offset + 8, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder makerOrderId(final long value)
        {
            buffer.putLong(offset + 16, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder takerUserId(final long value)
        {
            buffer.putLong(offset + 24, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder makerUserId(final long value)
        {
            buffer.putLong(offset + 32, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder price(final long value)
        {
            buffer.putLong(offset + 40, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder quantity(final long value)
        {
            buffer.putLong(offset + 48, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder takerSide(final OrderSide value)
        {
            buffer.putByte(offset + 56, (byte)value.value());
            return this;
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

        public TradesEncoder takerOmsOrderId(final long value)
        {
            buffer.putLong(offset + 57, value, BYTE_ORDER);
            return this;
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

        public TradesEncoder makerOmsOrderId(final long value)
        {
            buffer.putLong(offset + 65, value, BYTE_ORDER);
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

        final TradeExecutionBatchDecoder decoder = new TradeExecutionBatchDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
