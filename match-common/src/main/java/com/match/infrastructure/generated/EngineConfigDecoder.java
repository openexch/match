/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;


/**
 * Engine-creation config replicated via the cluster log
 */
@SuppressWarnings("all")
public final class EngineConfigDecoder
{
    public static final int BLOCK_LENGTH = 17;
    public static final int TEMPLATE_ID = 8;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 10;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final EngineConfigDecoder parentMessage = this;
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

    public EngineConfigDecoder wrap(
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

    public EngineConfigDecoder wrapAndApplyHeader(
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

    public EngineConfigDecoder sbeRewind()
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

    public long configVersion()
    {
        return (buffer.getInt(offset + 0, BYTE_ORDER) & 0xFFFF_FFFFL);
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

    public short implRaw()
    {
        return ((short)(buffer.getByte(offset + 4) & 0xFF));
    }

    public EngineImpl impl()
    {
        return EngineImpl.get(((short)(buffer.getByte(offset + 4) & 0xFF)));
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

    public long bookCapacity()
    {
        return (buffer.getInt(offset + 5, BYTE_ORDER) & 0xFFFF_FFFFL);
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

    public long maxMatchesPerOrder()
    {
        return (buffer.getInt(offset + 9, BYTE_ORDER) & 0xFFFF_FFFFL);
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

    public long maxOrdersPerLevel()
    {
        return (buffer.getInt(offset + 13, BYTE_ORDER) & 0xFFFF_FFFFL);
    }


    private final MarketsDecoder markets = new MarketsDecoder(this);

    public static long marketsDecoderId()
    {
        return 10;
    }

    public static int marketsDecoderSinceVersion()
    {
        return 0;
    }

    public MarketsDecoder markets()
    {
        markets.wrap(buffer);
        return markets;
    }

    public static final class MarketsDecoder
        implements Iterable<MarketsDecoder>, java.util.Iterator<MarketsDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final EngineConfigDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        MarketsDecoder(final EngineConfigDecoder parentMessage)
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

        public MarketsDecoder next()
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
            return 44;
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

        public java.util.Iterator<MarketsDecoder> iterator()
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


        public byte symbol(final int index)
        {
            if (index < 0 || index >= 16)
            {
                throw new IndexOutOfBoundsException("index out of range: index=" + index);
            }

            final int pos = offset + 4 + (index * 1);

            return buffer.getByte(pos);
        }


        public static String symbolCharacterEncoding()
        {
            return java.nio.charset.StandardCharsets.US_ASCII.name();
        }

        public int getSymbol(final byte[] dst, final int dstOffset)
        {
            final int length = 16;
            if (dstOffset < 0 || dstOffset > (dst.length - length))
            {
                throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + dstOffset);
            }

            buffer.getBytes(offset + 4, dst, dstOffset, length);

            return length;
        }

        public String symbol()
        {
            final byte[] dst = new byte[16];
            buffer.getBytes(offset + 4, dst, 0, 16);

            int end = 0;
            for (; end < 16 && dst[end] != 0; ++end);

            return new String(dst, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        }


        public int getSymbol(final Appendable value)
        {
            for (int i = 0; i < 16; ++i)
            {
                final int c = buffer.getByte(offset + 4 + i) & 0xFF;
                if (c == 0)
                {
                    return i;
                }

                try
                {
                    value.append(c > 127 ? '?' : (char)c);
                }
                catch (final java.io.IOException ex)
                {
                    throw new java.io.UncheckedIOException(ex);
                }
            }

            return 16;
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

        public long minPrice()
        {
            return buffer.getLong(offset + 20, BYTE_ORDER);
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

        public long maxPrice()
        {
            return buffer.getLong(offset + 28, BYTE_ORDER);
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

        public long tickSize()
        {
            return buffer.getLong(offset + 36, BYTE_ORDER);
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("marketId=");
            builder.append(this.marketId());
            builder.append('|');
            builder.append("symbol=");
            for (int i = 0; i < symbolLength() && this.symbol(i) > 0; i++)
            {
                builder.append((char)this.symbol(i));
            }
            builder.append('|');
            builder.append("minPrice=");
            builder.append(this.minPrice());
            builder.append('|');
            builder.append("maxPrice=");
            builder.append(this.maxPrice());
            builder.append('|');
            builder.append("tickSize=");
            builder.append(this.tickSize());
            builder.append(')');

            return builder;
        }
        
        public MarketsDecoder sbeSkip()
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

        final EngineConfigDecoder decoder = new EngineConfigDecoder();
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
        builder.append("[EngineConfig](sbeTemplateId=");
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
        builder.append("configVersion=");
        builder.append(this.configVersion());
        builder.append('|');
        builder.append("impl=");
        builder.append(this.impl());
        builder.append('|');
        builder.append("bookCapacity=");
        builder.append(this.bookCapacity());
        builder.append('|');
        builder.append("maxMatchesPerOrder=");
        builder.append(this.maxMatchesPerOrder());
        builder.append('|');
        builder.append("maxOrdersPerLevel=");
        builder.append(this.maxOrdersPerLevel());
        builder.append('|');
        builder.append("markets=[");
        final int marketsOriginalOffset = markets.offset;
        final int marketsOriginalIndex = markets.index;
        final MarketsDecoder markets = this.markets();
        if (markets.count() > 0)
        {
            while (markets.hasNext())
            {
                markets.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        markets.offset = marketsOriginalOffset;
        markets.index = marketsOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public EngineConfigDecoder sbeSkip()
    {
        sbeRewind();
        MarketsDecoder markets = this.markets();
        if (markets.count() > 0)
        {
            while (markets.hasNext())
            {
                markets.next();
                markets.sbeSkip();
            }
        }

        return this;
    }
}
