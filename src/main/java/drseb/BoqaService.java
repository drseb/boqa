package drseb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ontologizer.association.AssociationParser.Type;
import ontologizer.go.Ontology;
import ontologizer.go.Term;
import sonumina.boqa.server.BOQACore;
import sonumina.boqa.server.ItemResultEntry;

/**
 * BOQA-Wrapper for phenotype association files.
 * 
 * @author Sebastian Koehler
 *
 */
public class BoqaService {

	private final Pattern boqaIdAndNameField = Pattern.compile("^(.+) \\((.+:\\d+)\\)$");

	private BOQACore boqaCore;
	private Ontology ontology;
	// private HashMap<String, String> itemName2itemRealId;

	public BoqaService(String hpOboFilePath, String annotationFilePath) {

		System.out.println("init new BoqaService");
		BOQACore.setAssociationFileType(Type.PAF);
		boqaCore = new BOQACore(hpOboFilePath, annotationFilePath);
		this.ontology = boqaCore.getOntology();
	}

	/**
	 * Determines the score for each item for the given query.
	 * 
	 * @param queryTerms
	 * @return
	 */
	public HashMap<String, ResultEntry> scoreItems(HashSet<Term> queryTerms) {

		System.out.println("calling rank on " + queryTerms);
		List<Integer> queryAsBoqaIndices = new ArrayList<Integer>();
		for (Term queryTerm : queryTerms) {
			int id = boqaCore.getIdOfTerm(queryTerm);
			queryAsBoqaIndices.add(id);
		}

		List<ItemResultEntry> resultList = boqaCore.score(queryAsBoqaIndices);
		HashMap<String, ResultEntry> geneId2resultListSimple = new HashMap<String, BoqaService.ResultEntry>();

		for (int i = 0; i < resultList.size(); i++) {

			int boqaId = resultList.get(i).getItemId();

			String boqaIdStr = boqaId + "";
			String idAndName = boqaCore.getItemName(boqaId);
			double score = resultList.get(i).getScore();

			Matcher m = boqaIdAndNameField.matcher(idAndName);
			if (m.find()) {
				String diseaseName = m.group(1);
				String dbAndId = m.group(2);
				geneId2resultListSimple.put(boqaIdStr, new ResultEntry(boqaIdStr, dbAndId, diseaseName, score));
			}
			else {
				System.err.println("no match of pattern " + boqaIdAndNameField.pattern() + " in " + idAndName);
			}

		}

		return geneId2resultListSimple;
	}

	/**
	 * Scores each item and ranks them subsequently
	 * 
	 * @param queryTerms
	 * @return
	 */
	public ArrayList<ResultEntry> rankItems(HashSet<Term> queryTerms) {
		HashMap<String, ResultEntry> scored = scoreItems(queryTerms);

		ArrayList<ResultEntry> results = new ArrayList<BoqaService.ResultEntry>(scored.values());
		Collections.sort(results);

		return results;
	}

	public Ontology getOntology() {
		return ontology;
	}

	public class ResultEntry implements Comparable<ResultEntry> {
		private String boqaId;
		private String itemRealId;
		private String itemName;

		private double score;

		public ResultEntry(String boqaId, String itemRealId, String itemName, double score) {
			this.boqaId = boqaId;
			this.itemRealId = itemRealId;
			this.itemName = itemName;
			this.score = score;
		}

		public int compareTo(ResultEntry o) {
			if (this.score < o.score)
				return 1;
			if (this.score > o.score)
				return -1;
			return 0;
		}

		/**
		 * @return the boqaId
		 */
		public String getBoqaId() {
			return boqaId;
		}

		/**
		 * @return the itemRealId
		 */
		public String getItemRealId() {
			return itemRealId;
		}

		/**
		 * @return the itemName
		 */
		public String getItemName() {
			return itemName;
		}

		/**
		 * @return the score
		 */
		public double getScore() {
			return score;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("ResultEntry [boqaId=");
			builder.append(boqaId);
			builder.append(", itemRealId=");
			builder.append(itemRealId);
			builder.append(", itemName=");
			builder.append(itemName);
			builder.append(", score=");
			builder.append(score);
			builder.append("]");
			return builder.toString();
		}

	}

	public static void setPrintBoqa(String soughtOmim) {
		BOQACore.debugThis = soughtOmim;
	}

	public void scoreItemsForTestQuery() {
		HashSet<Term> query = new HashSet<Term>();
		int c = 0;
		for (Term t : ontology) {
			query.add(t);
			if (++c > 5)
				break;
		}

		scoreItems(query);
	}

}
