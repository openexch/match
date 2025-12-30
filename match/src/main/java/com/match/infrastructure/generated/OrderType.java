/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

@SuppressWarnings("all")
public enum OrderType
{
    LIMIT((short)0),

    MARKET((short)1),

    LIMIT_MAKER((short)2),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    OrderType(final short value)
    {
        this.value = value;
    }

    /**
     * The raw encoded value in the Java type representation.
     *
     * @return the raw value encoded.
     */
    public short value()
    {
        return value;
    }

    /**
     * Lookup the enum value representing the value.
     *
     * @param value encoded to be looked up.
     * @return the enum value representing the value.
     */
    public static OrderType get(final short value)
    {
        switch (value)
        {
            case 0: return LIMIT;
            case 1: return MARKET;
            case 2: return LIMIT_MAKER;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
