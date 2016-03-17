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

package sonumina.boqa.calculation;

import ontologizer.go.Term;
import ontologizer.go.TermID;


/**
 * Class maintaining observations.
 * 
 * @author Sebastian Bauer
 *
 */
public class Observations
{
	/** Boolean array that indicates which terms are on or off in the query */
	public boolean [] observations;
	
	/**
	 * Create an observation object from a sparse array in which terms with
	 * on status are listed.  The terms are specified as plain
	 * int values that range between 0 and the number of the
	 * terms of the ontology.
	 *  
	 * @param onTermIDs defines the terms that are considered to be on.
	 *  All other are considered to be off.
	 * @param boqa the boqa object for which the observations should be used.
	 *  The object must have passed the setup() call.
	 * @return the observations
	 */
	static public Observations createFromSparseOnArray(BOQA boqa, int ... onTermIDs)
	{
		int maxTermID = boqa.getSlimGraph().getNumberOfVertices();

		Observations obs = new Observations();
		obs.observations = new boolean[maxTermID];

		for (int onTermID : onTermIDs)
		{
			if (onTermID >= maxTermID)
					throw new IllegalArgumentException("Observations id " + onTermID + " refers to a non-existing term");
			obs.observations[onTermID] = true; 
		}
		return obs;
	}

	/**
	 * Create an observations object from sparse array in which terms with
	 * on status are indicated.  The terms are specified as TermID instances
	 * that must be present in the ontology associated with the given
	 * boqa object.
	 *  
	 * @param onTermIDs defines the terms that are considered to be on.
	 *  All other are considered to be off.
	 * @param boqa the boqa object for which the observations should be used.
	 *  The object must have passed the setup() call.
	 * @return the observations
	 */
	static public Observations createFromSparseOnArray(BOQA boqa, TermID ... onTermIDs)
	{
		int maxTermID = boqa.getSlimGraph().getNumberOfVertices();

		Observations obs = new Observations();
		obs.observations = new boolean[maxTermID];

		for (TermID onTermID : onTermIDs)
		{
			Term onTerm = boqa.getOntology().getTerm(onTermID);
			if (onTerm == null) throw new IllegalArgumentException("TermID " + onTermID.toString() + " couldn't been found in the ontology");
			int index = boqa.getTermIndex(onTerm);
			obs.observations[index] = true;
		}
		return obs;
	}
}
