package zw.co.reikan.loans.core.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether this process is a DEPLOYMENT — one that must refuse the
 * development defaults committed to this public repo (the placeholder JWT
 * secret, the bootstrap admin's fallback password).
 *
 * <p>"Deployment" means no {@code dev}/{@code test}/{@code local}/{@code it}
 * profile is active, INCLUDING when no profile is active at all: a container
 * started without {@code SPRING_PROFILES_ACTIVE} fails closed, not open. Local
 * work has to opt out explicitly. Names match exactly, so a deployed box running
 * e.g. {@code test-environment} is still a deployment.
 */
public final class DeploymentProfiles {

    public static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "local", "it");

    private DeploymentProfiles() {
    }

    public static boolean isDeployment(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .noneMatch(NON_DEPLOYMENT_PROFILES::contains);
    }
}
