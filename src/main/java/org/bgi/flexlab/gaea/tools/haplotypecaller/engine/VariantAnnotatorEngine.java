package org.bgi.flexlab.gaea.tools.haplotypecaller.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.bgi.flexlab.gaea.data.exception.UserException;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocation;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocationParser;
import org.bgi.flexlab.gaea.tools.haplotypecaller.ReadLikelihoods;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.Annotation;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.GenotypeAnnotation;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.InfoFieldAnnotation;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.ReducibleAnnotation;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.VariantAnnotation;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotator.VariantOverlapAnnotator;
import org.bgi.flexlab.gaea.tools.haplotypecaller.argumentcollection.VariantAnnotationArgumentCollection;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.ClassUtils;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.ReducibleAnnotationData;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.RefMetaDataTracker;
import org.bgi.flexlab.gaea.util.Utils;
import org.reflections.ReflectionUtils;

import com.google.common.collect.Sets;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;

public final class VariantAnnotatorEngine {
	private final List<InfoFieldAnnotation> infoAnnotations;
	private final List<GenotypeAnnotation> genotypeAnnotations;
	private Set<String> reducibleKeys;
	private GenomeLocationParser parser = null;
	private VariantOverlapAnnotator variantOverlapAnnotator;

	private List<VariantContext> features = null;

	private VariantAnnotatorEngine(final AnnotationManager annots, final List<VariantContext> dbSNPInput,
			final Map<String, List<VariantContext>> featureInputs) {
		infoAnnotations = annots.createInfoFieldAnnotations();
		genotypeAnnotations = annots.createGenotypeAnnotations();
		variantOverlapAnnotator = createOverlapAnnotator(dbSNPInput, featureInputs);
		reducibleKeys = new HashSet<>();
		for (InfoFieldAnnotation annot : infoAnnotations) {
			if (annot instanceof ReducibleAnnotation) {
				reducibleKeys.add(((ReducibleAnnotation) annot).getRawKeyName());
			}
		}
	}

	private VariantAnnotatorEngine(final AnnotationManager annots) {
		infoAnnotations = annots.createInfoFieldAnnotations();
		genotypeAnnotations = annots.createGenotypeAnnotations();
		reducibleKeys = new HashSet<>();
		for (InfoFieldAnnotation annot : infoAnnotations) {
			if (annot instanceof ReducibleAnnotation) {
				reducibleKeys.add(((ReducibleAnnotation) annot).getRawKeyName());
			}
		}
	}

	/**
	 * Makes the engine for all known annotation types (minus the excluded ones).
	 * 
	 * @param annotationsToExclude
	 *            list of annotations to exclude (pass an empty list to indicate
	 *            that there are no exclusions)
	 * @param dbSNPInput
	 *            input for variants from a known set from DbSNP or null if not
	 *            provided. The annotation engine will mark variants overlapping
	 *            anything in this set using
	 *            {@link htsjdk.variant.vcf.VCFConstants#DBSNP_KEY}.
	 * @param comparisonFeatureInputs
	 *            list of inputs with known variants. The annotation engine will
	 *            mark variants overlapping anything in those sets using the name
	 *            given by {@link FeatureInput#getName()}. Note: the DBSNP
	 *            FeatureInput should be passed in separately, and not as part of
	 *            this List - an UserException will be thrown otherwise. Note: there
	 *            are no non-DBSNP comparison FeatureInputs an empty List should be
	 *            passed in here, rather than null.
	 */
	public static VariantAnnotatorEngine ofAllMinusExcluded(final List<String> annotationsToExclude,
			final List<VariantContext> dbSNPInput, final Map<String, List<VariantContext>> comparisonFeatureInputs) {
		Utils.nonNull(annotationsToExclude, "annotationsToExclude is null");
		Utils.nonNull(comparisonFeatureInputs, "comparisonFeatureInputs is null");
		return new VariantAnnotatorEngine(AnnotationManager.ofAllMinusExcluded(annotationsToExclude), dbSNPInput,
				comparisonFeatureInputs);
	}

	/**
	 * Makes the engine for given annotation types and groups (minus the excluded
	 * ones).
	 * 
	 * @param annotationGroupsToUse
	 *            list of annotations groups to include
	 * @param annotationsToUse
	 *            list of of annotations to include
	 * @param annotationsToExclude
	 *            list of annotations to exclude
	 * @param dbSNPInput
	 *            input for variants from a known set from DbSNP or null if not
	 *            provided. The annotation engine will mark variants overlapping
	 *            anything in this set using
	 *            {@link htsjdk.variant.vcf.VCFConstants#DBSNP_KEY}.
	 * @param comparisonFeatureInputs
	 *            list of inputs with known variants. The annotation engine will
	 *            mark variants overlapping anything in those sets using the name
	 *            given by {@link FeatureInput#getName()}. Note: the DBSNP
	 *            FeatureInput should be passed in separately, and not as part of
	 *            this List - an UserException will be thrown otherwise. Note: there
	 *            are no non-DBSNP comparison FeatureInputs an empty List should be
	 *            passed in here, rather than null.
	 */
	public static VariantAnnotatorEngine ofSelectedMinusExcluded(final List<String> annotationGroupsToUse,
			final List<String> annotationsToUse, final List<String> annotationsToExclude,
			final List<VariantContext> dbSNPInput, final Map<String, List<VariantContext>> comparisonFeatureInputs) {
		Utils.nonNull(annotationGroupsToUse, "annotationGroupsToUse is null");
		Utils.nonNull(annotationsToUse, "annotationsToUse is null");
		Utils.nonNull(annotationsToExclude, "annotationsToExclude is null");
		Utils.nonNull(comparisonFeatureInputs, "comparisonFeatureInputs is null");
		return new VariantAnnotatorEngine(AnnotationManager.ofSelectedMinusExcluded(annotationGroupsToUse,
				annotationsToUse, annotationsToExclude), dbSNPInput, comparisonFeatureInputs);
	}

	public static VariantAnnotatorEngine ofSelectedMinusExcluded(final List<String> annotationGroupsToUse,
			final List<String> annotationsToUse, final List<String> annotationsToExclude) {
		Utils.nonNull(annotationGroupsToUse, "annotationGroupsToUse is null");
		Utils.nonNull(annotationsToUse, "annotationsToUse is null");
		Utils.nonNull(annotationsToExclude, "annotationsToExclude is null");
		return new VariantAnnotatorEngine(AnnotationManager.ofSelectedMinusExcluded(annotationGroupsToUse,
				annotationsToUse, annotationsToExclude));
	}

	/**
	 * An overload of
	 * {@link org.broadinstitute.hellbender.tools.walkers.annotator.VariantAnnotatorEngine#ofSelectedMinusExcluded
	 * ofSelectedMinusExcluded} except that it accepts a
	 * {@link org.broadinstitute.hellbender.cmdline.argumentcollections.VariantAnnotationArgumentCollection}
	 * as input.
	 * 
	 * @param argumentCollection
	 *            VariantAnnotationArgumentCollection containing requested
	 *            annotations.
	 * @param dbSNPInput
	 *            input for variants from a known set from DbSNP or null if not
	 *            provided. The annotation engine will mark variants overlapping
	 *            anything in this set using
	 *            {@link htsjdk.variant.vcf.VCFConstants#DBSNP_KEY}.
	 * @param comparisonFeatureInputs
	 *            list of inputs with known variants. The annotation engine will
	 *            mark variants overlapping anything in those sets using the name
	 *            given by {@link FeatureInput#getName()}. Note: the DBSNP
	 *            FeatureInput should be passed in separately, and not as part of
	 *            this List - an UserException will be thrown otherwise. Note: there
	 *            are no non-DBSNP comparison FeatureInputs an empty List should be
	 *            passed in here, rather than null.
	 * @return a VariantAnnotatorEngine initialized with the requested annotations
	 */
	public static VariantAnnotatorEngine ofSelectedMinusExcluded(
			final VariantAnnotationArgumentCollection argumentCollection, final List<VariantContext> dbSNPInput,
			final Map<String, List<VariantContext>> comparisonFeatureInputs) {
		return ofSelectedMinusExcluded(argumentCollection.annotationGroupsToUse, argumentCollection.annotationsToUse,
				argumentCollection.annotationsToExclude, dbSNPInput, comparisonFeatureInputs);
	}

	public static VariantAnnotatorEngine ofSelectedMinusExcluded(
			final VariantAnnotationArgumentCollection argumentCollection) {
		for(String line : argumentCollection.annotationGroupsToUse) {
			System.err.println("annotationGroupsToUse:"+line);
		}
		for(String line : argumentCollection.annotationsToExclude) {
			System.err.println("annotationsToExclude:"+line);
		}
		return ofSelectedMinusExcluded(argumentCollection.annotationGroupsToUse, argumentCollection.annotationsToUse,
				argumentCollection.annotationsToExclude);
	}

	private VariantOverlapAnnotator createOverlapAnnotator(final List<VariantContext> dbSNPInput,
			final Map<String, List<VariantContext>> featureInputs) {
		final Map<String, List<VariantContext>> overlaps = new LinkedHashMap<>();
		for (final String fi : featureInputs.keySet()) {
			overlaps.put(fi, featureInputs.get(fi));
		}
		if (overlaps.values().contains(VCFConstants.DBSNP_KEY)) {
			throw new UserException("The map of overlaps must not contain " + VCFConstants.DBSNP_KEY);
		}
		if (dbSNPInput != null) {
			overlaps.put(VCFConstants.DBSNP_KEY, dbSNPInput); // add overlap detection with DBSNP by default
		}

		return new VariantOverlapAnnotator(dbSNPInput, overlaps, parser);
	}

	public void setFeatures(List<VariantContext> other) {
		this.features = other;
	}

	public void initializeOverlapAnnotator(final List<VariantContext> dbSNPInput,
			final Map<String, List<VariantContext>> featureInputs) {
		final Map<String, List<VariantContext>> overlaps = new LinkedHashMap<>();
		for (final String fi : featureInputs.keySet()) {
			overlaps.put(fi, featureInputs.get(fi));
		}
		if (overlaps.values().contains(VCFConstants.DBSNP_KEY)) {
			throw new UserException("The map of overlaps must not contain " + VCFConstants.DBSNP_KEY);
		}
		if (dbSNPInput != null) {
			overlaps.put(VCFConstants.DBSNP_KEY, dbSNPInput); // add overlap detection with DBSNP by default
		}

		variantOverlapAnnotator = new VariantOverlapAnnotator(dbSNPInput, overlaps, parser);
	}

	/**
	 * Returns the list of genotype annotations that will be applied. Note: The
	 * returned list is unmodifiable.
	 */
	public List<GenotypeAnnotation> getGenotypeAnnotations() {
		return Collections.unmodifiableList(genotypeAnnotations);
	}

	public void setGenomeLocationParser(SAMSequenceDictionary dict) {
		parser = new GenomeLocationParser(dict);
	}

	/**
	 * Returns the list of info annotations that will be applied. Note: The returned
	 * list is unmodifiable.
	 */
	public List<InfoFieldAnnotation> getInfoAnnotations() {
		return Collections.unmodifiableList(infoAnnotations);
	}

	/**
	 * Returns the set of descriptions to be added to the VCFHeader line (for all
	 * annotations in this engine).
	 */
	public Set<VCFHeaderLine> getVCFAnnotationDescriptions(List<String> overlapNames) {
		return getVCFAnnotationDescriptions(false, overlapNames);
	}

	/**
	 * Returns the set of descriptions to be added to the VCFHeader line (for all
	 * annotations in this engine).
	 * 
	 * @param useRaw
	 *            Whether to prefer reducible annotation raw key descriptions over
	 *            their normal descriptions
	 */
	public Set<VCFHeaderLine> getVCFAnnotationDescriptions(boolean useRaw, List<String> overlapNames) {
		final Set<VCFHeaderLine> descriptions = new LinkedHashSet<>();

		for (final InfoFieldAnnotation annotation : infoAnnotations) {
			if (annotation instanceof ReducibleAnnotation && useRaw) {
				descriptions.addAll(((ReducibleAnnotation) annotation).getRawDescriptions());
			} else {
				descriptions.addAll(annotation.getDescriptions());
			}
		}
		for (final GenotypeAnnotation annotation : genotypeAnnotations) {
			descriptions.addAll(annotation.getDescriptions());
		}
		for (final String db : overlapNames) {
			if (VCFStandardHeaderLines.getInfoLine(db, false) != null) {
				descriptions.add(VCFStandardHeaderLines.getInfoLine(db));
			} else {
				descriptions.add(new VCFInfoHeaderLine(db, 0, VCFHeaderLineType.Flag, db + " Membership"));
			}
		}

		Utils.validateArg(!descriptions.contains(null),
				"getVCFAnnotationDescriptions should not contain null. This error is likely due to an incorrect implementation of getDescriptions() in one or more of the annotation classes");
		return descriptions;
	}
	
	public Set<VCFHeaderLine> getVCFAnnotationDescriptions(boolean useRaw) {
        final Set<VCFHeaderLine> descriptions = new LinkedHashSet<>();

        for ( final InfoFieldAnnotation annotation : infoAnnotations) {
            if (annotation instanceof ReducibleAnnotation && useRaw) {
                descriptions.addAll(((ReducibleAnnotation)annotation).getRawDescriptions());
            } else {
                descriptions.addAll(annotation.getDescriptions());
            }
        }
        for ( final GenotypeAnnotation annotation : genotypeAnnotations) {
            descriptions.addAll(annotation.getDescriptions());
        }
        for ( final String db : variantOverlapAnnotator.getOverlapNames() ) {
            if ( VCFStandardHeaderLines.getInfoLine(db, false) != null ) {
                descriptions.add(VCFStandardHeaderLines.getInfoLine(db));
            } else {
                descriptions.add(new VCFInfoHeaderLine(db, 0, VCFHeaderLineType.Flag, db + " Membership"));
            }
        }
        
        return descriptions;
    }

	/**
	 * Combine (raw) data for reducible annotations (those that use raw data in
	 * gVCFs) Mutates annotationMap by removing the annotations that were combined
	 *
	 * Additionally, will combine other annotations by parsing them as numbers and
	 * reducing them down to the
	 * 
	 * @param allelesList
	 *            the list of merged alleles across all variants being combined
	 * @param annotationMap
	 *            attributes of merged variant contexts -- is modifying by removing
	 *            successfully combined annotations
	 * @return a map containing the keys and raw values for the combined annotations
	 */
	@SuppressWarnings({ "unchecked" })
	public Map<String, Object> combineAnnotations(final List<Allele> allelesList, Map<String, List<?>> annotationMap) {
		Map<String, Object> combinedAnnotations = new HashMap<>();

		// go through all the requested reducible info annotationTypes
		for (final InfoFieldAnnotation annotationType : infoAnnotations) {
			if (annotationType instanceof ReducibleAnnotation) {
				ReducibleAnnotation currentASannotation = (ReducibleAnnotation) annotationType;
				if (annotationMap.containsKey(currentASannotation.getRawKeyName())) {
					final List<ReducibleAnnotationData<?>> annotationValue = (List<ReducibleAnnotationData<?>>) annotationMap
							.get(currentASannotation.getRawKeyName());
					final Map<String, Object> annotationsFromCurrentType = currentASannotation
							.combineRawData(allelesList, annotationValue);
					combinedAnnotations.putAll(annotationsFromCurrentType);
					// remove the combined annotations so that the next method only processes the
					// non-reducible ones
					annotationMap.remove(currentASannotation.getRawKeyName());
				}
			}
		}
		return combinedAnnotations;
	}

	/**
	 * Finalize reducible annotations (those that use raw data in gVCFs)
	 * 
	 * @param vc
	 *            the merged VC with the final set of alleles, possibly subset to
	 *            the number of maxAltAlleles for genotyping
	 * @param originalVC
	 *            the merged but non-subset VC that contains the full list of merged
	 *            alleles
	 * @return a VariantContext with the final annotation values for reducible
	 *         annotations
	 */
	public VariantContext finalizeAnnotations(VariantContext vc, VariantContext originalVC) {
		final Map<String, Object> variantAnnotations = new LinkedHashMap<>(vc.getAttributes());

		// go through all the requested info annotationTypes
		for (final InfoFieldAnnotation annotationType : infoAnnotations) {
			if (annotationType instanceof ReducibleAnnotation) {

				ReducibleAnnotation currentASannotation = (ReducibleAnnotation) annotationType;

				final Map<String, Object> annotationsFromCurrentType = currentASannotation.finalizeRawData(vc,
						originalVC);
				if (annotationsFromCurrentType != null) {
					variantAnnotations.putAll(annotationsFromCurrentType);
				}
				// clean up raw annotation data after annotations are finalized
				variantAnnotations.remove(currentASannotation.getRawKeyName());
			}
		}

		// generate a new annotated VC
		final VariantContextBuilder builder = new VariantContextBuilder(vc).attributes(variantAnnotations);

		// annotate genotypes, creating another new VC in the process
		final VariantContext annotated = builder.make();
		return annotated;
	}

	/**
	 * Annotates the given variant context - adds all annotations that satisfy the
	 * predicate.
	 * 
	 * @param vc
	 *            the variant context to annotate
	 * @param features
	 *            context containing the features that overlap the given variant
	 * @param ref
	 *            the reference context of the variant to annotate or null if there
	 *            is none
	 * @param likelihoods
	 *            likelihoods indexed by sample, allele, and read within sample. May
	 *            be null
	 * @param addAnnot
	 *            function that indicates if the given annotation type should be
	 *            added to the variant
	 */
	public VariantContext annotateContext(final VariantContext vc, final ReadLikelihoods<Allele> likelihoods,
			final Predicate<VariantAnnotation> addAnnot) {
		Utils.nonNull(vc, "vc cannot be null");

		// annotate genotypes, creating another new VC in the process
		final VariantContextBuilder builder = new VariantContextBuilder(vc);
		builder.genotypes(annotateGenotypes(vc, likelihoods, addAnnot));
		final VariantContext newGenotypeAnnotatedVC = builder.make();

		final Map<String, Object> infoAnnotMap = new LinkedHashMap<>(newGenotypeAnnotatedVC.getAttributes());
		for (final InfoFieldAnnotation annotationType : this.infoAnnotations) {
			if (addAnnot.test(annotationType)) {
				final Map<String, Object> annotationsFromCurrentType = annotationType.annotate(null,
						newGenotypeAnnotatedVC, likelihoods);
				if (annotationsFromCurrentType != null) {
					infoAnnotMap.putAll(annotationsFromCurrentType);
				}
			}
		}

		// create a new VC with info and genotype annotations
		final VariantContext annotated = builder.attributes(infoAnnotMap).make();

		// annotate db occurrences
		return variantOverlapAnnotator
				.annotateOverlaps(features,
						variantOverlapAnnotator
								.annotateRsID(
										RefMetaDataTracker
												.getValues(features,
														GenomeLocation.createGenomeLocation(annotated.getContig(),
																annotated.getStart(), annotated.getStart(),annotated.getStart())),
										annotated));
	}

	private GenotypesContext annotateGenotypes(final VariantContext vc, final ReadLikelihoods<Allele> likelihoods,
			final Predicate<VariantAnnotation> addAnnot) {
		if (genotypeAnnotations.isEmpty()) {
			return vc.getGenotypes();
		}

		final GenotypesContext genotypes = GenotypesContext.create(vc.getNSamples());
		for (final Genotype genotype : vc.getGenotypes()) {
			final GenotypeBuilder gb = new GenotypeBuilder(genotype);
			for (final GenotypeAnnotation annotation : genotypeAnnotations) {
				if (addAnnot.test(annotation)) {
					annotation.annotate(null, vc, genotype, gb, likelihoods);
				}
			}
			genotypes.add(gb.make());
		}

		return genotypes;
	}

	/**
	 * Method which checks if a key is a raw key of the requested reducible
	 * annotations
	 * 
	 * @param key
	 *            annotation key to check
	 * @return true if the key is the raw key for a requested annotation
	 */
	public boolean isRequestedReducibleRawKey(String key) {
		return reducibleKeys.contains(key);
	}

	private static final class AnnotationManager {

		private final List<String> annotationGroupsToUse;
		private final List<String> annotationsToUse;
		private final List<String> annotationsToExclude;

		private AnnotationManager(final List<String> annotationGroupsToUse, final List<String> annotationsToUse,
				final List<String> annotationsToExclude) {
			this.annotationGroupsToUse = annotationGroupsToUse;
			this.annotationsToUse = annotationsToUse;
			this.annotationsToExclude = annotationsToExclude;

			final Set<String> allAnnotationNames = new LinkedHashSet<>(AnnotationManager.getAllAnnotationNames());
			final Set<String> unknownAnnots = Sets.difference(new LinkedHashSet<>(annotationsToUse),
					allAnnotationNames);
			assertAnnotationExists(unknownAnnots);

			final Set<String> unknownAnnotsExclude = Sets.difference(new LinkedHashSet<>(annotationsToExclude),
					allAnnotationNames);
			assertAnnotationExists(unknownAnnotsExclude);

			final Set<String> unknownGroups = Sets.difference(new LinkedHashSet<>(annotationGroupsToUse),
					new LinkedHashSet<>(AnnotationManager.getAllAnnotationGroupNames()));
			if (!unknownGroups.isEmpty()) {
				throw new UserException.BadArgumentValueException("group", "Unknown annotation group in "
						+ unknownGroups + ". Known groups are " + AnnotationManager.getAllAnnotationGroupNames());
			}
		}

		private void assertAnnotationExists(final Set<String> missingAnnots) {
			if (!missingAnnots.isEmpty()) {
				throw new UserException.BadArgumentValueException("annotation", "Annotation " + missingAnnots
						+ " not found; please check that you have specified the name correctly");
			}
		}

		/**
		 * An annotation will be included only when: - it is in one of the annotation
		 * groups or - it is listed explicitly - and it is not excluded explicitly.
		 */
		static AnnotationManager ofSelectedMinusExcluded(final List<String> annotationGroupsToUse,
				final List<String> annotationsToUse, final List<String> annotationsToExclude) {
			final List<String> groups = new ArrayList<>(annotationGroupsToUse);// make copy
			final List<String> annots = new ArrayList<>(annotationsToUse);// make copy
			final List<String> excludes = new ArrayList<>(annotationsToExclude);// make copy
			return new AnnotationManager(groups, annots, excludes);
		}

		/**
		 * An annotation will be included only when it is not excluded explicitly.
		 */
		static AnnotationManager ofAllMinusExcluded(final List<String> annotationsToExclude) {
			final List<String> groups = getAllAnnotationGroupNames();
			final List<String> annots = getAllAnnotationNames();
			return new AnnotationManager(groups, annots, annotationsToExclude);
		}

		private static List<String> getAllAnnotationNames() {
			final Set<VariantAnnotation> union = Sets.union(new LinkedHashSet<>(makeAllGenotypeAnnotations()),
					new LinkedHashSet<>(AnnotationManager.makeAllInfoFieldAnnotations()));
			return union.stream().map(a -> a.getClass().getSimpleName()).collect(Collectors.toList());
		}

		/**
		 * Annotation group names are simple names of all interfaces that are subtypes
		 * of {@ Annotation}.
		 */
		public static List<String> getAllAnnotationGroupNames() {
			return ClassUtils.knownSubInterfaceSimpleNames(Annotation.class);
		}

		public List<InfoFieldAnnotation> createInfoFieldAnnotations() {
			final List<InfoFieldAnnotation> all = makeAllInfoFieldAnnotations();
			return filterAnnotations(all);
		}

		private static List<InfoFieldAnnotation> makeAllInfoFieldAnnotations() {
			return ClassUtils.makeInstancesOfSubclasses(InfoFieldAnnotation.class, Annotation.class.getPackage());
		}

		public List<GenotypeAnnotation> createGenotypeAnnotations() {
			final List<GenotypeAnnotation> all = makeAllGenotypeAnnotations();
			return filterAnnotations(all);
		}

		private static List<GenotypeAnnotation> makeAllGenotypeAnnotations() {
			return ClassUtils.makeInstancesOfSubclasses(GenotypeAnnotation.class, Annotation.class.getPackage());
		}

		/**
		 * Returns a list of annotations that either: - belong to at least one of the
		 * requested annotation groups - belong to the set of requested annotations
		 *
		 * - and are NOT listed for exclusion
		 *
		 * The list is sorted by simple name of the class.
		 */
		private <T extends VariantAnnotation> List<T> filterAnnotations(final List<T> all) {
			final SortedSet<T> annotations = new TreeSet<>(Comparator.comparing(t -> t.getClass().getSimpleName()));

			final Set<Class<?>> knownAnnotationGroups = ClassUtils.knownSubInterfaces(Annotation.class);

			for (final T t : all) {
				if (!annotationsToExclude.contains(t.getClass().getSimpleName())) {
					// if any group matches requested groups, it's in
					@SuppressWarnings("unchecked")
					final Set<Class<?>> annotationGroupsForT = ReflectionUtils.getAllSuperTypes(t.getClass(),
							sup -> sup.isInterface() && knownAnnotationGroups.contains(sup));
					if (annotationGroupsForT.stream()
							.anyMatch(iface -> annotationGroupsToUse.contains(iface.getSimpleName()))) {
						annotations.add(t);
					} else if (annotationsToUse.contains(t.getClass().getSimpleName())) {
						annotations.add(t);
					}
				}
			}

			return Collections.unmodifiableList(new ArrayList<>(annotations));
		}

	}

}