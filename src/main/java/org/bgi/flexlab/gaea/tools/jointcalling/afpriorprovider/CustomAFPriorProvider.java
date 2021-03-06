package org.bgi.flexlab.gaea.tools.jointcalling.afpriorprovider;

import java.util.List;

public class CustomAFPriorProvider extends AFPriorProvider{
	private final double[] priors;

    /**
     *
     * @param priorValues must exactly the number of genomes in the samples (the total ploidy).
     */
    public CustomAFPriorProvider(final List<Double> priorValues) {
        if (priorValues == null)
            throw new IllegalArgumentException("the input prior values cannot be null");
        priors = new double[priorValues.size() + 1];

        int i = 1;
        double sum = 0;
        for (double value : priorValues) {
            if (value <= 0 || value >= 1)
                throw new IllegalArgumentException("the AF prior value "+ value + " is out of the valid interval (0,1)");
            if (Double.isNaN(value))
                throw new IllegalArgumentException("NaN is not a valid prior AF value");
            priors[i++] = Math.log10(value);
            sum += value;
        }
        if (sum >= 1)
            throw new IllegalArgumentException("the AF prior value sum must be less than 1: " + sum);
        priors[0] = Math.log10(1 - sum);
    }

    @Override
    protected double[] buildPriors(final int totalPloidy) {
        if (totalPloidy != priors.length - 1)
            throw new IllegalStateException("requesting an invalid prior total ploidy " + totalPloidy + " != " + (priors.length - 1));
        return priors;
    }
}
