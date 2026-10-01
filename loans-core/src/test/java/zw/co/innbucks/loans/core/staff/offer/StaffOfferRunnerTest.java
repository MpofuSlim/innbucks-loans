package zw.co.innbucks.loans.core.staff.offer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.CronTrigger;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Starting runs: who they are recorded as, a failure recorded on its own, and the weekly trigger on market time. */
class StaffOfferRunnerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 6, 0);

    private StaffOfferRunService runService;
    private StaffOfferRunner runner;
    private MarketTimeZone marketTimeZone;

    @BeforeEach
    void setUp() {
        runService = mock(StaffOfferRunService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("credit1");
        marketTimeZone = new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-05T06:00:00Z"), ZoneOffset.UTC));
        runner = new StaffOfferRunner(runService, authService, marketTimeZone);
    }

    @Test
    @DisplayName("a portal run is the signed-in user's; a scheduled one is the scheduler's")
    void whoStartedIt() {
        runner.runNow();
        runner.runScheduled();

        verify(runService).run(StaffOfferRunTrigger.MANUAL, "credit1");
        verify(runService).run(StaffOfferRunTrigger.SCHEDULED, StaffOfferRunner.SCHEDULER);
    }

    @Test
    @DisplayName("a run that breaks off is recorded as FAILED in its own transaction, and surfaces naming that record")
    void recordsFailure() {
        IllegalStateException failure = new IllegalStateException("database went away");
        when(runService.run(any(), any())).thenThrow(failure);
        StaffOfferRunResponse recorded = StaffOfferRunResponse.of(StaffOfferRun.builder().id(4L)
                .cycleStart(NOW.toLocalDate()).trigger(StaffOfferRunTrigger.MANUAL).startedBy("credit1")
                .startedAt(NOW).finishedAt(NOW).status(StaffOfferRunStatus.FAILED)
                .reason("The run failed and nothing it did was kept: IllegalStateException: database went away")
                .build());
        when(runService.recordFailure(StaffOfferRunTrigger.MANUAL, "credit1", NOW, failure)).thenReturn(recorded);

        assertThatThrownBy(() -> runner.runNow())
                .isInstanceOfSatisfying(StaffOfferRunFailedException.class,
                        failed -> assertThat(failed.run()).isSameAs(recorded))
                .hasCause(failure)
                .hasMessage("The offer run failed and nothing it did was kept. It is recorded as run 4, with the"
                        + " reason; try again, and if it fails again, report run 4");
    }

    @Test
    @DisplayName("if even the failure cannot be recorded, the error that stopped the run surfaces as it was")
    void failureNotRecorded() {
        IllegalStateException failure = new IllegalStateException("database went away");
        when(runService.run(any(), any())).thenThrow(failure);
        when(runService.recordFailure(any(), any(), any(), any())).thenThrow(new IllegalStateException("still away"));

        assertThatThrownBy(() -> runner.runScheduled()).isSameAs(failure);
    }

    @Test
    @DisplayName("a run refused because another is in progress is not a failure")
    void inProgressIsNotAFailure() {
        when(runService.run(any(), any())).thenThrow(new ConflictException("in progress"));

        assertThatThrownBy(() -> runner.runScheduled()).isInstanceOf(ConflictException.class);
        verify(runService, never()).recordFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the weekly job fires on the configured cron in the market's time zone and never throws")
    void weeklyJob() {
        StaffOfferProperties properties = new StaffOfferProperties();
        properties.setRunCron("0 30 7 * * FRI");
        StaffOfferRunner failing = mock(StaffOfferRunner.class);
        when(failing.runScheduled()).thenThrow(new IllegalStateException("down"));
        StaffOfferRunJob job = new StaffOfferRunJob(failing, properties, marketTimeZone);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        job.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).singleElement().satisfies((TriggerTask task) -> {
            CronTrigger trigger = (CronTrigger) task.getTrigger();
            assertThat(trigger.getExpression()).isEqualTo("0 30 7 * * FRI");
            assertThat(trigger).hasFieldOrPropertyWithValue("zoneId", ZoneId.of("Africa/Harare"));
        });
        job.execute();
        verify(failing).runScheduled();
        doThrow(new ConflictException("in progress")).when(failing).runScheduled();
        job.execute();
        verify(failing, times(2)).runScheduled();
    }

    @Test
    @DisplayName("the settings refuse a cron that does not parse and a validity under a day")
    void settings() {
        StaffOfferProperties properties = new StaffOfferProperties();
        assertThat(properties.isRunCronValid()).isTrue();
        properties.setRunCron("every monday");
        assertThat(properties.isRunCronValid()).isFalse();
        properties.setRunCron(null);
        assertThat(properties.isRunCronValid()).as("a missing cron is refused too").isFalse();
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator();
        properties.setRunCron("0 0 8 * * MON");
        properties.setValidityDays(0);
        assertThat(validator.validate(properties)).extracting(violation -> violation.getMessage())
                .containsExactly("loans.staff-offers.validity-days must be at least 1");
        verifyNoInteractions(runService);
    }
}
