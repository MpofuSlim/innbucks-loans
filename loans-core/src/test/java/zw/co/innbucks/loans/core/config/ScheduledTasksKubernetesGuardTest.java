package zw.co.innbucks.loans.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In the fleet cell the pod's Secret could switch the money-moving jobs on without the manifest changing
 * (SPRING_PROFILES_INCLUDE and friends), so in a Kubernetes pod the profile alone must stop the boot. Each
 * case replaces the process environment, so the result does not depend on where the test runs.
 */
class ScheduledTasksKubernetesGuardTest {

    /** What the kubelet puts into every pod, and all Boot looks for. */
    private static final Map<String, Object> POD_ENV = Map.of(
            "KUBERNETES_SERVICE_HOST", "10.43.0.1",
            "KUBERNETES_SERVICE_PORT", "443");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ScheduledTasksKubernetesGuard.class);

    @Test
    @DisplayName("scheduled-tasks in a pod without the opt-in → the boot stops before any job can tick")
    void refusedInAPodWithoutTheOptIn() {
        runner.withInitializer(context -> environment(context.getEnvironment(), POD_ENV, "api", "scheduled-tasks"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(ScheduledTasksKubernetesGuard.ALLOWED_PROPERTY)
                            .hasMessageContaining("SCHEDULED_TASKS_ALLOWED_ON_KUBERNETES");
                });
    }

    @Test
    @DisplayName("an opt-in that is set but empty (KEY= in the Secret) → still refused")
    void refusedInAPodWhenTheOptInIsEmpty() {
        runner.withInitializer(context -> environment(context.getEnvironment(), POD_ENV, "api", "scheduled-tasks"))
                .withPropertyValues(ScheduledTasksKubernetesGuard.ALLOWED_PROPERTY + "=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("scheduled-tasks in a pod WITH the opt-in (the go-live step) → starts")
    void allowedInAPodWithTheOptIn() {
        runner.withInitializer(context -> environment(context.getEnvironment(), POD_ENV, "api", "scheduled-tasks"))
                .withPropertyValues(ScheduledTasksKubernetesGuard.ALLOWED_PROPERTY + "=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ScheduledTasksKubernetesGuard.class));
    }

    @Test
    @DisplayName("scheduled-tasks outside Kubernetes (the staging box's Docker container) → starts, no opt-in needed")
    void outsideKubernetesIsUnaffected() {
        runner.withInitializer(context -> environment(context.getEnvironment(), Map.of(), "api", "scheduled-tasks"))
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ScheduledTasksKubernetesGuard.class));
    }

    @Test
    @DisplayName("the cell as shipped (api only, in a pod) → the guard is not even created")
    void apiOnlyInAPodHasNoGuard() {
        runner.withInitializer(context -> environment(context.getEnvironment(), POD_ENV, "api"))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ScheduledTasksKubernetesGuard.class));
    }

    private static void environment(ConfigurableEnvironment environment, Map<String, Object> processEnv,
                                    String... activeProfiles) {
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        processEnv));
        environment.setActiveProfiles(activeProfiles);
    }
}
