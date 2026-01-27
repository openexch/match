/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;


/**
 * Incremental book level changes
 */
@SuppressWarnings("all")
public final class BookDeltaDecoder
{
    public static final int BLOCK_LENGTH = 28;
    public static final int TEMPLATE_ID = 25;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final BookDeltaDecoder parentMessage = this;
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

    public BookDeltaDecoder wrap(
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

    public BookDeltaDecoder wrapAndApplyHeader(
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

    public BookDeltaDecoder sbeRewind()
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


    public static int bidVersionId()
    {
        return 3;
    }

    public static int bidVersionSinceVersion()
    {
        return 0;
    }

    public static int bidVersionEncodingOffset()
    {
        return 12;
    }

    public static int bidVersionEncodingLength()
    {
        return 8;
    }

    public static String bidVersionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long bidVersionNullValue()
    {
        return -9223372036854775808L;
    }

    public static long bidVersionMinValue()
    {
        return -9223372036854775807L;
    }

    public static long bidVersionMaxValue()
    {
        return 9223372036854775807L;
    }

    public long bidVersion()
    {
        return buffer.getLong(offset + 12, BYTE_ORDER);
    }


    public static int askVersionId()
    {
        return 4;
    }

    public static int askVersionSinceVersion()
    {
        return 0;
    }

    public static int askVersionEncodingOffset()
    {
        return 20;
    }

    public static int askVersionEncodingLength()
    {
        return 8;
    }

    public static String askVersionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long askVersionNullValue()
    {
        return -9223372036854775808L;
    }

    public static long askVersionMinValue()
    {
        return -9223372036854775807L;
    }

    public static long askVersionMaxValue()
    {
        return 9223372036854775807L;
    }

    public long askVersion()
    {
        return buffer.getLong(offset + 20, BYTE_ORDER);
    }


    private final ChangesDecoder changes = new ChangesDecoder(this);

    public static long changesDecoderId()
    {
        return 10;
    }

    public static int changesDecoderSinceVersion()
    {
        return 0;
    }

    public ChangesDecoder changes()
    {
        changes.wrap(buffer);
        return changes;
    }

    public static final class ChangesDecoder
        implements Iterable<ChangesDecoder>, java.util.Iterator<ChangesDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final BookDeltaDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        ChangesDecoder(final BookDeltaDecoder parentMessage)
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

        public ChangesDecoder next()
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
            return 22;
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

        public java.util.Iterator<ChangesDecoder> iterator()
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

        public static int priceId()
        {
            return 1;
        }

        public static int priceSinceVersion()
        {
            return 0;
        }

        public static int priceEncodingOffset()
        {
            return 0;
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
            return buffer.getLong(offset + 0, BYTE_ORDER);
        }


        public static int quantityId()
        {
            return 2;
        }

        public static int quantitySinceVersion()
        {
            return 0;
        }

        public static int quantityEncodingOffset()
        {
            return 8;
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
            return buffer.getLong(offset + 8, BYTE_ORDER);
        }


        public static int orderCountId()
        {
            return 3;
        }

        public static int orderCountSinceVersion()
        {
            return 0;
        }

        public static int orderCountEncodingOffset()
        {
            return 16;
        }

        public static int orderCountEncodingLength()
        {
            return 4;
        }

        public static String orderCountMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static int orderCountNullValue()
        {
            return -2147483648;
        }

        public static int orderCountMinValue()
        {
            return -2147483647;
        }

        public static int orderCountMaxValue()
        {
            return 2147483647;
        }

        public int orderCount()
        {
            return buffer.getInt(offset + 16, BYTE_ORDER);
        }


        public static int sideId()
        {
            return 4;
        }

        public static int sideSinceVersion()
        {
            return 0;
        }

        public static int sideEncodingOffset()
        {
            return 20;
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

        public short sideRaw()
        {
            return ((short)(buffer.getByte(offset + 20) & 0xFF));
        }

        public OrderSide side()
        {
            return OrderSide.get(((short)(buffer.getByte(offset + 20) & 0xFF)));
        }


        public static int updateTypeId()
        {
            return 5;
        }

        public static int updateTypeSinceVersion()
        {
            return 0;
        }

        public static int updateTypeEncodingOffset()
        {
            return 21;
        }

        public static int updateTypeEncodingLength()
        {
            return 1;
        }

        public static String updateTypeMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public short updateTypeRaw()
        {
            return ((short)(buffer.getByte(offset + 21) & 0xFF));
        }

        public BookUpdateType updateType()
        {
            return BookUpdateType.get(((short)(buffer.getByte(offset + 21) & 0xFF)));
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("price=");
            builder.append(this.price());
            builder.append('|');
            builder.append("quantity=");
            builder.append(this.quantity());
            builder.append('|');
            builder.append("orderCount=");
            builder.append(this.orderCount());
            builder.append('|');
            builder.append("side=");
            builder.append(this.side());
            builder.append('|');
            builder.append("updateType=");
            builder.append(this.updateType());
            builder.append(')');

            return builder;
        }
        
        public ChangesDecoder sbeSkip()
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

        final BookDeltaDecoder decoder = new BookDeltaDecoder();
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
        builder.append("[BookDelta](sbeTemplateId=");
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
        builder.append("bidVersion=");
        builder.append(this.bidVersion());
        builder.append('|');
        builder.append("askVersion=");
        builder.append(this.askVersion());
        builder.append('|');
        builder.append("changes=[");
        final int changesOriginalOffset = changes.offset;
        final int changesOriginalIndex = changes.index;
        final ChangesDecoder changes = this.changes();
        if (changes.count() > 0)
        {
            while (changes.hasNext())
            {
                changes.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        changes.offset = changesOriginalOffset;
        changes.index = changesOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public BookDeltaDecoder sbeSkip()
    {
        sbeRewind();
        ChangesDecoder changes = this.changes();
        if (changes.count() > 0)
        {
            while (changes.hasNext())
            {
                changes.next();
                changes.sbeSkip();
            }
        }

        return this;
    }
}
