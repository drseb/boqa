package sonumina.boqa.calculation;

/**
 * A simple class maintaining ints.
 * 
 * @author Sebastian Bauer
 */
public class IntArray
{
	private int [] array;
	private int length;

	public IntArray(int maxLength)
	{
		array = new int[maxLength];
	}
	
	public IntArray(boolean [] dense)
	{
		int c = 0;
		for (int i=0;i<dense.length;i++)
			if (dense[i])
				c++;

		array = new int[c];
		c = 0;
		for (int i=0;i<dense.length;i++)
			if (dense[i])
				array[c++] = i;
		length = c;
	}
	
	public void add(int e)
	{
		array[length++] = e;
	}

	/**
	 * Helper function to create sub array of length elements from
	 * the given array.
	 * 
	 * @param array
	 * @param length
	 * @return
	 */
	private static int [] subArray(int [] array, int length)
	{
		int [] a = new int[length];
		for (int i=0;i<length;i++)
			a[i] = array[i];
		return a;
	}
	

	public int [] get()
	{
		return subArray(array, length);
	}
}

