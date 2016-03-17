package sonumina.boqa.calculation;

import org.junit.Assert;
import org.junit.Test;

public class UtilTest
{
	@Test
	public void testSetDiff()
	{
		int [] a = new int[]{4,3,1};
		int [] b = new int[]{1,3};
		
		int [] d = Util.setDiff(a, b);
		Assert.assertEquals(1,d.length);
		Assert.assertEquals(4,d[0]);
	}
}
