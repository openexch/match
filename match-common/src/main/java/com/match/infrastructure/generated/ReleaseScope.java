/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

@SuppressWarnings("all")
public enum ReleaseScope
{
    UP_TO((short)0),

    ORDER((short)1),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    ReleaseScope(final short value)
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
    public static ReleaseScope get(final short value)
    {
        switch (value)
        {
            case 0: return UP_TO;
            case 1: return ORDER;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
