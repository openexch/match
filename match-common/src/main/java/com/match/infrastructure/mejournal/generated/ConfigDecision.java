/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.mejournal.generated;

@SuppressWarnings("all")
public enum ConfigDecision
{
    FRESH((short)0),

    ADOPT((short)1),

    SWITCH((short)2),

    BUMP((short)3),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    ConfigDecision(final short value)
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
    public static ConfigDecision get(final short value)
    {
        switch (value)
        {
            case 0: return FRESH;
            case 1: return ADOPT;
            case 2: return SWITCH;
            case 3: return BUMP;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
