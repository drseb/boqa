package sonumina.boqa.calculation;

/**
 * Special class for handling observations used during the Benchmark.
 * 
 * For observations generated in the benchmark we know the truth, which
 * we will store in additional attributes within this class.
 * 
 * @author Sebastian Bauer
 *
 */
public class BenchmarkObservations extends Observations {
	/** The associated item. Useful only for the benchmark */
	public int item;
	
	/** Some statistics. Useful only for the benchmark */
	public Configuration observationStats;
}
