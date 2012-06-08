#Genome Analysis ToolKit, Lund Oncology Department Fork

##New walkers

###SplitSAMBySoftClip
Splits SAM/BAM files in two. One part with all the reads that contain soft clipping (outputRoot + "_sc.bam"). The other part contains all the reads that do not have any soft clipping in their CIGAR-strings (outputRoot + ".bam").

Example:

SAM file needs to have sort order queryname, use Picards `SamSort` to fix:

	java -Xmx4g -jar ./picard/SortSam.jar I=aligned.bam O=aligned.querynamesorted.bam SO=queryname

Split file into `aligned.querynamesorted.split.bam` and `aligned.querynamesorted.split_sc.bam`: 

	java -Xmx4g -jar ./GenomeAnalysisTK.jar -T SplitSAMBySoftClip -I aligned.querynamesorted.bam --outputRoot ./aligned.querynamesorted.split -R ./human_g1k_v37.fasta -U

(The tool requires GATK Unsafe mode because the sort order cannot be indexed)