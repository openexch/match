/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;


/**
 * Engine-creation config replicated via the cluster log
 */
@SuppressWarnings("all")
public final class EngineConfigEncoder
{
    public static final int BLOCK_LENGTH = 17;
    public static final int TEMPLATE_ID = 8;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 10;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final EngineConfigEncoder parentMessage = this;
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

    public EngineConfigEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public EngineConfigEncoder wrapAndApplyHeader(
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

    public static int configVersionId()
    {
        return 1;
    }

    public static int configVersionSinceVersion()
    {
        return 0;
    }

    public static int configVersionEncodingOffset()
    {
        return 0;
    }

    public static int configVersionEncodingLength()
    {
        return 4;
    }

    public static String configVersionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long configVersionNullValue()
    {
        return 4294967295L;
    }

    public static long configVersionMinValue()
    {
        return 0L;
    }

    public static long configVersionMaxValue()
    {
        return 4294967294L;
    }

    public EngineConfigEncoder configVersion(final long value)
    {
        buffer.putInt(offset + 0, (int)value, BYTE_ORDER);
        return this;
    }


    public static int implId()
    {
        return 2;
    }

    public static int implSinceVersion()
    {
        return 0;
    }

    public static int implEncodingOffset()
    {
        return 4;
    }

    public static int implEncodingLength()
    {
        return 1;
    }

    public static String implMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public EngineConfigEncoder impl(final EngineImpl value)
    {
        buffer.putByte(offset + 4, (byte)value.value());
        return this;
    }

    public static int bookCapacityId()
    {
        return 3;
    }

    public static int bookCapacitySinceVersion()
    {
        return 0;
    }

    public static int bookCapacityEncodingOffset()
    {
        return 5;
    }

    public static int bookCapacityEncodingLength()
    {
        return 4;
    }

    public static String bookCapacityMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long bookCapacityNullValue()
    {
        return 4294967295L;
    }

    public static long bookCapacityMinValue()
    {
        return 0L;
    }

    public static long bookCapacityMaxValue()
    {
        return 4294967294L;
    }

    public EngineConfigEncoder bookCapacity(final long value)
    {
        buffer.putInt(offset + 5, (int)value, BYTE_ORDER);
        return this;
    }


    public static int maxMatchesPerOrderId()
    {
        return 4;
    }

    public static int maxMatchesPerOrderSinceVersion()
    {
        return 0;
    }

    public static int maxMatchesPerOrderEncodingOffset()
    {
        return 9;
    }

    public static int maxMatchesPerOrderEncodingLength()
    {
        return 4;
    }

    public static String maxMatchesPerOrderMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long maxMatchesPerOrderNullValue()
    {
        return 4294967295L;
    }

    public static long maxMatchesPerOrderMinValue()
    {
        return 0L;
    }

    public static long maxMatchesPerOrderMaxValue()
    {
        return 4294967294L;
    }

    public EngineConfigEncoder maxMatchesPerOrder(final long value)
    {
        buffer.putInt(offset + 9, (int)value, BYTE_ORDER);
        return this;
    }


    public static int maxOrdersPerLevelId()
    {
        return 5;
    }

    public static int maxOrdersPerLevelSinceVersion()
    {
        return 0;
    }

    public static int maxOrdersPerLevelEncodingOffset()
    {
        return 13;
    }

    public static int maxOrdersPerLevelEncodingLength()
    {
        return 4;
    }

    public static String maxOrdersPerLevelMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long maxOrdersPerLevelNullValue()
    {
        return 4294967295L;
    }

    public static long maxOrdersPerLevelMinValue()
    {
        return 0L;
    }

    public static long maxOrdersPerLevelMaxValue()
    {
        return 4294967294L;
    }

    public EngineConfigEncoder maxOrdersPerLevel(final long value)
    {
        buffer.putInt(offset + 13, (int)value, BYTE_ORDER);
        return this;
    }


    private final MarketsEncoder markets = new MarketsEncoder(this);

    public static long marketsId()
    {
        return 10;
    }

    public MarketsEncoder marketsCount(final int count)
    {
        markets.wrap(buffer, count);
        return markets;
    }

    public static final class MarketsEncoder
    {
        public static final int HEADER_SIZE = 4;
        private final EngineConfigEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        MarketsEncoder(final EngineConfigEncoder parentMessage)
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
            buffer.putShort(limit + 0, (short)44, BYTE_ORDER);
            buffer.putShort(limit + 2, (short)count, BYTE_ORDER);
        }

        public MarketsEncoder next()
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
            return 44;
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

        public MarketsEncoder marketId(final int value)
        {
            buffer.putInt(offset + 0, value, BYTE_ORDER);
            return this;
        }


        public static int symbolId()
        {
            return 2;
        }

        public static int symbolSinceVersion()
        {
            return 0;
        }

        public static int symbolEncodingOffset()
        {
            return 4;
        }

        public static int symbolEncodingLength()
        {
            return 16;
        }

        public static String symbolMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static byte symbolNullValue()
        {
            return (byte)0;
        }

        public static byte symbolMinValue()
        {
            return (byte)32;
        }

        public static byte symbolMaxValue()
        {
            return (byte)126;
        }

        public static int symbolLength()
        {
            return 16;
        }


        public MarketsEncoder symbol(final int index, final byte value)
        {
            if (index < 0 || index >= 16)
            {
                throw new IndexOutOfBoundsException("index out of range: index=" + index);
            }

            final int pos = offset + 4 + (index * 1);
            buffer.putByte(pos, value);

            return this;
        }

        public static String symbolCharacterEncoding()
        {
            return java.nio.charset.StandardCharsets.US_ASCII.name();
        }

        public MarketsEncoder putSymbol(final byte[] src, final int srcOffset)
        {
            final int length = 16;
            if (srcOffset < 0 || srcOffset > (src.length - length))
            {
                throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + srcOffset);
            }

            buffer.putBytes(offset + 4, src, srcOffset, length);

            return this;
        }

        public MarketsEncoder symbol(final String src)
        {
            final int length = 16;
            final int srcLength = null == src ? 0 : src.length();
            if (srcLength > length)
            {
                throw new IndexOutOfBoundsException("String too large for copy: byte length=" + srcLength);
            }

            buffer.putStringWithoutLengthAscii(offset + 4, src);

            for (int start = srcLength; start < length; ++start)
            {
                buffer.putByte(offset + 4 + start, (byte)0);
            }

            return this;
        }

        public MarketsEncoder symbol(final CharSequence src)
        {
            final int length = 16;
            final int srcLength = null == src ? 0 : src.length();
            if (srcLength > length)
            {
                throw new IndexOutOfBoundsException("CharSequence too large for copy: byte length=" + srcLength);
            }

            buffer.putStringWithoutLengthAscii(offset + 4, src);

            for (int start = srcLength; start < length; ++start)
            {
                buffer.putByte(offset + 4 + start, (byte)0);
            }

            return this;
        }

        public static int minPriceId()
        {
            return 3;
        }

        public static int minPriceSinceVersion()
        {
            return 0;
        }

        public static int minPriceEncodingOffset()
        {
            return 20;
        }

        public static int minPriceEncodingLength()
        {
            return 8;
        }

        public static String minPriceMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long minPriceNullValue()
        {
            return -9223372036854775808L;
        }

        public static long minPriceMinValue()
        {
            return -9223372036854775807L;
        }

        public static long minPriceMaxValue()
        {
            return 9223372036854775807L;
        }

        public MarketsEncoder minPrice(final long value)
        {
            buffer.putLong(offset + 20, value, BYTE_ORDER);
            return this;
        }


        public static int maxPriceId()
        {
            return 4;
        }

        public static int maxPriceSinceVersion()
        {
            return 0;
        }

        public static int maxPriceEncodingOffset()
        {
            return 28;
        }

        public static int maxPriceEncodingLength()
        {
            return 8;
        }

        public static String maxPriceMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long maxPriceNullValue()
        {
            return -9223372036854775808L;
        }

        public static long maxPriceMinValue()
        {
            return -9223372036854775807L;
        }

        public static long maxPriceMaxValue()
        {
            return 9223372036854775807L;
        }

        public MarketsEncoder maxPrice(final long value)
        {
            buffer.putLong(offset + 28, value, BYTE_ORDER);
            return this;
        }


        public static int tickSizeId()
        {
            return 5;
        }

        public static int tickSizeSinceVersion()
        {
            return 0;
        }

        public static int tickSizeEncodingOffset()
        {
            return 36;
        }

        public static int tickSizeEncodingLength()
        {
            return 8;
        }

        public static String tickSizeMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long tickSizeNullValue()
        {
            return -9223372036854775808L;
        }

        public static long tickSizeMinValue()
        {
            return -9223372036854775807L;
        }

        public static long tickSizeMaxValue()
        {
            return 9223372036854775807L;
        }

        public MarketsEncoder tickSize(final long value)
        {
            buffer.putLong(offset + 36, value, BYTE_ORDER);
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

        final EngineConfigDecoder decoder = new EngineConfigDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
