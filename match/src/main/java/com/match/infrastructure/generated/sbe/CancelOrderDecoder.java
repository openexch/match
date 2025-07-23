/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated.sbe;

import org.agrona.DirectBuffer;


/**
 * Cancel order event
 */
@SuppressWarnings("all")
public final class CancelOrderDecoder
{
    public static final int BLOCK_LENGTH = 21;
    public static final int TEMPLATE_ID = 2;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 0;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final CancelOrderDecoder parentMessage = this;
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

    public CancelOrderDecoder wrap(
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

    public CancelOrderDecoder wrapAndApplyHeader(
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

    public CancelOrderDecoder sbeRewind()
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

    public static int userIdId()
    {
        return 1;
    }

    public static int userIdSinceVersion()
    {
        return 0;
    }

    public static int userIdEncodingOffset()
    {
        return 0;
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


    public byte userId(final int index)
    {
        if (index < 0 || index >= 7)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 0 + (index * 1);

        return buffer.getByte(pos);
    }


    public static String userIdCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.UTF_8.name();
    }

    public int getUserId(final byte[] dst, final int dstOffset)
    {
        final int length = 7;
        if (dstOffset < 0 || dstOffset > (dst.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + dstOffset);
        }

        buffer.getBytes(offset + 0, dst, dstOffset, length);

        return length;
    }

    public String userId()
    {
        final byte[] dst = new byte[7];
        buffer.getBytes(offset + 0, dst, 0, 7);

        int end = 0;
        for (; end < 7 && dst[end] != 0; ++end);

        return new String(dst, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }


    public static int orderIdId()
    {
        return 2;
    }

    public static int orderIdSinceVersion()
    {
        return 0;
    }

    public static int orderIdEncodingOffset()
    {
        return 7;
    }

    public static int orderIdEncodingLength()
    {
        return 7;
    }

    public static String orderIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte orderIdNullValue()
    {
        return (byte)0;
    }

    public static byte orderIdMinValue()
    {
        return (byte)32;
    }

    public static byte orderIdMaxValue()
    {
        return (byte)126;
    }

    public static int orderIdLength()
    {
        return 7;
    }


    public byte orderId(final int index)
    {
        if (index < 0 || index >= 7)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 7 + (index * 1);

        return buffer.getByte(pos);
    }


    public static String orderIdCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.UTF_8.name();
    }

    public int getOrderId(final byte[] dst, final int dstOffset)
    {
        final int length = 7;
        if (dstOffset < 0 || dstOffset > (dst.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + dstOffset);
        }

        buffer.getBytes(offset + 7, dst, dstOffset, length);

        return length;
    }

    public String orderId()
    {
        final byte[] dst = new byte[7];
        buffer.getBytes(offset + 7, dst, 0, 7);

        int end = 0;
        for (; end < 7 && dst[end] != 0; ++end);

        return new String(dst, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }


    public static int marketId()
    {
        return 3;
    }

    public static int marketSinceVersion()
    {
        return 0;
    }

    public static int marketEncodingOffset()
    {
        return 14;
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


    public byte market(final int index)
    {
        if (index < 0 || index >= 7)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 14 + (index * 1);

        return buffer.getByte(pos);
    }


    public static String marketCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.UTF_8.name();
    }

    public int getMarket(final byte[] dst, final int dstOffset)
    {
        final int length = 7;
        if (dstOffset < 0 || dstOffset > (dst.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + dstOffset);
        }

        buffer.getBytes(offset + 14, dst, dstOffset, length);

        return length;
    }

    public String market()
    {
        final byte[] dst = new byte[7];
        buffer.getBytes(offset + 14, dst, 0, 7);

        int end = 0;
        for (; end < 7 && dst[end] != 0; ++end);

        return new String(dst, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final CancelOrderDecoder decoder = new CancelOrderDecoder();
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
        builder.append("[CancelOrder](sbeTemplateId=");
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
        builder.append("userId=");
        for (int i = 0; i < userIdLength() && this.userId(i) > 0; i++)
        {
            builder.append((char)this.userId(i));
        }
        builder.append('|');
        builder.append("orderId=");
        for (int i = 0; i < orderIdLength() && this.orderId(i) > 0; i++)
        {
            builder.append((char)this.orderId(i));
        }
        builder.append('|');
        builder.append("market=");
        for (int i = 0; i < marketLength() && this.market(i) > 0; i++)
        {
            builder.append((char)this.market(i));
        }

        limit(originalLimit);

        return builder;
    }
    
    public CancelOrderDecoder sbeSkip()
    {
        sbeRewind();

        return this;
    }
}
