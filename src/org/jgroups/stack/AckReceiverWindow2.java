package org.jgroups.stack;


import org.jgroups.Message;
import org.jgroups.util.Tuple;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;


/**
 * Counterpart of AckSenderWindow. Simple FIFO buffer.
 * Every message received is ACK'ed (even duplicates) and added to a hashmap
 * keyed by seqno. The next seqno to be received is stored in <code>next_to_remove</code>. When a message with
 * a seqno less than next_to_remove is received, it will be discarded. The <code>remove()</code> method removes
 * and returns a message whose seqno is equal to next_to_remove, or null if not found.<br>
 * Change May 28 2002 (bela): replaced TreeSet with HashMap. Keys do not need to be sorted, and adding a key to
 * a sorted set incurs overhead.
 *
 * @author Bela Ban
 * @version $Id: AckReceiverWindow2.java,v 1.1 2010/02/26 09:02:03 belaban Exp $
 */
public class AckReceiverWindow2 {
    private final AtomicLong                   next_to_remove;
    private final AtomicBoolean                processing=new AtomicBoolean(false);
    private final ConcurrentMap<Long,Segment>  segments=new ConcurrentHashMap<Long,Segment>();
    private final int                          segment_capacity;
    private long                               highest_segment_created=0;

    static final Message                       TOMBSTONE=new Message(true);


    public AckReceiverWindow2(long initial_seqno, int segment_capacity) {
        next_to_remove=new AtomicLong(initial_seqno);
        this.segment_capacity=segment_capacity;
        long index=next_to_remove.get() / segment_capacity;
        long first_seqno=(next_to_remove.get() / segment_capacity) * segment_capacity;
        this.segments.put(index, new Segment(first_seqno, segment_capacity));
        Segment initial_segment=findOrCreateSegment(next_to_remove.get());
        for(long i=0; i < next_to_remove.get(); i++) {
            initial_segment.add(i, TOMBSTONE);
        }
    }

    public AtomicBoolean getProcessing() {
        return processing;
    }



    /** Adds a new message. Message cannot be null
     * @return True if the message was added, false if not (e.g. duplicate, message was already present)
     */
    public boolean add(long seqno, Message msg) {
        return add2(seqno, msg) == 1;
    }


    /**
     * Adds a message if not yet received
     * @param seqno
     * @param msg
     * @return -1 if not added because seqno < next_to_remove, 0 if not added because already present,
     *          1 if added successfully
     */
    public byte add2(long seqno, Message msg) {
        Segment segment=findOrCreateSegment(seqno);
        if(segment == null)
            return -1;
        return segment.add(seqno, msg);
    }


    /**
     * Removes a message whose seqno is equal to <code>next_to_remove</code>, increments the latter. Returns message
     * that was removed, or null, if no message can be removed. Messages are thus removed in order.
     */
    public Message remove() {
        long next=next_to_remove.get();
        Segment segment=findOrCreateSegment(next);
        if(segment == null)
            return null;
        Message retval=segment.remove(next);
        if(retval != null)
            next_to_remove.compareAndSet(next, next +1);
        return retval;
    }



    /**
     * Removes as many messages as possible (in sequence, without gaps)
     * @param max Max number of messages to be removed
     * @return Tuple<List<Message>,Long>: a tuple of the message list and the highest seqno removed
     */
    public Tuple<List<Message>,Long> removeMany(int max) {
        List<Message> list=new LinkedList<Message>(); // we remove msgs.size() messages *max*
        Tuple<List<Message>,Long> retval=new Tuple<List<Message>,Long>(list, 0L);

        return null;
    }


    public Message removeOOBMessage() {
        return null;
    }

    /**
     * Removes as many OOB messages as possible and return the highest seqno
     * @return the highest seqno or -1 if no OOB message was found
     */
    public long removeOOBMessages() {
        return -1;
    }


    public boolean hasMessagesToRemove() {
        return false;
    }


    public void reset() {
    }

    public int size() {
        return 0;
    }

    public String toString() {
        StringBuilder sb=new StringBuilder();

        return sb.toString();
    }


    public String printDetails() {
        StringBuilder sb=new StringBuilder();

        return sb.toString();
    }

    private Segment findOrCreateSegment(long seqno) {
        long index=seqno / segment_capacity;
        if(index > highest_segment_created) {
            long start_seqno=seqno / segment_capacity * segment_capacity;
            Segment segment=new Segment(start_seqno, segment_capacity);
            Segment tmp=segments.putIfAbsent(index, segment);
            if(tmp != null) // segment already exists
                segment=tmp;
            else
                highest_segment_created=index;
            return segment;
        }

        return segments.get(index);
    }


    private static class Segment {
        final long                          start_index; // e.g. 5000. Then seqno 5100 would be at index 100
        final int                           capacity;
        final AtomicReferenceArray<Message> array;
        final AtomicInteger                 count=new AtomicInteger(0); // counts the numbers of non-empty elements (also tombstones)

        public Segment(long start_index, int capacity) {
            this.start_index=start_index;
            this.capacity=capacity;
            this.array=new AtomicReferenceArray<Message>(capacity);
        }

        public byte add(long seqno, Message msg) {
            int index=index(seqno);
            if(index < 0)
                return -1;
            boolean success=array.compareAndSet(index, null, msg);
            if(success) {
                count.incrementAndGet();
                return 1;
            }
            else
                return 0;
        }

        public Message remove(long seqno) {
            int index=index(seqno);
            Message retval=array.get(index);
            if(retval != null && array.compareAndSet(index, retval, TOMBSTONE))
                return retval;
            return null;
        }

        public String toString() {
            return start_index + " - " + (start_index + capacity -1) + " (" + count + " elements set)";
        }

        private int index(long seqno) {
            if(seqno < start_index)
                return -1;

            int index=(int)(seqno - start_index);
            if(index < 0 || index >= capacity) {
                // todo: replace with returning -1
                throw new IndexOutOfBoundsException("index=" + index + ", start_index=" + start_index + ", seqno=" + seqno);
            }
            return index;
        }

    }

}