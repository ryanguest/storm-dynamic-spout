package com.salesforce.storm.spout.sideline.coordinator;

import com.salesforce.storm.spout.sideline.SpoutCoordinator;
import com.salesforce.storm.spout.sideline.TupleMessageId;
import com.salesforce.storm.spout.sideline.kafka.DelegateSidelineSpout;
import com.salesforce.storm.spout.sideline.tupleBuffer.TupleBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the lifecycle of spinning up virtual spouts.
 */
public class SpoutMonitor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SpoutMonitor.class);

    /**
     * How often our monitor thread will output a status report, in milliseconds.
     */
    public static final long REPORT_STATUS_INTERVAL_MS = 60_000;

    /**
     * The executor service that we submit VirtualSidelineSpouts to run within.
     */
    private final ThreadPoolExecutor executor;

    /**
     * This Queue contains requests to our thread to fire up new VirtualSidelineSpouts.
     * Instances are taken off of this queue and put into the ExecutorService's task queue.
     */
    private final Queue<DelegateSidelineSpout> newSpoutQueue;

    /**
     * This buffer/queue holds tuples that are ready to be sent out to the topology.
     * It is filled by VirtualSidelineSpout instances, and drained by SidelineSpout.
     */
    private final TupleBuffer tupleOutputQueue;

    /**
     * This buffer/queue holds tuples that are ready to be acked by VirtualSidelineSpouts.
     * Its segmented by VirtualSidelineSpout ids => Queue of tuples to be acked.
     * It is filled by SidelineSpout, and drained by VirtualSidelineSpout instances.
     */
    private final Map<String,Queue<TupleMessageId>> ackedTuplesQueue;

    /**
     * This buffer/queue holds tuples that are ready to be failed by VirtualSidelineSpouts.
     * Its segmented by VirtualSidelineSpout ids => Queue of tuples to be failed.
     * It is filled by SidelineSpout, and drained by VirtualSidelineSpout instances.
     */
    private final Map<String,Queue<TupleMessageId>> failedTuplesQueue;

    /**
     * This latch allows the SpoutCoordinator to block on start up until its initial
     * set of VirtualSidelineSpout instances have started.
     */
    private final CountDownLatch latch;

    /**
     * Used to get the System time, allows easy mocking of System clock in tests.
     */
    private final Clock clock;

    private final Map<String, SpoutRunner> spoutRunners = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture> spoutThreads = new ConcurrentHashMap<>();
    private boolean isOpen = true;

    /**
     * The last timestamp of a status report.
     */
    private long lastStatusReport = 0;

    /**
     * How often the monitor thread will run, in milliseconds.
     */
    private long monitorThreadIntervalMs = 2000L;

    public SpoutMonitor(
            final Queue<DelegateSidelineSpout> newSpoutQueue,
            final TupleBuffer tupleOutputQueue,
            final Map<String, Queue<TupleMessageId>> ackedTuplesQueue,
            final Map<String, Queue<TupleMessageId>> failedTuplesQueue,
            final CountDownLatch latch,
            final Clock clock
    ) {
        this.newSpoutQueue = newSpoutQueue;
        this.tupleOutputQueue = tupleOutputQueue;
        this.ackedTuplesQueue = ackedTuplesQueue;
        this.failedTuplesQueue = failedTuplesQueue;
        this.latch = latch;
        this.clock = clock;

        /**
         * Create our executor service with a fixed thread size.
         * Its configured to:
         *   - Time out idle threads after 1 minute
         *   - Keep at most 0 idle threads alive (after timing out).
         *   - Maximum of SPOUT_RUNNER_THREAD_POOL_SIZE threads running concurrently.
         *   - Use essentially an unbounded task queue.
         */
        this.executor = new ThreadPoolExecutor(
            // Number of idle threads to keep around
            SpoutCoordinator.SPOUT_RUNNER_THREAD_POOL_SIZE,
            // Maximum number of threads to utilize
            SpoutCoordinator.SPOUT_RUNNER_THREAD_POOL_SIZE,
            // How long to keep idle threads around for before closing them
            1L, TimeUnit.MINUTES,
            // Task input queue
            new LinkedBlockingQueue<Runnable>()
        );
    }

    @Override
    public void run() {
        try {
            // Rename our thread.
            Thread.currentThread().setName("VirtualSpoutMonitor");

            // Start monitoring loop.
            while (isOpen) {
                // Look for completed tasks
                watchForCompletedTasks();

                // Look for new VirtualSpouts that need to be started
                startNewSpoutTasks();

                // Periodically report status
                reportStatus();

                // Pause for a period before checking for more spouts
                try {
                    Thread.sleep(getMonitorThreadIntervalMs());
                } catch (InterruptedException ex) {
                    logger.warn("!!!!!! Thread interrupted, shutting down...");
                    return;
                }
            }
            logger.warn("Spout coordinator is ceasing to run due to shutdown request...");
        } catch (Exception ex) {
            // TODO: Should we restart the monitor?
            logger.error("!!!!!! SpoutMonitor threw an exception {}", ex);
        }
    }

    /**
     * Submit any new spout tasks as they show up.
     */
    private void startNewSpoutTasks() {
        // Look for new spouts to start.
        for (DelegateSidelineSpout spout; (spout = newSpoutQueue.poll()) != null;) {
            logger.info("Preparing thread for spout {}", spout.getConsumerId());

            final SpoutRunner spoutRunner = new SpoutRunner(
                    spout,
                    tupleOutputQueue,
                    ackedTuplesQueue,
                    failedTuplesQueue,
                    latch,
                    getClock()
            );

            spoutRunners.put(spout.getConsumerId(), spoutRunner);

            // Run as a CompletableFuture
            final CompletableFuture spoutInstance = CompletableFuture.runAsync(spoutRunner, this.executor);
            spoutThreads.put(spout.getConsumerId(), spoutInstance);
        }
    }

    /**
     * Look for any tasks that have finished running and handle them appropriately.
     */
    private void watchForCompletedTasks() {
        // Cleanup loop
        for (String virtualSpoutId: spoutThreads.keySet()) {
            final CompletableFuture future = spoutThreads.get(virtualSpoutId);
            final SpoutRunner spoutRunner = spoutRunners.get(virtualSpoutId);

            if (future.isDone()) {
                if (future.isCompletedExceptionally()) {
                    // This threw an exception.  We need to handle the error case.
                    // TODO: Handle errors here.
                    logger.error("{} seems to have errored", virtualSpoutId);
                } else {
                    // Successfully finished?
                    logger.info("{} seems to have finished, cleaning up", virtualSpoutId);
                }

                // Remove from spoutThreads?
                // Remove from spoutInstances?
                spoutRunners.remove(virtualSpoutId);
                spoutThreads.remove(virtualSpoutId);
            }
        }

    }

    /**
     * This method will periodically show a status report to our logger interface.
     */
    private void reportStatus() {
        // Get current time
        final long now = getClock().millis();

        // Set initial value if none set.
        if (lastStatusReport == 0) {
            lastStatusReport = now;
            return;
        }

        // Only report once every 60 seconds.
        if ((now - lastStatusReport) <= REPORT_STATUS_INTERVAL_MS) {
            // Do nothing.
            return;
        }
        // Update timestamp
        lastStatusReport = now;

        // Show a status report
        logger.info("Active Tasks: {}, Queued Tasks: {}, ThreadPool Size: {}/{}, Completed Tasks: {}, Total Tasks Submitted: {}",
            executor.getActiveCount(),
            executor.getQueue().size(),
            executor.getPoolSize(),
            executor.getMaximumPoolSize(),
            executor.getCompletedTaskCount(),
            executor.getTaskCount()
        );
        logger.info("TupleBuffer size: {}, Running VirtualSpoutIds: {}", tupleOutputQueue.size(), spoutThreads.keySet());
    }

    /**
     * Call this method to indicate that we want to stop all running VirtualSideline Spout instances as well
     * as finish running our monitor thread.
     */
    public void close() {
        isOpen = false;

        // Ask the executor to shut down, this will prevent it from
        // accepting/starting new tasks.
        executor.shutdown();

        // Loop thru our runners and request stop on each
        for (SpoutRunner spoutRunner : spoutRunners.values()) {
            spoutRunner.requestStop();
        }

        // Wait for the executor to cleanly shut down
        try {
            logger.info("Waiting a maximum of {} ms for threadpool to shutdown", SpoutCoordinator.MAX_SPOUT_STOP_TIME_MS);
            executor.awaitTermination(SpoutCoordinator.MAX_SPOUT_STOP_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            logger.error("Interrupted while stopping: {}", ex);
        }

        // If we didn't shut down cleanly
        if (!executor.isShutdown()) {
            // Force a shut down
            logger.warn("Forcing unclean shutdown of threadpool");
            executor.shutdownNow();
        }

        // Clear our our internal state.
        spoutRunners.clear();
        spoutThreads.clear();
    }

    /**
     * @return - return the number of spout runner instances.
     *           *note* - it doesn't mean all of these are actually running.
     */
    public int getTotalSpouts() {
        return spoutRunners.size();
    }

    /**
     * @return - Our system clock instance.
     */
    private Clock getClock() {
        return clock;
    }

    /**
     * Configure how often our monitor thread should run thru its maintenance loop.
     * @param duration - a duration
     * @param timeUnit - the time unit of the duration.
     */
    public void setMonitorThreadInterval(long duration, TimeUnit timeUnit) {
        this.monitorThreadIntervalMs = TimeUnit.MILLISECONDS.convert(duration, timeUnit);
        logger.info("Monitor thread will run on a {} millisecond interval", getMonitorThreadIntervalMs());
    }

    /**
     * @return - how often our monitor thread should run thru its maintenance loop.
     */
    private long getMonitorThreadIntervalMs() {
        return monitorThreadIntervalMs;
    }
}
