/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.generated;

@SuppressWarnings("all")
public enum BookUpdateType
{
    NEW_LEVEL((short)0),

    UPDATE_LEVEL((short)1),

    DELETE_LEVEL((short)2),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    BookUpdateType(final short value)
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
    public static BookUpdateType get(final short value)
    {
        switch (value)
        {
            case 0: return NEW_LEVEL;
            case 1: return UPDATE_LEVEL;
            case 2: return DELETE_LEVEL;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
