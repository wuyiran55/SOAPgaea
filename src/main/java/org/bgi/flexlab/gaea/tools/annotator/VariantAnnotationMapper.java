package org.bgi.flexlab.gaea.tools.annotator;

import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderVersion;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.bgi.flexlab.gaea.data.structure.header.GaeaSingleVCFHeader;
import org.bgi.flexlab.gaea.data.structure.reference.GenomeShare;
import org.bgi.flexlab.gaea.tools.annotator.config.Config;
import org.bgi.flexlab.gaea.tools.annotator.db.DBAnno;
import org.bgi.flexlab.gaea.tools.annotator.effect.VcfAnnotationContext;
import org.bgi.flexlab.gaea.tools.annotator.effect.VcfAnnotator;

public class VariantAnnotationMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
	
	private VCFHeader vcfHeader = null;
	private VCFHeaderVersion vcfVersion = null;
	private Text resultValue = new Text();
	private VCFCodec vcfCodec = new VCFCodec();
	private VcfAnnotator vcfAnnotator = null;
	private DBAnno dbAnno = null;
	private Config userConfig = null;
	private static GenomeShare genome;
	
	
	@Override
	protected void setup(Context context)
			throws IOException, InterruptedException {
		Configuration conf = context.getConfiguration();
		
		genome = new GenomeShare();
		if (conf.get("cacheref") != null)
			genome.loadChrList();
		else
			genome.loadChrList(conf.get("reference"));
		
		userConfig = new Config();
		AnnotatorBuild annoBuild = new AnnotatorBuild(userConfig);
		
		userConfig.setSnpEffectPredictor(annoBuild.createSnpEffPredictor());
		
		Path inputPath = new Path(conf.get("inputFilePath"));
		
		GaeaSingleVCFHeader singleVcfHeader = new GaeaSingleVCFHeader();
		singleVcfHeader.readHeaderFrom(inputPath, inputPath.getFileSystem(conf));
		vcfHeader = singleVcfHeader.getHeader();
		vcfVersion = singleVcfHeader.getVCFVersion(vcfHeader);
		vcfCodec.setVCFHeader(vcfHeader, vcfVersion);
		
		vcfAnnotator = new VcfAnnotator(null);
		dbAnno = new DBAnno(userConfig);
    	
	}

	@Override
	protected void map(LongWritable key, Text value, Context context)
			throws IOException, InterruptedException {
		
		
		String vcfLine = value.toString();
		if (vcfLine.startsWith("#")) {
			return;
		}
		
		VariantContext variantContext = vcfCodec.decode(vcfLine);
		VcfAnnotationContext vcfAnnoContext = new VcfAnnotationContext(variantContext);
		
		vcfAnnotator.annotate(vcfAnnoContext);
		dbAnno.annotate(vcfAnnoContext);
		
		resultValue.set(vcfAnnoContext.toVcfLine());
		context.write(NullWritable.get(), resultValue);
		
	}
}
