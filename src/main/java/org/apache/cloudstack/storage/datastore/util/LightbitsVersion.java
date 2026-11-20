/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.datastore.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What this build of the plugin is. Logged when the plugin loads on the management server and
 * on every KVM agent, and compared between the two: after an upgrade a host that still runs
 * the old jar (the jar was not replaced there, or the agent was not restarted) is the usual
 * cause of behavior that "was fixed in this release".
 */
public final class LightbitsVersion {
    private static final String VERSION;
    private static final String DESCRIPTION;

    static {
        Properties p = new Properties();
        try (InputStream in = LightbitsVersion.class.getResourceAsStream("/lightbits-plugin.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // falls through to "unknown"
        }
        VERSION = clean(p.getProperty("version")) + "+" + clean(p.getProperty("built"));
        DESCRIPTION = "Lightbits storage plugin " + clean(p.getProperty("version")) + " (built " + clean(p.getProperty("built"))
                + " against CloudStack " + clean(p.getProperty("cloudstack")) + ")";
    }

    private LightbitsVersion() {
    }

    private static String clean(String v) {
        return v == null || v.isBlank() || v.startsWith("${") ? "unknown" : v.trim();
    }

    /** Version and build time, identical for every copy of one build: {@code 0.1.0-SNAPSHOT+2026-11-19T22:40:00Z}. */
    public static String version() {
        return VERSION;
    }

    public static String describe() {
        return DESCRIPTION;
    }
}
