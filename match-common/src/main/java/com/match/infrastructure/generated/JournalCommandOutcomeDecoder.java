/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;

@SuppressWarnings("all")
public final class JournalCommandOutcomeDecoder
{
    public static final int BLOCK_LENGTH = 116;
    public static final int TEMPLATE_ID = 28;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 11;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final JournalCommandOutcomeDecoder parentMessage = this;
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

    public JournalCommandOutcomeDecoder wrap(
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

    public JournalCommandOutcomeDecoder wrapAndApplyHeader(
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

    public JournalCommandOutcomeDecoder sbeRewind()
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

    public static int egressSeqId()
    {
        return 1;
    }

    public static int egressSeqSinceVersion()
    {
        return 0;
    }

    public static int egressSeqEncodingOffset()
    {
        return 0;
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
        return buffer.getLong(offset + 0, BYTE_ORDER);
    }


    public static int commandIdHighId()
    {
        return 2;
    }

    public static int commandIdHighSinceVersion()
    {
        return 0;
    }

    public static int commandIdHighEncodingOffset()
    {
        return 8;
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
        return buffer.getLong(offset + 8, BYTE_ORDER);
    }


    public static int commandIdLowId()
    {
        return 3;
    }

    public static int commandIdLowSinceVersion()
    {
        return 0;
    }

    public static int commandIdLowEncodingOffset()
    {
        return 16;
    }

    public static int commandIdLowEncodingLength()
    {
        return 8;
    }

    public static String commandIdLowMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long commandIdLowNullValue()
    {
        return -9223372036854775808L;
    }

    public static long commandIdLowMinValue()
    {
        return -9223372036854775807L;
    }

    public static long commandIdLowMaxValue()
    {
        return 9223372036854775807L;
    }

    public long commandIdLow()
    {
        return buffer.getLong(offset + 16, BYTE_ORDER);
    }


    public static int userIdId()
    {
        return 4;
    }

    public static int userIdSinceVersion()
    {
        return 0;
    }

    public static int userIdEncodingOffset()
    {
        return 24;
    }

    public static int userIdEncodingLength()
    {
        return 8;
    }

    public static String userIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long userIdNullValue()
    {
        return -9223372036854775808L;
    }

    public static long userIdMinValue()
    {
        return -9223372036854775807L;
    }

    public static long userIdMaxValue()
    {
        return 9223372036854775807L;
    }

    public long userId()
    {
        return buffer.getLong(offset + 24, BYTE_ORDER);
    }


    public static int omsOrderIdId()
    {
        return 5;
    }

    public static int omsOrderIdSinceVersion()
    {
        return 0;
    }

    public static int omsOrderIdEncodingOffset()
    {
        return 32;
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
        return buffer.getLong(offset + 32, BYTE_ORDER);
    }


    public static int oldOrderIdId()
    {
        return 6;
    }

    public static int oldOrderIdSinceVersion()
    {
        return 0;
    }

    public static int oldOrderIdEncodingOffset()
    {
        return 40;
    }

    public static int oldOrderIdEncodingLength()
    {
        return 8;
    }

    public static String oldOrderIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long oldOrderIdNullValue()
    {
        return -9223372036854775808L;
    }

    public static long oldOrderIdMinValue()
    {
        return -9223372036854775807L;
    }

    public static long oldOrderIdMaxValue()
    {
        return 9223372036854775807L;
    }

    public long oldOrderId()
    {
        return buffer.getLong(offset + 40, BYTE_ORDER);
    }


    public static int priceId()
    {
        return 7;
    }

    public static int priceSinceVersion()
    {
        return 0;
    }

    public static int priceEncodingOffset()
    {
        return 48;
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
        return buffer.getLong(offset + 48, BYTE_ORDER);
    }


    public static int quantityId()
    {
        return 8;
    }

    public static int quantitySinceVersion()
    {
        return 0;
    }

    public static int quantityEncodingOffset()
    {
        return 56;
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
        return buffer.getLong(offset + 56, BYTE_ORDER);
    }


    public static int budgetId()
    {
        return 9;
    }

    public static int budgetSinceVersion()
    {
        return 0;
    }

    public static int budgetEncodingOffset()
    {
        return 64;
    }

    public static int budgetEncodingLength()
    {
        return 8;
    }

    public static String budgetMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long budgetNullValue()
    {
        return -9223372036854775808L;
    }

    public static long budgetMinValue()
    {
        return -9223372036854775807L;
    }

    public static long budgetMaxValue()
    {
        return 9223372036854775807L;
    }

    public long budget()
    {
        return buffer.getLong(offset + 64, BYTE_ORDER);
    }


    public static int marketIdId()
    {
        return 10;
    }

    public static int marketIdSinceVersion()
    {
        return 0;
    }

    public static int marketIdEncodingOffset()
    {
        return 72;
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
        return buffer.getInt(offset + 72, BYTE_ORDER);
    }


    public static int commandKindId()
    {
        return 11;
    }

    public static int commandKindSinceVersion()
    {
        return 0;
    }

    public static int commandKindEncodingOffset()
    {
        return 76;
    }

    public static int commandKindEncodingLength()
    {
        return 1;
    }

    public static String commandKindMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short commandKindNullValue()
    {
        return (short)255;
    }

    public static short commandKindMinValue()
    {
        return (short)0;
    }

    public static short commandKindMaxValue()
    {
        return (short)254;
    }

    public short commandKind()
    {
        return ((short)(buffer.getByte(offset + 76) & 0xFF));
    }


    public static int orderTypeId()
    {
        return 12;
    }

    public static int orderTypeSinceVersion()
    {
        return 0;
    }

    public static int orderTypeEncodingOffset()
    {
        return 77;
    }

    public static int orderTypeEncodingLength()
    {
        return 1;
    }

    public static String orderTypeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short orderTypeNullValue()
    {
        return (short)255;
    }

    public static short orderTypeMinValue()
    {
        return (short)0;
    }

    public static short orderTypeMaxValue()
    {
        return (short)254;
    }

    public short orderType()
    {
        return ((short)(buffer.getByte(offset + 77) & 0xFF));
    }


    public static int orderSideId()
    {
        return 13;
    }

    public static int orderSideSinceVersion()
    {
        return 0;
    }

    public static int orderSideEncodingOffset()
    {
        return 78;
    }

    public static int orderSideEncodingLength()
    {
        return 1;
    }

    public static String orderSideMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short orderSideNullValue()
    {
        return (short)255;
    }

    public static short orderSideMinValue()
    {
        return (short)0;
    }

    public static short orderSideMaxValue()
    {
        return (short)254;
    }

    public short orderSide()
    {
        return ((short)(buffer.getByte(offset + 78) & 0xFF));
    }


    public static int appliedPositionId()
    {
        return 14;
    }

    public static int appliedPositionSinceVersion()
    {
        return 0;
    }

    public static int appliedPositionEncodingOffset()
    {
        return 79;
    }

    public static int appliedPositionEncodingLength()
    {
        return 8;
    }

    public static String appliedPositionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long appliedPositionNullValue()
    {
        return -9223372036854775808L;
    }

    public static long appliedPositionMinValue()
    {
        return -9223372036854775807L;
    }

    public static long appliedPositionMaxValue()
    {
        return 9223372036854775807L;
    }

    public long appliedPosition()
    {
        return buffer.getLong(offset + 79, BYTE_ORDER);
    }


    public static int timestampId()
    {
        return 15;
    }

    public static int timestampSinceVersion()
    {
        return 0;
    }

    public static int timestampEncodingOffset()
    {
        return 87;
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
        return buffer.getLong(offset + 87, BYTE_ORDER);
    }


    public static int orderIdId()
    {
        return 16;
    }

    public static int orderIdSinceVersion()
    {
        return 0;
    }

    public static int orderIdEncodingOffset()
    {
        return 95;
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
        return buffer.getLong(offset + 95, BYTE_ORDER);
    }


    public static int statusId()
    {
        return 17;
    }

    public static int statusSinceVersion()
    {
        return 0;
    }

    public static int statusEncodingOffset()
    {
        return 103;
    }

    public static int statusEncodingLength()
    {
        return 4;
    }

    public static String statusMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int statusNullValue()
    {
        return -2147483648;
    }

    public static int statusMinValue()
    {
        return -2147483647;
    }

    public static int statusMaxValue()
    {
        return 2147483647;
    }

    public int status()
    {
        return buffer.getInt(offset + 103, BYTE_ORDER);
    }


    public static int reasonId()
    {
        return 18;
    }

    public static int reasonSinceVersion()
    {
        return 0;
    }

    public static int reasonEncodingOffset()
    {
        return 107;
    }

    public static int reasonEncodingLength()
    {
        return 4;
    }

    public static String reasonMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int reasonNullValue()
    {
        return -2147483648;
    }

    public static int reasonMinValue()
    {
        return -2147483647;
    }

    public static int reasonMaxValue()
    {
        return 2147483647;
    }

    public int reason()
    {
        return buffer.getInt(offset + 107, BYTE_ORDER);
    }


    public static int oldCancelledId()
    {
        return 19;
    }

    public static int oldCancelledSinceVersion()
    {
        return 0;
    }

    public static int oldCancelledEncodingOffset()
    {
        return 111;
    }

    public static int oldCancelledEncodingLength()
    {
        return 1;
    }

    public static String oldCancelledMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short oldCancelledNullValue()
    {
        return (short)255;
    }

    public static short oldCancelledMinValue()
    {
        return (short)0;
    }

    public static short oldCancelledMaxValue()
    {
        return (short)254;
    }

    public short oldCancelled()
    {
        return ((short)(buffer.getByte(offset + 111) & 0xFF));
    }


    public static int resultId()
    {
        return 20;
    }

    public static int resultSinceVersion()
    {
        return 0;
    }

    public static int resultEncodingOffset()
    {
        return 112;
    }

    public static int resultEncodingLength()
    {
        return 4;
    }

    public static String resultMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int resultNullValue()
    {
        return -2147483648;
    }

    public static int resultMinValue()
    {
        return -2147483647;
    }

    public static int resultMaxValue()
    {
        return 2147483647;
    }

    public int result()
    {
        return buffer.getInt(offset + 112, BYTE_ORDER);
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final JournalCommandOutcomeDecoder decoder = new JournalCommandOutcomeDecoder();
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
        builder.append("[JournalCommandOutcome](sbeTemplateId=");
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
        builder.append("egressSeq=");
        builder.append(this.egressSeq());
        builder.append('|');
        builder.append("commandIdHigh=");
        builder.append(this.commandIdHigh());
        builder.append('|');
        builder.append("commandIdLow=");
        builder.append(this.commandIdLow());
        builder.append('|');
        builder.append("userId=");
        builder.append(this.userId());
        builder.append('|');
        builder.append("omsOrderId=");
        builder.append(this.omsOrderId());
        builder.append('|');
        builder.append("oldOrderId=");
        builder.append(this.oldOrderId());
        builder.append('|');
        builder.append("price=");
        builder.append(this.price());
        builder.append('|');
        builder.append("quantity=");
        builder.append(this.quantity());
        builder.append('|');
        builder.append("budget=");
        builder.append(this.budget());
        builder.append('|');
        builder.append("marketId=");
        builder.append(this.marketId());
        builder.append('|');
        builder.append("commandKind=");
        builder.append(this.commandKind());
        builder.append('|');
        builder.append("orderType=");
        builder.append(this.orderType());
        builder.append('|');
        builder.append("orderSide=");
        builder.append(this.orderSide());
        builder.append('|');
        builder.append("appliedPosition=");
        builder.append(this.appliedPosition());
        builder.append('|');
        builder.append("timestamp=");
        builder.append(this.timestamp());
        builder.append('|');
        builder.append("orderId=");
        builder.append(this.orderId());
        builder.append('|');
        builder.append("status=");
        builder.append(this.status());
        builder.append('|');
        builder.append("reason=");
        builder.append(this.reason());
        builder.append('|');
        builder.append("oldCancelled=");
        builder.append(this.oldCancelled());
        builder.append('|');
        builder.append("result=");
        builder.append(this.result());

        limit(originalLimit);

        return builder;
    }
    
    public JournalCommandOutcomeDecoder sbeSkip()
    {
        sbeRewind();

        return this;
    }
}
