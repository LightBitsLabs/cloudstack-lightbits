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
        QUOTA,       // project quota / capacity exhausted
        INVALID,     // 400 — bad request (e.g. missing projectName)
        TRANSIENT,   // 5xx, timeouts, connection failures — safe to retry
        UNKNOWN
    }

    private final Kind kind;
    private final int httpStatus;

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

    public static Kind kindOf(int status, String body) {
        switch (status) {
            case 400: return Kind.INVALID;
            case 401:
            case 403: return Kind.AUTH;
            case 404: return Kind.NOT_FOUND;
            case 409: return Kind.CONFLICT;
            default:
                if (status == 507 || (body != null && body.toLowerCase().contains("quota"))) {
                    return Kind.QUOTA;
                }
                return status >= 500 ? Kind.TRANSIENT : Kind.UNKNOWN;
        }
    }
}
