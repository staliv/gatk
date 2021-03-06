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

package org.broadinstitute.sting.utils.activeregion;


// the imports for unit testing.


import net.sf.picard.reference.ReferenceSequenceFile;
import org.broadinstitute.sting.BaseTest;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.fasta.CachingIndexedFastaSequenceFile;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;


public class ActivityProfileUnitTest extends BaseTest {
    private final static boolean DEBUG = false;
    private GenomeLocParser genomeLocParser;
    private GenomeLoc startLoc;

    @BeforeClass
    public void init() throws FileNotFoundException {
        // sequence
        ReferenceSequenceFile seq = new CachingIndexedFastaSequenceFile(new File(hg18Reference));
        genomeLocParser = new GenomeLocParser(seq);
        startLoc = genomeLocParser.createGenomeLoc("chr1", 1, 1, 100);
    }

    // --------------------------------------------------------------------------------
    //
    // Basic tests Provider
    //
    // --------------------------------------------------------------------------------

    private class BasicActivityProfileTestProvider extends TestDataProvider {
        List<Double> probs;
        List<ActiveRegion> expectedRegions;
        int extension = 0;
        GenomeLoc regionStart = startLoc;
        final ProfileType type;

        public BasicActivityProfileTestProvider(final ProfileType type, final List<Double> probs, boolean startActive, int ... startsAndStops) {
            super(BasicActivityProfileTestProvider.class);
            this.type = type;
            this.probs = probs;
            this.expectedRegions = toRegions(startActive, startsAndStops);
            setName(getName());
        }

        private String getName() {
            return String.format("type=%s probs=%s expectedRegions=%s", type, Utils.join(",", probs), Utils.join(",", expectedRegions));
        }

        public ActivityProfile makeProfile() {
            switch ( type ) {
                case Base: return new ActivityProfile(genomeLocParser);
                case BandPass:
                    // zero size => equivalent to ActivityProfile
                    return new BandPassActivityProfile(genomeLocParser, null, 0, 0.01, false);
                default: throw new IllegalStateException(type.toString());
            }
        }

        private List<ActiveRegion> toRegions(boolean isActive, int[] startsAndStops) {
            List<ActiveRegion> l = new ArrayList<ActiveRegion>();
            for ( int i = 0; i < startsAndStops.length - 1; i++) {
                int start = regionStart.getStart() + startsAndStops[i];
                int end = regionStart.getStart() + startsAndStops[i+1] - 1;
                GenomeLoc activeLoc = genomeLocParser.createGenomeLoc(regionStart.getContig(), start, end);
                ActiveRegion r = new ActiveRegion(activeLoc, Collections.<ActivityProfileState>emptyList(), isActive, genomeLocParser, extension);
                l.add(r);
                isActive = ! isActive;
            }
            return l;
        }
    }

    private enum ProfileType {
        Base, BandPass
    }

    @DataProvider(name = "BasicActivityProfileTestProvider")
    public Object[][] makeQualIntervalTestProvider() {
        for ( final ProfileType type : ProfileType.values() ) {
            new BasicActivityProfileTestProvider(type, Arrays.asList(1.0), true, 0, 1);
            new BasicActivityProfileTestProvider(type, Arrays.asList(1.0, 0.0), true, 0, 1, 2);
            new BasicActivityProfileTestProvider(type, Arrays.asList(0.0, 1.0), false, 0, 1, 2);
            new BasicActivityProfileTestProvider(type, Arrays.asList(1.0, 0.0, 1.0), true, 0, 1, 2, 3);
            new BasicActivityProfileTestProvider(type, Arrays.asList(1.0, 1.0, 1.0), true, 0, 3);
        }

        return BasicActivityProfileTestProvider.getTests(BasicActivityProfileTestProvider.class);
    }

    @Test(enabled = ! DEBUG, dataProvider = "BasicActivityProfileTestProvider")
    public void testBasicActivityProfile(BasicActivityProfileTestProvider cfg) {
        ActivityProfile profile = cfg.makeProfile();

        Assert.assertTrue(profile.isEmpty());

        Assert.assertEquals(profile.parser, genomeLocParser);

        for ( int i = 0; i < cfg.probs.size(); i++ ) {
            double p = cfg.probs.get(i);
            GenomeLoc loc = genomeLocParser.createGenomeLoc(cfg.regionStart.getContig(), cfg.regionStart.getStart() + i, cfg.regionStart.getStart() + i);
            profile.add(new ActivityProfileState(loc, p));
            Assert.assertFalse(profile.isEmpty(), "Profile shouldn't be empty after adding a state");
        }
        Assert.assertEquals(profile.regionStartLoc, genomeLocParser.createGenomeLoc(cfg.regionStart.getContig(), cfg.regionStart.getStart(), cfg.regionStart.getStart() ), "Start loc should be the start of the region");

        Assert.assertEquals(profile.size(), cfg.probs.size(), "Should have exactly the number of states we expected to add");
        assertProbsAreEqual(profile.stateList, cfg.probs);

        // TODO -- reanble tests
        //assertRegionsAreEqual(profile.createActiveRegions(0, 100), cfg.expectedRegions);
    }

    private void assertRegionsAreEqual(List<ActiveRegion> actual, List<ActiveRegion> expected) {
        Assert.assertEquals(actual.size(), expected.size());
        for ( int i = 0; i < actual.size(); i++ ) {
            Assert.assertTrue(actual.get(i).equalExceptReads(expected.get(i)));
        }
    }

    private void assertProbsAreEqual(List<ActivityProfileState> actual, List<Double> expected) {
        Assert.assertEquals(actual.size(), expected.size());
        for ( int i = 0; i < actual.size(); i++ ) {
            Assert.assertEquals(actual.get(i).isActiveProb, expected.get(i));
        }
    }

    // -------------------------------------------------------------------------------------
    //
    // Hardcore tests for adding to the profile and constructing active regions
    //
    // -------------------------------------------------------------------------------------

    private static class SizeToStringList<T> extends ArrayList<T> {
        @Override public String toString() { return "List[" + size() + "]"; }
    }

    @DataProvider(name = "RegionCreationTests")
    public Object[][] makeRegionCreationTests() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        final int contigLength = genomeLocParser.getContigs().getSequences().get(0).getSequenceLength();
        for ( int start : Arrays.asList(1, 10, 100, contigLength - 100, contigLength - 10) ) {
            for ( int regionSize : Arrays.asList(1, 10, 100, 1000, 10000) ) {
                for ( int maxRegionSize : Arrays.asList(10, 50, 200) ) {
                    for ( final boolean waitUntilEnd : Arrays.asList(false, true) ) {
                        for ( final boolean forceConversion : Arrays.asList(false, true) ) {
                            // what do I really want to test here?  I'd like to test a few cases:
                            // -- region is all active (1.0)
                            // -- region is all inactive (0.0)
                            // -- cut the interval into 1, 2, 3, 4, 5 ... 10 regions, each with alternating activity values
                            for ( final boolean startWithActive : Arrays.asList(true, false) ) {
                                for ( int nParts : Arrays.asList(1, 2, 3, 4, 5, 7, 10, 11, 13) ) {

//        for ( int start : Arrays.asList(1) ) {
//            for ( int regionSize : Arrays.asList(100) ) {
//                for ( int maxRegionSize : Arrays.asList(10) ) {
//                    for ( final boolean waitUntilEnd : Arrays.asList(true) ) {
//                        for ( final boolean forceConversion : Arrays.asList(false) ) {
//                            for ( final boolean startWithActive : Arrays.asList(true) ) {
//                                for ( int nParts : Arrays.asList(3) ) {
                                    regionSize = Math.min(regionSize, contigLength - start);
                                    final List<Boolean> regions = makeRegions(regionSize, startWithActive, nParts);
                                    tests.add(new Object[]{ start, regions, maxRegionSize, nParts, forceConversion, waitUntilEnd });
                                }
                            }
                        }
                    }
                }
            }
        }

        return tests.toArray(new Object[][]{});
    }

    private List<Boolean> makeRegions(final int totalRegionSize,
                                      final boolean startWithActive,
                                      final int nParts) {
        final List<Boolean> regions = new SizeToStringList<Boolean>();

        boolean isActive = startWithActive;
        final int activeRegionSize = Math.max(totalRegionSize / nParts, 1);
        for ( int i = 0; i < totalRegionSize; i += activeRegionSize ) {
            for ( int j = 0; j < activeRegionSize && j + i < totalRegionSize; j++ ) {
                regions.add(isActive);
            }
            isActive = ! isActive;
        }

        return regions;
    }


    @Test(enabled = !DEBUG, dataProvider = "RegionCreationTests")
    public void testRegionCreation(final int start, final List<Boolean> probs, int maxRegionSize, final int nParts, final boolean forceConversion, final boolean waitUntilEnd) {
        final ActivityProfile profile = new ActivityProfile(genomeLocParser);
        Assert.assertNotNull(profile.toString());

        final String contig = genomeLocParser.getContigs().getSequences().get(0).getSequenceName();
        final List<Boolean> seenSites = new ArrayList<Boolean>(Collections.nCopies(probs.size(), false));
        ActiveRegion lastRegion = null;
        for ( int i = 0; i < probs.size(); i++ ) {
            final boolean isActive = probs.get(i);
            final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, i + start);
            final ActivityProfileState state = new ActivityProfileState(loc, isActive ? 1.0 : 0.0);
            profile.add(state);
            Assert.assertNotNull(profile.toString());

            if ( ! waitUntilEnd ) {
                final List<ActiveRegion> regions = profile.popReadyActiveRegions(0, 1, maxRegionSize, false);
                lastRegion = assertGoodRegions(start, regions, maxRegionSize, lastRegion, probs, seenSites);
            }
        }

        if ( waitUntilEnd || forceConversion ) {
            final List<ActiveRegion> regions = profile.popReadyActiveRegions(0, 1, maxRegionSize, forceConversion);
            lastRegion = assertGoodRegions(start, regions, maxRegionSize, lastRegion, probs, seenSites);
        }

        for ( int i = 0; i < probs.size(); i++ ) {
            if ( forceConversion || (i + maxRegionSize + profile.getMaxProbPropagationDistance() < probs.size()))
                // only require a site to be seen if we are forcing conversion or the site is more than maxRegionSize from the end
                Assert.assertTrue(seenSites.get(i), "Missed site " + i);
        }

        Assert.assertNotNull(profile.toString());
    }

    private ActiveRegion assertGoodRegions(final int start, final List<ActiveRegion> regions, final int maxRegionSize, ActiveRegion lastRegion, final List<Boolean> probs, final List<Boolean> seenSites) {
        for ( final ActiveRegion region : regions ) {
            Assert.assertTrue(region.getLocation().size() > 0, "Region " + region + " has a bad size");
            Assert.assertTrue(region.getLocation().size() <= maxRegionSize, "Region " + region + " has a bad size: it's big than the max region size " + maxRegionSize);
            if ( lastRegion != null ) {
                Assert.assertTrue(region.getLocation().getStart() == lastRegion.getLocation().getStop() + 1, "Region " + region + " doesn't start immediately after previous region" + lastRegion);
            }

            // check that all active bases are actually active
            final int regionOffset = region.getLocation().getStart() - start;
            Assert.assertTrue(regionOffset >= 0 && regionOffset < probs.size(), "Region " + region + " has a bad offset w.r.t. start");
            for ( int j = 0; j < region.getLocation().size(); j++ ) {
                final int siteOffset = j + regionOffset;
                Assert.assertEquals(region.isActive(), probs.get(siteOffset).booleanValue());
                Assert.assertFalse(seenSites.get(siteOffset), "Site " + j + " in " + region + " was seen already");
                seenSites.set(siteOffset, true);
            }

            lastRegion = region;
        }

        return lastRegion;
    }

    // -------------------------------------------------------------------------------------
    //
    // Hardcore tests for adding to the profile and constructing active regions
    //
    // -------------------------------------------------------------------------------------

    @DataProvider(name = "SoftClipsTest")
    public Object[][] makeSoftClipsTest() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        final int contigLength = genomeLocParser.getContigs().getSequences().get(0).getSequenceLength();
        for ( int start : Arrays.asList(1, 10, 100, contigLength - 100, contigLength - 10, contigLength - 1) ) {
            for ( int precedingSites: Arrays.asList(0, 1, 10) ) {
                if ( precedingSites + start < contigLength ) {
                    for ( int softClipSize : Arrays.asList(1, 2, 10, 100) ) {
//        for ( int start : Arrays.asList(10) ) {
//            for ( int precedingSites: Arrays.asList(10) ) {
//                for ( int softClipSize : Arrays.asList(1) ) {
                        tests.add(new Object[]{ start, precedingSites, softClipSize });
                    }
                }
            }
        }

        return tests.toArray(new Object[][]{});
    }

    @Test(enabled = ! DEBUG, dataProvider = "SoftClipsTest")
    public void testSoftClips(final int start, int nPrecedingSites, final int softClipSize) {
        final ActivityProfile profile = new ActivityProfile(genomeLocParser);

        final int contigLength = genomeLocParser.getContigs().getSequences().get(0).getSequenceLength();
        final String contig = genomeLocParser.getContigs().getSequences().get(0).getSequenceName();
        for ( int i = 0; i < nPrecedingSites; i++ ) {
            final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, i + start);
            final ActivityProfileState state = new ActivityProfileState(loc, 0.0);
            profile.add(state);
        }

        final GenomeLoc softClipLoc = genomeLocParser.createGenomeLoc(contig, nPrecedingSites + start);
        profile.add(new ActivityProfileState(softClipLoc, 1.0, ActivityProfileState.Type.HIGH_QUALITY_SOFT_CLIPS, softClipSize));

        final int actualNumOfSoftClips = Math.min(softClipSize, profile.getMaxProbPropagationDistance());
        if ( nPrecedingSites == 0 ) {
            final int profileSize = Math.min(start + actualNumOfSoftClips, contigLength) - start + 1;
            Assert.assertEquals(profile.size(), profileSize, "Wrong number of states in the profile");
        }

        for ( int i = 0; i < profile.size(); i++ ) {
            final ActivityProfileState state = profile.getStateList().get(i);
            final boolean withinSCRange = state.getLoc().distance(softClipLoc) <= actualNumOfSoftClips;
            if ( withinSCRange ) {
                Assert.assertTrue(state.isActiveProb > 0.0, "active prob should be changed within soft clip size");
            } else {
                Assert.assertEquals(state.isActiveProb, 0.0, "active prob shouldn't be changed outside of clip size");
            }
        }
    }

    // -------------------------------------------------------------------------------------
    //
    // Tests to ensure we cut large active regions in the right place
    //
    // -------------------------------------------------------------------------------------

    private void addProb(final List<Double> l, final double v) {
        l.add(v);
    }

    @DataProvider(name = "ActiveRegionCutTests")
    public Object[][] makeActiveRegionCutTests() {
        final List<Object[]> tests = new LinkedList<Object[]>();

//        for ( final int activeRegionSize : Arrays.asList(30) ) {
//            for ( final int minRegionSize : Arrays.asList(5) ) {
        for ( final int activeRegionSize : Arrays.asList(10, 12, 20, 30, 40) ) {
            for ( final int minRegionSize : Arrays.asList(1, 5, 10) ) {
                final int maxRegionSize = activeRegionSize * 2 / 3;
                if ( minRegionSize >= maxRegionSize ) continue;
                { // test flat activity profile
                    final List<Double> probs = Collections.nCopies(activeRegionSize, 1.0);
                    tests.add(new Object[]{minRegionSize, maxRegionSize, maxRegionSize, probs});
                }

                { // test point profile is properly handled
                    for ( int end = 1; end < activeRegionSize; end++ ) {
                        final List<Double> probs = Collections.nCopies(end, 1.0);
                        tests.add(new Object[]{minRegionSize, maxRegionSize, Math.min(end, maxRegionSize), probs});
                    }
                }

                { // test increasing activity profile
                    final List<Double> probs = new ArrayList<Double>(activeRegionSize);
                    for ( int i = 0; i < activeRegionSize; i++ ) {
                        addProb(probs, (1.0*(i+1))/ activeRegionSize);
                    }
                    tests.add(new Object[]{minRegionSize, maxRegionSize, maxRegionSize, probs});
                }

                { // test decreasing activity profile
                    final List<Double> probs = new ArrayList<Double>(activeRegionSize);
                    for ( int i = 0; i < activeRegionSize; i++ ) {
                        addProb(probs, 1 - (1.0*(i+1))/ activeRegionSize);
                    }
                    tests.add(new Object[]{minRegionSize, maxRegionSize, maxRegionSize, probs});
                }

                { // test two peaks
//                    for ( final double rootSigma : Arrays.asList(2.0) ) {
//                        int maxPeak1 = 9; {
//                            int maxPeak2 = 16; {
                    for ( final double rootSigma : Arrays.asList(1.0, 2.0, 3.0) ) {
                        for ( int maxPeak1 = 0; maxPeak1 < activeRegionSize / 2; maxPeak1++ ) {
                            for ( int maxPeak2 = activeRegionSize / 2 + 1; maxPeak2 < activeRegionSize; maxPeak2++ ) {
                                final double[] gauss1 = makeGaussian(maxPeak1, activeRegionSize, rootSigma);
                                final double[] gauss2 = makeGaussian(maxPeak2, activeRegionSize, rootSigma+1);
                                final List<Double> probs = new ArrayList<Double>(activeRegionSize);
                                for ( int i = 0; i < activeRegionSize; i++ ) {
                                    addProb(probs, gauss1[i] + gauss2[i]);
                                }
                                final int cutSite = findCutSiteForTwoMaxPeaks(probs, minRegionSize);
                                if ( cutSite != -1 && cutSite < maxRegionSize )
                                    tests.add(new Object[]{minRegionSize, maxRegionSize, Math.max(cutSite, minRegionSize), probs});
                            }
                        }
                    }
                }

                { // test that the lowest of two minima is taken
                    // looks like a bunch of 1s, 0.5, some 1.0s, 0.75, some more 1s
//                    int firstMin = 0; {
//                    int secondMin = 4; {
                    for ( int firstMin = 1; firstMin < activeRegionSize; firstMin++ ) {
                        for ( int secondMin = firstMin + 1; secondMin < activeRegionSize; secondMin++ ) {
                            final List<Double> probs = new ArrayList<Double>(Collections.nCopies(activeRegionSize, 1.0));
                            probs.set(firstMin, 0.5);
                            probs.set(secondMin, 0.75);
                            final int expectedCut;
                            if ( firstMin + 1 < minRegionSize ) {
                                if ( firstMin == secondMin - 1 ) // edge case for non-min at minRegionSize
                                    expectedCut = maxRegionSize;
                                else
                                    expectedCut = secondMin + 1 > maxRegionSize ? maxRegionSize : ( secondMin + 1 < minRegionSize ? maxRegionSize : secondMin + 1);
                            } else if ( firstMin + 1 > maxRegionSize )
                                expectedCut = maxRegionSize;
                            else {
                                expectedCut = firstMin + 1;
                            }

                            Math.min(firstMin + 1, maxRegionSize);
                            tests.add(new Object[]{minRegionSize, maxRegionSize, expectedCut, probs});
                        }
                    }
                }
            }
        }

        return tests.toArray(new Object[][]{});
    }

    private double[] makeGaussian(final int mean, final int range, final double sigma) {
        final double[] gauss = new double[range];
        for( int iii = 0; iii < range; iii++ ) {
            gauss[iii] = MathUtils.normalDistribution(mean, sigma, iii) + ActivityProfile.ACTIVE_PROB_THRESHOLD;
        }
        return gauss;
    }

    private int findCutSiteForTwoMaxPeaks(final List<Double> probs, final int minRegionSize) {
        for ( int i = probs.size() - 2; i > minRegionSize; i-- ) {
            double prev = probs.get(i - 1);
            double next = probs.get(i + 1);
            double cur = probs.get(i);
            if ( cur < next && cur < prev )
                return i + 1;
        }

        return -1;
    }

    @Test(dataProvider = "ActiveRegionCutTests")
    public void testActiveRegionCutTests(final int minRegionSize, final int maxRegionSize, final int expectedRegionSize, final List<Double> probs) {
        final ActivityProfile profile = new ActivityProfile(genomeLocParser);

        final String contig = genomeLocParser.getContigs().getSequences().get(0).getSequenceName();
        for ( int i = 0; i <= maxRegionSize + profile.getMaxProbPropagationDistance(); i++ ) {
            final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, i + 1);
            final double prob = i < probs.size() ? probs.get(i) : 0.0;
            final ActivityProfileState state = new ActivityProfileState(loc, prob);
            profile.add(state);
        }

        final List<ActiveRegion> regions = profile.popReadyActiveRegions(0, minRegionSize, maxRegionSize, false);
        Assert.assertTrue(regions.size() >= 1, "Should only be one regions for this test");
        final ActiveRegion region = regions.get(0);
        Assert.assertEquals(region.getLocation().getStart(), 1, "Region should start at 1");
        Assert.assertEquals(region.getLocation().size(), expectedRegionSize, "Incorrect region size; cut must have been incorrect");
    }
}