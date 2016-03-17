/* Copyright (c) 2010-2013 Sebastian Bauer
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the
 *   distribution.
 *
 * * Neither the name of Sebastian Bauer nor the names of its
 *   contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE.  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package sonumina.boqa.calculation;

import java.util.logging.Logger;

/**
 * Helper function to create all the diff vectors. A diff vector describes the differences
 * between one vector and another with respect to one state change (i.e, 0->1 or 1->0).
 * 
 * @author Sebastian Bauer
 */
public class DiffVectors
{
	/** Our logger */
	private static Logger logger = Logger.getLogger(DiffVectors.class.getCanonicalName());

	private DiffVectors()
	{
	}

	/**
	 * For each item, contains the term ids which need to be switched on, if
	 * the previous item was on.
	 */
	public int [][] diffOnTerms;
	
	/**
	 * Same as diffOnTerms but for switching off terms.
	 */
	public int [][] diffOffTerms;

	/**
	 * Similar to diffOnTerms but each adjacent frequency-implied state
	 */
	public int [][][] diffOnTermsFreqs;
	
	/**
	 * Similar to diffOffTerms but each adjacent frequency-implied state
	 */
	public int [][][] diffOffTermsFreqs;
	
	/**
	 * The factors of each combination.
	 */
	public double [][] factors;

	/**
	 * Create the diff annotation vectors.
	 */
	private void initDiffVectors(int maxFrequencyTerms, int numberOfTerms, int [][] items2Terms, double [][] items2TermFrequencies,  int [][] item2TermFrequenciesOrder, int [][] items2DirectTerms, int [][] terms2Ancestors)
	{
		int i;

		long sum=0;

		int numberOfItems = items2Terms.length;

		logger.info("Determining differences");

		/* Fill diff matrix */
		diffOnTerms = new int[numberOfItems][];
		diffOffTerms = new int[numberOfItems][];
		diffOnTerms[0] = items2Terms[0]; /* For the first step, all terms must be activated */
		diffOffTerms[0] = new int[0];
		for (i=1;i<numberOfItems;i++)
		{
			int prevOnTerms[] = items2Terms[i-1];
			int newOnTerms[] = items2Terms[i];

			diffOnTerms[i] = Util.setDiff(newOnTerms, prevOnTerms);
			diffOffTerms[i] = Util.setDiff(prevOnTerms, newOnTerms);

			sum += diffOnTerms[i].length + diffOffTerms[i].length;
		}
		System.err.println(sum + " differences detected (" + (double)sum/numberOfItems + " per item)");

		logger.info("Determining differences with frequencies for maximal " + maxFrequencyTerms + " terms");
		diffOnTermsFreqs = new int[numberOfItems][][];
		diffOffTermsFreqs = new int[numberOfItems][][];
		factors = new double[numberOfItems][];
		for (int item=0;item<numberOfItems;item++)
		{
			int numTerms = items2TermFrequencies[item].length;
			int numTermsWithExplicitFrequencies = 0;
			int numConfigs = 0;

			/* Determine the number of terms that have non-1.0 frequency. We restrict them
			 * to the top 6 (the less probable) due to complexity issues and hope that this
			 * a good enough approximation. */
			for (i=0;i<numTerms && i<maxFrequencyTerms;i++)
			{
				if (items2TermFrequencies[item][item2TermFrequenciesOrder[item][i]] >= 1.0)
					break;
				numTermsWithExplicitFrequencies++;
			}

			/* We try each possible activity/inactivity combination of terms with explicit frequencies */
			SubsetGenerator sg = new SubsetGenerator(numTermsWithExplicitFrequencies,numTermsWithExplicitFrequencies);
			SubsetGenerator.Subset s;

			/* First, determine the number of configs (could calculate binomial coefficient of course) */
			while ((s = sg.next()) != null)
				numConfigs++;

			diffOnTermsFreqs[item] = new int[numConfigs][];  
			diffOffTermsFreqs[item] = new int[numConfigs][];
			factors[item] = new double[numConfigs];

			/* Contains the settings of the previous run */
			IntArray prevArray = new IntArray(numberOfTerms);

			int config = 0;

			while ((s = sg.next()) != null)
			{
				boolean [] hidden = new boolean[numberOfTerms];
				boolean [] taken = new boolean[numTermsWithExplicitFrequencies];

				double factor = 0.0;

				/* First, activate variable terms according to the current selection */
				for (i=0;i<s.r;i++)
				{
					int ti = item2TermFrequenciesOrder[item][s.j[i]]; /* index of term within the all directly associated indices */
					int h = items2DirectTerms[item][ti];			  /* global index of term */
					hidden[h] = true;
					for (int j = 0; j < terms2Ancestors[h].length; j++)
						hidden[terms2Ancestors[h][j]] = true;
					factor += Math.log(items2TermFrequencies[item][ti]);
					taken[s.j[i]] = true;
				}
				
				/* Needs also respect the inactive terms in the factor */
				for (i=0;i<numTermsWithExplicitFrequencies;i++)
				{
					if (!taken[i])
						factor += Math.log(1 - items2TermFrequencies[item][item2TermFrequenciesOrder[item][i]]);
				}

				/* Second, activate mandatory terms */
				for (i=numTermsWithExplicitFrequencies;i<numTerms;i++)
				{
					int ti = item2TermFrequenciesOrder[item][i];
					int h = items2DirectTerms[item][ti];  /* global index of term */
					hidden[h] = true;
					for (int j = 0; j < terms2Ancestors[h].length; j++)
						hidden[terms2Ancestors[h][j]] = true;
					/* Factor is always 0 */
				}
				
				/* Now make a sparse representation */
				IntArray newArray = new IntArray(hidden);

				/* And record the difference */
				diffOnTermsFreqs[item][config] = Util.setDiff(newArray.get(), prevArray.get());
				diffOffTermsFreqs[item][config] = Util.setDiff(prevArray.get(), newArray.get());
				factors[item][config] = factor;

				prevArray = newArray;
				config++;
			}
		}
		logger.info("Done with differences!");
	}

	/**
	 * Create a diff vector object from the given data.
	 *
	 * @param maxFrequencyTerms
	 * @param slimGraph
	 * @param items2Terms
	 * @param items2TermFrequencies
	 * @param item2TermFrequenciesOrder
	 * @param items2DirectTerms
	 * @param terms2Ancestors
	 * @return
	 */
	public static DiffVectors createDiffVectors(int maxFrequencyTerms, int numberOfTerms, int [][] items2Terms, double [][] items2TermFrequencies,  int [][] item2TermFrequenciesOrder, int [][] items2DirectTerms, int [][] terms2Ancestors)
	{
		DiffVectors dv = new DiffVectors();
		dv.initDiffVectors(maxFrequencyTerms, numberOfTerms, items2Terms, items2TermFrequencies, item2TermFrequenciesOrder, items2DirectTerms, terms2Ancestors);
		return dv;
	}
}

