/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.mejournal.generated;

import org.agrona.DirectBuffer;


/**
 * Command-id release
 */
@SuppressWarnings("all")
public final class CommandReleaseDecoder
{
    public static final int BLOCK_LENGTH = 44;
    public static final int TEMPLATE_ID = 4;
    public static final int SCHEMA_ID = 4;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final CommandReleaseDecoder parentMessage = this;
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

    public CommandReleaseDecoder wrap(
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

    public CommandReleaseDecoder wrapAndApplyHeader(
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

    public CommandReleaseDecoder sbeRewind()
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

    public static int journalSeqId()
    {
        return 1;
    }

    public static int journalSeqSinceVersion()
    {
        return 0;
    }

    public static int journalSeqEncodingOffset()
    {
        return 0;
    }

    public static int journalSeqEncodingLength()
    {
        return 8;
    }

    public static String journalSeqMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long journalSeqNullValue()
    {
        return -9223372036854775808L;
    }

    public static long journalSeqMinValue()
    {
        return -9223372036854775807L;
    }

    public static long journalSeqMaxValue()
    {
        return 9223372036854775807L;
    }

    public long journalSeq()
    {
        return buffer.getLong(offset + 0, BYTE_ORDER);
    }


    public static int meEpochId()
    {
        return 2;
    }

    public static int meEpochSinceVersion()
    {
        return 0;
    }

    public static int meEpochEncodingOffset()
    {
        return 8;
    }

    public static int meEpochEncodingLength()
    {
        return 8;
    }

    public static String meEpochMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long meEpochNullValue()
    {
        return -9223372036854775808L;
    }

    public static long meEpochMinValue()
    {
        return -9223372036854775807L;
    }

    public static long meEpochMaxValue()
    {
        return 9223372036854775807L;
    }

    public long meEpoch()
    {
        return buffer.getLong(offset + 8, BYTE_ORDER);
    }


    public static int egressSeqId()
    {
        return 3;
    }

    public static int egressSeqSinceVersion()
    {
        return 0;
    }

    public static int egressSeqEncodingOffset()
    {
        return 16;
    }

    public static int egressSeqEncodingLength()
    {
        return 8;
    }

    public static String egressSeqMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long egressSeqNullValue()
    {
        return -9223372036854775808L;
    }

    public static long egressSeqMinValue()
    {
        return -9223372036854775807L;
    }

    public static long egressSeqMaxValue()
    {
        return 9223372036854775807L;
    }

    public long egressSeq()
    {
        return buffer.getLong(offset + 16, BYTE_ORDER);
    }


    public static int releaseSeqId()
    {
        return 4;
    }

    public static int releaseSeqSinceVersion()
    {
        return 0;
    }

    public static int releaseSeqEncodingOffset()
    {
        return 24;
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

    public long releaseSeq()
    {
        return buffer.getLong(offset + 24, BYTE_ORDER);
    }


    public static int ledgerSizeId()
    {
        return 5;
    }

    public static int ledgerSizeSinceVersion()
    {
        return 0;
    }

    public static int ledgerSizeEncodingOffset()
    {
        return 32;
    }

    public static int ledgerSizeEncodingLength()
    {
        return 4;
    }

    public static String ledgerSizeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int ledgerSizeNullValue()
    {
        return -2147483648;
    }

    public static int ledgerSizeMinValue()
    {
        return -2147483647;
    }

    public static int ledgerSizeMaxValue()
    {
        return 2147483647;
    }

    public int ledgerSize()
    {
        return buffer.getInt(offset + 32, BYTE_ORDER);
    }


    public static int ledgerCapacityId()
    {
        return 6;
    }

    public static int ledgerCapacitySinceVersion()
    {
        return 0;
    }

    public static int ledgerCapacityEncodingOffset()
    {
        return 36;
    }

    public static int ledgerCapacityEncodingLength()
    {
        return 4;
    }

    public static String ledgerCapacityMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int ledgerCapacityNullValue()
    {
        return -2147483648;
    }

    public static int ledgerCapacityMinValue()
    {
        return -2147483647;
    }

    public static int ledgerCapacityMaxValue()
    {
        return 2147483647;
    }

    public int ledgerCapacity()
    {
        return buffer.getInt(offset + 36, BYTE_ORDER);
    }


    public static int tombstoneRangesId()
    {
        return 7;
    }

    public static int tombstoneRangesSinceVersion()
    {
        return 0;
    }

    public static int tombstoneRangesEncodingOffset()
    {
        return 40;
    }

    public static int tombstoneRangesEncodingLength()
    {
        return 4;
    }

    public static String tombstoneRangesMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int tombstoneRangesNullValue()
    {
        return -2147483648;
    }

    public static int tombstoneRangesMinValue()
    {
        return -2147483647;
    }

    public static int tombstoneRangesMaxValue()
    {
        return 2147483647;
    }

    public int tombstoneRanges()
    {
        return buffer.getInt(offset + 40, BYTE_ORDER);
    }


    private final ReleasesDecoder releases = new ReleasesDecoder(this);

    public static long releasesDecoderId()
    {
        return 10;
    }

    public static int releasesDecoderSinceVersion()
    {
        return 0;
    }

    public ReleasesDecoder releases()
    {
        releases.wrap(buffer);
        return releases;
    }

    public static final class ReleasesDecoder
        implements Iterable<ReleasesDecoder>, java.util.Iterator<ReleasesDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final CommandReleaseDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        ReleasesDecoder(final CommandReleaseDecoder parentMessage)
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

        public ReleasesDecoder next()
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

        public java.util.Iterator<ReleasesDecoder> iterator()
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

        public long commandIdHigh()
        {
            return buffer.getLong(offset + 0, BYTE_ORDER);
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

        public long idLowFloor()
        {
            return buffer.getLong(offset + 8, BYTE_ORDER);
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

        public short scopeRaw()
        {
            return ((short)(buffer.getByte(offset + 16) & 0xFF));
        }

        public ReleaseScope scope()
        {
            return ReleaseScope.get(((short)(buffer.getByte(offset + 16) & 0xFF)));
        }


        public static int resultId()
        {
            return 4;
        }

        public static int resultSinceVersion()
        {
            return 0;
        }

        public static int resultEncodingOffset()
        {
            return 17;
        }

        public static int resultEncodingLength()
        {
            return 1;
        }

        public static String resultMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public short resultRaw()
        {
            return ((short)(buffer.getByte(offset + 17) & 0xFF));
        }

        public ReleaseResult result()
        {
            return ReleaseResult.get(((short)(buffer.getByte(offset + 17) & 0xFF)));
        }


        public static int removedId()
        {
            return 5;
        }

        public static int removedSinceVersion()
        {
            return 0;
        }

        public static int removedEncodingOffset()
        {
            return 18;
        }

        public static int removedEncodingLength()
        {
            return 4;
        }

        public static String removedMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static int removedNullValue()
        {
            return -2147483648;
        }

        public static int removedMinValue()
        {
            return -2147483647;
        }

        public static int removedMaxValue()
        {
            return 2147483647;
        }

        public int removed()
        {
            return buffer.getInt(offset + 18, BYTE_ORDER);
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("commandIdHigh=");
            builder.append(this.commandIdHigh());
            builder.append('|');
            builder.append("idLowFloor=");
            builder.append(this.idLowFloor());
            builder.append('|');
            builder.append("scope=");
            builder.append(this.scope());
            builder.append('|');
            builder.append("result=");
            builder.append(this.result());
            builder.append('|');
            builder.append("removed=");
            builder.append(this.removed());
            builder.append(')');

            return builder;
        }
        
        public ReleasesDecoder sbeSkip()
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

        final CommandReleaseDecoder decoder = new CommandReleaseDecoder();
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
        builder.append("[CommandRelease](sbeTemplateId=");
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
        builder.append("journalSeq=");
        builder.append(this.journalSeq());
        builder.append('|');
        builder.append("meEpoch=");
        builder.append(this.meEpoch());
        builder.append('|');
        builder.append("egressSeq=");
        builder.append(this.egressSeq());
        builder.append('|');
        builder.append("releaseSeq=");
        builder.append(this.releaseSeq());
        builder.append('|');
        builder.append("ledgerSize=");
        builder.append(this.ledgerSize());
        builder.append('|');
        builder.append("ledgerCapacity=");
        builder.append(this.ledgerCapacity());
        builder.append('|');
        builder.append("tombstoneRanges=");
        builder.append(this.tombstoneRanges());
        builder.append('|');
        builder.append("releases=[");
        final int releasesOriginalOffset = releases.offset;
        final int releasesOriginalIndex = releases.index;
        final ReleasesDecoder releases = this.releases();
        if (releases.count() > 0)
        {
            while (releases.hasNext())
            {
                releases.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        releases.offset = releasesOriginalOffset;
        releases.index = releasesOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public CommandReleaseDecoder sbeSkip()
    {
        sbeRewind();
        ReleasesDecoder releases = this.releases();
        if (releases.count() > 0)
        {
            while (releases.hasNext())
            {
                releases.next();
                releases.sbeSkip();
            }
        }

        return this;
    }
}
