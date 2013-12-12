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

package org.broadinstitute.sting.gatk.walkers.annotator;

import org.broadinstitute.sting.commandline.*;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.arguments.DbsnpArgumentCollection;
import org.broadinstitute.sting.gatk.arguments.StandardVariantContextInputArgumentCollection;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.AlignmentContextUtils;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.downsampling.DownsampleType;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.*;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.*;
import org.broadinstitute.sting.utils.help.HelpConstants;
import org.broadinstitute.sting.utils.help.HelpUtils;
import org.broadinstitute.sting.utils.variant.GATKVCFUtils;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.variant.vcf.*;
import org.broadinstitute.sting.utils.help.DocumentedGATKFeature;
import org.broadinstitute.variant.variantcontext.VariantContext;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriter;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.*;

/**
 * Annotates variant calls with context information.
 *
 * <p>
 * VariantAnnotator is a GATK tool for annotating variant calls based on their context.
 * The tool is modular; new annotations can be written easily without modifying VariantAnnotator itself.
 *
 * <h3>Input</h3>
 * <p>
 * A variant set to annotate and optionally one or more BAM files.
 * </p>
 *
 * <h3>Output</h3>
 * <p>
 * An annotated VCF.
 * </p>
 *
 * <h3>Examples</h3>
 * <pre>
 * java -Xmx2g -jar GenomeAnalysisTK.jar \
 *   -R ref.fasta \
 *   -T VariantAnnotator \
 *   -I input.bam \
 *   -o output.vcf \
 *   -A Coverage \
 *   --variant input.vcf \
 *   -L input.vcf \
 *   --dbsnp dbsnp.vcf
 * </pre>
 *
 */
@DocumentedGATKFeature( groupName = HelpConstants.DOCS_CAT_VARMANIP, extraDocs = {CommandLineGATK.class} )
@Requires(value={})
@Allows(value={DataSource.READS, DataSource.REFERENCE})
@Reference(window=@Window(start=-50,stop=50))
@Downsample(by= DownsampleType.BY_SAMPLE, toCoverage=250)
@By(DataSource.REFERENCE)
public class VariantAnnotator extends RodWalker<Integer, Integer> implements AnnotatorCompatible, TreeReducible<Integer> {

    @ArgumentCollection
    protected StandardVariantContextInputArgumentCollection variantCollection = new StandardVariantContextInputArgumentCollection();

    /**
     * The INFO field will be annotated with information on the most biologically-significant effect
     * listed in the SnpEff output file for each variant.
     */
    @Input(fullName="snpEffFile", shortName = "snpEffFile", doc="A SnpEff output file from which to add annotations", required=false)
    public RodBinding<VariantContext> snpEffFile;
    public RodBinding<VariantContext> getSnpEffRodBinding() { return snpEffFile; }

    /**
      * rsIDs from this file are used to populate the ID column of the output.  Also, the DB INFO flag will be set when appropriate.
      */
    @ArgumentCollection
    protected DbsnpArgumentCollection dbsnp = new DbsnpArgumentCollection();
    public RodBinding<VariantContext> getDbsnpRodBinding() { return dbsnp.dbsnp; }

    /**
      * If a record in the 'variant' track overlaps with a record from the provided comp track, the INFO field will be annotated
      *  as such in the output with the track name (e.g. -comp:FOO will have 'FOO' in the INFO field).  Records that are filtered in the comp track will be ignored.
      *  Note that 'dbSNP' has been special-cased (see the --dbsnp argument).
      */
    @Input(fullName="comp", shortName = "comp", doc="comparison VCF file", required=false)
    public List<RodBinding<VariantContext>> comps = Collections.emptyList();
    public List<RodBinding<VariantContext>> getCompRodBindings() { return comps; }

    /**
      * An external resource VCF file or files from which to annotate.
      *
      * One can add annotations from one of the resource VCFs to the output.
      * For example, if you want to annotate your 'variant' VCF with the AC field value from the rod bound to 'resource',
      * you can specify '-E resource.AC' and records in the output VCF will be annotated with 'resource.AC=N' when a record exists in that rod at the given position.
      * If multiple records in the rod overlap the given position, one is chosen arbitrarily.
      */
    @Input(fullName="resource", shortName = "resource", doc="external resource VCF file", required=false)
    public List<RodBinding<VariantContext>> resources = Collections.emptyList();
    public List<RodBinding<VariantContext>> getResourceRodBindings() { return resources; }

    @Output(doc="File to which variants should be written")
    protected VariantContextWriter vcfWriter = null;

    /**
     * See the -list argument to view available annotations.
     */
    @Argument(fullName="annotation", shortName="A", doc="One or more specific annotations to apply to variant calls", required=false)
    protected List<String> annotationsToUse = new ArrayList<>();

    /**
     * Note that this argument has higher priority than the -A or -G arguments,
     * so annotations will be excluded even if they are explicitly included with the other options.
     */
    @Argument(fullName="excludeAnnotation", shortName="XA", doc="One or more specific annotations to exclude", required=false)
    protected List<String> annotationsToExclude = new ArrayList<>();

    /**
     * If specified, all available annotations in the group will be applied. See the VariantAnnotator -list argument to view available groups.
     * Keep in mind that RODRequiringAnnotations are not intended to be used as a group, because they require specific ROD inputs.
     */
    @Argument(fullName="group", shortName="G", doc="One or more classes/groups of annotations to apply to variant calls", required=false)
    protected List<String> annotationGroupsToUse = new ArrayList<>();

    /**
     * This option enables you to add annotations from one VCF to another.
     *
     * For example, if you want to annotate your 'variant' VCF with the AC field value from the rod bound to 'resource',
     * you can specify '-E resource.AC' and records in the output VCF will be annotated with 'resource.AC=N' when a record exists in that rod at the given position.
     * If multiple records in the rod overlap the given position, one is chosen arbitrarily.
     */
    @Argument(fullName="expression", shortName="E", doc="One or more specific expressions to apply to variant calls; see documentation for more details", required=false)
    protected Set<String> expressionsToUse = new ObjectOpenHashSet();

    /**
     * Note that the -XL argument can be used along with this one to exclude annotations.
     */
    @Argument(fullName="useAllAnnotations", shortName="all", doc="Use all possible annotations (not for the faint of heart)", required=false)
    protected Boolean USE_ALL_ANNOTATIONS = false;

    /**
     * Note that the --list argument requires a fully resolved and correct command-line to work. As a simpler alternative, you can use ListAnnotations (see Help Utilities).
     */
    @Argument(fullName="list", shortName="ls", doc="List the available annotations and exit", required=false)
    protected Boolean LIST = false;

    /**
     * By default, the dbSNP ID is added only when the ID field in the variant VCF is empty.
     */
    @Argument(fullName="alwaysAppendDbsnpId", shortName="alwaysAppendDbsnpId", doc="In conjunction with the dbSNP binding, append the dbSNP ID even when the variant VCF already has the ID field populated", required=false)
    protected Boolean ALWAYS_APPEND_DBSNP_ID = false;
    public boolean alwaysAppendDbsnpId() { return ALWAYS_APPEND_DBSNP_ID; }

    @Argument(fullName="MendelViolationGenotypeQualityThreshold",shortName="mvq",required=false,doc="The genotype quality threshold in order to annotate mendelian violation ratio")
    public double minGenotypeQualityP = 0.0;

    private VariantAnnotatorEngine engine;

    /**
     * Prepare the output file and the list of available features.
     */
    public void initialize() {

        if ( LIST ) {
            HelpUtils.listAnnotations();
            System.exit(0);
        }

        // get the list of all sample names from the variant VCF input rod, if applicable
        final List<String> rodName = Arrays.asList(variantCollection.variants.getName());
        final Set<String> samples = SampleUtils.getUniqueSamplesFromRods(getToolkit(), rodName);

        if ( USE_ALL_ANNOTATIONS )
            engine = new VariantAnnotatorEngine(annotationsToExclude, this, getToolkit());
        else
            engine = new VariantAnnotatorEngine(annotationGroupsToUse, annotationsToUse, annotationsToExclude, this, getToolkit());
        engine.initializeExpressions(expressionsToUse);

        // setup the header fields
        // note that if any of the definitions conflict with our new ones, then we want to overwrite the old ones
        final Set<VCFHeaderLine> hInfo = new HashSet<>();
        hInfo.addAll(engine.getVCFAnnotationDescriptions());
        for ( final VCFHeaderLine line : GATKVCFUtils.getHeaderFields(getToolkit(), Arrays.asList(variantCollection.variants.getName())) ) {
            if ( isUniqueHeaderLine(line, hInfo) )
                hInfo.add(line);
        }
        // for the expressions, pull the info header line from the header of the resource rod
        for ( final VariantAnnotatorEngine.VAExpression expression : engine.getRequestedExpressions() ) {
            // special case the ID field
            if ( expression.fieldName.equals("ID") ) {
                hInfo.add(new VCFInfoHeaderLine(expression.fullName, 1, VCFHeaderLineType.String, "ID field transferred from external VCF resource"));
                continue;
            }
            VCFInfoHeaderLine targetHeaderLine = null;
            for ( final VCFHeaderLine line : GATKVCFUtils.getHeaderFields(getToolkit(), Arrays.asList(expression.binding.getName())) ) {
                if ( line instanceof VCFInfoHeaderLine ) {
                    final VCFInfoHeaderLine infoline = (VCFInfoHeaderLine)line;
                    if ( infoline.getID().equals(expression.fieldName) ) {
                        targetHeaderLine = infoline;
                        break;
                    }
                }
            }

            if ( targetHeaderLine != null ) {
                if ( targetHeaderLine.getCountType() == VCFHeaderLineCount.INTEGER )
                    hInfo.add(new VCFInfoHeaderLine(expression.fullName, targetHeaderLine.getCount(), targetHeaderLine.getType(), targetHeaderLine.getDescription()));
                else
                    hInfo.add(new VCFInfoHeaderLine(expression.fullName, targetHeaderLine.getCountType(), targetHeaderLine.getType(), targetHeaderLine.getDescription()));
            } else {
                hInfo.add(new VCFInfoHeaderLine(expression.fullName, VCFHeaderLineCount.UNBOUNDED, VCFHeaderLineType.String, "Value transferred from another external VCF resource"));
            }
        }

        engine.invokeAnnotationInitializationMethods(hInfo);

        VCFHeader vcfHeader = new VCFHeader(hInfo, samples);
        vcfWriter.writeHeader(vcfHeader);
    }

    public static boolean isUniqueHeaderLine(VCFHeaderLine line, Set<VCFHeaderLine> currentSet) {
        if ( !(line instanceof VCFCompoundHeaderLine) )
            return true;

        for ( VCFHeaderLine hLine : currentSet ) {
            if ( hLine instanceof VCFCompoundHeaderLine && ((VCFCompoundHeaderLine)line).sameLineTypeAndName((VCFCompoundHeaderLine)hLine) )
                return false;
        }

        return true;
    }

    /**
     * We want reads that span deletions
     *
     * @return true
     */
    public boolean includeReadsWithDeletionAtLoci() { return true; }

    /**
     * For each site of interest, annotate based on the requested annotation types
     *
     * @param tracker  the meta-data tracker
     * @param ref      the reference base
     * @param context  the context for the given locus
     * @return 1 if the locus was successfully processed, 0 if otherwise
     */
    public Integer map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null )
            return 0;

        Collection<VariantContext> VCs = tracker.getValues(variantCollection.variants, context.getLocation());
        if ( VCs.size() == 0 )
            return 0;

        Collection<VariantContext> annotatedVCs = VCs;

        // if the reference base is not ambiguous, we can annotate
        Map<String, AlignmentContext> stratifiedContexts;
        if ( BaseUtils.simpleBaseToBaseIndex(ref.getBase()) != -1 ) {
            stratifiedContexts = AlignmentContextUtils.splitContextBySampleName(context.getBasePileup());
            annotatedVCs = new ArrayList<>(VCs.size());
            for ( VariantContext vc : VCs )
                annotatedVCs.add(engine.annotateContext(tracker, ref, stratifiedContexts, vc));
        }

        for ( VariantContext annotatedVC : annotatedVCs )
            vcfWriter.add(annotatedVC);

        return 1;
    }

    @Override
    public Integer reduceInit() { return 0; }

    @Override
    public Integer reduce(Integer value, Integer sum) { return value + sum; }

    @Override
    public Integer treeReduce(Integer lhs, Integer rhs) {
        return lhs + rhs;
    }

    /**
     * Tell the user the number of loci processed and close out the new variants file.
     *
     * @param result  the number of loci seen.
     */
    public void onTraversalDone(Integer result) {
        logger.info("Processed " + result + " loci.\n");
    }
}
