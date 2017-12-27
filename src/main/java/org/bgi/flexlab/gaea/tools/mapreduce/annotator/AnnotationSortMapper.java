/*******************************************************************************
 * Copyright (c) 2017, BGI-Shenzhen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *******************************************************************************/
package org.bgi.flexlab.gaea.tools.mapreduce.annotator;


import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderVersion;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.bgi.flexlab.gaea.data.structure.header.SingleVCFHeader;
import org.bgi.flexlab.gaea.tools.annotator.AnnotationContext;
import org.bgi.flexlab.gaea.tools.annotator.SampleAnnotationContext;
import org.bgi.flexlab.gaea.tools.annotator.VcfAnnoContext;

import java.io.IOException;
import java.util.HashMap;

public class AnnotationSortMapper extends Mapper<LongWritable, Text, Text, Text> {

	private Text resultKey;
	private Text resultValue;
	private Configuration conf;
	@Override
	protected void setup(Context context)
			throws IOException, InterruptedException {
		resultKey = new Text();
		resultValue = new Text();
		conf = context.getConfiguration();
	}

	@Override
	protected void map(LongWritable key, Text value, Context context)
			throws IOException, InterruptedException {
		InputSplit inputSplit = context.getInputSplit();
		String fileName = ((FileSplit) inputSplit).getPath().getName();
		String annoLine = value.toString();
		if (annoLine.startsWith("#")) return;

		String chr = null;
		VcfAnnoContext vac = new VcfAnnoContext();
		vac.parseAnnotationStrings(annoLine);

		for(SampleAnnotationContext sac: vac.getSampleAnnoContexts().values()){
			resultKey.set(sac.getSampleName());
			resultValue.set(vac.getAnnoStr()+"\t"+sac.toAlleleString(sac.getSingleAlt()));
			context.write(resultKey, resultValue);
		}
	}
	
	@Override
	protected void cleanup(Context context)
			throws IOException, InterruptedException {

	}
}
