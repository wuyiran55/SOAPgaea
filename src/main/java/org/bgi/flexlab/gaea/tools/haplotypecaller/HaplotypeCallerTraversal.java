package org.bgi.flexlab.gaea.tools.haplotypecaller;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.bgi.flexlab.gaea.data.mapreduce.input.bed.RegionHdfsParser;
import org.bgi.flexlab.gaea.data.mapreduce.output.vcf.GaeaVariantContextWriter;
import org.bgi.flexlab.gaea.data.mapreduce.writable.SamRecordWritable;
import org.bgi.flexlab.gaea.data.structure.bam.GaeaSamRecord;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocation;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocationParser;
import org.bgi.flexlab.gaea.data.structure.reference.ChromosomeInformationShare;
import org.bgi.flexlab.gaea.tools.haplotypecaller.argumentcollection.HaplotypeCallerArgumentCollection;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.ActivityProfileState;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.AssemblyRegion;
import org.bgi.flexlab.gaea.tools.haplotypecaller.downsampler.PositionalDownsampler;
import org.bgi.flexlab.gaea.tools.haplotypecaller.engine.HaplotypeCallerEngine;
import org.bgi.flexlab.gaea.tools.haplotypecaller.engine.VariantAnnotatorEngine;
import org.bgi.flexlab.gaea.tools.haplotypecaller.pileup.AssemblyRegionIterator;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.CountingReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.MappingQualityReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.ReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.ReadFilterLibrary;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.WellformedReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.RefMetaDataTracker;
import org.bgi.flexlab.gaea.tools.mapreduce.haplotypecaller.HaplotypeCallerOptions;
import org.bgi.flexlab.gaea.tools.vcfqualitycontrol2.util.GaeaVCFHeaderLines;
import org.bgi.flexlab.gaea.util.GaeaVCFConstants;
import org.bgi.flexlab.gaea.util.Utils;
import org.bgi.flexlab.gaea.util.Window;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.util.Locatable;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;

public class HaplotypeCallerTraversal {
	private static final int READ_QUALITY_FILTER_THRESHOLD = 20;
	
	// intervals of windows
	private List<GenomeLocation> intervals = null;

	// hc engine
	private HaplotypeCallerEngine hcEngine = null;

	// options
	private HaplotypeCallerOptions options = null;

	// reference
	private ChromosomeInformationShare ref = null;

	// region
	private RegionHdfsParser region = null;

	// reads data source
	private ReadsDataSource readsSource = null;

	// sam file header
	private SAMFileHeader header = null;

	// assembly region output stream
	private PrintStream assemblyRegionOutStream = null;

	// activity profile output stream
	private PrintStream activityProfileOutStream = null;

	public static final int NO_INTERVAL_SHARDING = -1;

	private final List<LocalReadShard> shards = new ArrayList<>();

	private Shard<GaeaSamRecord> currentReadShard;

	private int maxProbPropagationDistance = 50;

	private double activeProbThreshold = 0.002;

	private int assemblyRegionPadding = 100;

	private int maxAssemblyRegionSize = 300;

	private int minAssemblyRegionSize = 50;

	private int maxReadsPerAlignmentStart = 0;

	private RefMetaDataTracker features = null;

	private final Map<String, ReadFilter> toolDefaultReadFilters = new LinkedHashMap<>();

	private final Map<String, ReadFilter> allDiscoveredReadFilters = new LinkedHashMap<>();

	private HaplotypeCallerArgumentCollection hcArgs = null;

	private VCFHeader vcfHeader = null;

	private boolean doNotRunPhysicalPhasing = false;

	public HaplotypeCallerTraversal(RegionHdfsParser region, HaplotypeCallerOptions options, SAMFileHeader header) {
		this.options = options;
		this.region = region;
		this.header = header;
		hcArgs = options.getHaplotypeCallerArguments();
		hcEngine = new HaplotypeCallerEngine(hcArgs, header);
		setHeader();
	}

	private void setHeader() {
		if(vcfHeader != null)
			return;
		VariantAnnotatorEngine engine = hcEngine.getVariantAnnotatorEngine();

		final Set<VCFHeaderLine> headerInfo = new HashSet<>();

		// initialize the annotations (this is particularly important to turn off
		// RankSumTest dithering in integration tests)
		// do this before we write the header because SnpEff adds to header lines
		headerInfo.addAll(engine.getVCFAnnotationDescriptions(options.getCompNames()));

		headerInfo.addAll(hcEngine.getGenotypeingEngine().getAppropriateVCFInfoHeaders());
		// all callers need to add these standard annotation header lines
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.DOWNSAMPLED_KEY));
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.MLE_ALLELE_COUNT_KEY));
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.MLE_ALLELE_FREQUENCY_KEY));
		// all callers need to add these standard FORMAT field header lines
		VCFStandardHeaderLines.addStandardFormatLines(headerInfo, true, VCFConstants.GENOTYPE_KEY,
				VCFConstants.GENOTYPE_QUALITY_KEY, VCFConstants.DEPTH_KEY, VCFConstants.GENOTYPE_PL_KEY);

		if (!doNotRunPhysicalPhasing) {
			headerInfo.add(GaeaVCFHeaderLines.getFormatLine(GaeaVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY));
			headerInfo.add(GaeaVCFHeaderLines.getFormatLine(GaeaVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY));
		}

		// FILTER fields are added unconditionally as it's not always 100% certain the
		// circumstances
		// where the filters are used. For example, in emitting all sites the lowQual
		// field is used
		headerInfo.add(GaeaVCFHeaderLines.getFilterLine(GaeaVCFConstants.LOW_QUAL_FILTER_NAME));

		//getReferenceConfidenceModelHeaderLine(headerInfo);

		vcfHeader = new VCFHeader(headerInfo);
	}

	private void getReferenceConfidenceModelHeaderLine( SampleList samples,final Set<VCFHeaderLine> headerInfo) {
		ReferenceConfidenceModel referenceConfidenceModel = new ReferenceConfidenceModel(samples, header,
				hcArgs.indelSizeToEliminateInRefModel);
		if (hcArgs.emitReferenceConfidence != ReferenceConfidenceMode.NONE) {
			headerInfo.addAll(referenceConfidenceModel.getVCFHeaderLines());
		}
	}

	public VCFHeader getVCFHeader() {
		System.err.println(vcfHeader.toString());
		return this.vcfHeader;
	}

	public void dataSourceReset(Window win, Iterable<SamRecordWritable> iterable, ChromosomeInformationShare ref,
			RefMetaDataTracker features) {
		if (readsSource != null)
			readsSource.dataReset(iterable);
		else
			readsSource = new ReadsDataSource(iterable, header);
		this.ref = ref;
		this.features = features;
		initializeIntervals(win);
		hcArgs.dbsnp = features.getValues("DB");
		hcEngine.initializeAnnotationEngine(hcArgs);
		makeReadsShard(options.getReadShardSize(), options.getReadShardPadding());
	}

	private void initializeIntervals(Window win) {
		this.intervals = GenomeLocation.getGenomeLocationFromWindow(win, region);
	}

	private void makeReadsShard(int readShardSize, int readShardPadding) {
		for (final GenomeLocation interval : intervals) {
			if (readShardSize != NO_INTERVAL_SHARDING) {
				shards.addAll(LocalReadShard.divideIntervalIntoShards(interval, readShardSize, readShardPadding,
						readsSource, header.getSequenceDictionary()));
			} else {
				shards.add(new LocalReadShard(interval,
						interval.expandWithinContig(readShardPadding, header.getSequenceDictionary()), readsSource));
			}
		}
	}

	public final CountingReadFilter getMergedCountingReadFilter(final SAMFileHeader samHeader) {
		Utils.nonNull(samHeader);
		return getMergedReadFilter(samHeader, CountingReadFilter::fromList);
	}

	/**
	 * Merge the default filters with the users's command line read filter requests,
	 * then initialize the resulting filters.
	 *
	 * @param samHeader
	 *            a SAMFileHeader to initialize read filter instances. May not be
	 *            null.
	 * @param aggregateFunction
	 *            function to use to merge ReadFilters, usually
	 *            ReadFilter::fromList. The function must return the ALLOW_ALL_READS
	 *            filter wrapped in the appropriate type when passed a null or empty
	 *            list.
	 * @param <T>
	 *            extends ReadFilter, type returned by the wrapperFunction
	 * @return Single merged read filter.
	 */
	public <T extends ReadFilter> T getMergedReadFilter(final SAMFileHeader samHeader,
			final BiFunction<List<ReadFilter>, SAMFileHeader, T> aggregateFunction) {

		Utils.nonNull(samHeader);
		Utils.nonNull(aggregateFunction);

		// start with the tool's default filters in the order they were specified, and
		// remove any that were disabled
		// on the command line
		// if --disableToolDefaultReadFilters is specified, just initialize an empty
		// list with initial capacity of user filters
		final List<ReadFilter> finalFilters = options.getDisableToolDefaultReadFilters()
				? new ArrayList<>(options.getUserEnabledReadFilterNames().size())
				: toolDefaultReadFilters.entrySet().stream().filter(e -> !isDisabledFilter(e.getKey()))
						.map(e -> e.getValue()).collect(Collectors.toList());

		// now add in any additional filters enabled on the command line (preserving
		// order)
		final List<ReadFilter> clFilters = makeStandardHCReadFilters();//getAllInstances();
		if (clFilters != null) {
			clFilters.stream().filter(f -> !finalFilters.contains(f)) // remove redundant filters
					.forEach(f -> finalFilters.add(f));
		}

		return aggregateFunction.apply(finalFilters, samHeader);
	}

	private List<ReadFilter> getAllInstances() {
		final ArrayList<ReadFilter> filters = new ArrayList<>(options.getUserEnabledReadFilterNames().size());
		options.getUserEnabledReadFilterNames().forEach(s -> {
			ReadFilter rf = allDiscoveredReadFilters.get(s);
			filters.add(rf);
		});
		return filters;
	}
	
	public List<ReadFilter> makeStandardHCReadFilters() {
        List<ReadFilter> filters = new ArrayList<>();
        filters.add(new MappingQualityReadFilter(READ_QUALITY_FILTER_THRESHOLD));
        filters.add(ReadFilterLibrary.MAPPING_QUALITY_AVAILABLE);
        filters.add(ReadFilterLibrary.MAPPED);
        filters.add(ReadFilterLibrary.NOT_SECONDARY_ALIGNMENT);
        filters.add(ReadFilterLibrary.NOT_DUPLICATE);
        filters.add(ReadFilterLibrary.PASSES_VENDOR_QUALITY_CHECK);
        filters.add(ReadFilterLibrary.NON_ZERO_REFERENCE_LENGTH_ALIGNMENT);
        filters.add(ReadFilterLibrary.GOOD_CIGAR);
        filters.add(new WellformedReadFilter());

        return filters;
    }

	public final void traverse(GaeaVariantContextWriter writer) {
		CountingReadFilter countedFilter = getMergedCountingReadFilter(header);

		for (final LocalReadShard readShard : shards) {
			// Since reads in each shard are lazily fetched, we need to pass the filter to
			// the window
			// instead of filtering the reads directly here
			readShard.setReadFilter(countedFilter);
			readShard.setDownsampler(
					maxReadsPerAlignmentStart > 0 ? new PositionalDownsampler(maxReadsPerAlignmentStart, header)
							: null);
			currentReadShard = readShard;

			processReadShard(readShard, features, writer);
		}
	}

	private void processReadShard(Shard<GaeaSamRecord> shard, RefMetaDataTracker features,
			GaeaVariantContextWriter writer) {
		final Iterator<AssemblyRegion> assemblyRegionIter = new AssemblyRegionIterator(shard, header, ref, features,
				hcEngine, minAssemblyRegionSize, maxAssemblyRegionSize, assemblyRegionPadding, activeProbThreshold,
				maxProbPropagationDistance, true);

		// Call into the tool implementation to process each assembly region
		// from this shard.
		while (assemblyRegionIter.hasNext()) {
			final AssemblyRegion assemblyRegion = assemblyRegionIter.next();
			writeAssemblyRegion(assemblyRegion);
			List<VariantContext> results = apply(assemblyRegion, features);

			for (VariantContext context : results)
				writer.write(context);
		}
	}

	private void writeAssemblyRegion(final AssemblyRegion region) {
		writeActivityProfile(region.getSupportingStates());

		if (assemblyRegionOutStream != null) {
			printIGVFormatRow(assemblyRegionOutStream,
					new GenomeLocation(region.getContig(), region.getStart(), region.getStart()), "end-marker", 0.0);
			printIGVFormatRow(assemblyRegionOutStream, region, "size=" + new GenomeLocation(region).size(),
					region.isActive() ? 1.0 : -1.0);
		}
	}

	private void writeActivityProfile(final List<ActivityProfileState> states) {
		if (activityProfileOutStream != null) {
			for (final ActivityProfileState state : states) {
				printIGVFormatRow(activityProfileOutStream, state.getLoc(), "state",
						Math.min(state.isActiveProb(), 1.0));
			}
		}
	}

	public List<VariantContext> apply(final AssemblyRegion region, final RefMetaDataTracker featureContext) {
		return hcEngine.callRegion(region, featureContext);
	}

	public void printIGVFormatRow(final PrintStream out, final Locatable loc, final String featureName,
			final double... values) {
		// note that start and stop are 0-based in IGV files, but the stop is exclusive
		// so we don't subtract 1 from it
		out.printf("%s\t%d\t%d\t%s", loc.getContig(), loc.getStart() - 1, loc.getEnd(), featureName);
		for (final double value : values) {
			out.print(String.format("\t%.5f", value));
		}
		out.println();
	}

	public boolean isDisabledFilter(final String filterName) {
		return options.getUserDisabledReadFilterNames().contains(filterName)
				|| (options.getDisableToolDefaultReadFilters()
						&& !options.getUserEnabledReadFilterNames().contains(filterName));
	}

	public void setReadFilters() {

	}

	public GenomeLocation getCurrentLocation() {
		return this.currentReadShard.getInterval();
	}

	public void clear() {

	}
}