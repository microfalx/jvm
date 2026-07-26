package net.microfalx.jvm;

import net.microfalx.metrics.Batch;
import net.microfalx.metrics.Metrics;
import net.microfalx.metrics.SeriesStore;
import net.microfalx.threadpool.AbstractRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.microfalx.lang.ArgumentUtils.requireNonNull;
import static net.microfalx.lang.ArgumentUtils.requireNotEmpty;
import static net.microfalx.lang.ExceptionUtils.getRootCauseDescription;
import static net.microfalx.lang.StringUtils.joinNames;

/**
 * Base class for all metrics collectors.
 */
public abstract class AbstractMetrics<M, C extends AbstractCollector<M>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMetrics.class);

    protected final static Metrics METRICS = VirtualMachineUtils.METRICS.withGroup("Metrics");

    private ScheduledExecutorService executor;
    private volatile String name;
    private volatile boolean memory = true;
    private volatile boolean started;
    private volatile Duration interval = Duration.ofSeconds(5);
    private volatile Future<?> scrapeTask;
    private volatile SeriesStore seriesStore;

    private Duration averageInterval = Duration.ofMinutes(5);

    protected final Object lock = new Object();

    private static ScheduledExecutorService executorService;

    protected AbstractMetrics() {
    }

    /**
     * Returns whether the metrics are stored in memory.
     *
     * @return {@code true} in memory, {@code false} otherwise
     */
    public final boolean isMemory() {
        return memory;
    }

    /**
     * Changes the metrics store to memory.
     *
     * @return self
     */
    public final AbstractMetrics<M, C> useMemory() {
        checkIfStarted();
        this.memory = true;
        return this;
    }

    /**
     * Changes the metrics store to memory.
     *
     * @param name the name of the store
     * @return self
     */
    public final AbstractMetrics<M, C> useDisk(String name) {
        requireNotEmpty(name);
        checkIfStarted();
        this.memory = false;
        this.name = name;
        return this;
    }

    /**
     * Returns the scrape interval.
     *
     * @return a non-null instance
     */
    public final Duration getInterval() {
        return interval;
    }

    /**
     * Changes the scrape interval.
     *
     * @param interval the new interval
     * @return self
     */
    public AbstractMetrics<M, C> setInterval(Duration interval) {
        requireNonNull(interval);
        this.interval = interval;
        createScrapeTask();
        updateThresholds();
        return this;
    }

    /**
     * Returns the interval used to average metrics.
     *
     * @return a non-null instance
     */
    public final Duration getAverageInterval() {
        return averageInterval;
    }

    /**
     * Changes the time interval used to calculate averages (default is 5 minutes).
     *
     * @param averageInterval the new interval
     * @return self
     */
    public final AbstractMetrics<M, C> setAverageInterval(Duration averageInterval) {
        requireNonNull(averageInterval);
        this.averageInterval = averageInterval;
        updateThresholds();
        return this;
    }

    /**
     * Changes the executor service.
     *
     * @param executor the executor
     */
    public AbstractMetrics<M, C> setExecutor(ScheduledExecutorService executor) {
        requireNonNull(executor);
        this.executor = executor;
        return this;
    }

    /**
     * Returns the store for JVM metrics.
     *
     * @return a non-null instance
     */
    public synchronized SeriesStore getStore() {
        if (seriesStore == null) initialize();
        return seriesStore;
    }

    /**
     * Starts data collection.
     */
    public synchronized void start() {
        if (!started) {
            if (executor == null) executor = getSharedExecutor();
            initialize();
            createScrapeTask();
        }
        started = true;
    }

    /**
     * Scrapes for new metrics.
     */
    public void scrape() {
        if (seriesStore == null) initialize();
        Batch batch = Batch.create(currentTimeMillis());
        METRICS.time("Scrape " + getMetricsName(), t -> collectMetrics(batch));
        METRICS.time("Store " + getMetricsName(), t -> getStore().add(batch));
    }

    /**
     * Stops the data collection.
     */
    public synchronized void stop() {
        started = false;
    }

    /**
     * Clear the metrics.
     */
    public synchronized void clear() {
        if (this.seriesStore != null) this.seriesStore.clear();
    }

    /**
     * Returns whether the metrics collection is started.
     *
     * @return {@code true} if started, {@code false} otherwise
     */
    public synchronized boolean isStarted() {
        return started;
    }

    /**
     * Subclasses would collect the metrics and add them to the current batch.
     *
     * @param batch the batch
     */
    protected abstract void collectMetrics(Batch batch);

    /**
     * Returns a name associated with the metrics.
     * <p>
     * Mostly used in logs and for performance monitoring.
     *
     * @return a non-null instance
     */
    protected abstract String getMetricsName();

    /**
     * Subclasses will be notified when thresholds are changed.
     */
    protected void updateThresholds() {
        // empty by default
    }

    private void createScrapeTask() {
        if (scrapeTask != null) scrapeTask.cancel(false);
        scrapeTask = executor.scheduleAtFixedRate(new CollectorWorker(), 0, interval.toMillis(), MILLISECONDS);
    }

    private void checkIfStarted() {
        if (started) throw new VirtualMachineException("Already started");
    }

    private synchronized void initialize() {
        if (seriesStore == null) {
            seriesStore = memory ? SeriesStore.memory() : SeriesStore.disk(name);
        }
    }

    private static ScheduledExecutorService getSharedExecutor() {
        synchronized (AbstractMetrics.class) {
            if (executorService == null) {
                executorService = Executors.newScheduledThreadPool(5);
            }
            return executorService;
        }
    }

    class CollectorWorker extends AbstractRunnable {

        public CollectorWorker() {
            setName(joinNames("Scrapper", AbstractMetrics.this.getMetricsName()));
        }

        @Override
        public void run() {
            if (started) {
                try {
                    scrape();
                } catch (Exception e) {
                    if (!(e instanceof InterruptedException)) {
                        LOGGER.warn("Failed to collect VM metrics, root cause: {}", getRootCauseDescription(e));
                    }
                }
            }
        }

        @Override
        public String toString() {
            return getName() + ", interval: " + getInterval();
        }
    }


}
