package net.keyboardbuilders.keyboardbuilders.account;

import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * The saved-request cache without its {@code ?continue} marker, so a designer back from
 * GitHub lands on {@code /account}, not {@code /account?continue}.
 *
 * <p>JAVA, NOT KOTLIN, FOR ONE ARGUMENT. Spring documents {@code null} as "no marker" and
 * checks the field for null where it reads it, but its JSpecify annotations say non-null,
 * and the Kotlin build checks those strictly with no way to suppress it for one call.
 */
public class PlainRequestCache extends HttpSessionRequestCache {

    public PlainRequestCache() {
        setMatchingRequestParameterName(null);
    }
}
