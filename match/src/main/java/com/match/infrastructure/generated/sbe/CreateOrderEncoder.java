/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated.sbe;

import org.agrona.MutableDirectBuffer;


/**
 * Create order event
 */
@SuppressWarnings("all")
public final class CreateOrderEncoder
{
    public static final int BLOCK_LENGTH = 46;
    public static final int TEMPLATE_ID = 1;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 0;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final CreateOrderEncoder parentMessage = this;
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

    public CreateOrderEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public CreateOrderEncoder wrapAndApplyHeader(
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

    public static int orderTypeId()
    {
        return 1;
    }

    public static int orderTypeSinceVersion()
    {
        return 0;
    }

    public static int orderTypeEncodingOffset()
    {
        return 0;
    }

    public static int orderTypeEncodingLength()
    {
        return 4;
    }

    public static String orderTypeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public CreateOrderEncoder orderType(final OrderType value)
    {
        buffer.putInt(offset + 0, value.value(), BYTE_ORDER);
        return this;
    }

    public static int orderSideId()
    {
        return 2;
    }

    public static int orderSideSinceVersion()
    {
        return 0;
    }

    public static int orderSideEncodingOffset()
    {
        return 4;
    }

    public static int orderSideEncodingLength()
    {
        return 4;
    }

    public static String orderSideMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public CreateOrderEncoder orderSide(final OrderSide value)
    {
        buffer.putInt(offset + 4, value.value(), BYTE_ORDER);
        return this;
    }

    public static int priceId()
    {
        return 3;
    }

    public static int priceSinceVersion()
    {
        return 0;
    }

    public static int priceEncodingOffset()
    {
        return 8;
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

    public static double priceNullValue()
    {
        return Double.NaN;
    }

    public static double priceMinValue()
    {
        return -1.7976931348623157E308d;
    }

    public static double priceMaxValue()
    {
        return 1.7976931348623157E308d;
    }

    public CreateOrderEncoder price(final double value)
    {
        buffer.putDouble(offset + 8, value, BYTE_ORDER);
        return this;
    }


    public static int quantityId()
    {
        return 4;
    }

    public static int quantitySinceVersion()
    {
        return 0;
    }

    public static int quantityEncodingOffset()
    {
        return 16;
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

    public static double quantityNullValue()
    {
        return Double.NaN;
    }

    public static double quantityMinValue()
    {
        return -1.7976931348623157E308d;
    }

    public static double quantityMaxValue()
    {
        return 1.7976931348623157E308d;
    }

    public CreateOrderEncoder quantity(final double value)
    {
        buffer.putDouble(offset + 16, value, BYTE_ORDER);
        return this;
    }


    public static int totalPriceId()
    {
        return 5;
    }

    public static int totalPriceSinceVersion()
    {
        return 0;
    }

    public static int totalPriceEncodingOffset()
    {
        return 24;
    }

    public static int totalPriceEncodingLength()
    {
        return 8;
    }

    public static String totalPriceMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static double totalPriceNullValue()
    {
        return Double.NaN;
    }

    public static double totalPriceMinValue()
    {
        return -1.7976931348623157E308d;
    }

    public static double totalPriceMaxValue()
    {
        return 1.7976931348623157E308d;
    }

    public CreateOrderEncoder totalPrice(final double value)
    {
        buffer.putDouble(offset + 24, value, BYTE_ORDER);
        return this;
    }


    public static int userIdId()
    {
        return 6;
    }

    public static int userIdSinceVersion()
    {
        return 0;
    }

    public static int userIdEncodingOffset()
    {
        return 32;
    }

    public static int userIdEncodingLength()
    {
        return 7;
    }

    public static String userIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte userIdNullValue()
    {
        return (byte)0;
    }

    public static byte userIdMinValue()
    {
        return (byte)32;
    }

    public static byte userIdMaxValue()
    {
        return (byte)126;
    }

    public static int userIdLength()
    {
        return 7;
    }


    public CreateOrderEncoder userId(final int index, final byte value)
    {
        if (index < 0 || index >= 7)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 32 + (index * 1);
        buffer.putByte(pos, value);

        return this;
    }

    public static String userIdCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.UTF_8.name();
    }

    public CreateOrderEncoder putUserId(final byte[] src, final int srcOffset)
    {
        final int length = 7;
        if (srcOffset < 0 || srcOffset > (src.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + srcOffset);
        }

        buffer.putBytes(offset + 32, src, srcOffset, length);

        return this;
    }

    public CreateOrderEncoder userId(final String src)
    {
        final int length = 7;
        final byte[] bytes = (null == src || src.isEmpty()) ? org.agrona.collections.ArrayUtil.EMPTY_BYTE_ARRAY : src.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > length)
        {
            throw new IndexOutOfBoundsException("String too large for copy: byte length=" + bytes.length);
        }

        buffer.putBytes(offset + 32, bytes, 0, bytes.length);

        for (int start = bytes.length; start < length; ++start)
        {
            buffer.putByte(offset + 32 + start, (byte)0);
        }

        return this;
    }

    public static int marketId()
    {
        return 7;
    }

    public static int marketSinceVersion()
    {
        return 0;
    }

    public static int marketEncodingOffset()
    {
        return 39;
    }

    public static int marketEncodingLength()
    {
        return 7;
    }

    public static String marketMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte marketNullValue()
    {
        return (byte)0;
    }

    public static byte marketMinValue()
    {
        return (byte)32;
    }

    public static byte marketMaxValue()
    {
        return (byte)126;
    }

    public static int marketLength()
    {
        return 7;
    }


    public CreateOrderEncoder market(final int index, final byte value)
    {
        if (index < 0 || index >= 7)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 39 + (index * 1);
        buffer.putByte(pos, value);

        return this;
    }

    public static String marketCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.UTF_8.name();
    }

    public CreateOrderEncoder putMarket(final byte[] src, final int srcOffset)
    {
        final int length = 7;
        if (srcOffset < 0 || srcOffset > (src.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + srcOffset);
        }

        buffer.putBytes(offset + 39, src, srcOffset, length);

        return this;
    }

    public CreateOrderEncoder market(final String src)
    {
        final int length = 7;
        final byte[] bytes = (null == src || src.isEmpty()) ? org.agrona.collections.ArrayUtil.EMPTY_BYTE_ARRAY : src.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > length)
        {
            throw new IndexOutOfBoundsException("String too large for copy: byte length=" + bytes.length);
        }

        buffer.putBytes(offset + 39, bytes, 0, bytes.length);

        for (int start = bytes.length; start < length; ++start)
        {
            buffer.putByte(offset + 39 + start, (byte)0);
        }

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

        final CreateOrderDecoder decoder = new CreateOrderDecoder();
        decoder.wrap(buffer, offset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
