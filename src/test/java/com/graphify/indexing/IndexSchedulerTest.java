package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingChangedEvent;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

class IndexSchedulerTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    @Autowired
    IndexRunService runs;

    @Autowired
    OrphanCleanupJob cleanup;

    @Autowired
    IndexLock lock;

    @Autowired
    ApplicationContext context;

    private final RecordingScheduler recorder = new RecordingScheduler();
    private SettingsOverride overrides;
    private IndexScheduler scheduler;

    @BeforeEach
    void setUp() {
        overrides = new SettingsOverride(settings);
        scheduler = new IndexScheduler(recorder, settings, runs, cleanup);
        lock.forceRelease();
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
        lock.forceRelease();
    }

    @Test
    void isNotABeanWhenSchedulingIsDisabled() {
        assertThat(context.getBeansOfType(IndexScheduler.class)).isEmpty();
    }

    @Test
    void schedulesBothJobsFromTheirCronSettings() {
        scheduler.start();

        assertThat(recorder.expressions()).containsExactly(settings.getCron(SettingKeys.INDEX_CRON),
                settings.getCron(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON));
    }

    @Test
    void aChangedCronReplacesOnlyItsOwnSchedule() {
        scheduler.start();
        overrides.set(SettingKeys.INDEX_CRON, "0 30 1 * * *");

        scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.INDEX_CRON));
        scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.API_PAGE_DEFAULT_SIZE));

        assertThat(recorder.expressions()).hasSize(3).last().isEqualTo("0 30 1 * * *");
        assertThat(recorder.futures.get(0).isCancelled()).isTrue();
        assertThat(recorder.futures.get(1).isCancelled()).isFalse();
    }

    @Test
    void aFailingRescheduleNeverEscapesTheListener() {
        scheduler.start();
        recorder.failing = true;

        assertThatCode(() -> scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.INDEX_CRON)))
                .doesNotThrowAnyException();
        assertThat(recorder.futures.get(0).isCancelled()).isFalse();
    }

    @Test
    void aScheduledRunIsSkippedWhileTheLockIsHeld() {
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class);

        assertThatCode(scheduler::runScheduledIndex).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class)).isEqualTo(before);
    }

    /** Records triggers instead of running them. */
    static final class RecordingScheduler extends ThreadPoolTaskScheduler {

        final List<CronTrigger> triggers = new ArrayList<>();
        final List<RecordedFuture> futures = new ArrayList<>();
        boolean failing;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            if (failing) {
                throw new IllegalStateException("scheduler unavailable");
            }
            triggers.add((CronTrigger) trigger);
            RecordedFuture future = new RecordedFuture();
            futures.add(future);
            return future;
        }

        List<String> expressions() {
            return triggers.stream().map(CronTrigger::getExpression).toList();
        }
    }

    static final class RecordedFuture implements ScheduledFuture<Object> {

        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
