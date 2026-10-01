package zw.co.innbucks.loans.core.user;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;

/**
 * What a lending portal user is told when an account is made for them or their password is reset: which system it is
 * for, their username, the temporary password, and where to sign in. Worded like the ticketing fleet's credential
 * messages (user-service {@code CredentialDeliveryListener}).
 *
 * <p>SMS is one sentence, because the gateway refuses {@code !:/?"*;} and so cannot carry {@code Username:}, and the
 * sign-in address only as a bare host ({@link PortalProperties#getSignInUrl()}). WhatsApp and email carry labelled
 * lines and the full address. Every SMS here passes the gateway unchanged ({@code SmsTemplatesTest}): a temporary
 * password the SMS path had to rewrite would be one the user could never sign in with.
 */
@Slf4j
@Component
public class PortalCredentialMessages {

    /** Why the user is being sent a temporary password. */
    public enum Reason {
        /** An administrator made an account for them. */
        ACCOUNT_CREATED,
        /** An administrator reset their password. */
        ADMIN_RESET,
        /** They asked for a new password themselves, by username. */
        SELF_SERVICE_RESET
    }

    static final String PORTAL = "InnBucks Loans portal";

    /** The full sign-in address, or null. */
    private final String signInUrl;
    /** The sign-in address as SMS can carry it, or null. */
    private final String smsSignInAddress;

    public PortalCredentialMessages(PortalProperties properties) {
        this.signInUrl = StringUtils.trimToNull(properties.getSignInUrl());
        this.smsSignInAddress = smsAddress(signInUrl);
        if (signInUrl == null) {
            log.info("[startup] No portal sign-in address (loans.portal.sign-in-url): temporary-password messages name"
                    + " the {} without a link", PORTAL);
        } else if (smsSignInAddress == null) {
            log.warn("[startup] The portal sign-in address {} cannot go by SMS (the gateway refuses : and /), so"
                    + " temporary-password SMS name the {} without it; WhatsApp and email carry it", signInUrl, PORTAL);
        } else {
            log.info("[startup] Temporary-password messages link to {} ({} by SMS)", signInUrl, smsSignInAddress);
        }
    }

    /**
     * One sentence the SMS gateway passes unchanged, e.g. {@code Hi Dionne, your InnBucks Loans portal account ...}.
     * A username the gateway would alter (one made before usernames were held to letters, digits, dots, hyphens and
     * {@code @}) is left out rather than sent wrong.
     */
    public String sms(Reason reason, String firstName, String username, String temporaryPassword) {
        String signIn = smsSignInAddress == null ? "Please sign in and change it immediately."
                : "Please sign in at " + smsSignInAddress + " and change it immediately.";
        String credentials = smsSafe(username)
                ? "Your username is " + username + " and your temporary password is " + temporaryPassword + ". "
                : "Your temporary password is " + temporaryPassword + ". ";
        return greeting(firstName) + " " + event(reason) + ". " + credentials + signIn + afterword(reason);
    }

    /** The same, in labelled lines with the full address: WhatsApp renders line breaks and any character. */
    public String whatsApp(Reason reason, String firstName, String username, String temporaryPassword) {
        return greeting(firstName) + " " + event(reason) + ".\n\n"
                + credentials(username, temporaryPassword)
                + "\nYou will be asked to choose your own password when you sign in." + afterword(reason);
    }

    /** ASCII and no colon: the notification API refuses in an email subject what the SMS gateway refuses. */
    public String emailSubject(Reason reason) {
        return switch (reason) {
            case ACCOUNT_CREATED -> "Your " + PORTAL + " account is ready";
            case ADMIN_RESET, SELF_SERVICE_RESET -> "Your " + PORTAL + " password has been reset";
        };
    }

    public String email(Reason reason, String firstName, String username, String temporaryPassword) {
        return greeting(firstName) + "\n\n" + StringUtils.capitalize(event(reason)) + ".\n\n"
                + "Use these to sign in.\n" + credentials(username, temporaryPassword)
                + "\nFor your security, you will be asked to choose your own password when you sign in."
                + afterword(reason) + "\n\nThe InnBucks Loans Team";
    }

    private String credentials(String username, String temporaryPassword) {
        return "Username: " + username + "\n"
                + "Temporary password: " + temporaryPassword + "\n"
                + (signInUrl == null ? "" : "Sign in at: " + signInUrl + "\n");
    }

    private static String greeting(String firstName) {
        return StringUtils.isBlank(firstName) ? "Hello," : "Hi " + firstName.strip() + ",";
    }

    private static String event(Reason reason) {
        return switch (reason) {
            case ACCOUNT_CREATED -> "your " + PORTAL + " account is ready";
            case ADMIN_RESET -> "your " + PORTAL + " password has been reset by an administrator";
            case SELF_SERVICE_RESET -> "your " + PORTAL + " password has been reset";
        };
    }

    /** Only a reset nobody vouched for asks to be reported: anyone who knows a username can request one. */
    private static String afterword(Reason reason) {
        return reason == Reason.SELF_SERVICE_RESET ? " If you did not ask for this, tell your administrator." : "";
    }

    /**
     * The address without its scheme or a trailing slash, when SMS can carry that unchanged: a bare host such as
     * {@code lending.innbucks.co.zw}, which phones still show as a link. Anything with a path, port or query cannot.
     */
    static String smsAddress(String url) {
        if (url == null) {
            return null;
        }
        String bare = StringUtils.removeEnd(url.replaceFirst("^https?://", ""), "/");
        return smsSafe(bare) ? bare : null;
    }

    /** One word the SMS gateway carries as it is. */
    static boolean smsSafe(String word) {
        return StringUtils.isNotEmpty(word) && !StringUtils.containsWhitespace(word)
                && word.equals(SmsTextSanitizer.toGsmSafe(word));
    }
}
