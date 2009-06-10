package org.broadinstitute.sting.utils.sam;

import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.SAMRecord;

import java.util.List;

import org.broadinstitute.sting.utils.StingException;


/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/**
 * @author aaron
 *
 * allows query calls to the artificial sam iterator, which allows you
 * to test out classes that use specific itervals.  The reads returned will
 * all lie in order in the specified interval.
 */
public class ArtificialSAMQueryIterator extends ArtificialSAMIterator {

    // get the next positon
    protected int finalPos = 0;
    protected int startPos = 0;
    protected int contigIndex = -1;
    protected boolean overlapping = false;
    protected int startingChr = 0;
    protected boolean seeked = false;

    /**
     * create the fake iterator, given the mapping of chromosomes and read counts
     *
     * @param startingChr the starting chromosome
     * @param endingChr   the ending chromosome
     * @param readCount   the number of reads in each chromosome
     * @param header      the associated header
     */
    ArtificialSAMQueryIterator( int startingChr, int endingChr, int readCount, int unmappedReadCount, SAMFileHeader header ) {
        super(startingChr, endingChr, readCount, unmappedReadCount, header);
        this.startingChr = startingChr;
    }

    /**
     * query containing - get reads contained by the specified interval
     *
     * @param contig the contig index string
     * @param start  the start position
     * @param stop   the stop position
     */
    public void queryContained( String contig, int start, int stop ) {
        this.overlapping = false;
        initialize(contig, start, stop);
    }

    /**
     * query containing - get reads contained by the specified interval
     *
     * @param contig the contig index string
     * @param start  the start position
     * @param stop   the stop position
     */
    public void queryOverlapping( String contig, int start, int stop ) {
        this.overlapping = true;
        initialize(contig, start, stop);
    }


    /**
     * initialize the query iterator
     *
     * @param contig the contig
     * @param start  the start position
     * @param stop   the stop postition
     */
    private void initialize( String contig, int start, int stop ) {
        ensureUntouched();
        finalPos = stop;
        startPos = start;
        if (finalPos < 0) {
            finalPos = Integer.MAX_VALUE;
        }
        // sanity check that we have the contig
        contigIndex = -1;
        List<SAMSequenceRecord> list = header.getSequenceDictionary().getSequences();
        for (SAMSequenceRecord rec : list) {
            if (rec.getSequenceName().equals(contig)) {
                contigIndex = rec.getSequenceIndex();
            }
        }
        if (contigIndex < 0) { throw new IllegalArgumentException("Contig" + contig + " doesn't exist"); }
        while (super.hasNext() && this.peek().getReferenceIndex() < contigIndex) {
            super.next();
        }
        if (!super.hasNext()) {
            throw new StingException("Unable to find the target chromosome");
        }
        while (super.hasNext() && this.peek().getAlignmentStart() < start) {
            super.next();
        }
        // sanity check that we have an actual matching read next
        SAMRecord rec = this.peek();
        if (!matches(rec)) {
            throw new StingException("The next read doesn't match");    
        }
        // set the seeked variable to true
        seeked = true;
    }

    /**
     * given a read and the query type, check if it matches our regions
     *
     * @param rec the read
     *
     * @return true if it belongs in our region
     */
    public boolean matches( SAMRecord rec ) {
        if (rec.getReferenceIndex() != this.contigIndex) {
            return false;
        }
        if (!overlapping) {
            // if the start or the end are somewhere within our range
            if (( rec.getAlignmentStart() >= startPos && rec.getAlignmentEnd() <= finalPos )) {
                return true;
            }
        } else {
            if (( rec.getAlignmentStart() <= finalPos && rec.getAlignmentStart() >= startPos ) ||
                    ( rec.getAlignmentEnd() <= finalPos && rec.getAlignmentEnd() >= startPos )) {
                return true;
            }
        }
        return false;
    }


    /**
     * override the hasNext, to incorportate our limiting factor
     *
     * @return
     */
    public boolean hasNext() {
        boolean res = super.hasNext();
        if (!seeked) {
            return res;
        }
        if (res && matches(this.next)) {
            return true;
        }
        return false;
    }

    /** make sure we haven't been used as an iterator yet; this is to miror the MergingSamIterator2 action. */
    public void ensureUntouched() {
        if (this.currentChromo != 0 || this.currentRead > 0) {
            throw new UnsupportedOperationException("We've already been used as an iterator; you can't query after that");
        }
    }
}
