/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.mejournal.generated;

import org.agrona.MutableDirectBuffer;


/**
 * Engine config decision
 */
@SuppressWarnings("all")
public final class EngineConfigEncoder
{
    public static final int BLOCK_LENGTH = 39;
    public static final int TEMPLATE_ID = 5;
    public static final int SCHEMA_ID = 4;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
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

    public EngineConfigEncoder journalSeq(final long value)
    {
        buffer.putLong(offset + 0, value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder meEpoch(final long value)
    {
        buffer.putLong(offset + 8, value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder egressSeq(final long value)
    {
        buffer.putLong(offset + 16, value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder configVersion(final long value)
    {
        buffer.putInt(offset + 24, (int)value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder behaviorVersion(final long value)
    {
        buffer.putInt(offset + 28, (int)value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder ledgerCapacity(final long value)
    {
        buffer.putInt(offset + 32, (int)value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder maxWireVersion(final int value)
    {
        buffer.putShort(offset + 36, (short)value, BYTE_ORDER);
        return this;
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

    public EngineConfigEncoder decision(final ConfigDecision value)
    {
        buffer.putByte(offset + 38, (byte)value.value());
        return this;
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
