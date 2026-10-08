package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingChangedEvent;
import com.graphify.settings.SettingKeys;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Schedules the full index run (index.cron) and the orphan cleanup (cleanup.orphan_symbols_cron), and replaces a
 * schedule when its setting changes (spec §6.4: no restart). app.scheduling.enabled=false turns it off (tests).
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class IndexScheduler implements DisposableBean {

    /** index_run.started_by of scheduled runs. */
    static final String SCHEDULER_ACTOR = "scheduler";

    /** One thread per job, so a long cleanup never delays the start of an index run. */
    private static final int JOB_THREADS = 2;

    private static final Logger log = LoggerFactory.getLogger(IndexScheduler.class);

    private final TaskScheduler scheduler;
    private final AppSettings settings;
    private final IndexRunService runs;
    private final OrphanCleanupJob cleanup;
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    @Autowired
    public IndexScheduler(AppSettings settings, IndexRunService runs, OrphanCleanupJob cleanup) {
        this(ownScheduler(), settings, runs, cleanup);
    }

    IndexScheduler(TaskScheduler scheduler, AppSettings settings, IndexRunService runs, OrphanCleanupJob cleanup) {
        this.scheduler = scheduler;
        this.settings = settings;
        this.runs = runs;
        this.cleanup = cleanup;
    }

    private static ThreadPoolTaskScheduler ownScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(JOB_THREADS);
        scheduler.setThreadNamePrefix("index-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    /** {@link IndexRunRecovery} has already run (before the context finished refreshing), so no stale lock is met. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        schedule(SettingKeys.INDEX_CRON);
        schedule(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON);
    }

    /** Never throws: the setting update has already committed, and its caller must not see a scheduling failure. */
    @EventListener
    public void onSettingChanged(SettingChangedEvent event) {
        if (!event.key().equals(SettingKeys.INDEX_CRON) && !event.key().equals(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON)) {
            return;
        }
        try {
            schedule(event.key());
            log.info("Re-scheduled {} to '{}'", event.key(), settings.getCron(event.key()));
        } catch (RuntimeException e) {
            log.error("Could not re-schedule {}: {}", event.key(), UrlMasking.mask(e.getMessage()));
        }
    }

    /** Schedules the new trigger first, so a failure leaves the previous schedule running. */
    private synchronized void schedule(String key) {
        Runnable job = key.equals(SettingKeys.INDEX_CRON) ? this::runScheduledIndex : this::runScheduledCleanup;
        ScheduledFuture<?> next = scheduler.schedule(job, new CronTrigger(settings.getCron(key)));
        ScheduledFuture<?> previous = scheduled.put(key, next);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    void runScheduledIndex() {
        try {
            long runId = runs.start(RunScope.ALL, null, false, RunTrigger.SCHEDULED, SCHEDULER_ACTOR);
            log.info("Scheduled index run {} started", runId);
        } catch (ConflictException e) {
            log.info("Scheduled index run skipped: {}", UrlMasking.mask(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Scheduled index run could not start: {}", UrlMasking.mask(e.getMessage()));
        }
    }

    void runScheduledCleanup() {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.error("Orphan symbol cleanup failed: {}", UrlMasking.mask(e.getMessage()));
        }
    }

    @Override
    public void destroy() {
        scheduled.values().forEach(future -> future.cancel(false));
        if (scheduler instanceof ThreadPoolTaskScheduler own) {
            own.shutdown();
        }
    }
}
