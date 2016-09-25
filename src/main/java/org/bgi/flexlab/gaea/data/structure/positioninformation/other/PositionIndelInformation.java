package org.bgi.flexlab.gaea.data.structure.positioninformation.other;

import org.bgi.flexlab.gaea.data.structure.positioninformation.BooleanPositionInformation;
import org.bgi.flexlab.gaea.data.structure.positioninformation.CalculatePositionInforamtionInterface;
import org.bgi.flexlab.gaea.util.CigarState;
import org.bgi.flexlab.gaea.util.SAMInformationBasic;
import org.bgi.flexlab.gaea.util.SystemConfiguration;
import org.bgi.flexlab.gaea.data.structure.positioninformation.BamPositionInformation;



public class PositionIndelInformation extends BooleanPositionInformation implements CalculatePositionInforamtionInterface<SAMInformationBasic>{

	public PositionIndelInformation(int windowSize) {
		super(windowSize);
		// TODO Auto-generated constructor stub
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void add(BamPositionInformation posInfo) {
		// TODO Auto-generated method stub
		CigarState cigarState = posInfo.getCigarState();
		int cigar = cigarState.getCurrentCigar();
		int cigarLength = (cigar >> 4);
		
		if (eligibleCigar(cigarState, cigar, cigarLength, posInfo)) {
			cigar = cigarState.getCigar().get(cigarState.getCigarState()[0] + 1);
			int cigarOP = (cigar & 0xf);
			if(cigarOP == SystemConfiguration.BAM_CDEL || cigarOP == SystemConfiguration.BAM_CINS) {
				info[(int) (posInfo.distBetweenRefPosAndWinStart())] = true;
			}
		}
	}
	
	@SuppressWarnings("rawtypes")
	private boolean eligibleCigar(CigarState cigarState, int cigar, int cigarLength, BamPositionInformation posInfo) {
		return cigarState.getCigarState()[1] + cigarLength - 1 == posInfo.getRefPosition() && 
				cigarState.getCigarState()[0] + 1 < cigarState.getCigar().size();
	}

}
