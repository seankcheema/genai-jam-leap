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

    /*
     * ===========================================================================
     * WHY THE ORIGINAL IMPLEMENTATION WAS SLOW
     * ===========================================================================
     *
     * The original code looked like this:
     *
     *     for (int i = 0; i < size; i++) {
     *         primes.add(new BigInteger(2000, new Random()).nextProbablePrime());
     *     }
     *
     * Finding a single 2000-bit probable prime is genuinely expensive: it
     * generates a random 2000-bit candidate and then repeatedly runs trial
     * division followed by Miller-Rabin/Lucas primality rounds on successive
     * odd candidates until one passes. On this machine a single call to
     * nextProbablePrime() at 2000 bits takes roughly 400-700ms, so generating
     * 100 of them sequentially costs (100 * ~400-700ms) which is exactly the
     * ~42-70 second baseline we measured.
     *
     * The critical thing to notice is that the 100 primes are completely
     * INDEPENDENT of one another - primes[5] does not depend in any way on
     * primes[0..4]. This is a textbook "embarrassingly parallel" workload.
     * The original loop, however, runs entirely on a single thread, so no
     * matter how many CPU cores the machine has (this box has 4 logical
     * processors), only one core is ever doing work at a time while the
     * other 3 sit idle. That is the root cause of the slowness: it isn't
     * that the primality test itself is inefficient (nextProbablePrime()'s
     * default certainty is already a sensible, standard choice - see the
     * correctness note below), it's that the work available to be done in
     * parallel simply wasn't being parallelised.
     *
     * ===========================================================================
     * WHAT WAS CHANGED, AND WHY IT'S FASTER
     * ===========================================================================
     *
     * We submit each of the `size` independent prime-search tasks to a fixed
     * thread pool sized to Runtime.getRuntime().availableProcessors(). Each
     * task is a small Callable<BigInteger> that does exactly what a single
     * loop iteration used to do: build a random 2000-bit BigInteger and call
     * nextProbablePrime() on it. Because the searches are independent, the
     * threads never need to coordinate, synchronize on shared mutable state,
     * or wait on each other - they simply run concurrently, each pinned to
     * its own CPU core (up to the number of cores available). With 4 cores
     * available, we would expect close to a 4x speedup versus the strictly
     * sequential baseline (real-world speedup is slightly less than a clean
     * 4x due to thread pool startup/scheduling overhead and the fact that
     * the individual searches don't all take exactly the same amount of
     * time, so the pool isn't perfectly saturated for the entire run).
     *
     * We deliberately did NOT change the bit length (still exactly 2000, as
     * required by the security application) and did NOT weaken the
     * primality test (nextProbablePrime() is used with its default,
     * well-established certainty guarantee, exactly as before) - the only
     * change is *where* the independent work runs, not *what* work is done
     * or how rigorously primality is checked. So the 100 results returned
     * are exactly as trustworthy as the 100 results the original sequential
     * version would have returned; we've only changed the scheduling of the
     * work, not its correctness.
     *
     * ===========================================================================
     * THREAD-SAFETY / CORRECTNESS CONSIDERATIONS
     * ===========================================================================
     *
     * 1. Random source: the original code created a `new Random()` for every
     *    single prime search - i.e. it was never actually shared across
     *    threads. We preserved that "one random source per search" property,
     *    but instead of `new Random()` we use ThreadLocalRandom.current()
     *    inside each task. Two reasons:
     *      a) java.util.Random IS internally thread-safe (it CAS-updates its
     *         seed atomically), so sharing a single instance across threads
     *         would not corrupt state - but it WOULD become a serious
     *         contention bottleneck under heavy parallel use, since every
     *         thread would be fighting over the same atomic seed field on
     *         every random number generation, effectively serialising the
     *         very work we're trying to parallelise.
     *      b) Constructing many `new Random()` instances in quick succession
     *         from multiple threads at nearly the same moment risks
     *         correlated/duplicate seeds, since the no-arg Random()
     *         constructor seeds from nanoTime() plus a shared atomic seed
     *         uniquifier - under heavy concurrent construction this
     *         uniquifier is still safe (it's also CAS-updated) but it adds
     *         unnecessary contention for no benefit.
     *    ThreadLocalRandom.current() sidesteps both problems: each worker
     *    thread in the pool gets its own independent, unshared generator
     *    instance with no locking/CAS contention at all, and no risk of two
     *    threads racing to construct/seed a generator at the same instant.
     *    This is the standard, recommended source of randomness for
     *    multi-threaded workloads in modern Java.
     *
     * 2. Result collection: each task returns its BigInteger via a Future,
     *    and we read the futures back in submission order into the result
     *    list. There is no shared mutable state being written concurrently
     *    (we do not have multiple threads calling primes.add() on a shared
     *    non-thread-safe ArrayList at the same time), so there's no need for
     *    synchronization, a concurrent collection, or extra locking - each
     *    thread only ever touches its own task's private local state, and
     *    the only "merge" point is the single controlling thread reading
     *    completed Future results one at a time.
     *
     * 3. Executor shutdown: the pool is created and shut down within this
     *    method call (try/finally) so no threads are leaked between calls to
     *    getPrimes(), and a failure in any one task (e.g. an unexpected
     *    RuntimeException) surfaces via ExecutionException rather than being
     *    silently swallowed.
     */

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
