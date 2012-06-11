/*
 * Copyright (c) 2010 The Broad Institute
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
 * NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers;

import net.sf.samtools.*;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.utils.sam.ReadUtils;

import java.util.*;

/**
 * Divides the input data set into two BAM files, one with reads that contain soft clipping in the CIGAR. The split files are named: outputRoot + "_sc.bam" and outputRoot + ".bam"
 */
@WalkerName("SplitSAMBySoftClip")
@Requires({DataSource.READS})
public class SplitSAMBySoftClipPresenceFileWalker extends ReadPairWalker<Collection<SAMRecord>, Map<String, SAMFileWriter>> {
    @Argument(fullName="outputRoot", doc="output BAM file", required=true)
    public String outputRoot = null;

    @Argument(fullName = "bam_compression", shortName = "compress", doc = "Compression level to use for writing BAM files", required = false)
    public Integer BAMCompression = 5;

    private static Logger logger = Logger.getLogger(SplitSAMBySoftClipPresenceFileWalker.class);
    private static String VERSION = "0.0.1";

    public void initialize() {
        logger.info("SplitSAMBySoftClip version: " + VERSION);
    }

    // --------------------------------------------------------------------------------------------------------------
    //
    // Standard I/O routines
    //
    // --------------------------------------------------------------------------------------------------------------
    public void onTraversalDone(Map<String, SAMFileWriter> outputs) {
        for ( SAMFileWriter output : outputs.values() ) {
            output.close();
        }
    }

    @Override
    public Collection<SAMRecord> map(Collection<SAMRecord> reads) {
        return reads;
    }

    public Map<String, SAMFileWriter> reduceInit() {

        //Copy header from original file
        SAMFileHeader header = duplicateSAMFileHeader(this.getToolkit().getSAMFileHeader());

        //Create the output files
        final String softClipFilename = outputRoot  + "_sc.bam";
        final String noSoftClipFilename = outputRoot  + ".bam";

        logger.info(String.format("Creating BAM output file %s for reads with soft clipping", softClipFilename));
        SAMFileWriter softClipOutput = ReadUtils.createSAMFileWriterWithCompression(header, true, softClipFilename, BAMCompression);

        logger.info(String.format("Creating BAM output file %s for reads without soft clipping", noSoftClipFilename));
        SAMFileWriter noSoftClipOutput = ReadUtils.createSAMFileWriterWithCompression(header, true, noSoftClipFilename, BAMCompression);

        HashMap<String, SAMFileWriter> outputs = new HashMap<String, SAMFileWriter>();
        outputs.put("noSoftClip", noSoftClipOutput);
        outputs.put("softClip", softClipOutput);

        return outputs;

    }

    /**
     * Write out the read
     */
    public Map<String, SAMFileWriter> reduce(Collection<SAMRecord> reads, Map<String, SAMFileWriter> outputs) {


        String outputKey = "noSoftClip";

        for (SAMRecord read : reads) {
            //Find out if read contains soft clipping in CIGAR
            Cigar cigar = read.getCigar();

            for(CigarElement cigarElement : cigar.getCigarElements()) {
                if (cigarElement.getOperator().equals(CigarOperator.SOFT_CLIP)) {
                    outputKey = "softClip";
                    break;
                }
            }
        }

        SAMFileWriter output = outputs.get(outputKey);

        if ( output != null ) {
            for (SAMRecord read : reads) {
                output.addAlignment(read);
            }
        } else {
            throw new RuntimeException(String.format("Could not find output with key %s", outputKey));
        }

        return outputs;
    }

    public static SAMFileHeader duplicateSAMFileHeader(SAMFileHeader toCopy) {
        SAMFileHeader copy = new SAMFileHeader();

        copy.setGroupOrder(toCopy.getGroupOrder());
        copy.setProgramRecords(toCopy.getProgramRecords());
        copy.setReadGroups(toCopy.getReadGroups());
        copy.setSequenceDictionary(toCopy.getSequenceDictionary());

        for (Map.Entry<String, String> e : toCopy.getAttributes())
            copy.setAttribute(e.getKey(), e.getValue());

        //Force "queryname" sort order
        copy.setSortOrder(SAMFileHeader.SortOrder.unsorted);

        return copy;
    }

}