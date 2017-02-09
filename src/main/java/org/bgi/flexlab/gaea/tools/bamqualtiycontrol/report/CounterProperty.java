package org.bgi.flexlab.gaea.tools.bamqualtiycontrol.report;

public interface CounterProperty {
	
	public enum Interval implements CounterProperty{
		WHOLEGENOME, TARGET, FLANK, CHRX, CHRY;
	}
	
	public enum Depth implements CounterProperty{
		ABOVE_ZREO(0), FOURX(4), TENX(10), TWENTYX(20), THIRTYX(30), FIFTYX(50), HUNDREDX(100),
		TOTALDEPTH(-1);
		
		private int depth;
		
		Depth(int depth) {
			this.depth = depth;
		}
		
		public int getDepth() {
			return depth;
		}
	}
	
	public enum DepthType implements CounterProperty{
		WITHOUT_PCR(0), NORMAL(0);
		
		private int depth;
		
		private DepthType(int depth) {
			// TODO Auto-generated constructor stub
			this.depth = depth;
		}
		
		public DepthType setDepth(int depth) {
			this.depth = depth;
			return this;
		}
		
		public int getDepth() {
			return depth;
		}
	}
	
	public enum BaseType implements CounterProperty{
		TOTALBASE(1), MISMATCH(1), COVERED(1), INDELREF(1), MISMATCHREF(1);
		
		private int count;
		
		private BaseType(int count) {
			this.count = count;
		}
		
		public BaseType setCount(int count) {
			this.count = count;
			return this;
		}
		
		public int getCount() {
			return count;
		}
	}
	
	public enum ReadType implements CounterProperty{
		TOTALREADS, MAPPED, UNIQUE, DUP, PE, CLIPPED, MISMATCH, INDEL;
	}
}