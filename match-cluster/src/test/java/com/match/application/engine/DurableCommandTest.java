// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.FixedPoint;
import com.match.application.publisher.*;
import com.match.infrastructure.journal.SettlementJournal;
import com.match.infrastructure.persistence.*;
import org.agrona.ExpandableArrayBuffer;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.ByteOrder;

public class DurableCommandTest {
    private Engine engine;
    private MatchEventPublisher publisher;
    private SettlementJournal journal;
    private SbeDemuxer demux;
    private static final long PRICE = FixedPoint.fromDouble(59000);
    private static final long QTY = FixedPoint.fromDouble(1);
    @Before public void setup() {
        engine = new Engine("direct");
        journal = new SettlementJournal(1 << 16);
        publisher = new MatchEventPublisher();
        publisher.setSettlementJournal(journal);
        publisher.initMarket(1, new MarketEventHandler() {
            public int getMarketId() { return 1; }
            public void onEvent(PublishEvent e, long s, boolean end) {}
        });
        publisher.start(); engine.setEventPublisher(publisher); demux = new SbeDemuxer(engine);
    }
    @After public void close() { publisher.shutdown(); engine.close(); }
    // Independent wire fixture: schema 1/v11, durable command template 9, fixed block 71.
    private void send(long id, int kind, long old, long price) {
        var b = new ExpandableArrayBuffer();
        b.putShort(0, (short)71, ByteOrder.LITTLE_ENDIAN);
        b.putShort(2, (short)9, ByteOrder.LITTLE_ENDIAN);
        b.putShort(4, (short)1, ByteOrder.LITTLE_ENDIAN);
        b.putShort(6, (short)11, ByteOrder.LITTLE_ENDIAN);
        long[] values = {17, id, 100, 9001, old, price, QTY};
        // Command UUID, user, OMS parent, old leg, price, quantity; budget follows.
        for (int i=0; i<7; i++) b.putLong(8+i*8, values[i], ByteOrder.LITTLE_ENDIAN);
        b.putLong(64, 0, ByteOrder.LITTLE_ENDIAN);
        b.putInt(72, 1, ByteOrder.LITTLE_ENDIAN);
        b.putByte(76, (byte)kind); b.putByte(77, (byte)0); b.putByte(78, (byte)0);
        demux.dispatch(b, 0, 79, 1000 + id);
    }
    @Test public void lostAckRetryCreatesOneOrder() {
        long before=engine.getOrderIdGenerator(); send(1,0,0,PRICE); send(1,0,0,PRICE);
        assertEquals(before+1, engine.getOrderIdGenerator());
        assertEquals(4, engine.getEngine(1).getBidOrders().length);
    }
    @Test public void sameIdentityDifferentPayloadCannotApply() {
        long before=engine.getOrderIdGenerator(); send(1,0,0,PRICE); send(1,0,0,PRICE+100000000);
        assertEquals(before+1, engine.getOrderIdGenerator());
        assertEquals(PRICE, engine.getEngine(1).getBestBid());
    }
    @Test public void distinctSliceCommandsMayShareParent() {
        long before=engine.getOrderIdGenerator(); send(1,0,0,PRICE); send(2,0,0,PRICE);
        assertEquals(before+2, engine.getOrderIdGenerator());
    }
    @Test public void snapshotRestartRetainsDedupe() {
        long before=engine.getOrderIdGenerator(); send(1,0,0,PRICE);
        var snapshot=new ExpandableArrayBuffer(); int n=SnapshotCodec.serialize(engine,0,0,snapshot);
        engine.close(); engine=new Engine("direct"); engine.setEventPublisher(publisher);
        SnapshotCodec.deserialize(snapshot,0,n,engine); demux=new SbeDemuxer(engine);
        send(1,0,0,PRICE); assertEquals(before+1, engine.getOrderIdGenerator());
        assertEquals(4, engine.getEngine(1).getBidOrders().length);
    }
    private com.match.domain.commands.DurableOrderIntent intent(long id,int kind,long old,long price,int side) {
        return new com.match.domain.commands.DurableOrderIntent(17,id,100,9001,old,price,QTY,0,1,kind,0,side);
    }
    private java.util.List<byte[]> entries() {
        var result=new java.util.ArrayList<byte[]>();
        journal.ringBuffer().read((t,b,i,n)->{ byte[] v=new byte[n]; b.getBytes(i,v); result.add(v); },1024);
        return result;
    }
    @Test public void retryJournalsOriginalOutcomeWithoutRepeatingTradeOrTerminal() throws Exception {
        var c=intent(1,0,0,PRICE,0);
        engine.setCurrentLogPosition(128); var original=engine.acceptDurable(c,1000);
        engine.setCurrentLogPosition(256); var retry=engine.acceptDurable(c,2000);
        assertEquals(original,retry);
        var events=entries(); assertEquals(2,events.size());
        for(byte[] bytes:events) {
            var b=new org.agrona.concurrent.UnsafeBuffer(bytes);
            var h=new com.match.infrastructure.generated.MessageHeaderDecoder();
            var d=new com.match.infrastructure.generated.JournalCommandOutcomeDecoder().wrapAndApplyHeader(b,0,h);
            assertEquals(1,h.schemaId()); assertEquals(28,h.templateId());
            assertEquals(128,d.appliedPosition()); assertEquals(1000,d.timestamp()); assertEquals(original.orderId(),d.orderId());
        }
        String export=System.getProperty("runtime.command.fixture");
        if(export!=null) {
            java.nio.file.Files.write(java.nio.file.Path.of(export+".first.bin"),events.get(0));
            java.nio.file.Files.write(java.nio.file.Path.of(export+".retry.bin"),events.get(1));
        }
    }
    @Test public void rejectedAmendHasDurableOutcomeButNoFinancialTerminal() {
        var created=engine.acceptDurable(intent(1,0,0,PRICE,0),1000); entries();
        var amended=engine.acceptDurable(intent(2,2,created.orderId(),0,0),2000);
        assertEquals(1,amended.result()); assertEquals(4,amended.status()); assertFalse(amended.oldCancelled());
        assertEquals(created.orderId(),amended.orderId()); assertEquals(PRICE,engine.getEngine(1).getBestBid());
        assertEquals(1,entries().size()); assertEquals(0,journal.appendedTerminals());
    }
    @Test public void replaceOutcomePreservesBothLegsAndRetryDoesNotReplaceAgain() {
        var created=engine.acceptDurable(intent(1,0,0,PRICE,0),1000);
        var change=intent(2,2,created.orderId(),PRICE+100000000,0);
        var replaced=engine.acceptDurable(change,2000);
        assertTrue(replaced.oldCancelled()); assertNotEquals(created.orderId(),replaced.orderId());
        assertEquals(created.orderId(),replaced.intent().oldOrderId());
        assertEquals(replaced,engine.acceptDurable(change,3000));
        assertEquals(4,engine.getEngine(1).getBidOrders().length); assertEquals(0,journal.appendedTerminals());
    }
    @Test public void cancelRetryEmitsOneFinancialTerminal() {
        var created=engine.acceptDurable(intent(1,0,0,PRICE,0),1000);
        var c=intent(2,1,created.orderId(),0,0);
        var cancelled=engine.acceptDurable(c,2000);
        assertEquals(3,cancelled.status()); assertEquals(cancelled,engine.acceptDurable(c,3000));
        assertEquals(1,journal.appendedTerminals()); assertTrue(engine.getEngine(1).isBidEmpty());
    }
    @Test public void wrongOwnerCannotCancelLiveLeg() {
        var created=engine.acceptDurable(intent(1,0,0,PRICE,0),1000);
        var c=new com.match.domain.commands.DurableOrderIntent(17,2,101,9001,created.orderId(),0,0,0,1,1,0,0);
        assertEquals(5,engine.acceptDurable(c,2000).result());
        assertEquals(PRICE,engine.getEngine(1).getBestBid()); assertEquals(0,journal.appendedTerminals());
    }
    @Test public void absentLegIsNotFinancialClosureEvidence() {
        assertEquals(4,engine.acceptDurable(intent(1,1,777,0,0),1000).result());
        assertEquals(0,journal.appendedTerminals());
    }
    @Test public void journalTurnedOffLaterStillAppliesAndCountsDarkOutcome() {
        publisher.setSettlementJournal(null);
        long before=engine.getOrderIdGenerator();
        engine.acceptDurable(intent(1,0,0,PRICE,0),1000);
        assertEquals(before+1,engine.getOrderIdGenerator()); assertFalse(engine.getEngine(1).isBidEmpty());
        assertEquals(1,publisher.getDarkCommandOutcomeCount()); assertTrue(entries().isEmpty());
    }
    @Test public void truncatedOrCorruptSnapshotCannotDiscardDedupe() {
        send(1,0,0,PRICE); var b=new ExpandableArrayBuffer(); int n=SnapshotCodec.serialize(engine,0,0,b);
        for(int cut=20;cut<n;cut++) {
            final int len=cut;
            assertThrows(IllegalStateException.class,()->SnapshotCodec.deserialize(b,0,len,engine));
        }
        b.putByte(n-1,(byte)(b.getByte(n-1)^1));
        assertThrows(IllegalStateException.class,()->SnapshotCodec.deserialize(b,0,n,engine));
        assertEquals(1,engine.commandLedger().size());
    }
    @Test public void restartRetainsReplaceOwnershipAndOutcomes() {
        var created=engine.acceptDurable(intent(1,0,0,PRICE,0),1000);
        var change=intent(2,2,created.orderId(),PRICE+100000000,0);
        var replaced=engine.acceptDurable(change,2000);
        var b=new ExpandableArrayBuffer(); int n=SnapshotCodec.serialize(engine,0,0,b);
        engine.close(); engine=new Engine("direct"); engine.setEventPublisher(publisher);
        assertEquals(n,SnapshotCodec.deserialize(b,0,n,engine).bytesConsumed);
        assertEquals(replaced,engine.acceptDurable(change,3000));
        assertEquals(3,engine.acceptDurable(intent(3,1,replaced.orderId(),0,0),4000).status());
    }

    @Test public void fullLedgerFencesNewCommandsWithoutForgettingOldIdentity() {
        var c=intent(1,0,0,PRICE,0); var original=engine.acceptDurable(c,1000); entries();
        for(int i=2;i<=DurableCommandLedger.MAX_ENTRIES;i++)
            engine.commandLedger().record(new com.match.domain.commands.DurableCommandOutcome(
                intent(i,1,999,0,0),0,1000,0,-1,0,false,4));
        long before=engine.getOrderIdGenerator();
        assertEquals(3,engine.acceptDurable(intent(100001,0,0,PRICE,0),2000).result());
        assertEquals(before,engine.getOrderIdGenerator());
        assertEquals(original,engine.acceptDurable(c,3000));
        assertEquals(DurableCommandLedger.MAX_ENTRIES,engine.commandLedger().size());
    }


    /** Journal presence is node env, not replicated state: it must not change what the log applies. */
    @Test public void journalDarkNodeAppliesAndDedupesWithoutHalting() {
        publisher.shutdown();
        publisher = new MatchEventPublisher(); // SETTLEMENT_JOURNAL_ENABLED unset on this node
        publisher.initMarket(1, new MarketEventHandler() {
            public int getMarketId() { return 1; }
            public void onEvent(PublishEvent e, long s, boolean end) {}
        });
        publisher.start(); engine.setEventPublisher(publisher);
        long before=engine.getOrderIdGenerator();
        send(1,0,0,PRICE); send(1,0,0,PRICE);
        assertEquals(before+1, engine.getOrderIdGenerator());
        assertEquals(PRICE, engine.getEngine(1).getBestBid());
        assertEquals(1, engine.commandLedger().size());
        assertEquals(2, publisher.getDarkCommandOutcomeCount());
    }

    @Test public void journalDarkAndJournaledReplicasReachTheSameState() {
        var dark = new Engine("direct");
        var darkPublisher = new MatchEventPublisher();
        darkPublisher.initMarket(1, new MarketEventHandler() {
            public int getMarketId() { return 1; }
            public void onEvent(PublishEvent e, long s, boolean end) {}
        });
        darkPublisher.start(); dark.setEventPublisher(darkPublisher);
        try {
            var c = intent(1,0,0,PRICE,0);
            engine.setCurrentLogPosition(128); dark.setCurrentLogPosition(128);
            assertEquals(engine.acceptDurable(c,1000), dark.acceptDurable(c,1000));
            engine.setCurrentLogPosition(256); dark.setCurrentLogPosition(256);
            assertEquals(engine.acceptDurable(c,2000), dark.acceptDurable(c,2000));
            var a = new ExpandableArrayBuffer(); int n = SnapshotCodec.serialize(engine,0,0,a);
            var b = new ExpandableArrayBuffer(); int m = SnapshotCodec.serialize(dark,0,0,b);
            assertEquals(n, m);
            byte[] x = new byte[n], y = new byte[m]; a.getBytes(0,x); b.getBytes(0,y);
            assertArrayEquals(x, y);
        } finally { darkPublisher.shutdown(); dark.close(); }
    }
}
