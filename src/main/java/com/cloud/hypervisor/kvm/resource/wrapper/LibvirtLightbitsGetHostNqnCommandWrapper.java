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
            return new LightbitsGetHostNqnAnswer(command, nqn,
                    command.getEndpoints() == null ? new java.util.ArrayList<>() : dataIps(command.getEndpoints()));
        } catch (Exception e) {
            LOG.warn("Lightbits: failed to determine host NQN", e);
            return new LightbitsGetHostNqnAnswer(command, false, "failed to determine host NQN: " + e.getMessage());
        }
    }

    /**
     * The source address this host uses toward each endpoint (what the cluster sees a
     * connection come from), without repeats. An endpoint that does not resolve or has no
     * route is logged and skipped.
     */
    java.util.List<String> dataIps(java.util.List<String> endpoints) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String endpoint : endpoints) {
            String host = hostOf(endpoint);
            try {
                String address = resolve(host);
                String src = routeSource(address);
                if (src == null) {
                    LOG.warn("Lightbits: no route source toward {} ({})", endpoint, address);
                } else if (!out.contains(src)) {
                    out.add(src);
                }
            } catch (Exception e) {
                LOG.warn("Lightbits: could not find the source address toward {}: {}", endpoint, e.getMessage());
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
        Process p = new ProcessBuilder("ip", "-o", "route", "get", address).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            return null;
        }
        return parseRouteSource(out);
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
