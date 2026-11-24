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

import com.cloud.agent.api.Command;

/**
 * Asks a KVM host for its NVMe host NQN (/etc/nvme/hostnqn).
 *
 * Sent by the LightbitsHostListener on host connect so the management server can persist
 * the NQN as a host detail; the driver's grantAccess/revokeAccess use it for volume ACLs.
 * Serialized by full class name (ArrayTypeAdaptor), so it only needs the plugin jar on
 * both the management-server and agent classpaths.
 */
public class LightbitsGetHostNqnCommand extends Command {

    /**
     * The pool's NVMe discovery endpoints (host:port). When set, the agent also reports the
     * source address it uses to reach each one (the host's data IPs, for volume IP ACLs). An
     * older agent ignores the field.
     */
    private java.util.List<String> endpoints;

    public LightbitsGetHostNqnCommand() {
        super();
    }

    public LightbitsGetHostNqnCommand(java.util.List<String> endpoints) {
        super();
        this.endpoints = endpoints == null ? null : new java.util.ArrayList<>(endpoints);
    }

    /**
     * When true the agent makes this host's in-band authentication key if it has none, and
     * reports it with the number of Lightbits paths that do not hold that key. An older agent ignores it.
     */
    private boolean inbandAuth;

    public LightbitsGetHostNqnCommand(java.util.List<String> endpoints, boolean inbandAuth) {
        this(endpoints);
        this.inbandAuth = inbandAuth;
    }

    /**
     * When true (with inbandAuth) the agent also makes this host's controller key if it has
     * none, for two-way authentication, and reports it with the number of Lightbits paths that
     * do not hold it. An older agent ignores it.
     */
    private boolean twoWay;

    public LightbitsGetHostNqnCommand(java.util.List<String> endpoints, boolean inbandAuth, boolean twoWay) {
        this(endpoints, inbandAuth);
        this.twoWay = inbandAuth && twoWay;
    }

    public boolean isTwoWay() {
        return twoWay;
    }

    /**
     * When true (with inbandAuth) the agent rotates this host's keys first, if no Lightbits
     * volume is in use on it, and reports the new ones. An older agent ignores it.
     */
    private boolean rotateKeys;

    public LightbitsGetHostNqnCommand withRotateKeys(boolean rotateKeys) {
        this.rotateKeys = inbandAuth && rotateKeys;
        return this;
    }

    public boolean isRotateKeys() {
        return rotateKeys;
    }

    public java.util.List<String> getEndpoints() {
        return endpoints;
    }

    public boolean isInbandAuth() {
        return inbandAuth;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
