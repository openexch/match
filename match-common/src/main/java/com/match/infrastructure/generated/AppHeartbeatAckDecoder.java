/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

import org.agrona.DirectBuffer;


/**
 * Server acknowledges heartbeat
 */
@SuppressWarnings("all")
public final class AppHeartbeatAckDecoder
{
    public static final int BLOCK_LENGTH = 16;
    public static final int TEMPLATE_ID = 23;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String SEMANTIC_VERSION = "5.2";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final AppHeartbeatAckDecoder parentMessage = this;
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

    public AppHeartbeatAckDecoder wrap(
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

    public AppHeartbeatAckDecoder wrapAndApplyHeader(
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

    public AppHeartbeatAckDecoder sbeRewind()
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

    public static int clientIdId()
    {
        return 1;
    }

    public static int clientIdSinceVersion()
    {
        return 0;
    }

    public static int clientIdEncodingOffset()
    {
        return 0;
    }

    public static int clientIdEncodingLength()
    {
        return 8;
    }

    public static String clientIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long clientIdNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long clientIdMinValue()
    {
        return 0x0L;
    }

    public static long clientIdMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public long clientId()
    {
        return buffer.getLong(offset + 0, BYTE_ORDER);
    }


    public static int serverTimestampId()
    {
        return 2;
    }

    public static int serverTimestampSinceVersion()
    {
        return 0;
    }

    public static int serverTimestampEncodingOffset()
    {
        return 8;
    }

    public static int serverTimestampEncodingLength()
    {
        return 8;
    }

    public static String serverTimestampMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long serverTimestampNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long serverTimestampMinValue()
    {
        return 0x0L;
    }

    public static long serverTimestampMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public long serverTimestamp()
    {
        return buffer.getLong(offset + 8, BYTE_ORDER);
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final AppHeartbeatAckDecoder decoder = new AppHeartbeatAckDecoder();
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
        builder.append("[AppHeartbeatAck](sbeTemplateId=");
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
        builder.append("clientId=");
        builder.append(this.clientId());
        builder.append('|');
        builder.append("serverTimestamp=");
        builder.append(this.serverTimestamp());

        limit(originalLimit);

        return builder;
    }
    
    public AppHeartbeatAckDecoder sbeSkip()
    {
        sbeRewind();

        return this;
    }
}
