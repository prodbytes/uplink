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

    /** A value received from JavaScript as a Java string. */
    static String string(Object value) {
        if (value == null || JSValue.isUndefined(value)) {
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
     * <p>A CORS request comes first, the only kind whose status and body are readable. When
     * it fails, a {@code no-cors} request tells a server that answered without CORS headers
     * ({@code hidden}) from one that could not be reached ({@code failed}).
     */
    @JS.Coerce
    @JS(args = {"url", "method", "readBody", "timeoutMillis", "callback"}, value = """
            const line = s => String(s == null ? '' : s).replace(/[\\r\\n]+/g, ' ');
            const reply = (kind, status, finalUrl, type, redirected, retryAfter, detail, body) =>
                callback([kind, status, line(finalUrl), line(type), redirected ? '1' : '0', line(retryAfter), line(detail)].join('\\n')
                    + '\\n' + (body || ''));
            const timedOut = e => e && (e.name === 'TimeoutError' || e.name === 'AbortError');
            const target = new URL(url);
            if (location.protocol === 'https:' && target.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(target.hostname)) {
                reply('unverified', 0, url, '', false, '', 'http:// link: the browser blocks it on an https:// page (mixed content)', '');
                return;
            }
            const options = mode => ({ method, mode, redirect: 'follow', credentials: 'omit', cache: 'no-store',
                                       signal: AbortSignal.timeout(timeoutMillis) });
            (async () => {
                try {
                    const r = await fetch(url, options('cors'));
                    let body = '';
                    if (readBody && method !== 'HEAD') {
                        body = await r.text();
                    } else if (r.body) {
                        r.body.cancel().catch(() => {});
                    }
                    reply('received', r.status, r.url || url, r.headers.get('content-type'), r.redirected,
                          r.headers.get('retry-after'), '', body);
                } catch (e) {
                    if (timedOut(e)) {
                        reply('failed', 0, url, '', false, '', 'request timed out', '');
                        return;
                    }
                    try {
                        await fetch(url, options('no-cors'));
                        reply('hidden', 0, url, '', false, '', '', '');
                    } catch (e2) {
                        reply('failed', 0, url, '', false, '',
                              timedOut(e2) ? 'request timed out' : 'network error (DNS, connection or TLS)', '');
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

    @JS.Coerce
    @JS(args = {"message"}, value = "console.error(message);")
    static native void logError(String message);
}
