package io.jethro.app.social;

/**
 * Live health of one social source (ADR-0050) so connections are VISIBLE in the UI: is it reaching
 * the site, when did it last poll, how many posts it pulled, and any error. Purely observational.
 *
 * @param name          source name (sim / stocktwits / telegram)
 * @param healthy       last poll reached the source (a real feed: at least one successful call)
 * @param lastPollMillis wall-clock of the last poll, 0 if never
 * @param lastCount     posts returned on the last poll
 * @param detail        short human line — endpoint reached, symbols polled, or the last error
 */
public record SocialSourceStatus(String name, boolean healthy, long lastPollMillis, int lastCount,
                                 String detail) {
}
