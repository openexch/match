// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.commands.*;
import java.util.*;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/** Replicated, non-evicting ledger. Capacity fences NEW commands, never forgets retry identities.
 * Retention/epoch compaction must be a separately replicated protocol before lifting this bound.
 */
public final class DurableCommandLedger {
    public static final int MAX_ENTRIES = 100_000;
    public static final int RECORD_BYTES = 120;
    private final Map<UUID, DurableCommandOutcome> outcomes = new HashMap<>();
    private final Map<Long, DurableOrderIntent> legs = new HashMap<>();
    public DurableCommandOutcome get(DurableOrderIntent c) { return outcomes.get(c.id()); }
    public DurableOrderIntent owner(long leg) { return legs.get(leg); }
    public int size() { return outcomes.size(); }
    public void clear() { outcomes.clear(); legs.clear(); }
    public void record(DurableCommandOutcome o) {
        if (o.result()==DurableCommandOutcome.CONFLICT || o.result()==DurableCommandOutcome.CAPACITY)
            throw new IllegalArgumentException("A delivery rejection cannot replace a canonical outcome");
        if (outcomes.size() >= MAX_ENTRIES || outcomes.putIfAbsent(o.intent().id(), o) != null)
            throw new IllegalStateException("Duplicate or full command ledger");
        if (o.orderId() > 0 && (o.intent().kind() == 0 ||
                o.intent().kind() == 2 && o.oldCancelled())) legs.put(o.orderId(), o.intent());
    }
    public int write(MutableDirectBuffer b, int p) {
        b.putByte(p++, (byte)2); b.putInt(p,1); p+=4; b.putInt(p,size()); p+=4;
        var sorted = new TreeMap<>(outcomes);
        for (var o : sorted.values()) {
            var c=o.intent();
            long[] longs={c.idHigh(),c.idLow(),c.userId(),c.omsOrderId(),c.oldOrderId(),c.price(),
                c.quantity(),c.budget(),o.appliedPosition(),o.timestamp(),o.orderId()};
            for(long n:longs) { b.putLong(p,n); p+=8; }
            int[] ints={c.marketId(),c.kind(),c.type(),c.side(),o.status(),o.reason(),o.oldCancelled()?1:0,o.result()};
            for(int n:ints) { b.putInt(p,n); p+=4; }
        }
        return p;
    }
    public int read(DirectBuffer b,int p,int end) {
        clear();
        if (p==end) return p;
        if (end-p<9 || b.getByte(p++)!=2 || b.getInt(p)!=1)
            throw new IllegalStateException("Unsupported/truncated command ledger snapshot");
        p+=4; int count=b.getInt(p); p+=4;
        if (count<0 || count>MAX_ENTRIES || (long)count*RECORD_BYTES!=end-p)
            throw new IllegalStateException("Invalid command ledger snapshot length");
        for(int i=0;i<count;i++) {
            long[] a=new long[11]; int[] z=new int[8];
            for(int j=0;j<a.length;j++) { a[j]=b.getLong(p); p+=8; }
            for(int j=0;j<z.length;j++) { z[j]=b.getInt(p); p+=4; }
            if(a[8]<0 || a[9]<0 || a[10]<0 || z[4]<-1 || z[4]>4 || z[5]<0 || z[6]<0 || z[6]>1
                    || z[7]<0 || z[7]>6 || z[7]==2 || z[7]==3)
                throw new IllegalStateException("Invalid command ledger outcome");
            record(new DurableCommandOutcome(new DurableOrderIntent(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],
                z[0],z[1],z[2],z[3]),a[8],a[9],a[10],z[4],z[5],z[6]==1,z[7]));
        }
        return p;
    }
}
