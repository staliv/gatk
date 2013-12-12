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

package org.broadinstitute.sting.gatk.walkers.variantutils;

import net.sf.picard.liftover.LiftOver;
import net.sf.picard.util.Interval;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.ArgumentCollection;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.arguments.StandardVariantContextInputArgumentCollection;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.sting.utils.help.HelpConstants;
import org.broadinstitute.sting.utils.variant.GATKVCFUtils;
import org.broadinstitute.sting.utils.variant.GATKVariantContextUtils;
import org.broadinstitute.variant.variantcontext.writer.Options;
import org.broadinstitute.variant.vcf.*;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.help.DocumentedGATKFeature;
import org.broadinstitute.variant.variantcontext.VariantContext;
import org.broadinstitute.variant.variantcontext.VariantContextBuilder;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriterFactory;

import java.io.File;
import java.util.*;

/**
 * Lifts a VCF file over from one build to another.
 *
 * "Lifting over" variants means adjusting variant calls from one reference to another. Specifically, the process adjusts the position of the call to match the corresponding position on the target reference.
 * For example, if you have variants called from reads aligned to the hg19 reference, and you want to compare them to calls made based on the b37 reference, you need to liftover one of the callsets to the other reference.
 *
 * LiftoverVariants is intended to be the first of two processing steps for the liftover process.
 * The second step is to run FilterLiftedVariants on the output of LiftoverVariants. This will produce valid well-behaved VCF files, where you'll see that the contig names in the header have all been correctly replaced.
 *
 * To be clear, the VCF resulting from the LiftoverVariants run is not guaranteed to be valid according to the official specification.  The file could
 * possibly be mis-sorted and the header may not be complete. That is why you need to run FilterLiftedVariants on it.
 */
@DocumentedGATKFeature( groupName = HelpConstants.DOCS_CAT_VARMANIP, extraDocs = {CommandLineGATK.class} )
public class LiftoverVariants extends RodWalker<Integer, Integer> {

    @ArgumentCollection
    protected StandardVariantContextInputArgumentCollection variantCollection = new StandardVariantContextInputArgumentCollection();

    @Output(doc="File to which variants should be written", required=true, defaultToStdout=false)
    protected File file = null;
    protected VariantContextWriter writer = null;

    @Argument(fullName="chain", shortName="chain", doc="Chain file", required=true)
    protected File CHAIN = null;

    @Argument(fullName="newSequenceDictionary", shortName="dict", doc="Sequence .dict file for the new build", required=true)
    protected File NEW_SEQ_DICT = null;

    @Argument(fullName="recordOriginalLocation", shortName="recordOriginalLocation", doc="Should we record what the original location was in the INFO field?", required=false)
    protected Boolean RECORD_ORIGINAL_LOCATION = false;

    private LiftOver liftOver;

    private long successfulIntervals = 0, failedIntervals = 0;

    public void initialize() {
        try {
            liftOver = new LiftOver(CHAIN);
        } catch (RuntimeException e) {
            throw new UserException.BadInput("there is a problem with the chain file you are using: " + e.getMessage());
        }

        liftOver.setLiftOverMinMatch(LiftOver.DEFAULT_LIFTOVER_MINMATCH);

        try {
            final SAMFileHeader toHeader = new SAMFileReader(NEW_SEQ_DICT).getFileHeader();
            liftOver.validateToSequences(toHeader.getSequenceDictionary());
        } catch (RuntimeException e) {
            throw new UserException.BadInput("the chain file you are using is not compatible with the reference you are trying to lift over to; please use the appropriate chain file for the given reference");    
        }

        String trackName = variantCollection.variants.getName();
        Set<String> samples = SampleUtils.getSampleListWithVCFHeader(getToolkit(), Arrays.asList(trackName));
        Map<String, VCFHeader> vcfHeaders = GATKVCFUtils.getVCFHeadersFromRods(getToolkit(), Arrays.asList(trackName));

        Set<VCFHeaderLine> metaData = new HashSet<VCFHeaderLine>();
        if ( vcfHeaders.containsKey(trackName) )
            metaData.addAll(vcfHeaders.get(trackName).getMetaDataInSortedOrder());
        if ( RECORD_ORIGINAL_LOCATION ) {
            metaData.add(new VCFInfoHeaderLine("OriginalChr", 1, VCFHeaderLineType.String, "Original contig name for the record"));
            metaData.add(new VCFInfoHeaderLine("OriginalStart", 1, VCFHeaderLineType.Integer, "Original start position for the record"));
        }


        final VCFHeader vcfHeader = new VCFHeader(metaData, samples);
        writer = VariantContextWriterFactory.create(file, getMasterSequenceDictionary(), EnumSet.of(Options.ALLOW_MISSING_FIELDS_IN_HEADER));
        writer.writeHeader(vcfHeader);
    }

    private void convertAndWrite(VariantContext vc, ReferenceContext ref) {

        final Interval fromInterval = new Interval(vc.getChr(), vc.getStart(), vc.getStart(), false, String.format("%s:%d", vc.getChr(), vc.getStart()));
        final int length = vc.getEnd() - vc.getStart();
        final Interval toInterval = liftOver.liftOver(fromInterval);
        VariantContext originalVC = vc;

        if ( toInterval != null ) {
            // check whether the strand flips, and if so reverse complement everything
            if ( fromInterval.isPositiveStrand() != toInterval.isPositiveStrand() && vc.isPointEvent() ) {
                vc = GATKVariantContextUtils.reverseComplement(vc);
            }

            vc = new VariantContextBuilder(vc).loc(toInterval.getSequence(), toInterval.getStart(), toInterval.getStart() + length).make();

            if ( RECORD_ORIGINAL_LOCATION ) {
                vc = new VariantContextBuilder(vc)
                        .attribute("OriginalChr", fromInterval.getSequence())
                        .attribute("OriginalStart", fromInterval.getStart()).make();
            }

            if ( originalVC.isSNP() && originalVC.isBiallelic() && GATKVariantContextUtils.getSNPSubstitutionType(originalVC) != GATKVariantContextUtils.getSNPSubstitutionType(vc) ) {
                logger.warn(String.format("VCF at %s / %d => %s / %d is switching substitution type %s/%s to %s/%s",
                        originalVC.getChr(), originalVC.getStart(), vc.getChr(), vc.getStart(),
                        originalVC.getReference(), originalVC.getAlternateAllele(0), vc.getReference(), vc.getAlternateAllele(0)));
            }

            writer.add(vc);
            successfulIntervals++;
        } else {
            failedIntervals++;
        }
    }

    public Integer map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null )
            return 0;

        Collection<VariantContext> VCs = tracker.getValues(variantCollection.variants, context.getLocation());
        for ( VariantContext vc : VCs )
            convertAndWrite(vc, ref);

        return 0;
    }

    public Integer reduceInit() { return 0; }

    public Integer reduce(Integer value, Integer sum) { return 0; }

    public void onTraversalDone(Integer result) {
        System.out.println("Converted " + successfulIntervals + " records; failed to convert " + failedIntervals + " records.");
        writer.close();
    }
}
