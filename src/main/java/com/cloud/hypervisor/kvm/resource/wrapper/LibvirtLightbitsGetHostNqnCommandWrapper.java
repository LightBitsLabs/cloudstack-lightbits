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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnAnswer;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

/**
 * Reports this host's NVMe host NQN. Discovered by LibvirtRequestWrapper's Reflections scan
 * of this package (same jar-drop mechanism as the storage adaptor).
 *
 * Reads /etc/nvme/hostnqn; if the file is missing, obtains the NQN from
 * `nvme show-hostnqn` (deterministic, derived from the DMI system UUID) and pins it to the
 * file so every future connect — ours or a manual nvme-cli one — uses the same identity.
 */
@ResourceWrapper(handles = LightbitsGetHostNqnCommand.class)
public class LibvirtLightbitsGetHostNqnCommandWrapper
        extends CommandWrapper<LightbitsGetHostNqnCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOG = LogManager.getLogger(LibvirtLightbitsGetHostNqnCommandWrapper.class);

    @Override
    public Answer execute(LightbitsGetHostNqnCommand command, LibvirtComputingResource resource) {
        try {
            String nqn = readTrimmed(hostNqnPath());
            if (nqn == null) {
                nqn = trimToNull(runShowHostNqn());
                if (nqn != null) {
                    pin(nqn);
                }
            }
            if (nqn == null) {
                return new LightbitsGetHostNqnAnswer(command, false,
                        hostNqnPath() + " is missing and 'nvme show-hostnqn' returned nothing - is nvme-cli installed?");
            }
            if (!nqn.startsWith("nqn.")) {
                return new LightbitsGetHostNqnAnswer(command, false, "unexpected host NQN '" + nqn + "'");
            }
            LightbitsGetHostNqnAnswer answer;
            if (command.getEndpoints() == null) {
                answer = new LightbitsGetHostNqnAnswer(command, nqn, new java.util.ArrayList<>());
            } else {
                java.util.List<String> failed = new java.util.ArrayList<>();
                java.util.List<String> ips = dataIps(command.getEndpoints(), failed);
                answer = new LightbitsGetHostNqnAnswer(command, nqn, ips).withDataIpsPartial(!failed.isEmpty());
            }
            if (command.isInbandAuth()) {
                answer.withKeyScope(command.getKeyScope());   // tells the management server the keys are this scope's
                if (command.isRotateKeys()) {
                    try {
                        rotateKeys(command.getKeyScope());
                        answer.withRotation(true, null);
                    } catch (RuntimeException e) {
                        LOG.warn("Lightbits: {}", e.getMessage());
                        answer.withRotation(false, e.getMessage());
                    }
                }
                inbandAuth(answer, command.getKeyScope());
                if (command.isTwoWay() && answer.getDhchapKey() != null) {
                    ctrlKey(answer, command.getKeyScope());
                }
                if (answer.getDhchapKey() != null) {
                    // after both keys are prepared: preparing one can copy a flagged host-wide key
                    // into this cluster and flag it too
                    exposure(answer, command.getKeyScope());
                }
            }
            return answer;
        } catch (Exception e) {
            LOG.warn("Lightbits: failed to determine host NQN", e);
            return new LightbitsGetHostNqnAnswer(command, false, "failed to determine host NQN: " + e.getMessage());
        }
    }

    /**
     * This host's in-band authentication key (made if missing) and how many of its Lightbits
     * paths do not hold that key (none, or an old one). A failure is reported in the answer, not as a failed command: the
     * NQN and data IPs are still good.
     */
    void inbandAuth(LightbitsGetHostNqnAnswer answer) {
        inbandAuth(answer, null);
    }

    /** {@link #inbandAuth(LightbitsGetHostNqnAnswer)} with this host's keys for one cluster (null: the host-wide keys). */
    void inbandAuth(LightbitsGetHostNqnAnswer answer, String scope) {
        String key;
        try {
            key = keysFor(scope).ensure();
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not prepare this host's in-band authentication key: {}", e.getMessage());
            answer.withInbandAuth(null, null, e.getMessage());
            return;
        }
        Integer keyless;
        try {
            keyless = pathsWithoutKey(key, scope);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not count this host's NVMe/TCP paths without its current key: {}", e.getMessage());
            keyless = null;
        }
        answer.withInbandAuth(key, keyless, null);
    }

    /** Whether the keys just reported may have been copied; left unset (unknown) when it cannot be checked. */
    void exposure(LightbitsGetHostNqnAnswer answer, String scope) {
        try {
            answer.withKeysPossiblyExposed(keysFor(scope).keysPossiblyExposed());
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not check whether this host's in-band authentication keys need rotating: {}", e.getMessage());
        }
    }

    /**
     * This host's controller key (made if missing), for two-way authentication, and how many of
     * its Lightbits paths do not hold it. A failure leaves the key out (the host is registered
     * one-way) and is logged; the rest of the answer stands.
     */
    void ctrlKey(LightbitsGetHostNqnAnswer answer) {
        ctrlKey(answer, null);
    }

    void ctrlKey(LightbitsGetHostNqnAnswer answer, String scope) {
        String ctrl;
        try {
            ctrl = keysFor(scope).ensureCtrlKey();
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not prepare this host's in-band authentication controller key: {}", e.getMessage());
            return;
        }
        Integer without;
        try {
            without = pathsWithoutCtrlKey(ctrl, scope);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not count this host's NVMe/TCP paths without its current controller key: {}", e.getMessage());
            without = null;
        }
        answer.withCtrlKey(ctrl, without);
    }

    /** Paths whose controller key is none or an old one. */
    protected int pathsWithoutCtrlKey(String currentCtrlKey) {
        return com.cloud.hypervisor.kvm.storage.LightbitsHostKey.pathsWithoutCtrlKey(currentCtrlKey);
    }

    /** Rotate this host's keys if no Lightbits volume is in use here; throws with the reason otherwise. */
    /** This host's keys for one cluster (its subsystem NQN), or the host-wide keys for null. */
    protected com.cloud.hypervisor.kvm.storage.LightbitsHostKey keysFor(String scope) {
        return hostKey().forScope(scope);
    }

    /** Paths of the cluster's subsystem (all Lightbits paths for null) that do not hold the current key. */
    protected int pathsWithoutKey(String currentKey, String scope) {
        return scope == null ? pathsWithoutKey(currentKey)
                : com.cloud.hypervisor.kvm.storage.LightbitsHostKey.pathsWithoutKey(currentKey, scope);
    }

    protected int pathsWithoutCtrlKey(String currentCtrlKey, String scope) {
        return scope == null ? pathsWithoutCtrlKey(currentCtrlKey)
                : com.cloud.hypervisor.kvm.storage.LightbitsHostKey.pathsWithoutCtrlKey(currentCtrlKey, scope);
    }

    /** Rotate this host's keys for one cluster (null: the host-wide keys). */
    protected void rotateKeys(String scope) {
        if (scope == null) {
            rotateKeys();
        } else {
            com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.rotateInbandKeys(scope);
        }
    }

    protected void rotateKeys() {
        com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.rotateInbandKeys();
    }

    protected com.cloud.hypervisor.kvm.storage.LightbitsHostKey hostKey() {
        return new com.cloud.hypervisor.kvm.storage.LightbitsHostKey();
    }

    /** Paths that hold no key or an old one; reported in the answer's keylessPaths field. */
    protected int pathsWithoutKey(String currentKey) {
        return com.cloud.hypervisor.kvm.storage.LightbitsHostKey.pathsWithoutKey(currentKey);
    }

    /**
     * The source address this host uses toward each endpoint (what the cluster sees a
     * connection come from), without repeats. An endpoint that does not resolve or has no
     * route is logged and skipped.
     */
    java.util.List<String> dataIps(java.util.List<String> endpoints) {
        return dataIps(endpoints, new java.util.ArrayList<>());
    }

    /** As {@link #dataIps(java.util.List)}, adding each endpoint that could not be looked up to {@code failed}. */
    java.util.List<String> dataIps(java.util.List<String> endpoints, java.util.List<String> failed) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String endpoint : endpoints) {
            String host = hostOf(endpoint);
            try {
                String address = resolve(host);
                String src = routeSource(address);
                if (src == null) {
                    LOG.warn("Lightbits: no route source toward {} ({})", endpoint, address);
                    failed.add(endpoint);
                } else if (!out.contains(src)) {
                    out.add(src);
                }
            } catch (Exception e) {
                LOG.warn("Lightbits: could not find the source address toward {}: {}", endpoint, e.getMessage());
                failed.add(endpoint);
            }
        }
        return out;
    }

    /** host of host:port, [v6]:port, or a bare host. */
    static String hostOf(String endpoint) {
        String e = endpoint.trim();
        if (e.startsWith("[")) {
            int close = e.indexOf(']');
            return close > 0 ? e.substring(1, close) : e;
        }
        int colon = e.lastIndexOf(':');
        return colon > 0 && e.indexOf(':') == colon ? e.substring(0, colon) : e;
    }

    protected String resolve(String host) throws java.net.UnknownHostException {
        return java.net.InetAddress.getByName(host).getHostAddress();
    }

    /** The "src" of {@code ip -o route get <address>}, or null. */
    protected String routeSource(String address) throws IOException, InterruptedException {
        // output to a file, so the wait below bounds the whole lookup (reading a pipe blocks until EOF)
        Path out = Files.createTempFile("lightbits-route", ".out");
        try {
            Process p = new ProcessBuilder("ip", "-o", "route", "get", address).redirectErrorStream(true)
                    .redirectOutput(out.toFile()).start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            return parseRouteSource(Files.readString(out));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    static String parseRouteSource(String ipRouteGetOutput) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bsrc\\s+(\\S+)").matcher(ipRouteGetOutput == null ? "" : ipRouteGetOutput);
        return m.find() ? m.group(1) : null;
    }

    private String readTrimmed(Path path) throws IOException {
        return Files.isReadable(path) ? trimToNull(Files.readString(path)) : null;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void pin(String nqn) {
        try {
            Files.createDirectories(hostNqnPath().getParent());
            Files.writeString(hostNqnPath(), nqn + "\n");
            LOG.info("Lightbits: pinned generated host NQN to {}", hostNqnPath());
        } catch (IOException e) {
            // show-hostnqn is DMI-UUID-derived, so the identity is stable even unpinned
            LOG.warn("Lightbits: could not write {} ({}); continuing with generated NQN", hostNqnPath(), e.getMessage());
        }
    }

    protected Path hostNqnPath() {
        return Path.of("/etc/nvme/hostnqn");
    }

    protected String runShowHostNqn() {
        String out = Script.runSimpleBashScript("nvme show-hostnqn");
        return out == null || out.isBlank() ? null : out.trim();
    }
}
