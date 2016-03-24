package sonumina.boqa.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import ontologizer.association.AssociationContainer;
import ontologizer.association.AssociationParser;
import ontologizer.association.AssociationParser.Type;
import ontologizer.go.OBOParser;
import ontologizer.go.OBOParserException;
import ontologizer.go.Ontology;
import ontologizer.go.Term;
import ontologizer.go.TermContainer;
import ontologizer.go.TermID;
import sonumina.boqa.calculation.BOQA;
import sonumina.boqa.calculation.BOQA.Result;
import sonumina.boqa.calculation.Observations;
import sonumina.math.graph.DirectedGraph;
import sonumina.math.graph.SlimDirectedGraphView;

/**
 * This is the server core for the boqa implementation. It main purpose is to maintain a separate id space of terms.
 * 
 * @author Sebastian Bauer
 */
public class BOQACore {
	private static Logger logger = Logger.getLogger(BOQACore.class.getName());

	static int numberOfThreads = Runtime.getRuntime().availableProcessors();

	public static String debugThis;

	private static Type associationFileType;

	/**
	 * The boqa object
	 */
	private BOQA boqa = new BOQA();

	/**
	 * The static ontology object. Defines terms that the user can select.
	 */
	private Ontology ontology;

	/**
	 * The corresponding slim view.
	 */
	private SlimDirectedGraphView<Term> slimGraph;

	/**
	 * Contains the indices of the term in sorted order
	 */
	private int[] sorted2Idx;

	/**
	 * Contains the rank of the term within the sorted order.
	 */
	private int[] idx2Sorted;

	/**
	 * Constructs the boqa core by loading the data from the given files.
	 * 
	 * @param definitionPath
	 *            name of the obo file
	 * @param associationPath
	 *            name of the association (GAF) file.
	 */
	public BOQACore(String definitionPath, String associationPath) {
		logger.info("Starting " + BOQACore.class.getName());

		long start = System.currentTimeMillis();

		OBOParser oboParser = new OBOParser(definitionPath, OBOParser.PARSE_DEFINITIONS);
		try {
			oboParser.doParse();
		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (OBOParserException e1) {
			e1.printStackTrace();
		}
		TermContainer goTerms = new TermContainer(oboParser.getTermMap(), oboParser.getFormatVersion(), oboParser.getDate());
		logger.info("OBO file \"" + definitionPath + "\" parsed");

		Ontology localOntology = Ontology.create(goTerms);
		logger.info("Ontology graph with " + localOntology.getNumberOfTerms() + " terms created");

		/* Load associations */
		AssociationContainer localAssociations;
		try {

			if (associationFileType.equals(Type.PAF)) {
				AssociationParser.setUserdefinedType(Type.PAF);
			}
			AssociationParser ap = new AssociationParser(associationPath, localOntology.getTermMap());
			localAssociations = new AssociationContainer(ap.getAssociations(), ap.getSynonym2gene(), ap.getDbObject2gene());
		} catch (IOException e) {
			e.printStackTrace();
			localAssociations = new AssociationContainer();
		}

		logger.info("Got ontology and associations" + (System.currentTimeMillis() - start) / 1000d + "s");

		init(localOntology, localAssociations);
	}

	/**
	 * Constructs the boqa core by using the specified ontology and associations.
	 * 
	 * @param localOntology
	 * @param localAssociations
	 */
	public BOQACore(Ontology localOntology, AssociationContainer localAssociations) {
		init(localOntology, localAssociations);
	}

	/**
	 * Initializes attributes of the class.
	 * 
	 * @param localOntology
	 * @param localAssociations
	 */
	private void init(Ontology localOntology, AssociationContainer localAssociations) {
		logger.info("Setting up BOQA");

		boqa.setConsiderFrequenciesOnly(false);
		boqa.setMaxFrequencyTerms(5);
		boqa.setPrecalculateScoreDistribution(false);
		boqa.setPrecalculateItemMaxs(false);
		boqa.setPrecalculateMaxICs(false);
		boqa.setup(localOntology, localAssociations);
		ontology = boqa.getOntology();
		slimGraph = boqa.getSlimGraph();

		logger.info("Sort terms");

		/* Create the sorted index */

		/* Sort the term according to the alphabet */
		class TermName {
			int index;
			String name;
		}
		TermName[] terms = new TermName[slimGraph.getNumberOfVertices()];
		for (int i = 0; i < slimGraph.getNumberOfVertices(); i++) {
			terms[i] = new TermName();
			terms[i].name = slimGraph.getVertex(i).getName();
			terms[i].index = i;
		}
		Arrays.sort(terms, new Comparator<TermName>() {
			@Override
			public int compare(TermName o1, TermName o2) {
				return o1.name.compareToIgnoreCase(o2.name);
			}
		});
		sorted2Idx = new int[terms.length];
		idx2Sorted = new int[terms.length];
		for (int i = 0; i < terms.length; i++) {
			sorted2Idx[i] = terms[i].index;
			idx2Sorted[terms[i].index] = i;
		}
	}

	/**
	 * Returns the term at the given sorted index.
	 * 
	 * @param sortedIdx
	 * @return
	 */
	public Term getTerm(int sortedIdx) {
		return slimGraph.getVertex(sorted2Idx[sortedIdx]);
	}

	/**
	 * Return an iterator that iterates over terms matching the patterns.
	 * 
	 * @param pattern
	 * @return
	 * 
	 * @TODO: Optimize via proper index data structure.
	 */
	public Iterable<Term> getTerms(final String pattern) {
		return new Iterable<Term>() {

			@Override
			public Iterator<Term> iterator() {
				return new Iterator<Term>() {
					int i = 0;

					final String pat = pattern != null ? pattern.toLowerCase() : null;

					@Override
					public boolean hasNext() {
						for (; i < slimGraph.getNumberOfVertices(); i++) {
							if (pat == null || pat.length() == 0 || getTerm(i).getName().toLowerCase().contains(pat)
									|| getTerm(i).getIDAsString().contains(pat))
								return true;
						}
						return false;
					}

					@Override
					public Term next() {
						return getTerm(i++);
					}

					@Override
					public void remove() {
					}
				};
			}
		};
	}

	/**
	 * Returns the a single term of given index with respect to the given pattern.
	 * 
	 * @param pattern
	 * @param which
	 * @return the term or null.
	 */
	public Term getTerm(final String pattern, int which) {
		for (Term t : getTerms(pattern))
			if (which-- == 0)
				return t;
		return null;
	}

	/**
	 * Returns the number of terms that match the given pattern.
	 * 
	 * @param pattern
	 *            may be null
	 * @return
	 */
	public int getNumberTerms(String pattern) {
		if (pattern == null || pattern.length() == 0)
			return slimGraph.getNumberOfVertices();

		int numberOfTerms = 0;
		for (@SuppressWarnings("unused")
		Term t : getTerms(pattern))
			numberOfTerms++;

		return numberOfTerms;

	}

	/**
	 * Returns the id of the given term.
	 * 
	 * @param t
	 * @return
	 */
	public int getIdOfTerm(Term t) {
		return idx2Sorted[slimGraph.getVertexIndex(t)];
	}

	/**
	 * Returns the server id of the given term.
	 * 
	 * @param t
	 * @return
	 */
	public int getIdOfTerm(TermID tid) {
		Term t = ontology.getTerm(tid);
		return idx2Sorted[slimGraph.getVertexIndex(t)];
	}

	/**
	 * Returns the term for the given term id.
	 * 
	 * @param tid
	 * @return
	 */
	public Term getTerm(TermID tid) {
		return ontology.getTerm(tid);
	}

	/**
	 * Score according to the given server ids.
	 * 
	 * @param serverIds
	 * @return
	 */
	public List<ItemResultEntry> score(List<Integer> serverIds) {
		return score(serverIds, true);
	}

	/**
	 * Score according to the given server ids.
	 * 
	 * @param serverIds
	 * @param multithreading
	 * @return
	 */
	public List<ItemResultEntry> score(List<Integer> serverIds, boolean multiThreading) {
		long start = System.currentTimeMillis();

		boolean[] observations = new boolean[slimGraph.getNumberOfVertices()];
		for (int id : serverIds) {
			observations[sorted2Idx[id]] = true;
			boqa.activateAncestors(sorted2Idx[id], observations);
		}

		List<ItemResultEntry> resultList = new ArrayList<ItemResultEntry>();

		Observations o = new Observations();
		o.observations = observations;

		Result result = boqa.assignMarginals(o, true, multiThreading ? numberOfThreads : 1);
		for (int i = 0; i < result.size(); i++) {
			ItemResultEntry newEntry = ItemResultEntry.create(i, result.getMarginal(i));
			resultList.add(newEntry);
		}

		Collections.sort(resultList, new Comparator<ItemResultEntry>() {
			@Override
			public int compare(ItemResultEntry o1, ItemResultEntry o2) {
				if (o1.getScore() < o2.getScore())
					return 1;
				if (o1.getScore() > o2.getScore())
					return -1;
				return 0;
			}
		});

		long diff = System.currentTimeMillis() - start;
		logger.info("Calculation took " + (diff) / 1000 + "." + (diff) % 1000 + " seconds");

		return resultList;
	}

	/**
	 * Returns the name of the given item.
	 * 
	 * @param itemId
	 * @return
	 */
	public String getItemName(int itemId) {
		return boqa.getItem(itemId).toString();
	}

	/**
	 * Returns the number of items annotated to the given term represented by the user id.
	 * 
	 * @param serverId
	 * @return
	 */
	public int getNumberOfTermsAnnotatedToTerm(int serverId) {
		return boqa.getNumberOfItemsAnnotatedToTerm(sorted2Idx[serverId]);
	}

	/**
	 * Returns the terms that are directly annotated to the given item.
	 * 
	 * @param itemId
	 * @return
	 */
	public int[] getTermsDirectlyAnnotatedTo(int itemId) {
		int[] t = boqa.getTermsDirectlyAnnotatedTo(itemId);
		int[] st = new int[t.length];

		for (int i = 0; i < t.length; i++)
			st[i] = idx2Sorted[t[i]];
		return st;
	}

	/**
	 * Returns the frequencies of the terms directly annotated to the given item. The order matches the order of
	 * getTermsDirectlyAnnotatedTo().
	 * 
	 * @param itemId
	 * @return
	 */
	public double[] getFrequenciesOfTermsDirectlyAnnotatedTo(int itemId) {
		double[] f = boqa.getFrequenciesOfTermsDirectlyAnnotatedTo(itemId);
		double[] sf = new double[f.length];
		for (int i = 0; i < sf.length; i++)
			sf[i] = f[i];
		return sf;
	}

	/**
	 * Returns the parents of the term.
	 * 
	 * @param sid
	 * @return
	 */
	public int[] getParents(int t) {
		int[] p = boqa.getParents(sorted2Idx[t]);
		int[] np = new int[p.length];
		for (int i = 0; i < p.length; i++)
			np[i] = idx2Sorted[p[i]];
		return np;
	}

	public static interface IAncestorVisitor {
		public void visit(int t);
	}

	/**
	 * Visits the ancestors of the given terms. For every visit, the visit method of the IAncestorVisitor interface is called.
	 * 
	 * @param t
	 */
	public void visitAncestors(Collection<Integer> terms, final IAncestorVisitor visitor) {
		/* Get initial terms */
		ArrayList<Term> initTerms = new ArrayList<Term>(terms.size());
		for (int t : terms)
			initTerms.add(slimGraph.getVertex(sorted2Idx[t]));

		/* Invoke bfs */
		ontology.getGraph().bfs(initTerms, true, new DirectedGraph.IVisitor<Term>() {
			@Override
			public boolean visited(Term vertex) {
				visitor.visit(idx2Sorted[slimGraph.getVertexIndex(vertex)]);
				return true;
			}
		});
	}

	public Ontology getOntology() {
		return this.ontology;
	}

	public SlimDirectedGraphView<Term> getOntologySlim() {
		return this.slimGraph;
	}

	/**
	 * Currently on PAF is supported. If not PAF is given the standard ontologizer AssociationParser behaviour is used.
	 * 
	 * @param type
	 */
	public static void setAssociationFileType(Type type) {
		associationFileType = type;
	}

}
