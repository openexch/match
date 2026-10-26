/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.mejournal.generated;

import org.agrona.DirectBuffer;


/**
 * Engine config decision
 */
@SuppressWarnings("all")
public final class EngineConfigDecoder
{
    public static final int BLOCK_LENGTH = 39;
    public static final int TEMPLATE_ID = 5;
    public static final int SCHEMA_ID = 4;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
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


    public static int configVersionId()
    {
        return 4;
    }

    public static int configVersionSinceVersion()
    {
        return 0;
    }

    public static int configVersionEncodingOffset()
    {
        return 24;
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
        return (buffer.getInt(offset + 24, BYTE_ORDER) & 0xFFFF_FFFFL);
    }


    public static int behaviorVersionId()
    {
        return 5;
    }

    public static int behaviorVersionSinceVersion()
    {
        return 0;
    }

    public static int behaviorVersionEncodingOffset()
    {
        return 28;
    }

    public static int behaviorVersionEncodingLength()
    {
        return 4;
    }

    public static String behaviorVersionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long behaviorVersionNullValue()
    {
        return 4294967295L;
    }

    public static long behaviorVersionMinValue()
    {
        return 0L;
    }

    public static long behaviorVersionMaxValue()
    {
        return 4294967294L;
    }

    public long behaviorVersion()
    {
        return (buffer.getInt(offset + 28, BYTE_ORDER) & 0xFFFF_FFFFL);
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
        return 32;
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

    public static long ledgerCapacityNullValue()
    {
        return 4294967295L;
    }

    public static long ledgerCapacityMinValue()
    {
        return 0L;
    }

    public static long ledgerCapacityMaxValue()
    {
        return 4294967294L;
    }

    public long ledgerCapacity()
    {
        return (buffer.getInt(offset + 32, BYTE_ORDER) & 0xFFFF_FFFFL);
    }


    public static int maxWireVersionId()
    {
        return 7;
    }

    public static int maxWireVersionSinceVersion()
    {
        return 0;
    }

    public static int maxWireVersionEncodingOffset()
    {
        return 36;
    }

    public static int maxWireVersionEncodingLength()
    {
        return 2;
    }

    public static String maxWireVersionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int maxWireVersionNullValue()
    {
        return 65535;
    }

    public static int maxWireVersionMinValue()
    {
        return 0;
    }

    public static int maxWireVersionMaxValue()
    {
        return 65534;
    }

    public int maxWireVersion()
    {
        return (buffer.getShort(offset + 36, BYTE_ORDER) & 0xFFFF);
    }


    public static int decisionId()
    {
        return 8;
    }

    public static int decisionSinceVersion()
    {
        return 0;
    }

    public static int decisionEncodingOffset()
    {
        return 38;
    }

    public static int decisionEncodingLength()
    {
        return 1;
    }

    public static String decisionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public short decisionRaw()
    {
        return ((short)(buffer.getByte(offset + 38) & 0xFF));
    }

    public ConfigDecision decision()
    {
        return ConfigDecision.get(((short)(buffer.getByte(offset + 38) & 0xFF)));
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
        builder.append("journalSeq=");
        builder.append(this.journalSeq());
        builder.append('|');
        builder.append("meEpoch=");
        builder.append(this.meEpoch());
        builder.append('|');
        builder.append("egressSeq=");
        builder.append(this.egressSeq());
        builder.append('|');
        builder.append("configVersion=");
        builder.append(this.configVersion());
        builder.append('|');
        builder.append("behaviorVersion=");
        builder.append(this.behaviorVersion());
        builder.append('|');
        builder.append("ledgerCapacity=");
        builder.append(this.ledgerCapacity());
        builder.append('|');
        builder.append("maxWireVersion=");
        builder.append(this.maxWireVersion());
        builder.append('|');
        builder.append("decision=");
        builder.append(this.decision());

        limit(originalLimit);

        return builder;
    }
    
    public EngineConfigDecoder sbeSkip()
    {
        sbeRewind();

        return this;
    }
}
