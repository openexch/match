/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.MutableDirectBuffer;

@SuppressWarnings("all")
public final class DurableOrderCommandEncoder
{
    public static final int BLOCK_LENGTH = 71;
    public static final int TEMPLATE_ID = 9;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 11;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final DurableOrderCommandEncoder parentMessage = this;
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

    public DurableOrderCommandEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public DurableOrderCommandEncoder wrapAndApplyHeader(
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

    public DurableOrderCommandEncoder commandIdHigh(final long value)
    {
        buffer.putLong(offset + 0, value, BYTE_ORDER);
        return this;
    }


    public static int commandIdLowId()
    {
        return 2;
    }

    public static int commandIdLowSinceVersion()
    {
        return 0;
    }

    public static int commandIdLowEncodingOffset()
    {
        return 8;
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

    public DurableOrderCommandEncoder commandIdLow(final long value)
    {
        buffer.putLong(offset + 8, value, BYTE_ORDER);
        return this;
    }


    public static int userIdId()
    {
        return 3;
    }

    public static int userIdSinceVersion()
    {
        return 0;
    }

    public static int userIdEncodingOffset()
    {
        return 16;
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

    public DurableOrderCommandEncoder userId(final long value)
    {
        buffer.putLong(offset + 16, value, BYTE_ORDER);
        return this;
    }


    public static int omsOrderIdId()
    {
        return 4;
    }

    public static int omsOrderIdSinceVersion()
    {
        return 0;
    }

    public static int omsOrderIdEncodingOffset()
    {
        return 24;
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

    public DurableOrderCommandEncoder omsOrderId(final long value)
    {
        buffer.putLong(offset + 24, value, BYTE_ORDER);
        return this;
    }


    public static int oldOrderIdId()
    {
        return 5;
    }

    public static int oldOrderIdSinceVersion()
    {
        return 0;
    }

    public static int oldOrderIdEncodingOffset()
    {
        return 32;
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

    public DurableOrderCommandEncoder oldOrderId(final long value)
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

    public DurableOrderCommandEncoder price(final long value)
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

    public DurableOrderCommandEncoder quantity(final long value)
    {
        buffer.putLong(offset + 48, value, BYTE_ORDER);
        return this;
    }


    public static int budgetId()
    {
        return 8;
    }

    public static int budgetSinceVersion()
    {
        return 0;
    }

    public static int budgetEncodingOffset()
    {
        return 56;
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

    public DurableOrderCommandEncoder budget(final long value)
    {
        buffer.putLong(offset + 56, value, BYTE_ORDER);
        return this;
    }


    public static int marketIdId()
    {
        return 9;
    }

    public static int marketIdSinceVersion()
    {
        return 0;
    }

    public static int marketIdEncodingOffset()
    {
        return 64;
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

    public DurableOrderCommandEncoder marketId(final int value)
    {
        buffer.putInt(offset + 64, value, BYTE_ORDER);
        return this;
    }


    public static int commandKindId()
    {
        return 10;
    }

    public static int commandKindSinceVersion()
    {
        return 0;
    }

    public static int commandKindEncodingOffset()
    {
        return 68;
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

    public DurableOrderCommandEncoder commandKind(final short value)
    {
        buffer.putByte(offset + 68, (byte)value);
        return this;
    }


    public static int orderTypeId()
    {
        return 11;
    }

    public static int orderTypeSinceVersion()
    {
        return 0;
    }

    public static int orderTypeEncodingOffset()
    {
        return 69;
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

    public DurableOrderCommandEncoder orderType(final short value)
    {
        buffer.putByte(offset + 69, (byte)value);
        return this;
    }


    public static int orderSideId()
    {
        return 12;
    }

    public static int orderSideSinceVersion()
    {
        return 0;
    }

    public static int orderSideEncodingOffset()
    {
        return 70;
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

    public DurableOrderCommandEncoder orderSide(final short value)
    {
        buffer.putByte(offset + 70, (byte)value);
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

        final DurableOrderCommandDecoder decoder = new DurableOrderCommandDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
