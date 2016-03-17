package sonumina.boqa.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import junit.framework.Assert;
import ontologizer.go.Term;

import org.junit.Test;

public class BOQACoreTest
{
	public static BOQACore c = new BOQACore("../boqa/data/human-phenotype-ontology.obo.gz","../boqa/data/phenotype_annotation.omim.gz");

	@Test
	public void test() throws InterruptedException, IOException
	{
		int i;

		List<ItemResultEntry> resultList = c.score(Arrays.asList(0,1));
		for (i=0;i<resultList.size();i++)
		{
			int id = resultList.get(i).getItemId();
			System.out.println(id + " " + c.getItemName(id) + " " + resultList.get(i).getScore());
			if (i>10) break;
		}
	}
	
	@Test
	public void testSortedOrder()
	{
		int numberOfTerms = c.getNumberTerms(null);
		Assert.assertTrue(numberOfTerms > 0);
		Term p = c.getTerm(0);
		for (int i=1;i<numberOfTerms;i++)
		{
			Term t = c.getTerm(i);
			Assert.assertTrue(p.getName().compareToIgnoreCase(t.getName()) <= 0);
			p = t;
		}
	}

	@Test
	public void testIdem()
	{
		int numberOfTerms = c.getNumberTerms(null);
		Assert.assertTrue(numberOfTerms > 0);

		for (int i=0;i<numberOfTerms;i++)
		{
			Term t = c.getTerm(i);
			Assert.assertEquals(i, c.getIdOfTerm(t));
		}
	}
}
