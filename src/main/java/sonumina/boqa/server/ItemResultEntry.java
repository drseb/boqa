package sonumina.boqa.server;

/**
 * Represents a single result entry for an item.
 *  
 * @author Sebastian Bauer
 */
public class ItemResultEntry
{
	private int itemId;
	private double score;
	
	static ItemResultEntry create(int itemId, double score)
	{
		ItemResultEntry re = new ItemResultEntry();
		re.itemId = itemId;
		re.score = score;
		return re;
	}
	
	/**
	 * Returns the score.
	 * 
	 * @return
	 */
	public double getScore()
	{
		return score;
	}
	
	/**
	 * Returns the id of the result entry.
	 * 
	 * @return
	 */
	public int getItemId()
	{
		return itemId;
	}
}
