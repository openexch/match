/* Generated SBE (Simple Binary Encoding) message codec. */
package com.match.infrastructure.journal.generated;

@SuppressWarnings("all")
public enum TerminalStatus
{
    FILLED((short)2),

    CANCELLED((short)3),

    REJECTED((short)4),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    TerminalStatus(final short value)
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
    public static TerminalStatus get(final short value)
    {
        switch (value)
        {
            case 2: return FILLED;
            case 3: return CANCELLED;
            case 4: return REJECTED;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
