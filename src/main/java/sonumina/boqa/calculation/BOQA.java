/* Copyright (c) 2010-2016 Sebastian Bauer & Sebastian Köhler
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import ontologizer.association.Association;
import ontologizer.association.AssociationContainer;
import ontologizer.association.Gene2Associations;
import ontologizer.enumeration.GOTermEnumerator;
import ontologizer.enumeration.ItemEnumerator;
import ontologizer.go.Ontology;
import ontologizer.go.Term;
import ontologizer.go.TermID;
import ontologizer.set.PopulationSet;
import ontologizer.types.ByteString;
import ontologizer.util.OntologyConstants;
import sonumina.algorithms.Algorithms;
import sonumina.boqa.server.BOQACore;
import sonumina.math.distribution.ApproximatedEmpiricalDistribution;
import sonumina.math.graph.SlimDirectedGraphView;

/**
 * This is core class implementing BOQA. Currently, it also implements other
 * procedures based on semantic similarity measures but this is planned to be
 * refactored. Thus, at the moment you will find also some methods and options
 * to work with semantic similarities.
 * 
 * In order to perform the calculation, you need to setup a new BOQA object,
 * invoke some methods to alter some options such as
 * setPrecalculateScoreDistribution() or setConsiderFrequenciesOnly() etc and
 * finally call setup() with ontologies and associations. One can then use
 * assignMarginals() on the observations to obtain marginal probabilities
 * according to the BOQA model.
 * 
 * <pre>
 * {
 * 	&#64;code
 * 	BOQA boqa = new BOQA();
 * 	boqa.setConsiderFrequenciesOnly(false);
 * 	boqa.setPrecalculateScoreDistribution(false);
 * 	boqa.setCacheScoreDistribution(false);
 * 	boqa.setStoreScoreDistriubtion(false);
 * 	boqa.setTryLoadingScoreDistribution(false);
 * 	boqa.setMaxQuerySizeForCachedDistribution(4);
 * 	boqa.setup(ontology, associations);
 * 
 * 	boqa.assignMarginals(false, 0, 1, 45, 66);
 * }
 * </pre>
 * 
 * Note that most times one refers to terms by plain ids. The id space matches
 * the id space of the associated slim graph. Several convenience methods are
 * provided to convert or access the data.
 * 
 * Refer to the BOQATest class for a working example usage.
 * 
 * @author Sebastian Bauer
 * @author Sebastian Köhler
 * 
 * @see setup, getTermIndex, sonumina.boqa.tests.BOQATest
 * 
 */
public class BOQA {
	/** Our logger */
	private static Logger logger = Logger.getLogger(BOQA.class.getCanonicalName());

	private Ontology graph;

	/** Term enumerator */
	private GOTermEnumerator termEnumerator;

	/** Slim variant of the graph */
	private SlimDirectedGraphView<Term> slimGraph;

	/** An array of all items */
	private ArrayList<ByteString> allItemList;

	/** Map items to their index */
	private HashMap<ByteString, Integer> item2Index;

	/** Links items to terms. The contents of each row is sorted. */
	private int[][] items2Terms;

	/**
	 * For each item, contains the term ids which need to be switched on, if the
	 * previous item was on.
	 */
	private int[][] diffOnTerms;

	/**
	 * Same as diffOnTerms but for switching off terms.
	 */
	private int[][] diffOffTerms;

	/**
	 * Similar to diffOnTerms but each adjacent frequency-implied state
	 */
	private int[][][] diffOnTermsFreqs;

	/**
	 * Similar to diffOffTerms but each adjacent frequency-implied state
	 */
	private int[][][] diffOffTermsFreqs;

	/**
	 * The factors of each combination.
	 */
	private double[][] factors;

	/** Links items to directly associated terms */
	private int[][] items2DirectTerms;

	/**
	 * Links items to the frequencies of corresponding directly associated terms.
	 * Frequencies are interpreted as probabilities that the corresponding term is
	 * on.
	 */
	private double[][] items2TermFrequencies;

	/**
	 * This contains the (ascending) order of the items2TermFrequencies, E.g., use
	 * item2TermFrequenciesOrder[0][2] to determine the term that is associated to
	 * first item and has the third lowest frequency.
	 */
	private int[][] item2TermFrequenciesOrder;

	/** Indicates whether an item have explicit frequencies */
	private boolean[] itemHasFrequencies;

	/** Contains all the ancestors of the terms */
	private int[][] term2Ancestors;

	/** Contains the parents of the terms */
	private int[][] term2Parents;

	/** Contains the children of the term */
	private int[][] term2Children;

	/** Contains the descendants of the (i.e., children, grand-children, etc.) */
	private int[][] term2Descendants;

	/** Contains the order of the terms */
	private int[] termsInTopologicalOrder;

	/** Contains the topological rank of the term */
	private int[] termsToplogicalRank;

	/** Contains the IC of the terms */
	private double[] terms2IC;

	/** Contains the term with maximum common ancestor of two terms */
	private int micaMatrix[][];

	/** Contains the jaccard index */
	private double jaccardMatrix[][];

	/** Contains the query cache, needs to be synched when accessed */
	private QuerySets queryCache;

	/** Used to parse frequency information */
	public static Pattern frequencyPattern = Pattern.compile("(\\d+)\\.?(\\d*)\\s*%");
	public static Pattern frequencyFractionPattern = Pattern.compile("(\\d+)/(\\d+)");
	public static Pattern NofMPattern = Pattern.compile("^(\\d+) of (\\d+)$");

	/* Settings for generation of random data */
	// private final double ALPHA = 0.002; // 0.01
	private double ALPHA = 0.002;
	private double BETA = 0.10; // 0.1

	/* Settings for inference */
	private double ALPHA_GRID[] = new double[] { 1e-10, 0.0005, 0.001, 0.005, 0.01 };
	private double BETA_GRID[] = new double[] { 1e-10, 0.005, 0.01, 0.05, 0.1, 0.2, 0.4, 0.8, 0.9 };

	// private static boolean CONSIDER_FREQUENCIES_ONLY = false;
	// private final static String [] evidenceCodes = null;
	// private final static int SIZE_OF_SCORE_DISTRIBUTION = 250000;
	// public static int maxTerms = -1;

	private boolean CONSIDER_FREQUENCIES_ONLY = true;
	private final String[] evidenceCodes = null;// new String[]{"PCS","ICE"};
	private int SIZE_OF_SCORE_DISTRIBUTION = 250000;
	private final int NUMBER_OF_BINS_IN_APPROXIMATED_SCORE_DISTRIBUTION = 10000;
	private int maxTerms = -1; /* Defines the maximal number of terms a query can have */
	private int maxFrequencyTerms = 10; /* Maximal number of frequency terms (k in the paper) */

	/** False positives can be explained via inheritance */
	private static int VARIANT_INHERITANCE_POSITIVES = 1 << 0;

	/** False negatives can be explained via inheritance */
	private static int VARIANT_INHERITANCE_NEGATIVES = 1 << 1;

	/** Model respects frequencies */
	private static int VARIANT_RESPECT_FREQUENCIES = 1 << 2;

	/** Defines the model as a combination of above flags */
	private int MODEL_VARIANT = VARIANT_RESPECT_FREQUENCIES | VARIANT_INHERITANCE_NEGATIVES;// |
																							// VARIANT_INHERITANCE_POSITIVES;

	/** If set to true, empty observation are allowed */
	private boolean ALLOW_EMPTY_OBSERVATIONS = false;

	/** Activate debugging */
	private final boolean DEBUG = false;

	/** Precalculate the jaccard matrix */
	private boolean PRECALCULATE_JACCARD = false;

	/** Use cached MaxIC terms. Speeds up Resnik */
	private boolean PRECALCULATE_MAXICS = true;

	/** Use precalculated max items. Speeds up Resnik */
	private boolean PRECALCULATE_ITEM_MAXS = true;

	/** Cache the queries */
	private final boolean CACHE_RANDOM_QUERIES = true;

	/** Forbid illegal queries */
	private final boolean FORBID_ILLEGAL_QUERIES = true;

	/** Cache the score distribution during calculation */
	private boolean CACHE_SCORE_DISTRIBUTION = true;

	/** Precalculate score distribution. Always implies CACHE_SCORE_DISTRIBUTION. */
	private boolean PRECALCULATE_SCORE_DISTRIBUTION = true;

	/** Tries to load the score distribution */
	private boolean TRY_LOADING_SCORE_DISTRIBUTION = true;

	/** Identifies whether score distribution should be stored */
	private boolean STORE_SCORE_DISTRIBUTION = true;

	/** Defines the maximal query size for the cached distribution */
	private int MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION = 20;

	/** Some more verbose output */
	private final boolean VERBOSE = false;

	/* Some configuration stuff */

	/**
	 * Sets the number of terms that can be selected to be on during the simulation.
	 * 
	 * @param maxTerms
	 */
	public void setSimulationMaxTerms(int maxTerms) {
		this.maxTerms = maxTerms;
	}

	/**
	 * Returns the number of terms that can be selected to be on during the
	 * simulation.
	 * 
	 * @return
	 */
	public int getSimulationMaxTerms() {
		return maxTerms;
	}

	/**
	 * Set alpha value used for generateObservations() and used for the ideal FABN
	 * scoring.
	 * 
	 * @param alpha
	 */
	public void setSimulationAlpha(double alpha) {
		ALPHA = alpha;
	}

	/**
	 * Returns the simulation alpha.
	 * 
	 * @return
	 */
	public double getSimulationAlpha() {
		return ALPHA;
	}

	/**
	 * Set beta value used for generateObservations() and used for the ideal FABN
	 * scoring.
	 * 
	 * @param alpha
	 */
	public void setSimulationBeta(double beta) {
		BETA = beta;
	}

	/**
	 * Returns the simulation beta.
	 * 
	 * @return
	 */
	public double getSimulationBeta() {
		return BETA;
	}

	/**
	 * Sets, whether only items with frequencies should be considered.
	 * 
	 * @param frequencies
	 */
	public void setConsiderFrequenciesOnly(boolean frequencies) {
		CONSIDER_FREQUENCIES_ONLY = frequencies;
	}

	/**
	 * Returns, whether only items with frequencies should be considered.
	 * 
	 * @return
	 */
	public boolean getConsiderFrequenciesOnly() {
		return CONSIDER_FREQUENCIES_ONLY;
	}

	/**
	 * Sets the maximum query size of for a cached distribution.
	 * 
	 * @param size
	 */
	public void setMaxQuerySizeForCachedDistribution(int size) {
		MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION = size;
	}

	/**
	 * Precalculate score distribution.
	 * 
	 * @param precalc
	 */
	public void setPrecalculateScoreDistribution(boolean precalc) {
		PRECALCULATE_SCORE_DISTRIBUTION = precalc;
	}

	/**
	 * Sets whether jaccard similarity shall be precalculated.
	 * 
	 * @param precalc
	 */
	public void setPrecalculateJaccard(boolean precalc) {
		this.PRECALCULATE_JACCARD = precalc;
	}

	/**
	 * Sets, whether maxICs should be precalculated.
	 * 
	 * @param precalc
	 */
	public void setPrecalculateMaxICs(boolean precalc) {
		this.PRECALCULATE_MAXICS = precalc;
	}

	/**
	 * Set whether we cache the score distribution.
	 * 
	 * @param cache
	 */
	public void setCacheScoreDistribution(boolean cache) {
		CACHE_SCORE_DISTRIBUTION = cache;
	}

	/**
	 * Set whether we store the score distribution.
	 * 
	 * @param store
	 */
	public void setStoreScoreDistriubtion(boolean store) {
		STORE_SCORE_DISTRIBUTION = store;
	}

	/**
	 * Sets whether the matrix that contains the max ic term of two given terms
	 * shall be precalculated.
	 * 
	 * @param precalc
	 */
	public void setPrecalculateItemMaxs(boolean precalc) {
		PRECALCULATE_ITEM_MAXS = precalc;
	}

	/**
	 * Sets whether score distribution should be loaded.
	 * 
	 * @param loading
	 * @return
	 */
	public void setTryLoadingScoreDistribution(boolean loading) {
		TRY_LOADING_SCORE_DISTRIBUTION = loading;
	}

	/**
	 * Sets the size of the score distribution.
	 * 
	 * @param size
	 */
	public void setSizeOfScoreDistribution(int size) {
		SIZE_OF_SCORE_DISTRIBUTION = size;
	}

	/**
	 * Returns the size of the score distribution.
	 * 
	 * @return
	 */
	public int getSizeOfScoreDistribution() {
		return SIZE_OF_SCORE_DISTRIBUTION;
	}

	/**
	 * Returns the number of terms considered in for frequency analysis.
	 * 
	 * @return
	 */
	public int getMaxFrequencyTerms() {
		return maxFrequencyTerms;
	}

	/**
	 * Sets the number of terms considered in for frequency analysis.
	 * 
	 * @param newMaxFrequencyTerms
	 */
	public void setMaxFrequencyTerms(int newMaxFrequencyTerms) {
		maxFrequencyTerms = newMaxFrequencyTerms;
	}

	/**
	 * Returns whether false negatives are propagated in a top-down fashion.
	 * 
	 * @return
	 */
	public boolean areFalseNegativesPropagated() {
		return (MODEL_VARIANT & VARIANT_INHERITANCE_POSITIVES) != 0;
	}

	/**
	 * Returns whether false positives are propagated in a bottom-up fashion.
	 * 
	 * @return
	 */
	public boolean areFalsePositivesPropagated() {
		return (MODEL_VARIANT & VARIANT_INHERITANCE_NEGATIVES) != 0;
	}

	/**
	 * Returns whether all false stuff is propagated.
	 * 
	 * @return
	 */
	public boolean allFalsesArePropagated() {
		return areFalseNegativesPropagated() && areFalsePositivesPropagated();
	}

	/**
	 * Returns whether frequencies should be respected.
	 * 
	 * @return
	 */
	public boolean respectFrequencies() {
		return (MODEL_VARIANT & VARIANT_RESPECT_FREQUENCIES) != 0;
	}

	/**
	 * Samples from the LPD of the node
	 * 
	 * @param rnd
	 * @param node
	 * @param hidden
	 * @param observed
	 * @return
	 */
	private boolean observeNode(Random rnd, int node, boolean[] hidden, boolean[] observed) {
		if (areFalsePositivesPropagated()) {
			/* Here, we consider that false positives will be inherited */
			for (int i = 0; i < term2Children[node].length; i++) {
				int chld = term2Children[node][i];
				if (observed[chld])
					return true;
			}
		}

		if (areFalseNegativesPropagated()) {
			/* Here, we consider that false negatives will be inherited */
			for (int i = 0; i < term2Parents[node].length; i++) {
				int parent = term2Parents[node][i];
				if (!observed[parent])
					return false;
			}
		}

		if (hidden[node])
			return rnd.nextDouble() > BETA; /* false negative */
		else
			return rnd.nextDouble() < ALPHA; /* false positive */
	}

	/**
	 * Returns the case for the given node, given the hidden and observed states.
	 * 
	 * @param node
	 * @param hidden
	 * @param observed
	 * @return
	 */
	private Configuration.NodeCase getNodeCase(int node, boolean[] hidden, boolean[] observed) {
		if (areFalsePositivesPropagated()) {
			/* Here, we consider that false positives are inherited */
			for (int i = 0; i < term2Children[node].length; i++) {
				int chld = term2Children[node][i];
				if (observed[chld]) {
					if (observed[node])
						return Configuration.NodeCase.INHERIT_TRUE;
					else {
						/* NaN */
						System.err.println(
								"A child of a node is on although the parent is not: Impossible configuration encountered!");
						return Configuration.NodeCase.FAULT;
					}
				}
			}
		}

		if (areFalseNegativesPropagated()) {
			/* Here, we consider that false negatives are inherited */
			for (int i = 0; i < term2Parents[node].length; i++) {
				int parent = term2Parents[node][i];
				if (!observed[parent]) {
					if (!observed[node])
						return Configuration.NodeCase.INHERIT_FALSE;
					else {
						/* NaN */
						System.err.println(
								"A parent of a node is off although the child is not: Impossible configuration encountered!");
						return Configuration.NodeCase.FAULT;
					}
				}
			}
		}

		if (hidden[node]) {
			/* Term is truly on */
			if (observed[node])
				return Configuration.NodeCase.TRUE_POSITIVE;
			else
				return Configuration.NodeCase.FALSE_NEGATIVE;
		}
		else {
			/* Term is truly off */
			if (!observed[node])
				return Configuration.NodeCase.TRUE_NEGATIVE;
			else
				return Configuration.NodeCase.FALSE_POSITIVE;
		}
	}

	/**
	 * Determines the cases of the observed states given the hidden states.
	 * Accumulates them in states.
	 * 
	 * @param observedTerms
	 * @param hidden
	 * @param stats
	 */
	private void determineCases(boolean[] observedTerms, boolean[] hidden, Configuration stats) {
		int numTerms = slimGraph.getNumberOfVertices();

		for (int i = 0; i < numTerms; i++) {
			Configuration.NodeCase c = getNodeCase(i, hidden, observedTerms);
			stats.increment(c);
		}
	}

	/**
	 * Indicates whether we want to measure the time of the algorithm. Used for
	 * profiling.
	 */
	private static final boolean MEASURE_TIME = false;

	private long timeDuration;

	/**
	 * Determines the case of the given items and the given observations.
	 * 
	 * @param item
	 * @param observed
	 * @param takeFrequenciesIntoAccount
	 *            select, if frequencies should be taken into account.
	 * @param previousHidden
	 *            is the storage used to store the hidden states. It must correspond
	 *            to the states of the previous item (item - 1). If this is the
	 *            first item, all elements must be initialized to 0. The supplied
	 *            object will be updated upon the return of this function with the
	 *            state of specified item.
	 * @param previousStats
	 *            is should correspond to the configuration of the previous item
	 *            (i.e., item - 1. If the configuration for the first item shall be
	 *            determined it should correspond to the configuration if no item is
	 *            active. The supplied object will be updated upon the return of
	 *            this function with the state of specified item.
	 * @return
	 */
	private WeightedConfigurationList determineCasesForItem(int item, boolean[] observed,
			boolean takeFrequenciesIntoAccount, boolean[] previousHidden, Configuration previousStats) {
		int numAnnotatedTerms = items2TermFrequencies[item].length;
		int numTerms = slimGraph.getNumberOfVertices();

		if (previousHidden == null && previousStats != null)
			throw new IllegalArgumentException();
		if (previousHidden != null && previousStats == null)
			throw new IllegalArgumentException();

		long now;
		if (MEASURE_TIME)
			now = System.nanoTime();

		/* Tracks the hidden state configuration that matches the observed state best */
		// double bestScore = Double.NEGATIVE_INFINITY;
		// boolean [] bestTaken = new boolean[numTermsWithExplicitFrequencies];

		WeightedConfigurationList statsList = new WeightedConfigurationList();

		if (true) {
			boolean[] hidden;
			Configuration stats;

			if (previousHidden == null) {
				/*
				 * If no previous state was given, we have to explicitly generate the state of
				 * the previous item. Obviously, this will be much slower.
				 */
				hidden = new boolean[numTerms];
				stats = new Configuration();
				if (item > 0) {
					int[] prevItemDirectTerms = items2DirectTerms[item - 1];
					for (int i = 0; i < prevItemDirectTerms.length; i++)
						for (int j = 0; j < term2Ancestors[prevItemDirectTerms[i]].length; j++)
							hidden[term2Ancestors[prevItemDirectTerms[i]][j]] = true;
				}
				determineCases(observed, hidden, stats);
			}
			else {
				hidden = previousHidden;
				stats = previousStats;
			}

			if (!takeFrequenciesIntoAccount) {
				/* New */
				int[] diffOn = diffOnTerms[item];
				int[] diffOff = diffOffTerms[item];

				/* Decrement config stats of the nodes we are going to change */
				for (int i = 0; i < diffOn.length; i++)
					stats.decrement(getNodeCase(diffOn[i], hidden, observed));
				for (int i = 0; i < diffOff.length; i++)
					stats.decrement(getNodeCase(diffOff[i], hidden, observed));

				/* Change nodes states */
				for (int i = 0; i < diffOn.length; i++)
					hidden[diffOn[i]] = true;
				for (int i = 0; i < diffOff.length; i++)
					hidden[diffOff[i]] = false;

				/* Increment config states of nodes that we have just changed */
				for (int i = 0; i < diffOn.length; i++)
					stats.increment(getNodeCase(diffOn[i], hidden, observed));
				for (int i = 0; i < diffOff.length; i++)
					stats.increment(getNodeCase(diffOff[i], hidden, observed));

				/* Old TODO: Move this into a test */
				if (false) {
					boolean[] oldHidden = new boolean[numTerms];
					Configuration oldStats = new Configuration();
					for (int h : items2DirectTerms[item]) {
						oldHidden[h] = true;
						activateAncestors(h, oldHidden);
					}
					determineCases(observed, oldHidden, oldStats);
					if (!oldStats.equals(stats))
						throw new RuntimeException("States don't match");
					statsList.add(oldStats, 0);
				}
				else {
					statsList.add(stats.clone(), 0);
				}
			}
			else {
				/* Initialize stats */
				if (previousHidden != null) {
					for (int i = 0; i < hidden.length; i++)
						hidden[i] = false;
				}
				stats.clear();
				determineCases(observed, hidden, stats);

				/*
				 * Loop over all tracked configurations that may appear due to the given item
				 * being active
				 */
				for (int c = 0; c < diffOnTermsFreqs[item].length; c++) {
					int[] diffOn = diffOnTermsFreqs[item][c];
					int[] diffOff = diffOffTermsFreqs[item][c];

					/* Decrement config stats of the nodes we are going to change */
					for (int i = 0; i < diffOn.length; i++)
						stats.decrement(getNodeCase(diffOn[i], hidden, observed));
					for (int i = 0; i < diffOff.length; i++)
						stats.decrement(getNodeCase(diffOff[i], hidden, observed));

					/* Change nodes states */
					for (int i = 0; i < diffOn.length; i++)
						hidden[diffOn[i]] = true;
					for (int i = 0; i < diffOff.length; i++)
						hidden[diffOff[i]] = false;

					/* Increment config states of nodes that we have just changed */
					for (int i = 0; i < diffOn.length; i++)
						stats.increment(getNodeCase(diffOn[i], hidden, observed));
					for (int i = 0; i < diffOff.length; i++)
						stats.increment(getNodeCase(diffOff[i], hidden, observed));

					/* Determine cases and store */
					statsList.add(stats.clone(), factors[item][c]);
				}
			}
		}
		else {
			/* TODO: Move this into a test */
			int numTermsWithExplicitFrequencies = 0;
			if (takeFrequenciesIntoAccount) {
				/*
				 * Determine the number of terms that have non-1.0 frequency. We restrict them
				 * to the top 6 (the less probable) due to complexity issues and hope that this
				 * a good enough approximation.
				 */
				for (int i = 0; i < numAnnotatedTerms && i < maxFrequencyTerms; i++) {
					if (items2TermFrequencies[item][item2TermFrequenciesOrder[item][i]] >= 1.0)
						break;
					numTermsWithExplicitFrequencies++;
				}
			}

			/*
			 * We try each possible activity/inactivity combination of terms with explicit
			 * frequencies
			 */
			SubsetGenerator sg = new SubsetGenerator(numTermsWithExplicitFrequencies, numTermsWithExplicitFrequencies);// numTermsWithExplicitFrequencies);
			SubsetGenerator.Subset s;

			while ((s = sg.next()) != null) {
				double factor = 0.0;
				boolean[] hidden = new boolean[numTerms];
				boolean[] taken = new boolean[numTermsWithExplicitFrequencies];

				/* first, activate variable terms according to the current selection */
				for (int i = 0; i < s.r; i++) {
					int ti = item2TermFrequenciesOrder[item][s.j[i]]; /*
																		 * index of term within the all directly
																		 * associated indices
																		 */
					int h = items2DirectTerms[item][ti]; /* global index of term */
					hidden[h] = true;
					activateAncestors(h, hidden);
					factor += Math.log(items2TermFrequencies[item][ti]);
					taken[s.j[i]] = true;
				}

				for (int i = 0; i < numTermsWithExplicitFrequencies; i++) {
					if (!taken[i])
						factor += Math.log(1 - items2TermFrequencies[item][item2TermFrequenciesOrder[item][i]]);
				}

				/* second, activate mandatory terms */
				for (int i = numTermsWithExplicitFrequencies; i < numAnnotatedTerms; i++) {
					int ti = item2TermFrequenciesOrder[item][i];
					int h = items2DirectTerms[item][ti]; /* global index of term */
					hidden[h] = true;
					activateAncestors(h, hidden);
				}

				/* Determine cases and store */
				Configuration stats = new Configuration();
				determineCases(observed, hidden, stats);
				statsList.add(stats, factor);
			}
		}

		if (MEASURE_TIME) {
			timeDuration += System.nanoTime() - now;
			System.out.println(timeDuration / (1000 * 1000) + " " + statsList.size());
		}

		return statsList;
	}

	/**
	 * Returns the log probability that the given term has the observed state given
	 * the hidden states.
	 * 
	 * If one of its more specific terms (descendants in this case) are on then the
	 * probability that the observed term is on is one. Otherwise the probability
	 * depends on the false-positive/false-negative rate.
	 * 
	 * @param termIndex
	 * @param alpha
	 * @param beta
	 * @param hidden
	 * @param observed
	 * @return
	 */
	public double scoreNode(int termIndex, double alpha, double beta, boolean[] hidden, boolean[] observed) {
		double score = 0.0;

		Configuration.NodeCase c = getNodeCase(termIndex, hidden, observed);

		switch (c) {
		case FALSE_NEGATIVE:
			score = Math.log(beta);
			break;
		case FALSE_POSITIVE:
			score = Math.log(alpha);
			break;
		case TRUE_POSITIVE:
			score = Math.log(1 - beta);
			break;
		case TRUE_NEGATIVE:
			score = Math.log(1 - alpha);
			break;
		case INHERIT_FALSE:
			score = Math.log(1);
			break;
		case INHERIT_TRUE:
			score = Math.log(1);
			break;
		}
		return score;
	}

	/**
	 * Score a hidden configuration given the observations.
	 * 
	 * @param observedTerms
	 * @param stats
	 * @param score
	 * @param hidden
	 * @return
	 */
	@SuppressWarnings("unused")
	private double scoreHidden(boolean[] observedTerms, double alpha, double beta, boolean[] hidden) {
		Configuration stats = new Configuration();
		determineCases(observedTerms, hidden, stats);
		double newScore = stats.getScore(alpha, beta);
		return newScore;
	}

	/**
	 * Calculates the score, when the given item is activated.
	 * 
	 * @param item
	 *            which is supposed to be active.
	 * @param observedTerms
	 * @param stats,
	 *            some statistics about false positives etc.
	 * @param takeFrequenciesIntoAccount
	 * @return
	 */
	public double score(int item, double alpha, double beta, boolean[] observedTerms,
			boolean takeFrequenciesIntoAccount) {
		WeightedConfigurationList stats = determineCasesForItem(item, observedTerms, takeFrequenciesIntoAccount, null,
				null);
		return stats.score(alpha, beta);
	}

	/**
	 * Returns the result of a logical or operation of the parents state.
	 * 
	 * @param v
	 * @param states
	 * @return
	 */
	public boolean orParents(int v, boolean[] states) {
		int[] parents = term2Parents[v];
		for (int i = 0; i < parents.length; i++)
			if (states[parents[i]])
				return true;
		return false;
	}

	/**
	 * Returns the result of a logical and operation of the parents state.
	 * 
	 * @param v
	 * @param states
	 * @return
	 */
	public boolean andParents(int v, boolean[] states) {
		int[] parents = term2Parents[v];
		for (int i = 0; i < parents.length; i++)
			if (!states[parents[i]])
				return false;
		return true;
	}

	/**
	 * Returns the result of a logical and operation of the children state.
	 * 
	 * @param v
	 * @param states
	 * @return
	 */
	public boolean andChildren(int v, boolean[] states) {
		int[] children = term2Children[v];
		for (int i = 0; i < children.length; i++)
			if (!states[children[i]])
				return false;
		return true;
	}

	/**
	 * Returns the result of a logical or operation of the children state.
	 * 
	 * @param v
	 * @param states
	 * @return
	 */
	public boolean orChildren(int v, boolean[] states) {
		int[] children = term2Children[v];
		for (int i = 0; i < children.length; i++)
			if (states[children[i]])
				return true;
		return false;
	}

	/**
	 * Generates observation according to the model parameter for the given item.
	 * 
	 * @param item
	 * @return
	 */
	public BenchmarkObservations generateObservations(int item, Random rnd) {
		int retry = 0;

		BenchmarkObservations o = null;

		do {
			int i;
			int[] falsePositive = new int[slimGraph.getNumberOfVertices()];
			int numFalsePositive = 0;
			int[] falseNegative = new int[slimGraph.getNumberOfVertices()];
			int numFalseNegative = 0;
			int numMissedInHidden = 0;

			int numPositive = 0;
			int numHidden = 0;

			boolean[] observations = new boolean[slimGraph.getNumberOfVertices()];
			boolean[] hidden = new boolean[slimGraph.getNumberOfVertices()];

			boolean CONSIDER_ONLY_DIRECT_ASSOCIATIONS = true;

			if (CONSIDER_ONLY_DIRECT_ASSOCIATIONS) {
				if (VERBOSE)
					System.out.println("Item " + item + " has " + items2DirectTerms[item].length + " annotations");
				for (i = 0; i < items2DirectTerms[item].length; i++) {
					boolean state = true;

					if (respectFrequencies()) {
						state = rnd.nextDouble() < items2TermFrequencies[item][i];

						if (VERBOSE)
							System.out.println(
									items2DirectTerms[item][i] + "(" + items2TermFrequencies[item][i] + ")=" + state);
					}

					if (state) {
						hidden[items2DirectTerms[item][i]] = state;
						observations[items2DirectTerms[item][i]] = state;

						activateAncestors(items2DirectTerms[item][i], hidden);
						activateAncestors(items2DirectTerms[item][i], observations);

						numPositive++;
					}
					else {
						numMissedInHidden++;
					}
				}

			}
			else {
				for (i = 0; i < items2Terms[item].length; i++) {
					hidden[items2Terms[item][i]] = true;
					observations[items2Terms[item][i]] = true;
					numPositive++;
				}
			}

			/* Fill in false and true positives */
			for (i = 0; i < observations.length; i++) {
				double r = rnd.nextDouble();
				if (observations[i]) {
					if (r < BETA) {
						falseNegative[numFalseNegative++] = i;
						// System.out.println("false negative " + i);
					}
				}
				else {
					if (r < ALPHA) {
						falsePositive[numFalsePositive++] = i;
						// System.out.println("false positive " + i);
					}
				}
			}

			/* apply false negatives */
			if (areFalseNegativesPropagated()) {
				/*
				 * false negative, but also make all descendants negative. They are considered
				 * as inherited in this case
				 */
				for (i = 0; i < numFalseNegative; i++) {
					observations[falseNegative[i]] = false;
					deactivateDecendants(falseNegative[i], observations);
				}
			}
			else {
				/* false negative */
				for (i = 0; i < numFalseNegative; i++)
					observations[falseNegative[i]] = false;

				/* fix for true path rule */
				for (i = 0; i < observations.length; i++) {
					if (observations[i])
						activateAncestors(i, observations);
				}
			}

			/* apply false positives */
			if (areFalsePositivesPropagated()) {
				/* fix for true path rule */
				for (i = 0; i < numFalsePositive; i++) {
					observations[falsePositive[i]] = true;
					activateAncestors(falsePositive[i], observations);
				}
			}
			else {
				/* False positive */
				for (i = 0; i < numFalsePositive; i++)
					observations[falsePositive[i]] = true;

				/* fix for the true path rule (reverse case) */
				for (i = 0; i < observations.length; i++) {
					if (!observations[i])
						deactivateDecendants(i, observations);
				}
			}

			if (maxTerms != -1) {
				IntArray sparse = new IntArray(observations);
				int[] mostSpecific = mostSpecificTerms(sparse.get());
				if (mostSpecific.length > maxTerms) {
					int[] newTerms = new int[maxTerms];

					/* Now randomly choose maxTerms and place them in new Terms */
					for (int j = 0; j < maxTerms; j++) {
						int r = rnd.nextInt(mostSpecific.length - j);
						newTerms[j] = mostSpecific[r];
						mostSpecific[r] = mostSpecific[mostSpecific.length - j
								- 1]; /* Move last selectable term into the place of the chosen one */
					}
					for (int j = 0; j < observations.length; j++)
						observations[j] = false;
					for (int t : newTerms) {
						observations[t] = true;
						activateAncestors(t, observations);
					}
				}
			}

			for (i = 0; i < hidden.length; i++)
				if (hidden[i])
					numHidden++;

			if (VERBOSE) {
				System.out.println("Number of terms that were missed in hidden: " + numMissedInHidden);
				System.out.println("Number of hidden positives:" + numPositive);
				System.out.println("Number of hidden negatives: " + numHidden);
			}

			numPositive = 0;
			numFalseNegative = 0;
			numFalsePositive = 0;
			for (i = 0; i < observations.length; i++) {
				if (observations[i]) {
					if (!hidden[i])
						numFalsePositive++;
					numPositive++;
				}
				else {
					if (hidden[i])
						numFalseNegative++;
				}
			}

			if (VERBOSE) {
				System.out.println("Number of observed positives:" + numPositive);
				System.out.println("Raw number of false positives: " + numFalsePositive);
				System.out.println("Raw number of false negatives " + numFalseNegative);
			}

			if (numPositive == 0 && !ALLOW_EMPTY_OBSERVATIONS) {
				/* Queries with no query make no sense */
				retry++;
				continue;
			}

			Configuration stats = new Configuration();
			determineCases(observations, hidden, stats);

			if (VERBOSE) {
				System.out.println(
						"Number of modelled false postives " + stats.getCases(Configuration.NodeCase.FALSE_POSITIVE)
								+ " (alpha=" + stats.falsePositiveRate() + "%)");
				System.out.println(
						"Number of modelled false negatives " + stats.getCases(Configuration.NodeCase.FALSE_NEGATIVE)
								+ " (beta=" + stats.falseNegativeRate() + "%)");
			}

			o = new BenchmarkObservations();
			o.item = item;
			o.observations = observations;
			o.observationStats = stats;
		} while (!ALLOW_EMPTY_OBSERVATIONS && retry++ < 50);
		return o;
	}

	/**
	 * Deactivate the ancestors of the given node.
	 * 
	 * @param i
	 * @param observations
	 */
	public void deactivateAncestors(int i, boolean[] observations) {
		for (int j = 0; j < term2Ancestors[i].length; j++)
			observations[term2Ancestors[i][j]] = false;
	}

	/**
	 * Activates the ancestors of the given node.
	 * 
	 * @param i
	 * @param observations
	 */
	public void activateAncestors(int i, boolean[] observations) {
		for (int j = 0; j < term2Ancestors[i].length; j++)
			observations[term2Ancestors[i][j]] = true;
	}

	/**
	 * Activates the ancestors of the given node.
	 * 
	 * @param i
	 * @param observations
	 */
	private void deactivateDecendants(int i, boolean[] observations) {
		for (int j = 0; j < term2Descendants[i].length; j++)
			observations[term2Descendants[i][j]] = false;
	}

	/**
	 * Extracts all items that have at least a single annotation with a frequency.
	 * 
	 * @return
	 */
	private Set<ByteString> extractItemsWithFrequencies(AssociationContainer assoc) {
		HashSet<ByteString> items = new HashSet<ByteString>();

		for (ByteString item : assoc.getAllAnnotatedGenes()) {
			boolean take = false;

			Gene2Associations item2Associations = assoc.get(item);
			for (Association a : item2Associations) {
				if (a.getAspect().length() != 0)
					take = true;
			}

			if (take)
				items.add(item);
		}

		return items;
	}

	/**
	 * Calculates a "fingerprint" for the current data. Note that the fingerprint is
	 * not necessary unqiue but it should be sufficient for the purpose.
	 * 
	 * @return
	 */
	private int fingerprint() {
		int fp = 0x3333;
		for (int i = 0; i < allItemList.size(); i++)
			fp += allItemList.get(i).hashCode();
		for (int i = 0; i < slimGraph.getNumberOfVertices(); i++) {
			fp += slimGraph.getVertex(i).getID().id;
			fp += slimGraph.getVertex(i).getName().hashCode();
		}
		fp += new Random(SIZE_OF_SCORE_DISTRIBUTION).nextInt();
		fp += new Random(MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION).nextInt();
		return fp;
	}

	/**
	 * Setups the BOQA for the given ontology and associations.
	 * 
	 * @param ontology
	 * @param associations
	 */
	public void setup(Ontology ontology, AssociationContainer associations) {
		graph = ontology;

		// graph.findRedundantISARelations();

		if (micaMatrix != null) {
			System.err.println("setup() called a 2nd time.");
			micaMatrix = null;
		}

		HashSet<ByteString> itemsToBeConsidered = new HashSet<ByteString>(associations.getAllAnnotatedGenes());
		provideGlobals(associations, itemsToBeConsidered);

		/*
		 * If we want to consider items with frequencies only, we like to shrink the
		 * item list to contain only the relevant items.
		 */
		if (CONSIDER_FREQUENCIES_ONLY) {
			System.out.println("WARNING: only considering items with frequency information");
			int oldSize = allItemList.size();

			itemsToBeConsidered = new HashSet<ByteString>();
			for (int i = 0; i < allItemList.size(); i++) {
				if (itemHasFrequencies[i])
					itemsToBeConsidered.add(allItemList.get(i));
			}
			if (itemsToBeConsidered.size() == 0)
				throw new RuntimeException("No items left after frequency filtering");
			provideGlobals(associations, itemsToBeConsidered);

			System.out.println("There were " + oldSize + " items but we consider only " + allItemList.size()
					+ " of them with frequencies.");
			System.out.println("Considering " + slimGraph.getNumberOfVertices() + " terms");
		}

		/**
		 * Here we precalculate the jaccard similiartiy of two given terms in a dense
		 * matrix
		 */
		if (PRECALCULATE_JACCARD) {
			logger.info("Calculating Jaccard");
			double[][] newJaccardMatrix = new double[slimGraph.getNumberOfVertices()][];
			for (int i = 0; i < slimGraph.getNumberOfVertices(); i++) {
				newJaccardMatrix[i] = new double[slimGraph.getNumberOfVertices() - i - 1];
				for (int j = i + 1; j < slimGraph.getNumberOfVertices(); j++)
					newJaccardMatrix[i][j - i - 1] = jaccard(i, j);
			}
			jaccardMatrix = newJaccardMatrix;
			logger.info("Calculated Jaccard");
		}

		/** Here we precalculate the maxICs of two given terms in a dense matrix */
		if (PRECALCULATE_MAXICS) {
			logger.info("Calculating max ICs");
			int[][] newMaxICMatrix = new int[slimGraph.getNumberOfVertices()][];
			for (int i = 0; i < slimGraph.getNumberOfVertices(); i++) {
				newMaxICMatrix[i] = new int[slimGraph.getNumberOfVertices() - i - 1];
				for (int j = i + 1; j < slimGraph.getNumberOfVertices(); j++)
					newMaxICMatrix[i][j - i - 1] = commonAncestorWithMaxIC(i, j);
			}
			micaMatrix = newMaxICMatrix;

			logger.info("Calculated max ICs");
		}

		/**
		 * Here we precalculate for each item the term which contributes as maximum ic
		 * term to the resnick calculation
		 */
		if (PRECALCULATE_ITEM_MAXS) {
			logger.info("Calculating item maxs");
			resnikTermSim.maxScoreForItem = new double[allItemList.size()][slimGraph.getNumberOfVertices()];
			linTermSim.maxScoreForItem = new double[allItemList.size()][slimGraph.getNumberOfVertices()];
			jcTermSim.maxScoreForItem = new double[allItemList.size()][slimGraph.getNumberOfVertices()];

			for (int item = 0; item < allItemList.size(); item++) {
				/* The fixed set */
				int[] t2 = items2DirectTerms[item];

				/* The set representing a single query term */
				int[] t1 = new int[1];

				for (int to = 0; to < slimGraph.getNumberOfVertices(); to++) {
					t1[0] = to;
					resnikTermSim.maxScoreForItem[item][to] = scoreMaxAvg(t1, t2, resnikTermSim);
					linTermSim.maxScoreForItem[item][to] = scoreMaxAvg(t1, t2, linTermSim);
					jcTermSim.maxScoreForItem[item][to] = scoreMaxAvg(t1, t2, jcTermSim);
				}
			}

			logger.info("Calculated item maxs");
		}

		resnikTermSim.setupDistribution();
		linTermSim.setupDistribution();
		jcTermSim.setupDistribution();

		/* Choose appropriate values */
		double numOfTerms = getSlimGraph().getNumberOfVertices();

		ALPHA_GRID = new double[] { 1e-10, 1 / numOfTerms, 2 / numOfTerms, 3 / numOfTerms, 4 / numOfTerms,
				5 / numOfTerms, 6 / numOfTerms };
		BETA_GRID = new double[] { 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95 };
	}

	/**
	 * Writes a dot suitable for tikz.
	 * 
	 * @param out
	 * @param hpoTerms
	 */
	public void writeDOTExample(File out, HashSet<TermID> hpoTerms) {
		/*
		 * Basically, this defines a new command \maxbox whose text width as given by
		 * the second argument is not wider than the first argument. The text which is
		 * then displayed in the box is used from the third argument.
		 */
		String preamble = "d2tfigpreamble=\"\\ifthenelse{\\isundefined{\\myboxlen}}{\\newlength{\\myboxlen}}{}"
				+ "\\newcommand*{\\maxbox}[3]{\\settowidth{\\myboxlen}{#2}" + "\\ifdim#1<\\myboxlen"
				+ "\\parbox{#1}{\\centering#3}" + "\\else" + "\\parbox{\\myboxlen}{\\centering#3}" + "\\fi}\"";

	}

	/**
	 * Write score distribution.
	 * 
	 * @throws IOException
	 */
	public void writeScoreDistribution(File f, int item) throws IOException {
		int[] shuffledTerms = new int[slimGraph.getNumberOfVertices()];

		/* Initialize shuffling */
		for (item = 0; item < shuffledTerms.length; item++)
			shuffledTerms[item] = item;

		FileWriter out = new FileWriter(f);

		Random rnd = new Random();

		for (int j = 0; j < SIZE_OF_SCORE_DISTRIBUTION; j++) {
			int q = 10;
			int[] randomizedTerms = new int[q];

			chooseTerms(rnd, q, randomizedTerms, shuffledTerms);
			double randomScore = resScoreVsItem(randomizedTerms, item);
			out.write(randomScore + " \n");
		}
		out.flush();
		out.close();

		logger.info("Score distribution for item " + allItemList.get(item) + " with " + items2DirectTerms[item].length
				+ " annotations written");
	}

	/**
	 * @return
	 */
	public static int getNumProcessors() {
		int numProcessors = MEASURE_TIME ? 1 : Runtime.getRuntime().availableProcessors();
		return numProcessors;
	}

	/**
	 * Provides some global variables, given the global graph, the global
	 * associations and the items.
	 * 
	 * @param allItemsToBeConsidered
	 */
	@SuppressWarnings("unused")
	private void provideGlobals(AssociationContainer assoc, Set<ByteString> allItemsToBeConsidered) {
		int i;

		/* list all evidence codes */
		HashMap<ByteString, Integer> evidences = new HashMap<ByteString, Integer>();
		for (Gene2Associations g2a : assoc) {
			for (Association a : g2a) {
				if (a.getEvidence() != null) {
					/* Worst implementation ever! */
					Integer evidence = evidences.get(a.getEvidence());
					if (evidence == null)
						evidence = 1;
					else
						evidence++;

					evidences.put(a.getEvidence(), evidence);
				}
			}
		}

		if (logger.isLoggable(Level.INFO)) {
			logger.info(allItemsToBeConsidered.size() + " items shall be considered");
			StringBuilder builder = new StringBuilder("Available evidences: ");
			for (Entry<ByteString, Integer> ev : evidences.entrySet())
				builder.append(ev.getKey().toString() + "->" + ev.getValue() + ",");
			logger.info(builder.toString());
		}

		if (evidenceCodes != null) {
			evidences.clear();
			for (String ev : evidenceCodes)
				evidences.put(new ByteString(ev), 1);

			if (logger.isLoggable(Level.INFO)) {
				StringBuilder builder = new StringBuilder("Requested evidences: ");
				for (ByteString ev : evidences.keySet())
					builder.append(ev.toString());
				logger.info(builder.toString());
			}
		}
		else {
			/* Means take everything */
			evidences = null;
		}

		PopulationSet allItems = new PopulationSet("all");
		allItems.addGenes(allItemsToBeConsidered);

		termEnumerator = allItems.enumerateGOTerms(graph, assoc, evidences != null ? evidences.keySet() : null);
		ItemEnumerator itemEnumerator = ItemEnumerator.createFromTermEnumerator(termEnumerator);

		/* Term stuff */
		/* fix by sebastian / I removed the following two lines */
		// Ontology inducedGraph =
		// graph.getInducedGraph(termEnumerator.getAllAnnotatedTermsAsList());
		// slimGraph = inducedGraph.getSlimGraphView();
		/*
		 * the following line is required to not break the system when terms are in the
		 * query that have not been seen before
		 */
		slimGraph = graph.getSlimGraphView();

		term2Parents = slimGraph.vertexParents;
		term2Children = slimGraph.vertexChildren;
		term2Ancestors = slimGraph.vertexAncestors;
		term2Descendants = slimGraph.vertexDescendants;
		// fix by sebastian
		termsInTopologicalOrder = slimGraph.getVertexIndices(graph.getTermsInTopologicalOrder());

		if (termsInTopologicalOrder.length != slimGraph.getNumberOfVertices())
			throw new RuntimeException("The ontology graph contains cycles.");
		termsToplogicalRank = new int[termsInTopologicalOrder.length];
		for (i = 0; i < termsInTopologicalOrder.length; i++)
			termsToplogicalRank[termsInTopologicalOrder[i]] = i;

		/* Item stuff */
		allItemList = new ArrayList<ByteString>();
		item2Index = new HashMap<ByteString, Integer>();
		i = 0;
		for (ByteString item : itemEnumerator) {
			allItemList.add(item);
			item2Index.put(item, i);
			i++;
		}

		logger.info(i + " items passed criterias (supplied evidence codes)");

		/* Fill item matrix */
		items2Terms = new int[allItemList.size()][];
		i = 0;
		for (ByteString item : itemEnumerator) {
			int j = 0;

			ArrayList<TermID> tids = itemEnumerator.getTermsAnnotatedToTheItem(item);
			items2Terms[i] = new int[tids.size()];

			for (TermID tid : tids)
				items2Terms[i][j++] = slimGraph.getVertexIndex(graph.getTerm(tid));

			Arrays.sort(items2Terms[i]);
			i++;
		}

		/* Fill direct item matrix */
		items2DirectTerms = new int[allItemList.size()][];
		i = 0;
		for (ByteString item : itemEnumerator) {
			int j = 0;

			ArrayList<TermID> tids = itemEnumerator.getTermsDirectlyAnnotatedToTheItem(item);

			if (false) {
				/* Perform sanity check */
				for (TermID s : tids) {
					for (TermID d : tids) {
						if (graph.existsPath(s, d) || graph.existsPath(d, s)) {
							System.out.println(
									"Item \"" + item + "\" is annotated to " + s.toString() + " and " + d.toString());
						}
					}
				}
			}

			// System.out.println(item.toString());
			items2DirectTerms[i] = new int[tids.size()];

			for (TermID tid : tids)
				items2DirectTerms[i][j++] = slimGraph.getVertexIndex(graph.getTerm(tid));
			i++;
		}

		/* Fill in frequencies for directly annotated terms. Also sort them */
		items2TermFrequencies = new double[allItemList.size()][];
		itemHasFrequencies = new boolean[allItemList.size()];
		item2TermFrequenciesOrder = new int[allItemList.size()][];
		for (i = 0; i < items2DirectTerms.length; i++) {
			/**
			 * A term and the corresponding frequency. We use this for sorting.
			 * 
			 * @author Sebastian Bauer
			 */
			class Freq implements Comparable<Freq> {
				public int termIdx;
				public double freq;

				@Override
				public int compareTo(Freq o) {
					if (freq > o.freq)
						return 1;
					if (freq < o.freq)
						return -1;
					return 0;
				}
			}

			items2TermFrequencies[i] = new double[items2DirectTerms[i].length];
			item2TermFrequenciesOrder[i] = new int[items2DirectTerms[i].length];
			Freq[] freqs = new Freq[items2DirectTerms[i].length];

			ByteString item = allItemList.get(i);
			Gene2Associations as = assoc.get(item);

			// Disabled
			// if (as.getAssociations().size() != items2DirectTerms[i].length)
			// throw new IllegalArgumentException("Number of associations differs (" +
			// as.getAssociations().size() + ") from the number of
			// directly annotated terms (" + items2DirectTerms[i].length + ").");

			for (int j = 0; j < items2DirectTerms[i].length; j++) {
				boolean hasExlipictFrequency = false;

				/* Default frequency */
				double f = 1.0;

				TermID tid = slimGraph.getVertex(items2DirectTerms[i][j]).getID();

				/* Find frequency. We now have a O(n^3) algo. Will be optimized later */
				for (Association a : as) {
					if (a.getTermID().equals(tid) && a.getAspect() != null) {
						f = getFrequencyFromString(a.getAspect().toString());
						if (f < 1.0)
							hasExlipictFrequency = true;
						/* We assume that the term appears only once */
						break;
					}
				}

				items2TermFrequencies[i][j] = f;
				freqs[j] = new Freq();
				freqs[j].termIdx = j;// items2DirectTerms[i][j];
				freqs[j].freq = f;

				if (hasExlipictFrequency)
					itemHasFrequencies[i] = true;
			}

			/* Now sort and remember the indices */
			Arrays.sort(freqs);
			for (int j = 0; j < items2DirectTerms[i].length; j++)
				item2TermFrequenciesOrder[i][j] = freqs[j].termIdx;
		}

		DiffVectors dv = DiffVectors.createDiffVectors(maxFrequencyTerms, slimGraph.getNumberOfVertices(), items2Terms,
				items2TermFrequencies, item2TermFrequenciesOrder, items2DirectTerms, term2Ancestors);
		diffOnTerms = dv.diffOnTerms;
		diffOffTerms = dv.diffOffTerms;
		diffOnTermsFreqs = dv.diffOnTermsFreqs;
		diffOffTermsFreqs = dv.diffOffTermsFreqs;
		factors = dv.factors;

		/* Calculate IC */
		terms2IC = new double[slimGraph.getNumberOfVertices()];
		for (i = 0; i < slimGraph.getNumberOfVertices(); i++) {
			Term t = slimGraph.getVertex(i);
			terms2IC[i] = -Math.log(
					((double) termEnumerator.getAnnotatedGenes(t.getID()).totalAnnotatedCount() / allItemList.size()));
		}

		ArrayList<Integer> itemIndices = new ArrayList<Integer>();
		for (int o = 0; o < allItemList.size(); o++)
			itemIndices.add(o);

		if (false) {
			System.out.println("Start TSP");
			long start = System.nanoTime();
			Algorithms.approximatedTSP(itemIndices, itemIndices.get(0), new Algorithms.IVertexDistance<Integer>() {
				@Override
				public double distance(Integer ai, Integer bi) {
					int[] at = items2Terms[ai.intValue()];
					int[] bt = items2Terms[bi.intValue()];
					return Algorithms.hammingDistanceSparse(at, bt);
				}
			});
			System.out.println("End (" + ((System.nanoTime() - start) / 1000 / 1000) + "ms)");
		}
	}

	/**
	 * Returns the number of items that are annotated to term i.
	 * 
	 * @param i
	 * @return
	 */
	public int getNumberOfItemsAnnotatedToTerm(int i) {
		Term t = slimGraph.getVertex(i);
		return termEnumerator.getAnnotatedGenes(t.getID()).totalAnnotatedCount();
	}

	/**
	 * Return the IC of term i
	 * 
	 * @param i
	 * @return
	 */
	public double getIC(int i) {
		return terms2IC[i];
	}

	/**
	 * Converts the frequency string to a double value.
	 * 
	 * @param freq
	 * @return
	 */
	private double getFrequencyFromString(String freq) {

		double d = OntologyConstants.frequencyOboId2double(freq);

		if (!(d >= 0 && d <= 1))
			System.err.println("problem converting: " + freq);

		return d;

		// double f = 1.0;
		//
		// if (freq == null || freq.length() == 0)
		// return 1.0;
		//
		// Matcher matcher = frequencyPattern.matcher(freq);
		// if (matcher.matches()) {
		// String fractionalPart = matcher.group(2);
		// if (fractionalPart == null || fractionalPart.length() == 0)
		// fractionalPart = "0";
		//
		// f = Double.parseDouble(matcher.group(1)) + Double.parseDouble(fractionalPart)
		// / Math.pow(10, fractionalPart.length());
		// f /= 100.0;
		// return f;
		// }
		// matcher = frequencyFractionPattern.matcher(freq);
		// // 12/30
		// if (matcher.matches()) {
		// f = Double.parseDouble(matcher.group(1)) /
		// Double.parseDouble(matcher.group(2));
		// return f;
		// }
		// // 12 of 30
		// matcher = NofMPattern.matcher(freq);
		// if (matcher.matches()) {
		// f = Double.parseDouble(matcher.group(1)) /
		// Double.parseDouble(matcher.group(2));
		// return f;
		// }
		//
		// // replace some of the legacy wordings:
		// if (freq.equalsIgnoreCase("typical") || freq.equalsIgnoreCase("common") ||
		// freq.equalsIgnoreCase("variable")) {
		// freq = "frequent";
		// }
		// if (freq.equalsIgnoreCase("hallmark")) {
		// freq = "very frequent";
		// }
		// if (freq.equalsIgnoreCase("rare")) {
		// freq = "occasional";
		// }
		//
		// // revision because new frequency identifiers apply now
		// if (freq.equalsIgnoreCase("very rare"))
		// f = 0.02;
		// else if (freq.equalsIgnoreCase("occasional"))
		// f = 0.1;
		// else if (freq.equalsIgnoreCase("frequent"))
		// f = 0.5;
		// else if (freq.equalsIgnoreCase("very frequent"))
		// f = 0.90;
		// else if (freq.equalsIgnoreCase("obligate"))
		// f = 1;
		// else
		// System.err.println("Unknown frequency identifier: " + freq);
		// return f;
	}

	/**
	 * This is a container for the results of the class.
	 * 
	 * @author Sebastian Bauer
	 */
	static public class Result {
		/** Contains the marginal probability for each item */
		private double[] marginals;

		/** Contains the marginal probability for each item */
		private double[] marginalsIdeal;
		private double[] scores;

		/** Some statistics for each item (number of false-positives, etc. ) */
		Configuration[] stats;

		/**
		 * Get the score of the given item.
		 * 
		 * @param i
		 * @return
		 */
		public double getScore(int i) {
			return scores[i];
		}

		/**
		 * Get the marginal probability of the given item.
		 * 
		 * @param i
		 * @return
		 */
		public double getMarginal(int i) {
			return marginals[i];
		}

		public double getMarginalIdeal(int i) {
			return marginalsIdeal[i];
		}

		public Configuration getStats(int i) {
			return stats[i];
		}

		public int size() {
			return marginals.length;
		}
	}

	/**
	 * Returns marginal probabilities for the (sparsely) given queries/observations.
	 * The terms are specified as plain int values that range between 0 and the
	 * number of the terms of the ontology.
	 * 
	 * @param takeFrequenciesIntoAccount
	 * @param observations
	 *            the ids of the terms. All specified terms are considered to be on.
	 *            All other are considered to be off.
	 * @return
	 */
	public Result assignMarginals(boolean takeFrequenciesIntoAccount, int... observations) {
		Observations o = Observations.createFromSparseOnArray(this, observations);
		return assignMarginals(o, takeFrequenciesIntoAccount);
	}

	/**
	 * Provides the marginals for the observations.
	 * 
	 * @param observations
	 * @param takeFrequenciesIntoAccount
	 * @return
	 */
	public Result assignMarginals(Observations observations, boolean takeFrequenciesIntoAccount) {
		return assignMarginals(observations, takeFrequenciesIntoAccount, 1);
	}

	/**
	 * Provides the marginals for the observations.
	 * 
	 * @param observations
	 * @param takeFrequenciesIntoAccount
	 * @param numThreads
	 *            defines the number of threads to be used for the calculation.
	 * @return
	 */
	public Result assignMarginals(final Observations observations, final boolean takeFrequenciesIntoAccount,
			final int numThreads) {
		int i;

		final Result res = new Result();
		res.scores = new double[allItemList.size()];
		res.marginals = new double[allItemList.size()];
		res.marginalsIdeal = new double[allItemList.size()];
		res.stats = new Configuration[allItemList.size()];

		for (i = 0; i < res.stats.length; i++)
			res.stats[i] = new Configuration();
		for (i = 0; i < res.scores.length; i++)
			res.scores[i] = Math.log(0);

		final BenchmarkObservations benchmarkObservations;
		if (observations instanceof BenchmarkObservations)
			benchmarkObservations = (BenchmarkObservations) observations;
		else
			benchmarkObservations = null;

		final double[][][] scores = new double[allItemList.size()][ALPHA_GRID.length][BETA_GRID.length];
		final double[] idealScores = new double[allItemList.size()];

		final ExecutorService es;
		if (numThreads > 1)
			es = Executors.newFixedThreadPool(numThreads);
		else
			es = null;

		/* Initialize values if no item is selected */
		final boolean[] previousHidden = new boolean[slimGraph.getNumberOfVertices()];
		final Configuration previousStat = new Configuration();
		determineCases(observations.observations, previousHidden, previousStat);

		ArrayList<Future<?>> futureList = new ArrayList<Future<?>>();

		for (i = 0; i < allItemList.size(); i++) {
			final int item = i;

			/* Construct the runnable suitable for the calculation for a single item */
			Runnable run = new Runnable() {

				@Override
				public void run() {
					WeightedConfigurationList stats = determineCasesForItem(item, observations.observations,
							takeFrequenciesIntoAccount, numThreads > 1 ? null : previousHidden,
							numThreads > 1 ? null : previousStat);

					if (BOQACore.debugThis != null && allItemList.get(item).toString().contains(BOQACore.debugThis))
						stats.doPrint();

					for (int a = 0; a < ALPHA_GRID.length; a++) {
						for (int b = 0; b < BETA_GRID.length; b++) {
							scores[item][a][b] = stats.score(ALPHA_GRID[a], BETA_GRID[b]);
							res.scores[item] = Util.logAdd(res.scores[item], scores[item][a][b]);
						}
					}

					if (benchmarkObservations != null) {
						/* This is used only for benchmarks, where we know the true configuration */
						if (benchmarkObservations.observationStats != null) {
							/* Calculate ideal scores */
							double fpr = benchmarkObservations.observationStats.falsePositiveRate();
							if (fpr == 0)
								fpr = 0.0000001;
							else if (fpr == 1.0)
								fpr = 0.999999;
							else if (Double.isNaN(fpr))
								fpr = 0.5;

							double fnr = benchmarkObservations.observationStats.falseNegativeRate();
							if (fnr == 0)
								fnr = 0.0000001;
							else if (fnr == 1)
								fnr = 0.999999;
							else if (Double.isNaN(fnr))
								fnr = 0.5;

							idealScores[item] = stats.score(fpr, fnr);
						}

					}
				}
			};

			if (es != null)
				futureList.add(es.submit(run));
			else
				run.run();
		}

		if (es != null) {
			es.shutdown();

			for (Future<?> f : futureList) {
				try {
					f.get();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			try {
				while (!es.awaitTermination(10, TimeUnit.SECONDS))
					;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		double normalization = Math.log(0);
		double idealNormalization = Math.log(0);

		for (i = 0; i < allItemList.size(); i++) {
			normalization = Util.logAdd(normalization, res.scores[i]);
			idealNormalization = Util.logAdd(idealNormalization, idealScores[i]);
		}

		for (i = 0; i < allItemList.size(); i++) {
			res.marginals[i] = Math.min(Math.exp(res.scores[i] - normalization), 1);
			res.marginalsIdeal[i] = Math.min(Math.exp(idealScores[i] - idealNormalization), 1);

			// System.out.println(i + ": " + idealScores[i] + " (" + res.getMarginalIdeal(i)
			// + ") " + res.scores[i] + " (" +
			// res.getMarginal(i) + ")");
			// System.out.println(res.marginals[i] + " " + res.marginalsIdeal[i]);
		}

		if (benchmarkObservations != null) {
			/*
			 * There is a possibility that ideal marginal is not as good as the marginal for
			 * the unknown parameter situation, i.e., if the initial signal got such
			 * disrupted that another item is more likely. This may produce strange plots.
			 * Therefore, we take the parameter estimated marginals as the ideal one if they
			 * match the reality better.
			 */
			if (res.marginalsIdeal[benchmarkObservations.item] < res.marginals[benchmarkObservations.item]) {
				for (i = 0; i < allItemList.size(); i++)
					res.marginalsIdeal[i] = res.marginals[i];
			}
		}

		// System.out.println(idealNormalization + " " + normalization);
		// if (exitNow)
		// System.exit(10);
		return res;
	}

	static long time;
	static long lastTime;

	/**
	 * Return a common ancestor of t1 and t2 that have max ic.
	 * 
	 * @param t1
	 * @param t2
	 * @return
	 */
	private int commonAncestorWithMaxIC(int t1, int t2) {
		if (micaMatrix != null) {
			if (t1 < t2)
				return micaMatrix[t1][t2 - t1 - 1];
			else if (t2 < t1)
				return micaMatrix[t2][t1 - t2 - 1];
			else
				return t1;
		}

		/* A rather slow implementation */
		int[] ancestorsA;
		int[] ancestorsB;

		if (term2Ancestors[t1].length > term2Ancestors[t2].length) {
			ancestorsA = term2Ancestors[t1];
			ancestorsB = term2Ancestors[t2];
		}
		else {
			ancestorsA = term2Ancestors[t1];
			ancestorsB = term2Ancestors[t2];
		}

		int bestTerm = -1;
		double bestIC = Double.NEGATIVE_INFINITY;

		for (int i = 0; i < ancestorsA.length; i++) {
			for (int j = 0; j < ancestorsB.length; j++) {
				if (ancestorsA[i] == ancestorsB[j]) {
					/* Ancestor is a common one */
					int term = ancestorsA[i];
					double ic = terms2IC[term];

					if (ic > bestIC) {
						bestIC = ic;
						bestTerm = term;
					}
					break;
				}
			}
		}

		if (bestTerm == -1) {
			throw new RuntimeException("No best term found, which is strange.");
		}

		return bestTerm;
	}

	/**
	 * Returns the jaccard index of the given two terms.
	 * 
	 * @param t1
	 * @param t2
	 * @return
	 */
	public double jaccard(int t1, int t2) {
		if (t1 == t2)
			return 1;

		if (jaccardMatrix != null) {
			if (t1 < t2)
				return jaccardMatrix[t1][t2 - t1 - 1];
			else
				return jaccardMatrix[t2][t1 - t2 - 1];
		}

		Term tt1 = slimGraph.getVertex(t1);
		Term tt2 = slimGraph.getVertex(t2);
		HashSet<ByteString> tt1a = new HashSet<ByteString>(
				termEnumerator.getAnnotatedGenes(tt1.getID()).totalAnnotated);
		HashSet<ByteString> tt2a = new HashSet<ByteString>(
				termEnumerator.getAnnotatedGenes(tt2.getID()).totalAnnotated);
		HashSet<ByteString> union = new HashSet<ByteString>(tt1a);
		union.addAll(tt2a);

		tt1a.retainAll(tt2a);

		return (double) tt1a.size() / union.size();
	}

	/**
	 * Returns a minimal length array of terms of which the induced graph is the
	 * same as of the given terms. These are the leaf terms.
	 * 
	 * @param terms
	 * @return
	 */
	public int[] mostSpecificTerms(int[] terms) {
		ArrayList<TermID> termList = new ArrayList<TermID>(terms.length);
		for (int i = 0; i < terms.length; i++)
			termList.add(slimGraph.getVertex(terms[i]).getID());

		Ontology termGraph = graph.getInducedGraph(termList);

		ArrayList<Term> leafTermList = termGraph.getLeafTerms();

		int[] specifcTerms = new int[leafTermList.size()];
		int i = 0;

		for (Term t : termGraph.getLeafTerms())
			specifcTerms[i++] = slimGraph.getVertexIndex(t);

		return specifcTerms;
	}

	/**
	 * Gets a sparse representation of the most specific terms in the observation
	 * map.
	 * 
	 * @param observations
	 * @return
	 */
	private int[] getMostSpecificTermsSparse(boolean[] observations) {
		int numObservedTerms = 0;
		for (int i = 0; i < observations.length; i++)
			if (observations[i])
				numObservedTerms++;

		int[] observedTerms = new int[numObservedTerms];
		for (int i = 0, j = 0; i < observations.length; i++) {
			if (observations[i])
				observedTerms[j++] = i;
		}

		return mostSpecificTerms(observedTerms);
	}

	/**
	 * Defines a function to determine the term similarity.
	 * 
	 * @author Sebastian Bauer
	 */
	public static interface ITermSim {
		public double termSim(int t1, int t2);

		/**
		 * @return the name of the method.
		 */
		public String name();
	}

	/**
	 * Class implementing a term similarity measure.
	 * 
	 * @author Sebastian Bauer
	 */
	public abstract class AbstractTermSim implements ITermSim {
		/** Contains for each item the maximal score for the given term */
		public double[][] maxScoreForItem;

		/** Stores the score distribution */
		private ApproximatedEmpiricalDistributions scoreDistributions;

		/** Lock for the score distribution */
		private ReentrantReadWriteLock scoreDistributionLock = new ReentrantReadWriteLock();

		/**
		 * Returns the score distribution for the given item for the given query size.
		 * If the score distribution has not been created yet, create it using the
		 * supplied queries.
		 * 
		 * @param querySize
		 * @param item
		 * @param queries
		 * @return
		 */
		private ApproximatedEmpiricalDistribution getScoreDistribution(int querySize, int item, int[][] queries) {
			scoreDistributionLock.readLock().lock();
			ApproximatedEmpiricalDistribution d = scoreDistributions
					.getDistribution(item * (MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION + 1) + querySize);
			scoreDistributionLock.readLock().unlock();

			if (d == null) {
				/* Determine score distribution */
				double[] scores = new double[SIZE_OF_SCORE_DISTRIBUTION];
				double maxScore = Double.NEGATIVE_INFINITY;

				for (int j = 0; j < SIZE_OF_SCORE_DISTRIBUTION; j++) {
					scores[j] = scoreVsItem(queries[j], item, this);
					if (scores[j] > maxScore)
						maxScore = scores[j];
				}

				ApproximatedEmpiricalDistribution d2 = new ApproximatedEmpiricalDistribution(scores,
						NUMBER_OF_BINS_IN_APPROXIMATED_SCORE_DISTRIBUTION);

				scoreDistributionLock.writeLock().lock();
				d = scoreDistributions.getDistribution(item * (MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION + 1) + querySize);
				if (d == null)
					scoreDistributions.setDistribution(item * (MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION + 1) + querySize,
							d2);
				scoreDistributionLock.writeLock().unlock();
			}

			return d;
		}

		/**
		 * Sets up the score distribution. At the moment, this must be called before
		 * maxScoreForItem is setup.
		 */
		public void setupDistribution() {
			/** Instantiates the query cache */
			if (CACHE_RANDOM_QUERIES) {
				boolean distributionLoaded = false;
				String scoreDistributionsName = "scoreDistributions-" + name() + "-" + allItemList.size() + "-"
						+ CONSIDER_FREQUENCIES_ONLY + "-" + SIZE_OF_SCORE_DISTRIBUTION + ".gz";

				queryCache = new QuerySets(MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION + 1);

				if ((CACHE_SCORE_DISTRIBUTION || PRECALCULATE_SCORE_DISTRIBUTION) && TRY_LOADING_SCORE_DISTRIBUTION) {
					try {
						File inFile = new File(scoreDistributionsName);
						InputStream underlyingStream = new GZIPInputStream(new FileInputStream(inFile));
						ObjectInputStream ois = new ObjectInputStream(underlyingStream);

						int fingerprint = ois.readInt();
						if (fingerprint == fingerprint()) {
							scoreDistributions = (ApproximatedEmpiricalDistributions) ois.readObject();
							distributionLoaded = true;
							logger.info("Score distribution loaded from \"" + inFile.getAbsolutePath() + "\"");
						}
					} catch (FileNotFoundException e) {
					} catch (IOException e) {
						e.printStackTrace();
					} catch (ClassNotFoundException e) {
						e.printStackTrace();
					}
				}

				if (PRECALCULATE_SCORE_DISTRIBUTION) {
					if (!distributionLoaded)
						scoreDistributions = new ApproximatedEmpiricalDistributions(
								allItemList.size() * (MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION + 1));

					logger.info("Precaculating score distribution for " + name());

					Random rnd = new Random(9);
					ExecutorService es = null;
					ArrayList<Future<?>> futureList = new ArrayList<Future<?>>();

					if (getNumProcessors() > 1)
						es = Executors.newFixedThreadPool(getNumProcessors());
					else
						es = null;

					for (int i = 0; i < allItemList.size(); i++) {
						final long seed = rnd.nextLong();
						final int item = i;

						Runnable run = new Runnable() {
							@Override
							public void run() {
								Random rnd = new Random(seed);

								for (int qs = 1; qs <= MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION; qs++) {
									int[][] queries = getRandomizedQueries(rnd, qs);
									getScoreDistribution(qs, item, queries);
								}
							}
						};

						if (es != null)
							futureList.add(es.submit(run));
						else
							run.run();
					}

					/* Cleanup */
					if (es != null) {
						es.shutdown();

						for (Future<?> f : futureList) {
							try {
								f.get();
							} catch (Exception e) {
								throw new RuntimeException(e);
							}
						}

						try {
							while (!es.awaitTermination(10, TimeUnit.SECONDS))
								;
						} catch (InterruptedException e) {
							e.printStackTrace();
							throw new RuntimeException(e);
						}
					}

					logger.info("Score distribution has been precalculated");

					if (STORE_SCORE_DISTRIBUTION && !distributionLoaded) {
						try {
							File outFile = new File(scoreDistributionsName);
							OutputStream underlyingStream = new GZIPOutputStream(new FileOutputStream(outFile));
							ObjectOutputStream oos = new ObjectOutputStream(underlyingStream);

							/*
							 * The fingerprint shall ensure that the score distribution and
							 * ontology/associations are compatible
							 */
							oos.writeInt(fingerprint());

							/* Finally, Write store distribution */
							oos.writeObject(scoreDistributions);
							underlyingStream.close();

							logger.info("Score distribution written to \"" + outFile.getAbsolutePath() + "\"");
						} catch (FileNotFoundException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}

		}
	}

	/**
	 * Term similarity measure according to Resnik
	 */
	public final AbstractTermSim resnikTermSim = new AbstractTermSim() {
		@Override
		public double termSim(int t1, int t2) {
			return terms2IC[commonAncestorWithMaxIC(t1, t2)];
		}

		@Override
		public String name() {
			return "resnik";
		}
	};

	/**
	 * Term similarity measure according to Lin. Note that the similarity of terms
	 * with information content of 0 is defined as 1 here.
	 */
	private final AbstractTermSim linTermSim = new AbstractTermSim() {
		@Override
		public double termSim(int t1, int t2) {
			double nominator = 2 * terms2IC[commonAncestorWithMaxIC(t1, t2)];
			double denominator = terms2IC[t1] + terms2IC[t2];
			if (nominator <= 0.0 && denominator <= 0.0)
				return 1;
			return nominator / denominator;
		}

		@Override
		public String name() {
			return "lin";
		};
	};

	/**
	 * Term similarity measure according to Jiang and Conrath
	 */
	private final AbstractTermSim jcTermSim = new AbstractTermSim() {
		@Override
		public double termSim(int t1, int t2) {
			return (1) / (1 + terms2IC[t1] + terms2IC[t2] - 2 * terms2IC[commonAncestorWithMaxIC(t1, t2)]);
		}

		@Override
		public String name() {
			return "jc";
		}
	};

	/**
	 * Score two list of terms according to max-avg-of-best method using the given
	 * term similarity measure.
	 * 
	 * @param tl1
	 * @param tl2
	 * @param termSim
	 * @return
	 */
	private double scoreMaxAvg(int[] tl1, int[] tl2, ITermSim termSim) {
		double totalScore = 0;
		for (int t1 : tl1) {
			double maxScore = Double.NEGATIVE_INFINITY;

			for (int t2 : tl2) {
				double score = termSim.termSim(t1, t2);
				if (score > maxScore)
					maxScore = score;
			}

			totalScore += maxScore;
		}
		totalScore /= tl1.length;
		return totalScore;
	}

	/**
	 * Score two list of terms according to resnik-max-avg-of-best method.
	 * 
	 * @param tl1
	 * @param tl2
	 * @return
	 */
	public double resScoreMaxAvg(int[] tl1, int[] tl2) {
		return scoreMaxAvg(tl1, tl2, resnikTermSim);
	}

	/**
	 * Score two list of terms according to lin-max-avg-of-best method.
	 * 
	 * @param tl1
	 * @param tl2
	 * @return
	 */
	public double linScoreMaxAvg(int[] tl1, int[] tl2) {
		return scoreMaxAvg(tl1, tl2, linTermSim);
	}

	/**
	 * Score two list of terms according to jc-max-avg-of-best method.
	 * 
	 * @param tl1
	 * @param tl2
	 * @return
	 */
	public double jcScoreMaxAvg(int[] tl1, int[] tl2) {
		return scoreMaxAvg(tl1, tl2, jcTermSim);
	}

	/**
	 * Sim score avg one list of a term vs an item.
	 * 
	 * @param tl1
	 * @param item
	 * @return
	 */
	private double scoreMaxAvgVsItem(int[] tl1, int item, AbstractTermSim termSim) {
		if (termSim.maxScoreForItem != null) {
			double score = 0;
			for (int t1 : tl1)
				score += termSim.maxScoreForItem[item][t1];
			score /= tl1.length;
			return score;
		}

		return scoreMaxAvg(tl1, items2DirectTerms[item], termSim);
	}

	/**
	 * Sim score avg one list of a term vs an item according to Resnik.
	 * 
	 * @param tl1
	 * @param item
	 * @return
	 */
	public double resScoreMaxAvgVsItem(int[] tl1, int item) {
		return scoreMaxAvgVsItem(tl1, item, resnikTermSim);
	}

	/**
	 * Sim score avg one list of a term vs an item according to Lin.
	 * 
	 * @param tl1
	 * @param item
	 * @return
	 */
	public double linScoreMaxAvgVsItem(int[] tl1, int item) {
		return scoreMaxAvgVsItem(tl1, item, linTermSim);
	}

	/**
	 * Sim score avg one list of a term vs an item according to Jiang and Conrath.
	 * 
	 * @param tl1
	 * @param item
	 * @return
	 */
	public double jcScoreMaxAvgVsItem(int[] tl1, int item) {
		return scoreMaxAvgVsItem(tl1, item, jcTermSim);
	}

	/**
	 * Sim score avg using two lists of terms.
	 * 
	 * @param t1
	 * @param t2
	 * @return
	 */
	private double simScoreAvg(int[] t1, int[] t2) {
		double score = 0;
		for (int to : t1) {
			for (int ti : t2) {
				int common = commonAncestorWithMaxIC(to, ti);
				score += terms2IC[common];
			}
		}
		score /= t1.length * t2.length;
		return score;
	}

	/**
	 * Score two list of terms.
	 * 
	 * @param t1
	 * @param t2
	 * @return
	 */
	private double simScore(int[] t1, int[] t2) {
		return resScoreMaxAvg(t1, t2);
	}

	/**
	 * Score one list of terms vs. an item using the default method and using the
	 * supplied term similarity measure.
	 * 
	 * @param tl1
	 * @param item
	 * @param termSim
	 * @return
	 */
	private double scoreVsItem(int[] tl1, int item, AbstractTermSim termSim) {
		return scoreMaxAvgVsItem(tl1, item, termSim);
	}

	/**
	 * Score one list of terms vs an item using the default method..
	 * 
	 * @param tl1
	 * @param item
	 * @return
	 */
	public double resScoreVsItem(int[] tl1, int item) {
		return scoreVsItem(tl1, item, resnikTermSim);
	}

	/**
	 * Creates an array suitable for shuffling.
	 * 
	 * @return
	 */
	public int[] newShuffledTerms() {
		int[] shuffledTerms = new int[slimGraph.getNumberOfVertices()];

		/* Initialize shuffling */
		for (int i = 0; i < shuffledTerms.length; i++)
			shuffledTerms[i] = i;

		return shuffledTerms;
	}

	/**
	 * Determined the pvalue of the given score for the given item.
	 * 
	 * @param rnd
	 *            defines the random number to be used
	 * @param observedTerms
	 * @param randomizedTerms
	 * @param querySize
	 * @param res
	 * @param item
	 * @param score
	 * @param termSim
	 * @return
	 */
	private int simPValue(Random rnd, int[] observedTerms, int[] randomizedTerms, int querySize, Result res, int item,
			double score, AbstractTermSim termSim) {
		/* Turn it into a p value by considering the distribution */
		if (CACHE_RANDOM_QUERIES) {
			if (querySize > MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION)
				querySize = MAX_QUERY_SIZE_FOR_CACHED_DISTRIBUTION;

			int[][] queries = getRandomizedQueries(rnd, querySize);

			if (CACHE_SCORE_DISTRIBUTION || PRECALCULATE_SCORE_DISTRIBUTION) {
				ApproximatedEmpiricalDistribution d = termSim.getScoreDistribution(querySize, item, queries);
				res.marginals[item] = 1 - (d.cdf(score, false) - d.prob(score));
			}
			else {
				int count = 0;

				for (int j = 0; j < SIZE_OF_SCORE_DISTRIBUTION; j++) {
					double randomScore = scoreVsItem(queries[j], item, termSim);
					if (randomScore >= score)
						count++;
				}

				res.marginals[item] = count / (double) SIZE_OF_SCORE_DISTRIBUTION;
			}
		}
		else {
			int count = 0;
			int[] shuffledTerms = newShuffledTerms();

			for (int j = 0; j < SIZE_OF_SCORE_DISTRIBUTION; j++) {
				chooseTerms(rnd, observedTerms.length, randomizedTerms, shuffledTerms);
				double randomScore = scoreVsItem(randomizedTerms, item, termSim);
				if (randomScore >= score)
					count++;
			}
			res.marginals[item] = count / (double) SIZE_OF_SCORE_DISTRIBUTION;
		}
		return querySize;
	}

	/**
	 * Makes the calculation according to a sim score avg max. We handle the
	 * observations as an item and compare it to all other items. Also calculates
	 * the significance (stored in the marginal attribute).
	 * 
	 * @param observations
	 * @param pval
	 *            to be set to true if significance should be determined
	 * @param rnd
	 *            the random source
	 * 
	 * @return
	 */
	public Result simScore(boolean[] observations, boolean pval, AbstractTermSim termSim, Random rnd) {
		int[] observedTerms = getMostSpecificTermsSparse(observations);
		int[] randomizedTerms = new int[observedTerms.length];

		int querySize = observedTerms.length;

		Result res = new Result();
		res.scores = new double[allItemList.size()];
		res.marginals = new double[allItemList.size()];

		long startTime = System.currentTimeMillis();
		long lastTime = startTime;

		for (int i = 0; i < allItemList.size(); i++) {
			long time = System.currentTimeMillis();

			if (time - lastTime > 5000) {
				System.out
						.println(termSim.name() + ": " + (time - startTime) + "ms " + i / (double) allItemList.size());
				lastTime = time;
			}

			/* Determine and remember the plain score */
			double score = scoreMaxAvgVsItem(observedTerms, i, termSim);
			res.scores[i] = score;

			querySize = simPValue(rnd, observedTerms, randomizedTerms, querySize, res, i, score, termSim);

		}

		return res;
	}

	/**
	 * Makes the calculation according to Resnik avg max. We handle the observations
	 * as an item and compare it to all other items. Also calculates the
	 * significance (stored in the marginal attribute).
	 * 
	 * @param observations
	 *            the input observations.
	 * @param pval
	 *            to be set to true if significance should be determined
	 * @param rnd
	 *            the random source
	 * 
	 * @return
	 */
	public Result resnikScore(boolean[] observations, boolean pval, Random rnd) {
		return simScore(observations, pval, resnikTermSim, rnd);
	}

	/**
	 * Makes the calculation according to Lin avg max. We handle the observations as
	 * an item and compare it to all other items. Also calculates the significance
	 * (stored in the marginal attribute).
	 * 
	 * @param observations
	 *            the input observations.
	 * @param pval
	 *            to be set to true if significance should be determined
	 * @param rnd
	 *            the random source
	 * 
	 * @return
	 */
	public Result linScore(boolean[] observations, boolean pval, Random rnd) {
		return simScore(observations, pval, linTermSim, rnd);
	}

	/**
	 * Makes the calculation according to JC avg max. We handle the observations as
	 * an item and compare it to all other items. Also calculates the significance
	 * (stored in the marginal attribute).
	 * 
	 * @param observations
	 *            the input observations.
	 * @param pval
	 *            to be set to true if significance should be determined
	 * @param rnd
	 *            the random source
	 * 
	 * @return
	 */
	public Result jcScore(boolean[] observations, boolean pval, Random rnd) {
		return simScore(observations, pval, jcTermSim, rnd);
	}

	/**
	 * Returns the term similarity according to Mathur and Dinakarpadnian.
	 * 
	 * @param t1
	 *            term 1
	 * @param t2
	 *            term 2
	 * @return
	 */
	public double mbTermSim(int t1, int t2) {
		return jaccard(t1, t2) * (terms2IC[t1] + terms2IC[t2]) / 2;
	}

	/**
	 * Returns the msim according to Mathur and Dinakarpadnian, i.e., the maximum
	 * simimlarity between t1 and all of tl2.
	 * 
	 * @param t1
	 * @param tl2
	 * @return
	 */
	public double msim(int t1, int tl2[]) {
		double s = 0.0;
		for (int j = 0; j < tl2.length; j++) {
			double snew = mbTermSim(t1, tl2[j]);
			if (snew > 0.0)
				s = snew;
		}
		return s;
	}

	/**
	 * Returns the unsymetric mbsim according to Mathur and Dinakarpadnian.
	 * 
	 * @param tl1
	 * @param tl2
	 * @return
	 */
	public double mbsimUnsym(int tl1[], int tl2[]) {
		double s = 0.0;
		for (int i = 0; i < tl1.length; i++)
			s += msim(tl1[i], tl2);

		return s / tl1.length;
	}

	/**
	 * Returns (symetric) mbsim according to Mathur and Dinakarpadnian.
	 * 
	 * @param tl1
	 * @param tl2
	 * @return
	 */
	public double mbsim(int tl1[], int tl2[]) {
		return (mbsimUnsym(tl1, tl2) + mbsimUnsym(tl2, tl1)) / 2;
	}

	/**
	 * Makes the calculation according to Mathur and Dinakarpadnian. We handle the
	 * observations as an item and compare it to all other items.
	 * 
	 * @param observations
	 *            the input observations.
	 * @param pval
	 *            to be set to true if significance should be determined
	 * @param rnd
	 *            the random source
	 * 
	 * @return
	 */
	public Result mbScore(boolean[] observations) {
		int[] observedTerms = getMostSpecificTermsSparse(observations);

		Result res = new Result();
		res.scores = new double[allItemList.size()];
		res.marginals = new double[allItemList.size()];

		long startTime = System.currentTimeMillis();
		long lastTime = startTime;

		for (int i = 0; i < allItemList.size(); i++) {
			long time = System.currentTimeMillis();

			if (time - lastTime > 5000) {
				System.out.println("mbScore: " + (time - startTime) + "ms " + i / (double) allItemList.size());
				lastTime = time;
			}

			/* Determine and remember the plain score */
			double score = mbsim(observedTerms, items2DirectTerms[i]);
			res.scores[i] = score;
		}
		return res;
	}

	/** Lock for randomized queries */
	private ReentrantReadWriteLock queriesLock = new ReentrantReadWriteLock();

	/**
	 * Returns an array containing randomized term query. In the returned array, the
	 * first index distinguishes each random query, and the second index
	 * distinguishes the terms.
	 * 
	 * @param rnd
	 *            source of random.
	 * @param querySize
	 *            defines the size of the query.
	 * @return
	 */
	private int[][] getRandomizedQueries(Random rnd, int querySize) {
		queriesLock.readLock().lock();
		int[][] queries = queryCache.getQueries(querySize);
		queriesLock.readLock().unlock();

		if (queries == null) {
			queriesLock.writeLock().lock();
			queries = queryCache.getQueries(querySize);
			if (queries == null) {
				int[] shuffledTerms = newShuffledTerms();

				queries = new int[SIZE_OF_SCORE_DISTRIBUTION][querySize];
				for (int j = 0; j < SIZE_OF_SCORE_DISTRIBUTION; j++)
					chooseTerms(rnd, querySize, queries[j], shuffledTerms);

				queryCache.setQueries(querySize, queries);
			}
			queriesLock.writeLock().unlock();
		}
		return queries;
	}

	/**
	 * Select size number of terms that are stored in chosen.
	 * 
	 * @param rnd
	 * @param size
	 * @param chosen
	 * @param storage
	 */
	public void chooseTerms(Random rnd, int size, int[] chosen, int[] storage) {
		if (FORBID_ILLEGAL_QUERIES) {
			boolean valid;
			int tries = 0;

			do {
				choose(rnd, size, chosen, storage);
				valid = true;

				outer: for (int i = 0; i < size; i++) {
					for (int j = 0; j < size; j++) {
						if (i == j)
							continue;

						/* If a chosen term is descendant of another one, we reject the query. */
						if (slimGraph.isDescendant(chosen[i], chosen[j])) {
							valid = false;
							break outer;
						}
					}
				}
				tries++;
			} while (!valid);
		}
		else {
			choose(rnd, size, chosen, storage);
		}
	}

	/**
	 * Chooses size randomly selected values from storage. Storage is manipulated by
	 * this call. Selected values are stored in chosen.
	 * 
	 * @param rnd
	 * @param size
	 *            number of elements that are chosen
	 * @param chosen
	 *            where the chosen values are deposited.
	 * @param storage
	 *            defines the elements from which to choose
	 */
	public static void choose(Random rnd, int size, int[] chosen, int[] storage) {
		/*
		 * Choose terms randomly as the size of observed terms. We avoid drawing the
		 * same term but alter shuffledTerms such that it can be used again in the next
		 * iteration. Note that this duplicates code from the above.
		 */
		for (int k = 0; k < size; k++) {
			int chosenIndex = rnd.nextInt(storage.length - k);
			int chosenTerm = storage[chosenIndex];

			/* Place last term at the position of the chosen term */
			storage[chosenIndex] = storage[storage.length - k - 1];

			/* Place chosen term at the last position */
			storage[storage.length - k - 1] = chosenTerm;

			chosen[k] = chosenTerm;
		}
	}

	/**
	 * Returns the mica of term, i.e., the a common ancestor of the given terms
	 * whose information content is maximal.
	 * 
	 * @param t1
	 * @param t2
	 * @return
	 */
	public int getCommonAncestorWithMaxIC(int t1, int t2) {
		return commonAncestorWithMaxIC(t1, t2);
	}

	/**
	 * Returns the current slim graph.
	 * 
	 * @return
	 */
	public SlimDirectedGraphView<Term> getSlimGraph() {
		return slimGraph;
	}

	/**
	 * Returns the index of the given term.
	 * 
	 * @param t
	 * @return
	 */
	public int getTermIndex(Term t) {
		return slimGraph.getVertexIndex(t);
	}

	/**
	 * Returns the ontology.
	 * 
	 * @return
	 */
	public Ontology getOntology() {
		return graph;
	}

	/**
	 * Returns the terms that are directly annotated to the given item.
	 * 
	 * @param itemId
	 * @return
	 */
	public int[] getTermsDirectlyAnnotatedTo(int itemId) {
		return items2DirectTerms[itemId];
	}

	/**
	 * Returns all the ancestors of the given term.
	 * 
	 * @param termId
	 * @return
	 */
	public int[] getAncestors(int termId) {
		return term2Ancestors[termId];
	}

	/**
	 * Returns the frequencies of terms directly annotated to the given item. The
	 * order of the entries match the order of getTermsDirectlyAnnotatedTo().
	 * 
	 * @param itemId
	 * @return
	 */
	public double[] getFrequenciesOfTermsDirectlyAnnotatedTo(int itemId) {
		return items2TermFrequencies[itemId];
	}

	/**
	 * Returns the parents of a given term.
	 * 
	 * @param t
	 * @return
	 */
	public int[] getParents(int t) {
		return term2Parents[t];
	}

	/**
	 * Returns whether the item has associated frequencies.
	 * 
	 * @param item
	 * @return
	 */
	public boolean hasItemFrequencies(int item) {
		return itemHasFrequencies[item];
	}

	/**
	 * Returns the evidence codes that should be respected. May be null in case all
	 * evidence codes are respected.
	 * 
	 * @return
	 */
	public String[] getEvidenceCodes() {
		return evidenceCodes;
	}

	/**
	 * Returns the ic of the given term.
	 * 
	 * @param t
	 * @return
	 */
	public double getTermIC(int t) {
		return terms2IC[t];
	}

	/**
	 * Returns the number of items.
	 * 
	 * @return
	 */
	public int getNumberOfItems() {
		return allItemList.size();
	}

	/**
	 * Returns the item for the given index.
	 * 
	 * @param itemIndex
	 * @return
	 */
	public ByteString getItem(int itemIndex) {
		return allItemList.get(itemIndex);
	}

}
