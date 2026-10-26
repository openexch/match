/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;

@SuppressWarnings("all")
public final class ReleaseCommandIdsEncoder
{
    public static final int BLOCK_LENGTH = 8;
    public static final int TEMPLATE_ID = 10;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 12;
    public static final String SEMANTIC_VERSION = "5.3";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final ReleaseCommandIdsEncoder parentMessage = this;
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

    public ReleaseCommandIdsEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public ReleaseCommandIdsEncoder wrapAndApplyHeader(
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

    public static int releaseSeqId()
    {
        return 1;
    }

    public static int releaseSeqSinceVersion()
    {
        return 0;
    }

    public static int releaseSeqEncodingOffset()
    {
        return 0;
    }

    public static int releaseSeqEncodingLength()
    {
        return 8;
    }

    public static String releaseSeqMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long releaseSeqNullValue()
    {
        return -9223372036854775808L;
    }

    public static long releaseSeqMinValue()
    {
        return -9223372036854775807L;
    }

    public static long releaseSeqMaxValue()
    {
        return 9223372036854775807L;
    }

    public ReleaseCommandIdsEncoder releaseSeq(final long value)
    {
        buffer.putLong(offset + 0, value, BYTE_ORDER);
        return this;
    }


    private final ReleasesEncoder releases = new ReleasesEncoder(this);

    public static long releasesId()
    {
        return 10;
    }

    public ReleasesEncoder releasesCount(final int count)
    {
        releases.wrap(buffer, count);
        return releases;
    }

    public static final class ReleasesEncoder
    {
        public static final int HEADER_SIZE = 4;
        private final ReleaseCommandIdsEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        ReleasesEncoder(final ReleaseCommandIdsEncoder parentMessage)
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
            buffer.putShort(limit + 0, (short)17, BYTE_ORDER);
            buffer.putShort(limit + 2, (short)count, BYTE_ORDER);
        }

        public ReleasesEncoder next()
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
            return 17;
        }

        public static int commandIdHighId()
        {
            return 1;
        }

        public static int commandIdHighSinceVersion()
        {
            return 0;
        }

        public static int commandIdHighEncodingOffset()
        {
            return 0;
        }

        public static int commandIdHighEncodingLength()
        {
            return 8;
        }

        public static String commandIdHighMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long commandIdHighNullValue()
        {
            return -9223372036854775808L;
        }

        public static long commandIdHighMinValue()
        {
            return -9223372036854775807L;
        }

        public static long commandIdHighMaxValue()
        {
            return 9223372036854775807L;
        }

        public ReleasesEncoder commandIdHigh(final long value)
        {
            buffer.putLong(offset + 0, value, BYTE_ORDER);
            return this;
        }


        public static int idLowFloorId()
        {
            return 2;
        }

        public static int idLowFloorSinceVersion()
        {
            return 0;
        }

        public static int idLowFloorEncodingOffset()
        {
            return 8;
        }

        public static int idLowFloorEncodingLength()
        {
            return 8;
        }

        public static String idLowFloorMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long idLowFloorNullValue()
        {
            return -9223372036854775808L;
        }

        public static long idLowFloorMinValue()
        {
            return -9223372036854775807L;
        }

        public static long idLowFloorMaxValue()
        {
            return 9223372036854775807L;
        }

        public ReleasesEncoder idLowFloor(final long value)
        {
            buffer.putLong(offset + 8, value, BYTE_ORDER);
            return this;
        }


        public static int scopeId()
        {
            return 3;
        }

        public static int scopeSinceVersion()
        {
            return 0;
        }

        public static int scopeEncodingOffset()
        {
            return 16;
        }

        public static int scopeEncodingLength()
        {
            return 1;
        }

        public static String scopeMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public ReleasesEncoder scope(final ReleaseScope value)
        {
            buffer.putByte(offset + 16, (byte)value.value());
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

        final ReleaseCommandIdsDecoder decoder = new ReleaseCommandIdsDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
