// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.EngineConfigDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
import com.openexchange.cluster.journal.JournalProfile;
import com.openexchange.cluster.journal.JournalReaders;
import com.openexchange.cluster.journal.JournalRetention;
import org.agrona.BitUtil;

import java.util.List;
import java.util.Map;

/**
 * The matching engine's one journal, the settlement journal, as the kit's journal retention reads it: where it is
 * recorded and where its records carry {@code journalSeq}, the epoch and the log position. Every offset comes from
 * the generated decoders, so a schema change moves it here with the codec.
 *
 * <p>All five records of schema 4 start with {@code journalSeq}, {@code meEpoch}, {@code egressSeq}. The epoch is
 * {@code meEpoch}: the {@code genesisId} of the recorded {@code EngineConfig}, the same number the member publishes
 * as its genesis. The log position is {@code egressSeq}: the position in the cluster log of the frame end of the
 * command that produced the record. A record is one unfragmented frame of at most
 * {@link SettlementJournal#MAX_FRAME_LENGTH} bytes.</p>
 */
public final class MatchJournalProfiles {

    /** The settlement journal's name in settings, reader files and metrics. */
    public static final String SETTLEMENT_NAME = JournalReaders.MATCH_SETTLEMENT;

    private static final int HEADER = MessageHeaderDecoder.ENCODED_LENGTH;
    private static final int DATA_HEADER_LENGTH = 32;
    private static final int FRAME_ALIGNMENT = 32;
    private static final int LOG_POSITION = HEADER + TradeDecoder.egressSeqEncodingOffset();

    /** The largest aligned frame of one record, data header included. */
    public static final int MAX_FRAME_LENGTH =
            BitUtil.align(SettlementJournal.MAX_FRAME_LENGTH + DATA_HEADER_LENGTH, FRAME_ALIGNMENT);

    /** Stream 4001 of the member's journal archive, schema 4. */
    public static final JournalProfile SETTLEMENT = new JournalProfile(SETTLEMENT_NAME,
            InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID, "aeron:ipc", TradeDecoder.SCHEMA_ID,
            HEADER + TradeDecoder.journalSeqEncodingOffset(), HEADER + TradeDecoder.meEpochEncodingOffset(),
            Map.of(TradeDecoder.TEMPLATE_ID, LOG_POSITION,
                    TerminalDecoder.TEMPLATE_ID, LOG_POSITION,
                    CommandOutcomeDecoder.TEMPLATE_ID, LOG_POSITION,
                    CommandReleaseDecoder.TEMPLATE_ID, LOG_POSITION,
                    EngineConfigDecoder.TEMPLATE_ID, LOG_POSITION),
            0, MAX_FRAME_LENGTH)
            // the engine journals nothing of an epoch before it is bound to it (pre-config frames are rejected
            // without a record, an epoch is never rebound): a recording that starts at epoch 0 and ends in this
            // genesis is not this engine's doing and is never purged
            .strictStartEpoch();

    /** The engine's journals, in the order the retention passes them. */
    public static final List<JournalProfile> ALL = List.of(SETTLEMENT);

    private MatchJournalProfiles() {
    }

    /**
     * The journal retention settings of this process ({@code JOURNAL_RETENTION_SETTLEMENT}, its required readers,
     * the poll, the pass budget and the two alarm bounds), checked before anything is launched.
     *
     * @param journalEnabled whether this member writes the settlement journal ({@code SETTLEMENT_JOURNAL_ENABLED})
     * @throws IllegalArgumentException naming a malformed or out-of-range setting, or a mode other than {@code off}
     *                                  on a member that writes no journal: a mode that could never act is refused
     */
    public static JournalRetention.Settings checkBoot(final boolean journalEnabled) {
        return check(JournalRetention.Settings.fromEnv(SETTLEMENT_NAME), journalEnabled);
    }

    /** As {@link #checkBoot} for settings already read (tests). */
    static JournalRetention.Settings check(final JournalRetention.Settings settings, final boolean journalEnabled) {
        final JournalRetention.Mode mode = settings.mode(SETTLEMENT_NAME);
        if (!journalEnabled && mode != JournalRetention.Mode.OFF) {
            throw new IllegalArgumentException("JOURNAL_RETENTION_SETTLEMENT=" + mode.text() + " on a member that"
                    + " writes no settlement journal (SETTLEMENT_JOURNAL_ENABLED is not true): there is no journal"
                    + " to retain. Enable the journal or leave the mode off");
        }
        return settings;
    }
}
