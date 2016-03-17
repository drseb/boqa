/* Copyright (c) 2010-2012 Sebastian Bauer
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

package sonumina.math.distribution;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A simple class representing an empirical probability
 * distribution.
 * 
 * @author Sebastian Bauer
 *
 */
public class EmpiricalDistribution implements IDistribution
{
	private double [] observations;
	
	/**  The cumulative counts */
	private int [] cumCounts;

	/**
	 * Constructs an empirical distribution.
	 * 
	 * @param newObservations
	 */
	public EmpiricalDistribution(double [] newObservations)
	{
		observations = new double[newObservations.length];
		for (int i=0;i<newObservations.length;i++)
			observations[i] = newObservations[i];
		Arrays.sort(observations);
	}

	/**
	 * Constructs an empirical distribution.
	 * 
	 * @param newObservations
	 * @param counts
	 */
	public EmpiricalDistribution(double [] newObservations, int [] counts)
	{
		if (newObservations.length != counts.length)
			throw new IllegalArgumentException("Length of import vectors doesn't match");

		class Item
		{
			public double obs;
			public int idx;
		};
		
		Item [] items = new Item[newObservations.length];
		for (int i=0;i<newObservations.length;i++)
		{
			items[i] = new Item();
			items[i].idx = i;
			items[i].obs = newObservations[i];
		}
		Arrays.sort(items, new Comparator<Item>() {
			public int compare(Item o1, Item o2)
			{
				if (o1.obs < o2.obs) return -1;
				if (o1.obs == o2.obs) return 0;
				return 1;
			};
		});

		cumCounts = new int[counts.length];
		observations = new double[newObservations.length];

		int totalCounts = 0;

		for (int i=0;i<items.length;i++)
		{
			observations[i] = items[i].obs;
			totalCounts += counts[items[i].idx];
			cumCounts[i] = totalCounts;
		}
	}

	/**
	 * Returns for x the value for the distribution function F(x) = P(X <= x).
	 * 
	 * @param observation
	 * @param upperTail
	 * @return
	 */
	public double cdf(double x, boolean upperTail)
	{
		int idx = Arrays.binarySearch(observations, x);
		
		if (cumCounts == null)
		{
			/* See doc to binarySearch */
			if (idx < 0)
				idx = - idx - 1;

			for (;idx<observations.length;idx++)
				if (observations[idx] != x)
					break;
			return idx/(double)observations.length;
		} else
		{
			if (idx < 0)
			{
				 /* We have to subtract one more as cumCounts[i] contains the cdf() for i
				  * and i points to the next larger observation of x */
				idx = - idx - 1 - 1;
			}
			return cumCounts[idx] / (double)cumCounts[cumCounts.length - 1];
		}
	}
}
