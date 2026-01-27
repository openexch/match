/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated.sbe;

@SuppressWarnings("all")
public enum OrderSide
{
    BID((short)0),

    ASK((short)1),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    OrderSide(final short value)
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
    public static OrderSide get(final short value)
    {
        switch (value)
        {
            case 0: return BID;
            case 1: return ASK;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
