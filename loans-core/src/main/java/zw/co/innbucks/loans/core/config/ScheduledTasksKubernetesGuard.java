package zw.co.innbucks.loans.core.config;

import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Stops a Kubernetes pod from starting the {@code scheduled-tasks} jobs unless
 * {@value #ALLOWED_PROPERTY} is true.
 *
 * <p>Those jobs move money: LoanBookingJob books with InnBucks, which PAYS on the apply call, and
 * NdasendaLodgementJob lodges payroll deductions that cannot be taken back. Each acts on the database's
 * whole backlog at its first tick. In the fleet cell they are off (the Deployment activates {@code api}
 * only), and switching them on there is its own go-live step.</p>
 *
 * <p>The Deployment's {@code SPRING_PROFILES_ACTIVE} cannot keep them off by itself. Spring reaches a
 * profile by several names (SPRING_PROFILES_INCLUDE, a profile group, SPRING_APPLICATION_JSON), and any of
 * them in the pod's Secret would switch the jobs on with no change to the manifest. So in a pod the profile
 * is never enough: the go-live step sets this property as well, and a profile that arrived any other way
 * stops the boot instead of starting the jobs.</p>
 *
 * <p>Kubernetes only, detected the way Boot detects it: the KUBERNETES_SERVICE_HOST and _PORT variables
 * the kubelet puts into every pod. A process outside Kubernetes (the staging box's Docker container, a
 * developer's machine) is not affected.</p>
 *
 * <p>The check runs while the context is being built. A {@code @Scheduled} job is started only once the
 * context has finished starting, so a refused boot has not run a single tick.</p>
 */
@Component
@Profile("scheduled-tasks")
public class ScheduledTasksKubernetesGuard {

    static final String ALLOWED_PROPERTY = "loans.scheduled-tasks.allowed-on-kubernetes";

    public ScheduledTasksKubernetesGuard(Environment environment) {
        if (CloudPlatform.KUBERNETES.isActive(environment)
                && !environment.getProperty(ALLOWED_PROPERTY, Boolean.class, false)) {
            throw new IllegalStateException("The scheduled-tasks profile is active in a Kubernetes pod (active "
                    + "profiles: " + Arrays.toString(environment.getActiveProfiles()) + ") but "
                    + ALLOWED_PROPERTY + " is not true. These jobs book loans with InnBucks, which pays, and "
                    + "lodge Ndasenda deductions, which cannot be taken back. Turning them on in the cell is "
                    + "its own go-live step: set SCHEDULED_TASKS_ALLOWED_ON_KUBERNETES=true together with the "
                    + "profile. If the profile arrived by accident (SPRING_PROFILES_INCLUDE, a profile group "
                    + "or SPRING_APPLICATION_JSON in the pod's Secret), remove it.");
        }
    }
}
