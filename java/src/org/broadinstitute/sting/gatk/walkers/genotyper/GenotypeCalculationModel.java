package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.utils.genotype.GenotypeWriter;

import java.util.*;

/**
 * The model representing how we calculate a genotype given the priors and a pile
 * of bases and quality scores
 */
public abstract class GenotypeCalculationModel implements Cloneable {

    public enum Model {
        SINGLE_SAMPLE,
        MULTI_SAMPLE_EM,
        MULTI_SAMPLE_ALL_MAFS
    }

    protected BaseMismatchModel baseModel;
    protected Set<String> samples;
    protected EmpiricalSubstitutionGenotypeLikelihoods.SequencerPlatform defaultPlatform;
    protected GenotypeWriter out;
    protected boolean GENOTYPE_MODE;
    protected double LOD_THRESHOLD;
    protected int maxDeletionsInPileup;
    protected boolean VERBOSE;

    /**
     * Create a new GenotypeCalculationModel object with given debugging level
     * Assumes that out is not null
     * @param baseModel     base model to use
     * @param samples       samples in input bam
     * @param platform      default platform
     * @param out           output writer
     * @param genotypeMode  genotyping
     * @param lod           lod threshold
     * @param maxDeletions  max deletions to tolerate
     * @param verbose       verbose flag
     */
    public GenotypeCalculationModel(BaseMismatchModel baseModel,
                                    Set<String> samples,
                                    EmpiricalSubstitutionGenotypeLikelihoods.SequencerPlatform platform,
                                    GenotypeWriter out,
                                    boolean genotypeMode,
                                    double lod,
                                    int maxDeletions,
                                    boolean verbose) {
        this.baseModel = baseModel;
        this.samples = samples;
        defaultPlatform = platform;
        this.out = out;
        GENOTYPE_MODE = genotypeMode;
        LOD_THRESHOLD = lod;
        maxDeletionsInPileup = maxDeletions;
        VERBOSE = verbose;
    }

    /**
     * Cloning of the object
     * @return clone
     * @throws CloneNotSupportedException
     */
    protected Object clone() throws CloneNotSupportedException {
        GenotypeCalculationModel gcm = (GenotypeCalculationModel)super.clone();
        gcm.baseModel = baseModel;
        gcm.samples = new HashSet<String>(samples);
        gcm.defaultPlatform = defaultPlatform;
        gcm.out = out;
        gcm.GENOTYPE_MODE = GENOTYPE_MODE;
        gcm.LOD_THRESHOLD = LOD_THRESHOLD;
        gcm.maxDeletionsInPileup = maxDeletionsInPileup;
        gcm.VERBOSE = VERBOSE;
        return gcm;
    }

    /**
     * Must be overridden by concrete subclasses
     * @param ref       reference base
     * @param context   alignment context
     * @param priors    priors to use for GL
     *
     * @return true if valid locus
     */
    public abstract boolean calculateGenotype(RefMetaDataTracker tracker,
                                              char ref,
                                              AlignmentContext context,
                                              DiploidGenotypePriors priors);
}