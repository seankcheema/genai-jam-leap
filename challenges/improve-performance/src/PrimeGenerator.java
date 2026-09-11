import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

public class PrimeGenerator {


    public List<BigInteger> getPrimes(int size) {
        System.out.println("About to find " + size + " primes.");

        // Size the pool to the number of logical cores actually available on
        // this machine, so we neither over-subscribe (too many threads
        // fighting for the same cores, adding context-switch overhead) nor
        // under-subscribe (leaving cores idle) relative to the hardware.
        int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        try {
            List<Future<BigInteger>> futures = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                Callable<BigInteger> task = () ->
                        // ThreadLocalRandom.current() gives this worker thread its
                        // own private random generator - no cross-thread locking,
                        // no shared-seed contention (see comment block above).
                        new BigInteger(2000, ThreadLocalRandom.current()).nextProbablePrime();
                futures.add(executor.submit(task));
            }

            List<BigInteger> primes = new ArrayList<>(size);
            for (Future<BigInteger> future : futures) {
                primes.add(future.get());
            }

            System.out.println("Found all " + primes.size() + " primes.");
            return primes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Prime generation was interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Prime generation task failed", e.getCause());
        } finally {
            // Always shut the pool down so we don't leak threads across
            // repeated calls to getPrimes().
            executor.shutdown();
        }
    }
}
