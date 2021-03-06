package org.bgi.flexlab.gaea.tools.haplotypecaller.annotation;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.bgi.flexlab.gaea.data.exception.UserException;
import org.bgi.flexlab.gaea.data.structure.bam.GaeaSamRecord;
import org.bgi.flexlab.gaea.data.structure.reference.ChromosomeInformationShare;
import org.bgi.flexlab.gaea.tools.haplotypecaller.ReadLikelihoods;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.ReducibleAnnotationData;
import org.bgi.flexlab.gaea.tools.vcfqualitycontrol2.util.GaeaVCFHeaderLines;
import org.bgi.flexlab.gaea.util.GaeaVCFConstants;
import org.bgi.flexlab.gaea.util.QualityUtils;
import org.bgi.flexlab.gaea.util.Utils;

import com.google.common.annotations.VisibleForTesting;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;

public final class RMSMappingQuality extends InfoFieldAnnotation implements StandardAnnotation, ReducibleAnnotation {
    private static final RMSMappingQuality instance = new RMSMappingQuality();

    @Override
    public String getRawKeyName() { return GaeaVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY;}

    @Override
    public List<String> getKeyNames() {
        return Arrays.asList(VCFConstants.RMS_MAPPING_QUALITY_KEY, getRawKeyName());
    }

    @Override
    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(VCFStandardHeaderLines.getInfoLine(getKeyNames().get(0)), GaeaVCFHeaderLines.getInfoLine(getRawKeyName()));
    }

    @Override
    public List<VCFInfoHeaderLine> getRawDescriptions() {
        return getDescriptions();
    }

    /**
     * Generate the raw data necessary to calculate the annotation. Raw data is the final endpoint for gVCFs.
     */
    @Override
    public Map<String, Object> annotateRawData(final ChromosomeInformationShare ref,
                                               final VariantContext vc,
                                               final ReadLikelihoods<Allele> likelihoods){
        Utils.nonNull(vc);
        if (likelihoods == null || likelihoods.readCount() == 0) {
            return Collections.emptyMap();
        }

        final Map<String, Object> annotations = new HashMap<>();
        final ReducibleAnnotationData<Number> myData = new ReducibleAnnotationData<>(null);
        calculateRawData(vc, likelihoods, myData);
        final String annotationString = formattedValue((double) myData.getAttributeMap().get(Allele.NO_CALL));
        annotations.put(getRawKeyName(), annotationString);
        return annotations;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME generics here blow up
    public Map<String, Object> combineRawData(final List<Allele> vcAlleles, final List<ReducibleAnnotationData<?>>  annotationList) {
        //VC already contains merged alleles from ReferenceConfidenceVariantContextMerger
        ReducibleAnnotationData combinedData = new ReducibleAnnotationData(null);

        for (final ReducibleAnnotationData currentValue : annotationList) {
            parseRawDataString(currentValue);
            combineAttributeMap(currentValue, combinedData);

        }
        final Map<String, Object> annotations = new HashMap<>();
        String annotationString = makeRawAnnotationString(vcAlleles, combinedData.getAttributeMap());
        annotations.put(getRawKeyName(), annotationString);
        return annotations;
    }

    public String makeRawAnnotationString(final List<Allele> vcAlleles, final Map<Allele, Number> perAlleleData) {
        return String.format("%.2f", perAlleleData.get(Allele.NO_CALL));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME generics here blow up
    public Map<String, Object> finalizeRawData(final VariantContext vc, final VariantContext originalVC) {
        if (!vc.hasAttribute(getRawKeyName()))
            return new HashMap<>();
        String rawMQdata = vc.getAttributeAsString(getRawKeyName(),null);
        if (rawMQdata == null)
            return new HashMap<>();

        ReducibleAnnotationData myData = new ReducibleAnnotationData(rawMQdata);
        parseRawDataString(myData);

        String annotationString = makeFinalizedAnnotationString(vc, myData.getAttributeMap());
        return Collections.singletonMap(getKeyNames().get(0), (Object)annotationString);
    }

    public String makeFinalizedAnnotationString(final VariantContext vc, final Map<Allele, Number> perAlleleData) {
        int numOfReads = getNumOfReads(vc);
        return String.format("%.2f", Math.sqrt((double)perAlleleData.get(Allele.NO_CALL)/numOfReads));
    }


    public void combineAttributeMap(ReducibleAnnotationData<Number> toAdd, ReducibleAnnotationData<Number> combined) {
        if (combined.getAttribute(Allele.NO_CALL) != null)
            combined.putAttribute(Allele.NO_CALL, (Double) combined.getAttribute(Allele.NO_CALL) + (Double) toAdd.getAttribute(Allele.NO_CALL));
        else
            combined.putAttribute(Allele.NO_CALL, toAdd.getAttribute(Allele.NO_CALL));

    }

    @SuppressWarnings({"unchecked", "rawtypes"})//FIXME
    public void calculateRawData(final VariantContext vc,
                                 final ReadLikelihoods<Allele> likelihoods,
                                 final ReducibleAnnotationData rawAnnotations){
        //put this as a double, like GATK3.5
        final double squareSum = IntStream.range(0, likelihoods.numberOfSamples()).boxed()
                .flatMap(s -> likelihoods.sampleReads(s).stream())
                .map(GaeaSamRecord::getMappingQuality)
                .filter(mq -> mq != QualityUtils.MAPPING_QUALITY_UNAVAILABLE)
                .mapToDouble(mq -> mq * mq).sum();

        rawAnnotations.putAttribute(Allele.NO_CALL, squareSum);
    }

    @Override
    public Map<String, Object> annotate(final ChromosomeInformationShare ref,
                                        final VariantContext vc,
                                        final ReadLikelihoods<Allele> likelihoods) {
        return annotateRawData(ref, vc, likelihoods);
    }

    @VisibleForTesting
    static String formattedValue(double rms) {
        return String.format("%.2f", rms);
    }

    /**
     * converts {@link GaeaVCFConstants#RAW_RMS_MAPPING_QUALITY_KEY} into  {@link VCFConstants#RMS_MAPPING_QUALITY_KEY}  annotation if present
     * @param vc which potentially contains rawMQ
     * @return if vc contained {@link GaeaVCFConstants#RAW_RMS_MAPPING_QUALITY_KEY} it will be replaced with {@link VCFConstants#RMS_MAPPING_QUALITY_KEY}
     * otherwise return the original vc
     */
    public VariantContext finalizeRawMQ(final VariantContext vc) {
        final String rawMQdata = vc.getAttributeAsString(getRawKeyName(), null);
        if (rawMQdata == null) {
            return vc;
        } else {
            final double squareSum = parseRawDataString(rawMQdata);
            final int numOfReads = getNumOfReads(vc);
            final double rms = Math.sqrt(squareSum / (double)numOfReads);
            final String finalizedRMSMAppingQuality = formattedValue(rms);
            return new VariantContextBuilder(vc)
                    .rmAttribute(getRawKeyName())
                    .attribute(getKeyNames().get(0), finalizedRMSMAppingQuality)
                    .make();
        }
    }

    protected void parseRawDataString(ReducibleAnnotationData<Number> myData) {
        final String rawDataString = myData.getRawData();
        String[] rawMQdataAsStringVector;
        rawMQdataAsStringVector = rawDataString.split(",");
        double squareSum = Double.parseDouble(rawMQdataAsStringVector[0]);
        myData.putAttribute(Allele.NO_CALL, squareSum);
    }

    //TODO once the AS annotations have been added genotype gvcfs this can be removed for a more generic approach
    private static double parseRawDataString(String rawDataString) {
        try {
            /*
             * TODO: this is copied from gatk3 where it ignored all but the first value, we should figure out if this is
             * the right thing to do or if it should just convert the string without trying to split it and fail if
             * there is more than one value
             */
            final double squareSum = Double.parseDouble(rawDataString.split(",")[0]);
            return squareSum;
        } catch (final NumberFormatException e){
            throw new UserException.BadInput("malformed " + GaeaVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY +" annotation: " + rawDataString);
        }
    }

    /**
     *
     * @return the number of reads at the given site, calculated as InfoField {@link VCFConstants#DEPTH_KEY} minus the
     * format field {@link GaeaVCFConstants#MIN_DP_FORMAT_KEY} or DP of each of the HomRef genotypes at that site
     * @throws UserException.BadInput if the {@link VCFConstants#DEPTH_KEY} is missing or if the calculated depth is <= 0
     */
    @VisibleForTesting
    static int getNumOfReads(final VariantContext vc) {
        //don't use the full depth because we don't calculate MQ for reference blocks
        int numOfReads = vc.getAttributeAsInt(VCFConstants.DEPTH_KEY, -1);
        if(vc.hasGenotypes()) {
            for(final Genotype gt : vc.getGenotypes()) {
                if(gt.isHomRef()) {
                    //site-level DP contribution will come from MIN_DP for gVCF-called reference variants or DP for BP resolution
                    if (gt.hasExtendedAttribute(GaeaVCFConstants.MIN_DP_FORMAT_KEY)) {
                        numOfReads -= Integer.parseInt(gt.getExtendedAttribute(GaeaVCFConstants.MIN_DP_FORMAT_KEY).toString());
                    } else if (gt.hasDP()) {
                        numOfReads -= gt.getDP();
                    }
                }
            }
        }
        if (numOfReads <= 0){
            numOfReads = -1;  //return -1 to result in a NaN
        }
        return numOfReads;
    }

    /**
     *
     * @return the number of reads at the given site, calculated as InfoField {@link VCFConstants#DEPTH_KEY} minus the
     * format field {@link GaeaVCFConstants#MIN_DP_FORMAT_KEY} or DP of each of the HomRef genotypes at that site.
     * Will fall back to calculating the reads from the stratifiedContexts then AlleleLikelyhoods data if provided.
     * @throws UserException.BadInput if the {@link VCFConstants#DEPTH_KEY} is missing or if the calculated depth is <= 0
     */
    @VisibleForTesting
    static int getNumOfReads(final VariantContext vc,
                             final ReadLikelihoods<Allele> likelihoods) {
        int numOfReads = 0;
        if (vc.hasAttribute(VCFConstants.DEPTH_KEY)) {
            return getNumOfReads(vc);
        } else if (likelihoods != null && likelihoods.numberOfAlleles() != 0) {
            for (int i = 0; i < likelihoods.numberOfSamples(); i++) {
                for (GaeaSamRecord read : likelihoods.sampleReads(i)) {
                    if (read.getMappingQuality() != QualityUtils.MAPPING_QUALITY_UNAVAILABLE) {
                        numOfReads++;
                    }
                }
            }
        }
        if (numOfReads <= 0) {
            numOfReads = -1;  //return -1 to result in a NaN
        }
        return numOfReads;
    }

    public static RMSMappingQuality getInstance() {
        return instance;
    }
}

