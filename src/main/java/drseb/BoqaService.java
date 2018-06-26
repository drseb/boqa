package drseb;

import java.util.ArrayList;
import java.util.Collection;
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

	/**
	 * Will use default AssociationFileType --> Type.PAF
	 * 
	 * @param hpOboFilePath
	 * @param annotationFilePath
	 */
	public BoqaService(String hpOboFilePath, String annotationFilePath) {

		System.out.println("init new BoqaService");
		BOQACore.setAssociationFileType(Type.PAF);
		boqaCore = new BOQACore(hpOboFilePath, annotationFilePath);
		this.ontology = boqaCore.getOntology();
	}

	/**
	 * @param hpOboFilePath
	 * @param annotationFilePath
	 * @param associationFileType
	 *            must be PAF or GPAF
	 */
	public BoqaService(String hpOboFilePath, String annotationFilePath, Type associationFileType) {

		System.out.println("init new BoqaService");
		BOQACore.setAssociationFileType(associationFileType);
		boqaCore = new BOQACore(hpOboFilePath, annotationFilePath);
		this.ontology = boqaCore.getOntology();
	}

	/**
	 * Determines the score for each item for the given query.
	 * 
	 * @param queryTerm
	 *            List of Term-IDs
	 * @return
	 */
	public HashMap<String, ResultEntry> scoreItems(Collection<String> queryTermIds) {

		HashSet<Term> queryTerms = getQueryTerms(queryTermIds);
		return scoreItems(queryTerms);
	}

	private HashSet<Term> getQueryTerms(Collection<String> queryTermIds) {
		if (queryTermIds == null || queryTermIds.size() < 1) {
			throw new IllegalArgumentException("given query term-ids invalid: " + queryTermIds);
		}

		HashSet<Term> queryTerms = new HashSet<>();
		for (String id : queryTermIds) {
			Term t = ontology.getTermIncludingAlternatives(id);
			if (t != null) {
				queryTerms.add(t);
			}
			else {
				System.err.println("could not find : " + id + " in ontoloy");
			}
		}
		if (queryTerms.size() < 1) {
			throw new IllegalArgumentException(
					"could not map any of the given query-ids to term-objects: " + queryTermIds);
		}
		return queryTerms;
	}

	/**
	 * Determines the score for each item for the given query.
	 * 
	 * @param queryTerms
	 * @return
	 */
	public HashMap<String, ResultEntry> scoreItems(HashSet<Term> queryTerms) {

		if (queryTerms == null || queryTerms.size() < 1) {
			throw new IllegalArgumentException("given query terms invalid: " + queryTerms);
		}

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

				// dirty fix
				if (dbAndId.contains("ORPHANET"))
					dbAndId = dbAndId.replaceAll("ORPHANET", "ORPHA");

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

		if (queryTerms == null || queryTerms.size() < 1) {
			throw new IllegalArgumentException("given query terms invalid: " + queryTerms);
		}

		HashMap<String, ResultEntry> scored = scoreItems(queryTerms);

		ArrayList<ResultEntry> results = new ArrayList<BoqaService.ResultEntry>(scored.values());
		Collections.sort(results);

		return results;
	}

	public ArrayList<ResultEntry> rankItems(ArrayList<String> queryTermIds) {
		HashSet<Term> queryTerms = getQueryTerms(queryTermIds);
		return rankItems(queryTerms);
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

		@Override
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
