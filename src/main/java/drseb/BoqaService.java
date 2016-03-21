package drseb;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import ontologizer.go.Ontology;
import ontologizer.go.Term;
import sonumina.boqa.server.BOQACore;
import sonumina.boqa.server.ItemResultEntry;

/**
 * BOQA-Wrapper
 * 
 * @author Sebastian Koehler
 *
 */
public class BoqaService {

	private BOQACore boqaCore;
	private Ontology ontology;
	private HashMap<String, String> itemName2itemRealId;

	public BoqaService(String hpOboFilePath, String annotationFilePath) {

		/*
		 * SBA has taken the item name (e.g. "SPASTIC DIPLEGIA, INFANTILE TYPE") as primary key, so that we loose the items original id
		 * "OMIM:270600". Now we create a mapping for this purpose!
		 */
		itemName2itemRealId = new HashMap<String, String>();
		try {

			BufferedReader in = new BufferedReader(new FileReader(annotationFilePath));
			String line = null;
			/*
			 * We have had some encoding issues. I.e. BOQA uses ontologizer-api for parsing the annotation data. This uses the names as
			 * primary identifiers and does not handle encoding correctly.
			 * 
			 * Also we check here if the file has been made name-unique, i.e. the name must be unique among the entries
			 */
			HashMap<String, String> name2id = new HashMap<String, String>();
			while ((line = in.readLine()) != null) {

				String[] split = line.split("\t");
				String itemName = split[2];
				// fix encoding issues
				itemName = itemName.replaceAll("[^\\x00-\\x7F]", "");

				String realItemId = split[0] + ":" + split[1];
				if (itemName2itemRealId.containsKey(itemName)) {
					if (itemName2itemRealId.get(itemName).equals(realItemId)) {
						continue;
					}
					else {
						throw new RuntimeException("error: NON-unique name2id mapping " + itemName + " -> " + itemName2itemRealId.get(itemName)
								+ " versus: " + realItemId);
					}
				}

				itemName2itemRealId.put(itemName, realItemId);

				// check for unique name->id mappings
				String id = split[0] + split[1]; // db + id in db
				if (name2id.containsKey(itemName)) {
					if (!id.equals(name2id.get(itemName))) {
						throw new RuntimeException(
								"file is not name-unique! will abort now! name: " + itemName + " id1: " + id + " id2: " + name2id.get(itemName));
					}
				}
			}
			in.close();

		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("init new BoqaService");
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
			String itemName = boqaCore.getItemName(boqaId);
			itemName = itemName.replaceAll("[^\\x00-\\x7F]", "");

			if (!itemName2itemRealId.containsKey(itemName)) {
				throw new RuntimeException("It should not happen that we cannot map item-name " + itemName + " to its id. Fix that!");
			}
			String itemRealId = itemName2itemRealId.get(itemName);
			double score = resultList.get(i).getScore();

			geneId2resultListSimple.put(boqaIdStr, new ResultEntry(boqaIdStr, itemRealId, itemName, score));
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

}
