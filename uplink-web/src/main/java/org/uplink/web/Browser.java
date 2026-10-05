package org.uplink.web;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSValue;

/**
 * The browser APIs uplink-web calls, as Web Image JavaScript bindings.
 *
 * <p>Values cross into Java as single strings: Web Image matches a JavaScript call to a
 * Java functional interface by argument types, and JavaScript numbers and {@code null}
 * do not match {@code int} or {@code String} parameters, so every callback takes one
 * {@code Object} and decodes it.
 */
final class Browser {

    /** A JavaScript-callable function of one value. */
    @FunctionalInterface
    interface Callback {
        void call(Object value);
    }

    private Browser() {
    }

    /**
     * A value received from JavaScript as a Java string. The bindings below only ever
     * pass strings. (No undefined check: Oracle GraalVM 25.0's compile-time API has no
     * JSValue.isUndefined, though 25.3's does.)
     */
    static String string(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof JSValue js ? js.asString() : value.toString();
    }

    /**
     * Fetches {@code url} and calls back with one string, lines separated by {@code \n}:
     * kind ({@code received}, {@code hidden}, {@code unverified} or {@code failed}),
     * status, final URL, Content-Type, whether it was redirected, Retry-After, detail,
     * then the body (the rest of the string).
     *
     * <p>A CORS request comes first, the only kind whose status and body are readable. Only
     * an HTML, XML or plain-text (robots.txt) body is read, as the CLI does; any other
     * download, such as a video, is cancelled. When the
     * CORS request fails, a {@code no-cors} probe tells a server that answered without CORS
     * headers ({@code hidden}) from one with no answer the browser lets through: unreachable,
     * or refusing cross-origin requests with Cross-Origin-Resource-Policy (often only on the
     * page a redirect leads to, as with YouTube's consent page). Those two look the same from
     * JavaScript, so such a link is {@code unverified}, not broken.
     */
    @JS.Coerce
    @JS(args = {"url", "method", "readBody", "timeoutMillis", "callback"}, value = """
            const line = s => String(s == null ? '' : s).replace(/[\\r\\n]+/g, ' ');
            const reply = (kind, status, finalUrl, type, redirected, retryAfter, detail, body) =>
                callback([kind, status, line(finalUrl), line(type), redirected ? '1' : '0', line(retryAfter), line(detail)].join('\\n')
                    + '\\n' + (body || ''));
            const timedOut = e => e && (e.name === 'TimeoutError' || e.name === 'AbortError');
            const started = performance.now();
            const ms = () => Math.round(performance.now() - started) + 'ms';
            const debug = message => console.debug('[uplink] ' + method + ' ' + url + ' -> ' + message);
            const target = new URL(url);
            if (location.protocol === 'https:' && target.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(target.hostname)) {
                reply('unverified', 0, url, '', false, '', 'http:// link: the browser blocks it on an https:// page (mixed content)', '');
                debug('not sent: mixed content');
                return;
            }
            const options = (mode, redirect) => ({ method, mode, redirect, credentials: 'omit', cache: 'no-store',
                                                   signal: AbortSignal.timeout(timeoutMillis) });
            (async () => {
                try {
                    const r = await fetch(url, options('cors', 'follow'));
                    const type = r.headers.get('content-type') || '';
                    let body = '';
                    if (readBody && method !== 'HEAD' && /html|xml|text\\/plain/i.test(type)) {
                        body = await r.text();
                    } else if (r.body) {
                        r.body.cancel().catch(() => {});
                    }
                    reply('received', r.status, r.url || url, type, r.redirected,
                          r.headers.get('retry-after'), '', body);
                    debug(r.status + (r.redirected ? ' via redirect to ' + r.url : '') + ', '
                          + (type || 'no content type') + (body ? ', ' + body.length + ' chars read' : '')
                          + ', ' + ms());
                } catch (e) {
                    if (timedOut(e)) {
                        reply('failed', 0, url, '', false, '', 'request timed out', '');
                        debug('timed out after ' + ms());
                        return;
                    }
                    // fetch() reports a CORS refusal and a network failure alike ("TypeError: Failed
                    // to fetch"); a no-cors request, whose response cannot be read, tells them apart.
                    // no-cors requests must follow redirects; the browser rejects any other mode.
                    try {
                        await fetch(url, options('no-cors', 'follow'));
                        reply('hidden', 0, url, '', false, '', '', '');
                        debug('answered without CORS headers (Access-Control-Allow-Origin), status hidden, ' + ms());
                    } catch (e2) {
                        if (timedOut(e2)) {
                            reply('failed', 0, url, '', false, '', 'request timed out', '');
                            debug('timed out after ' + ms());
                        } else {
                            reply('unverified', 0, url, '', false, '',
                                  'no answer the browser can read: unreachable, or the server refuses cross-origin requests', '');
                            debug('no readable answer: ' + e2 + ' (CORS request: ' + e + '). Either the server is unreachable'
                                  + ' (DNS, connection, TLS) or it sends Cross-Origin-Resource-Policy; the network errors'
                                  + ' in the console (ERR_NAME_NOT_RESOLVED, ERR_BLOCKED_BY_RESPONSE...) tell which. ' + ms());
                        }
                    }
                }
            })();
            """)
    static native void fetch(String url, String method, boolean readBody, int timeoutMillis, Callback callback);

    @JS.Coerce
    @JS(args = {"millis", "task"}, value = "setTimeout(() => task(), millis);")
    static native void setTimeout(int millis, Runnable task);

    /** Hands the page's state, as JSON, to {@code uplinkRender} in app.js. */
    @JS.Coerce
    @JS(args = {"json"}, value = "if (typeof globalThis.uplinkRender === 'function') globalThis.uplinkRender(json);")
    static native void render(String json);

    /**
     * Publishes {@code uplinkStart(params)} and {@code uplinkStop()} for app.js, then
     * tells it they are there.
     */
    @JS.Coerce
    @JS(args = {"start", "stop"}, value = """
            globalThis.uplinkStart = params => start(String(params));
            globalThis.uplinkStop = () => stop('');
            if (typeof globalThis.uplinkReady === 'function') globalThis.uplinkReady();
            """)
    static native void exportApi(Callback start, Callback stop);

    /** The browser's current UTC offset in minutes, east positive. */
    @JS.Coerce
    @JS(value = "return -new Date().getTimezoneOffset();")
    static native int utcOffsetMinutes();

    /** Writes {@code [uplink] message} to the console at {@code level}: debug, info, warn or error. */
    @JS.Coerce
    @JS(args = {"level", "message"}, value = "(console[level] || console.log)('[uplink] ' + message);")
    static native void log(String level, String message);
}
