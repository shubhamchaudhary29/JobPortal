package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class EmployerIngestionSchedulerTest {
    @Test
    void registersOneIndependentFixedDelayPerAtsProviderFromItsScheduleProperty() {
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("job-aggregation.scheduling.lever-delay-ms", "1234");
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new EmployerIngestionScheduler(coordinator, environment).configureTasks(registrar);

        List<IntervalTask> tasks = registrar.getFixedDelayTaskList();
        assertEquals(EmployerRegistryProperties.Source.values().length, tasks.size());
        Map<Duration, Long> delays = tasks.stream()
                .collect(Collectors.groupingBy(IntervalTask::getIntervalDuration, Collectors.counting()));
        assertEquals(Map.of(Duration.ofMillis(21_600_000L), 3L, Duration.ofMillis(1234), 1L), delays);
        assertTrue(tasks.stream().allMatch(task -> task.getInitialDelayDuration().isZero()));

        tasks.forEach(task -> task.getRunnable().run());
        verify(coordinator).run(EmployerRegistryProperties.Source.GREENHOUSE, SyncRunService.Trigger.SCHEDULED);
        verify(coordinator).run(EmployerRegistryProperties.Source.LEVER, SyncRunService.Trigger.SCHEDULED);
        verify(coordinator).run(EmployerRegistryProperties.Source.ASHBY, SyncRunService.Trigger.SCHEDULED);
        verify(coordinator).run(EmployerRegistryProperties.Source.SMARTRECRUITERS, SyncRunService.Trigger.SCHEDULED);
        verifyNoMoreInteractions(coordinator);
    }
}
