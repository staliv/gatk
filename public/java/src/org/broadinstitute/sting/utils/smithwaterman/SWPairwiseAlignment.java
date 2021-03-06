/*
* Copyright (c) 2012 The Broad Institute
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
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.sting.utils.smithwaterman;

import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import org.broadinstitute.sting.utils.exceptions.StingException;
import org.broadinstitute.sting.utils.sam.AlignmentUtils;

import java.util.*;

/**
 * Pairwise discrete smith-waterman alignment
 *
 * ************************************************************************
 * ****                    IMPORTANT NOTE:                             ****
 * ****  This class assumes that all bytes come from UPPERCASED chars! ****
 * ************************************************************************
 *
 * User: asivache
 * Date: Mar 23, 2009
 * Time: 1:54:54 PM
 */
public class SWPairwiseAlignment implements SmithWaterman {

    protected SWPairwiseAlignmentResult alignmentResult;

    protected final Parameters parameters;

    /**
     * The state of a trace step through the matrix
     */
    protected enum State {
        MATCH,
        INSERTION,
        DELETION,
        CLIP
    }

    /**
     * What strategy should we use when the best path does not start/end at the corners of the matrix?
     */
    public enum OVERHANG_STRATEGY {
        /*
         * Add softclips for the overhangs
         */
        SOFTCLIP,

        /*
         * Treat the overhangs as proper insertions/deletions
         */
        INDEL,

        /*
         * Treat the overhangs as proper insertions/deletions for leading (but not trailing) overhangs.
         * This is useful e.g. when we want to merge dangling tails in an assembly graph: because we don't
         * expect the dangling tail to reach the end of the reference path we are okay ignoring trailing
         * deletions - but leading indels are still very much relevant.
         */
        LEADING_INDEL,

        /*
         * Just ignore the overhangs
         */
        IGNORE
    }

    protected static boolean cutoff = false;

    protected OVERHANG_STRATEGY overhang_strategy = OVERHANG_STRATEGY.SOFTCLIP;

    /**
     * The SW scoring matrix, stored for debugging purposes if keepScoringMatrix is true
     */
    protected double[] SW = null;

    /**
     * Only for testing purposes in the SWPairwiseAlignmentMain function
     * set to true to keep SW scoring matrix after align call
     */
    protected static boolean keepScoringMatrix = false;

    /**
     * Create a new SW pairwise aligner.
     *
     * @deprecated in favor of constructors using the Parameter or ParameterSet class
     */
    @Deprecated
    public SWPairwiseAlignment(byte[] seq1, byte[] seq2, double match, double mismatch, double open, double extend ) {
        this(seq1, seq2, new Parameters(match, mismatch, open, extend));
    }

    /**
     * Create a new SW pairwise aligner
     *
     * After creating the object the two sequences are aligned with an internal call to align(seq1, seq2)
     *
     * @param seq1 the first sequence we want to align
     * @param seq2 the second sequence we want to align
     * @param parameters the SW parameters to use
     */
    public SWPairwiseAlignment(byte[] seq1, byte[] seq2, Parameters parameters) {
        this(parameters);
        align(seq1,seq2);
    }

    /**
     * Create a new SW pairwise aligner
     *
     * After creating the object the two sequences are aligned with an internal call to align(seq1, seq2)
     *
     * @param seq1 the first sequence we want to align
     * @param seq2 the second sequence we want to align
     * @param parameters the SW parameters to use
     * @param strategy   the overhang strategy to use
     */
    public SWPairwiseAlignment(final byte[] seq1, final byte[] seq2, final SWParameterSet parameters, final OVERHANG_STRATEGY strategy) {
        this(parameters.parameters);
        overhang_strategy = strategy;
        align(seq1, seq2);
    }

    /**
     * Create a new SW pairwise aligner, without actually doing any alignment yet
     *
     * @param parameters the SW parameters to use
     */
    protected SWPairwiseAlignment(Parameters parameters) {
        this.parameters = parameters;
    }

    /**
     * Create a new SW pairwise aligner
     *
     * After creating the object the two sequences are aligned with an internal call to align(seq1, seq2)
     *
     * @param seq1 the first sequence we want to align
     * @param seq2 the second sequence we want to align
     * @param namedParameters the named parameter set to get our parameters from
     */
    public SWPairwiseAlignment(byte[] seq1, byte[] seq2, SWParameterSet namedParameters) {
        this(seq1, seq2, namedParameters.parameters);
    }

    public SWPairwiseAlignment(byte[] seq1, byte[] seq2) {
        this(seq1,seq2,SWParameterSet.ORIGINAL_DEFAULT);
    }

    @Override
    public Cigar getCigar() { return alignmentResult.cigar ; }

    @Override
    public int getAlignmentStart2wrt1() { return alignmentResult.alignment_offset; }

    /**
     * Aligns the alternate sequence to the reference sequence
     *
     * @param reference  ref sequence
     * @param alternate  alt sequence
     */
    protected void align(final byte[] reference, final byte[] alternate) {
        if ( reference == null || reference.length == 0 || alternate == null || alternate.length == 0 )
            throw new IllegalArgumentException("Non-null, non-empty sequences are required for the Smith-Waterman calculation");

        final int n = reference.length;
        final int m = alternate.length;
        double [] sw = new double[(n+1)*(m+1)];
        if ( keepScoringMatrix ) SW = sw;
        int [] btrack = new int[(n+1)*(m+1)];

        calculateMatrix(reference, alternate, sw, btrack);
        alignmentResult = calculateCigar(n, m, sw, btrack, overhang_strategy); // length of the segment (continuous matches, insertions or deletions)
    }

    /**
     * Calculates the SW matrices for the given sequences
     *
     * @param reference  ref sequence
     * @param alternate  alt sequence
     * @param sw         the Smith-Waterman matrix to populate
     * @param btrack     the back track matrix to populate
     */
    protected void calculateMatrix(final byte[] reference, final byte[] alternate, double[] sw, int[] btrack) {
        calculateMatrix(reference, alternate, sw, btrack, overhang_strategy);
    }

    /**
     * Calculates the SW matrices for the given sequences
     *
     * @param reference  ref sequence
     * @param alternate  alt sequence
     * @param sw         the Smith-Waterman matrix to populate
     * @param btrack     the back track matrix to populate
     * @param overhang_strategy    the strategy to use for dealing with overhangs
     */
    protected void calculateMatrix(final byte[] reference, final byte[] alternate, double[] sw, int[] btrack, final OVERHANG_STRATEGY overhang_strategy) {
        if ( reference.length == 0 || alternate.length == 0 )
            throw new IllegalArgumentException("Non-null, non-empty sequences are required for the Smith-Waterman calculation");

        final int n = reference.length+1;
        final int m = alternate.length+1;

        //final double MATRIX_MIN_CUTOFF=-1e100;   // never let matrix elements drop below this cutoff
        final double MATRIX_MIN_CUTOFF;   // never let matrix elements drop below this cutoff
        if ( cutoff ) MATRIX_MIN_CUTOFF = 0.0;
        else MATRIX_MIN_CUTOFF = -1e100;

        final double[] best_gap_v = new double[m+1];
        Arrays.fill(best_gap_v, -1.0e40);
        final int[] gap_size_v = new int[m+1];
        final double[] best_gap_h = new double[n+1];
        Arrays.fill(best_gap_h,-1.0e40);
        final int[] gap_size_h = new int[n+1];

        // we need to initialize the SW matrix with gap penalties if we want to keep track of indels at the edges of alignments
        if ( overhang_strategy == OVERHANG_STRATEGY.INDEL || overhang_strategy == OVERHANG_STRATEGY.LEADING_INDEL ) {
            // initialize the first row
            sw[1] = parameters.w_open;
            double currentValue = parameters.w_open;
            for ( int i = 2; i < m; i++ ) {
                currentValue += parameters.w_extend;
                sw[i] = currentValue;
            }

            // initialize the first column
            sw[m] = parameters.w_open;
            currentValue = parameters.w_open;
            for ( int i = 2; i < n; i++ ) {
                currentValue += parameters.w_extend;
                sw[i*m] = currentValue;
            }
        }

        // build smith-waterman matrix and keep backtrack info:
        for ( int i = 1, row_offset_1 = 0 ; i < n ; i++ ) { // we do NOT update row_offset_1 here, see comment at the end of this outer loop
            byte a_base = reference[i-1]; // letter in a at the current pos

            final int row_offset = row_offset_1 + m;

            // On the entrance into the loop, row_offset_1 is the (linear) offset
            // of the first element of row (i-1) and row_offset is the linear offset of the
            // start of row i

            for ( int j = 1, data_offset_1 = row_offset_1 ; j < m ; j++, data_offset_1++ ) {

                // data_offset_1 is linearized offset of element [i-1][j-1]

                final byte b_base = alternate[j-1]; // letter in b at the current pos

                // in other words, step_diag = sw[i-1][j-1] + wd(a_base,b_base);
                final double step_diag = sw[data_offset_1] + wd(a_base,b_base);

                // optimized "traversal" of all the matrix cells above the current one (i.e. traversing
                // all 'step down' events that would end in the current cell. The optimized code
                // does exactly the same thing as the commented out loop below. IMPORTANT:
                // the optimization works ONLY for linear w(k)=wopen+(k-1)*wextend!!!!

                // if a gap (length 1) was just opened above, this is the cost of arriving to the current cell:
                double prev_gap = sw[data_offset_1+1]+parameters.w_open;

                best_gap_v[j] += parameters.w_extend; // for the gaps that were already opened earlier, extending them by 1 costs w_extend

                if ( prev_gap > best_gap_v[j] ) {
                    // opening a gap just before the current cell results in better score than extending by one
                    // the best previously opened gap. This will hold for ALL cells below: since any gap
                    // once opened always costs w_extend to extend by another base, we will always get a better score
                    // by arriving to any cell below from the gap we just opened (prev_gap) rather than from the previous best gap
                    best_gap_v[j] = prev_gap;
                    gap_size_v[j] = 1; // remember that the best step-down gap from above has length 1 (we just opened it)
                } else {
                    // previous best gap is still the best, even after extension by another base, so we just record that extension:
                    gap_size_v[j]++;
                }

                final double step_down = best_gap_v[j] ;
                final int kd = gap_size_v[j];

                // optimized "traversal" of all the matrix cells to the left of the current one (i.e. traversing
                // all 'step right' events that would end in the current cell. The optimized code
                // does exactly the same thing as the commented out loop below. IMPORTANT:
                // the optimization works ONLY for linear w(k)=wopen+(k-1)*wextend!!!!

                final int data_offset = row_offset + j; // linearized offset of element [i][j]
                prev_gap = sw[data_offset-1]+parameters.w_open; // what would it cost us to open length 1 gap just to the left from current cell
                best_gap_h[i] += parameters.w_extend; // previous best gap would cost us that much if extended by another base

                if ( prev_gap > best_gap_h[i] ) {
                    // newly opened gap is better (score-wise) than any previous gap with the same row index i; since
                    // gap penalty is linear with k, this new gap location is going to remain better than any previous ones
                    best_gap_h[i] = prev_gap;
                    gap_size_h[i] = 1;
                } else {
                    gap_size_h[i]++;
                }

                final double step_right = best_gap_h[i];
                final int ki = gap_size_h[i];

                if ( step_down > step_right ) {
                    if ( step_down > step_diag ) {
                        sw[data_offset] = Math.max(MATRIX_MIN_CUTOFF,step_down);
                        btrack[data_offset] = kd ; // positive=vertical
                    } else {
                        sw[data_offset] = Math.max(MATRIX_MIN_CUTOFF,step_diag);
                        btrack[data_offset] = 0; // 0 = diagonal
                    }
                } else {
                    // step_down <= step_right
                    if ( step_right > step_diag ) {
                        sw[data_offset] = Math.max(MATRIX_MIN_CUTOFF,step_right);
                        btrack[data_offset] = -ki; // negative = horizontal
                    } else {
                        sw[data_offset] = Math.max(MATRIX_MIN_CUTOFF,step_diag);
                        btrack[data_offset] = 0; // 0 = diagonal
                    }
                }
            }

            // IMPORTANT, IMPORTANT, IMPORTANT:
            // note that we update this (secondary) outer loop variable here,
            // so that we DO NOT need to update it
            // in the for() statement itself.
            row_offset_1 = row_offset;
        }
    }

    /*
     * Class to store the result of calculating the CIGAR from the back track matrix
     */
    protected final class SWPairwiseAlignmentResult {
        public final Cigar cigar;
        public final int alignment_offset;
        public SWPairwiseAlignmentResult(final Cigar cigar, final int alignment_offset) {
            this.cigar = cigar;
            this.alignment_offset = alignment_offset;
        }
    }

    /**
     * Calculates the CIGAR for the alignment from the back track matrix
     *
     * @param refLength            length of the reference sequence
     * @param altLength            length of the alternate sequence
     * @param sw                   the Smith-Waterman matrix to use
     * @param btrack               the back track matrix to use
     * @param overhang_strategy    the strategy to use for dealing with overhangs
     * @return non-null SWPairwiseAlignmentResult object
     */
    protected SWPairwiseAlignmentResult calculateCigar(final int refLength, final int altLength, final double[] sw, final int[] btrack, final OVERHANG_STRATEGY overhang_strategy) {
        // p holds the position we start backtracking from; we will be assembling a cigar in the backwards order
        int p1 = 0, p2 = 0;

        double maxscore = Double.NEGATIVE_INFINITY; // sw scores are allowed to be negative
        int segment_length = 0; // length of the segment (continuous matches, insertions or deletions)

        // if we want to consider overhangs as legitimate operators, then just start from the corner of the matrix
        if ( overhang_strategy == OVERHANG_STRATEGY.INDEL ) {
            p1 = refLength;
            p2 = altLength;
        } else {
            // look for the largest score on the rightmost column. we use >= combined with the traversal direction
            // to ensure that if two scores are equal, the one closer to diagonal gets picked
            for ( int i = 1, data_offset = altLength+1+altLength ; i < refLength+1 ; i++, data_offset += (altLength+1) ) {
                // data_offset is the offset of [i][m]
                if ( sw[data_offset] >= maxscore ) {
                    p1 = i; p2 = altLength ; maxscore = sw[data_offset];
                }
            }

            // now look for a larger score on the bottom-most row
            if ( overhang_strategy != OVERHANG_STRATEGY.LEADING_INDEL ) {
                for ( int j = 1, data_offset = refLength*(altLength+1)+1 ; j < altLength+1 ; j++, data_offset++ ) {
                    // data_offset is the offset of [n][j]
                    if ( sw[data_offset] > maxscore || sw[data_offset] == maxscore && Math.abs(refLength-j) < Math.abs(p1 - p2)) {
                        p1 = refLength;
                        p2 = j ;
                        maxscore = sw[data_offset];
                        segment_length = altLength - j ; // end of sequence 2 is overhanging; we will just record it as 'M' segment
                    }
                }
            }
        }

        final List<CigarElement> lce = new ArrayList<CigarElement>(5);

        if ( segment_length > 0 && overhang_strategy == OVERHANG_STRATEGY.SOFTCLIP ) {
            lce.add(makeElement(State.CLIP, segment_length));
            segment_length = 0;
        }

        // we will be placing all insertions and deletions into sequence b, so the states are named w/regard
        // to that sequence

        State state = State.MATCH;

        int data_offset = p1*(altLength+1)+p2;  // offset of element [p1][p2]
        do {
            int btr = btrack[data_offset];

            State new_state;
            int step_length = 1;

            if ( btr > 0 ) {
                new_state = State.DELETION;
                step_length = btr;
            } else if ( btr < 0 ) {
                new_state = State.INSERTION;
                step_length = (-btr);
            } else new_state = State.MATCH; // and step_length =1, already set above

            // move to next best location in the sw matrix:
            switch( new_state ) {
                case MATCH: data_offset -= (altLength+2); p1--; p2--; break; // move back along the diag in the sw matrix
                case INSERTION: data_offset -= step_length; p2 -= step_length; break; // move left
                case DELETION: data_offset -= (altLength+1)*step_length; p1 -= step_length; break; // move up
            }

            // now let's see if the state actually changed:
            if ( new_state == state ) segment_length+=step_length;
            else {
                // state changed, lets emit previous segment, whatever it was (Insertion Deletion, or (Mis)Match).
                lce.add(makeElement(state, segment_length));
                segment_length = step_length;
                state = new_state;
            }
        // next condition is equivalent to  while ( sw[p1][p2] != 0 ) (with modified p1 and/or p2:
        } while ( p1 > 0 && p2 > 0 );

        // post-process the last segment we are still keeping;
        // NOTE: if reads "overhangs" the ref on the left (i.e. if p2>0) we are counting
        // those extra bases sticking out of the ref into the first cigar element if DO_SOFTCLIP is false;
        // otherwise they will be softclipped. For instance,
        // if read length is 5 and alignment starts at offset -2 (i.e. read starts before the ref, and only
        // last 3 bases of the read overlap with/align to the ref), the cigar will be still 5M if
        // DO_SOFTCLIP is false or 2S3M if DO_SOFTCLIP is true.
        // The consumers need to check for the alignment offset and deal with it properly.
        final int alignment_offset;
        if ( overhang_strategy == OVERHANG_STRATEGY.SOFTCLIP ) {
            lce.add(makeElement(state, segment_length));
            if ( p2 > 0 ) lce.add(makeElement(State.CLIP, p2));
            alignment_offset = p1;
        } else if ( overhang_strategy == OVERHANG_STRATEGY.IGNORE ) {
            lce.add(makeElement(state, segment_length + p2));
            alignment_offset = p1 - p2;
        } else {  // overhang_strategy == OVERHANG_STRATEGY.INDEL || overhang_strategy == OVERHANG_STRATEGY.LEADING_INDEL

            // take care of the actual alignment
            lce.add(makeElement(state, segment_length));

            // take care of overhangs at the beginning of the alignment
            if ( p1 > 0 )
                lce.add(makeElement(State.DELETION, p1));
            else if ( p2 > 0 )
                lce.add(makeElement(State.INSERTION, p2));

            alignment_offset = 0;
        }

        Collections.reverse(lce);
        return new SWPairwiseAlignmentResult(AlignmentUtils.consolidateCigar(new Cigar(lce)), alignment_offset);
    }

    protected CigarElement makeElement(final State state, final int length) {
        CigarOperator op = null;
        switch (state) {
            case MATCH: op = CigarOperator.M; break;
            case INSERTION: op = CigarOperator.I; break;
            case DELETION: op = CigarOperator.D; break;
            case CLIP: op = CigarOperator.S; break;
        }
        return new CigarElement(length, op);
    }

    private double wd(byte x, byte y) {
        return (x == y ? parameters.w_match : parameters.w_mismatch);
    }

    public void printAlignment(byte[] ref, byte[] read) {
        printAlignment(ref,read,100);
    }
    
    public void printAlignment(byte[] ref, byte[] read, int width) {
        StringBuilder bread = new StringBuilder();
        StringBuilder bref = new StringBuilder();
        StringBuilder match = new StringBuilder();

        int i = 0;
        int j = 0;

        final int offset = getAlignmentStart2wrt1();

        Cigar cigar = getCigar();

        if ( overhang_strategy != OVERHANG_STRATEGY.SOFTCLIP ) {

            // we need to go through all the hassle below only if we do not do softclipping;
            // otherwise offset is never negative
            if ( offset < 0 ) {
                for (  ; j < (-offset) ; j++ ) {
                    bread.append((char)read[j]);
                    bref.append(' ');
                    match.append(' ');
                }
                // at negative offsets, our cigar's first element carries overhanging bases
                // that we have just printed above. Tweak the first element to
                // exclude those bases. Here we create a new list of cigar elements, so the original
                // list/original cigar are unchanged (they are unmodifiable anyway!)

                List<CigarElement> tweaked = new ArrayList<CigarElement>();
                tweaked.addAll(cigar.getCigarElements());
                tweaked.set(0,new CigarElement(cigar.getCigarElement(0).getLength()+offset,
                        cigar.getCigarElement(0).getOperator()));
                cigar = new Cigar(tweaked);
            }
        }

        if ( offset > 0 ) { // note: the way this implementation works, cigar will ever start from S *only* if read starts before the ref, i.e. offset = 0
            for (  ; i < getAlignmentStart2wrt1() ; i++ ) {
                bref.append((char)ref[i]);
                bread.append(' ');
                match.append(' ');
            }
        }
        
        for ( CigarElement e : cigar.getCigarElements() ) {
            switch (e.getOperator()) {
                case M :
                    for ( int z = 0 ; z < e.getLength() ; z++, i++, j++  ) {
                        bref.append((i<ref.length)?(char)ref[i]:' ');
                        bread.append((j < read.length)?(char)read[j]:' ');
                        match.append( ( i<ref.length && j < read.length ) ? (ref[i] == read[j] ? '.':'*' ) : ' ' );
                    }
                    break;
                case I :
                    for ( int z = 0 ; z < e.getLength(); z++, j++ ) {
                        bref.append('-');
                        bread.append((char)read[j]);
                        match.append('I');
                    }
                    break;
                case S :
                    for ( int z = 0 ; z < e.getLength(); z++, j++ ) {
                        bref.append(' ');
                        bread.append((char)read[j]);
                        match.append('S');
                    }
                    break;
                case D:
                    for ( int z = 0 ; z < e.getLength(); z++ , i++ ) {
                        bref.append((char)ref[i]);
                        bread.append('-');
                        match.append('D');
                    }
                    break;
                default:
                    throw new StingException("Unexpected Cigar element:" + e.getOperator());
            }
        }
        for ( ; i < ref.length; i++ ) bref.append((char)ref[i]);
        for ( ; j < read.length; j++ ) bread.append((char)read[j]);

        int pos = 0 ;
        int maxlength = Math.max(match.length(),Math.max(bread.length(),bref.length()));
        while ( pos < maxlength ) {
            print_cautiously(match,pos,width);
            print_cautiously(bread,pos,width);
            print_cautiously(bref,pos,width);
            System.out.println();
            pos += width;
        }
    }

    /** String builder's substring is extremely stupid: instead of trimming and/or returning an empty
     * string when one end/both ends of the interval are out of range, it crashes with an
     * exception. This utility function simply prints the substring if the interval is within the index range
     * or trims accordingly if it is not.
     * @param s
     * @param start
     * @param width
     */
    private static void print_cautiously(StringBuilder s, int start, int width) {
        if ( start >= s.length() ) {
            System.out.println();
            return;
        }
        int end = Math.min(start+width,s.length());
        System.out.println(s.substring(start,end));
    }
}
