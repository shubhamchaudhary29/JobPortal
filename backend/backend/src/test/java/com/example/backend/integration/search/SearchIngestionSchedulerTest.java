package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.backend.integration.aggregation.SyncRunService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class SearchIngestionSchedulerTest {
    @Test
    void schedulesEachRegisteredSearchProviderFromItsExistingScheduleProperty() {
        SearchIngestionCoordinator adzuna = mock(SearchIngestionCoordinator.class);
        SearchIngestionCoordinators registry = mock(SearchIngestionCoordinators.class);
        when(registry.all()).thenReturn(Map.of("adzuna", adzuna));
        ScheduledTaskRegistrar defaults = new ScheduledTaskRegistrar();
        ScheduledTaskRegistrar configured = new ScheduledTaskRegistrar();

        new SearchIngestionScheduler(registry, new MockEnvironment()).configureTasks(defaults);
        new SearchIngestionScheduler(registry, new MockEnvironment().withProperty("adzuna.schedule-delay-ms", "60000"))
                .configureTasks(configured);

        List<IntervalTask> tasks = defaults.getFixedDelayTaskList();
        assertEquals(1, tasks.size());
        assertEquals(Duration.ofMillis(43_200_000L), tasks.get(0).getIntervalDuration());
        assertEquals(Duration.ofMillis(60_000L), configured.getFixedDelayTaskList().get(0).getIntervalDuration());
        tasks.get(0).getRunnable().run();
        verify(adzuna).run(SyncRunService.Trigger.SCHEDULED);
    }
}
