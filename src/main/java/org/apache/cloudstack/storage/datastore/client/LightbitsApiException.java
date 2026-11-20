/*
 * Copyright 2026 Lightbits Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudstack.storage.datastore.client;

public class LightbitsApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        AUTH,        // 401/403 — bad or expired JWT
        NOT_FOUND,   // 404
        CONFLICT,    // 409 — e.g. volume name already exists
        PRECONDITION, // 412 - If-Match ETag is stale: the resource changed since it was read
        QUOTA,       // project quota / capacity exhausted
        INVALID,     // 400 — bad request (e.g. missing projectName)
        TRANSIENT,   // 5xx, timeouts, connection failures — safe to retry
        UNKNOWN
    }

    private final Kind kind;
    private final int httpStatus;
    private boolean neverSent;

    public LightbitsApiException(Kind kind, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public LightbitsApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = -1;
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Marks a failure where the request provably never reached the cluster (see {@link #isNeverSent()}). */
    public LightbitsApiException neverSent() {
        this.neverSent = true;
        return this;
    }

    /**
     * True when the request provably never reached the cluster: connection refused, connect
     * timeout, unresolvable name, failed TLS handshake. Only such a failure of a mutating call
     * may be sent again; after a 5xx, a read timeout or a reset the change may have been applied.
     */
    public boolean isNeverSent() {
        return neverSent;
    }

    public static Kind kindOf(int status, String body) {
        switch (status) {
            case 400: return Kind.INVALID;
            case 401:
            case 403: return Kind.AUTH;
            case 404: return Kind.NOT_FOUND;
            case 409: return Kind.CONFLICT;
            case 412: return Kind.PRECONDITION;
            default:
                if (status == 507) {
                    return Kind.QUOTA;
                }
                if (status >= 500) {
                    // a failing node may mention anything in its body; a 5xx must stay retryable
                    return Kind.TRANSIENT;
                }
                return body != null && body.toLowerCase().contains("quota") ? Kind.QUOTA : Kind.UNKNOWN;
        }
    }
}
