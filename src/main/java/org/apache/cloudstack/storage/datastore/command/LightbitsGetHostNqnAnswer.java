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
package org.apache.cloudstack.storage.datastore.command;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.LogLevel;
import com.cloud.agent.api.LogLevel.Log4jLevel;

/** The KVM host's NVMe host NQN, or a failure reason. */
public class LightbitsGetHostNqnAnswer extends Answer {

    private String hostNqn;
    /** Source addresses toward the command's endpoints; null from an agent older than the field. */
    private java.util.List<String> dataIps;
    /**
     * This host's in-band authentication key, when the command asked for it. Never logged:
     * CloudStack's command logging leaves out fields marked Off.
     */
    @LogLevel(Log4jLevel.Off)
    private String dhchapKey;
    /** Lightbits paths of this host that hold no key; null when not asked or not known. */
    private Integer keylessPaths;
    /** Why the key could not be prepared, when the command asked for it and that failed. */
    private String inbandAuthError;

    protected LightbitsGetHostNqnAnswer() {
    }

    public LightbitsGetHostNqnAnswer(LightbitsGetHostNqnCommand cmd, String hostNqn) {
        this(cmd, hostNqn, null);
    }

    public LightbitsGetHostNqnAnswer(LightbitsGetHostNqnCommand cmd, String hostNqn, java.util.List<String> dataIps) {
        super(cmd, true, null);
        this.hostNqn = hostNqn;
        this.dataIps = dataIps == null ? null : new java.util.ArrayList<>(dataIps);
    }

    public LightbitsGetHostNqnAnswer(LightbitsGetHostNqnCommand cmd, boolean result, String details) {
        super(cmd, result, details);
    }

    /** Adds the in-band authentication outcome: the key and keyless path count, or the error. */
    public LightbitsGetHostNqnAnswer withInbandAuth(String dhchapKey, Integer keylessPaths, String error) {
        this.dhchapKey = dhchapKey;
        this.keylessPaths = keylessPaths;
        this.inbandAuthError = error;
        return this;
    }

    public String getDhchapKey() {
        return dhchapKey;
    }

    public Integer getKeylessPaths() {
        return keylessPaths;
    }

    public String getInbandAuthError() {
        return inbandAuthError;
    }

    public String getHostNqn() {
        return hostNqn;
    }

    public java.util.List<String> getDataIps() {
        return dataIps;
    }
}
