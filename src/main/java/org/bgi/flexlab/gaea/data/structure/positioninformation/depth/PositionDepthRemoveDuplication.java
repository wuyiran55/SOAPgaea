package org.bgi.flexlab.gaea.data.structure.positioninformation.depth;

import org.bgi.flexlab.gaea.data.structure.bam.SAMInformationBasic;
import org.bgi.flexlab.gaea.data.structure.positioninformation.CalculatePositionInforamtionInterface;
import org.bgi.flexlab.gaea.data.structure.positioninformation.CompoundInformation;
import org.bgi.flexlab.gaea.data.structure.positioninformation.IntPositionInformation;

public class PositionDepthRemoveDuplication extends IntPositionInformation implements CalculatePositionInforamtionInterface<SAMInformationBasic>{

	public PositionDepthRemoveDuplication(int windowSize) {
		super(windowSize);
		// TODO Auto-generated constructor stub
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void add(CompoundInformation posInfo) {
		// TODO Auto-generated method stub
		if(!posInfo.isDup()) {
			info[posInfo.distBetweenRefPosAndWinStart()]++;
		}
	}
}