# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""
Marvin end-to-end tests for the Lightbits primary storage plugin.

Covers the flows validated manually during bring-up, as automated regression tests:
  - pool lifecycle (add in setUpClass / maintenance+delete in tearDownClass)
  - volume create / attach (NVMe/TCP) / detach / delete, backing volume verified on the cluster
  - volume resize (offline grow)
  - rainy day: bad credentials rejected, missing `environment` rejected
  - leak checker: no orphaned cs-<environment>-<uuid> volumes on the cluster

The suite creates its OWN pool (`lightbits-ci`, storage tag `lb-ci`, environment
LB_ENVIRONMENT) so it never interferes with other Lightbits pools on the same cluster or
CloudStack: placement is steered by the unique tag, cluster objects by the unique
environment prefix.

Requires (env):
  LB_API_ENDPOINT, LB_JWT, LB_PROJECT (default 'default'), LB_ENVIRONMENT (default 'ci-cs1'),
  and a KVM host with nvme-cli + nvme_tcp reachable to the cluster (Tier-2 lab).

Run:
  export LB_API_ENDPOINT=<host> LB_JWT=<jwt>
  nosetests --with-marvin --marvin-config=<cfg> --zone=<zone> --hypervisor=kvm \
      test/integration/plugin/test_lightbits_volumes.py
"""
import os
import time
import uuid

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.lib.base import (Cluster, DiskOffering, Network, Pod,
                             ServiceOffering, StoragePool, VirtualMachine,
                             Volume)
from marvin.lib.common import get_domain, get_template, get_zone
from marvin.lib.utils import cleanup_resources
from nose.plugins.attrib import attr

TAG = "lb-ci"          # storage tag unique to this suite's pool

# CloudStack reports a VM Running when qemu starts, but the guest OS is still booting; a
# virtio disk unplug is only ACKed once the guest kernel is fully up (the stock CentOS
# template boots slowly), so detach-after-attach needs a settle period. Pair with the
# `wait.detach.device` global setting >= 60000 on the environment.
GUEST_SETTLE_SECS = int(os.environ.get("LB_GUEST_SETTLE_SECS", "90"))


class TestLightbitsVolumes(cloudstackTestCase):

    @classmethod
    def setUpClass(cls):
        cls.apiclient = super(TestLightbitsVolumes, cls).getClsTestClient().getApiClient()
        cls.zone = get_zone(cls.apiclient, cls.getClsTestClient().getZoneForTests())
        cls.domain = get_domain(cls.apiclient)
        cls.template = get_template(cls.apiclient, cls.zone.id)
        cls.env = os.environ.get("LB_ENVIRONMENT", "ci-cs1")
        cls.lb_endpoint = os.environ["LB_API_ENDPOINT"]
        cls.lb_jwt = os.environ["LB_JWT"]
        cls.lb_project = os.environ.get("LB_PROJECT", "default")
        cls.run_id = uuid.uuid4().hex[:6]
        cls.networkid = Network.list(cls.apiclient, zoneid=cls.zone.id)[0].id
        cls._cleanup = []

        cls.pool = StoragePool.create(
            cls.apiclient,
            {"name": "lightbits-ci-" + cls.run_id,
             "url": "lightbits://%s/%s?replicas=3&environment=%s" % (
                 cls.lb_endpoint, cls.lb_project, cls.env)},
            scope="cluster", provider="Lightbits", tags=TAG,
            clusterid=Cluster.list(cls.apiclient, zoneid=cls.zone.id)[0].id,
            podid=Pod.list(cls.apiclient, zoneid=cls.zone.id)[0].id,
            zoneid=cls.zone.id,
            details={"jwt": cls.lb_jwt})

        # custom (sized-at-create) offering so volumes can also be resized by size
        cls.disk_offering = DiskOffering.create(
            cls.apiclient,
            {"name": "lb-ci-disk-" + cls.run_id, "displaytext": "Lightbits CI data disk"},
            custom=True, tags=TAG)
        cls.svc_offering = ServiceOffering.create(
            cls.apiclient,
            {"name": "lb-ci-svc-" + cls.run_id, "displaytext": "Lightbits CI 1x500MHz 512MB",
             "cpunumber": 1, "cpuspeed": 500, "memory": 512, "storagetype": "shared"},
            tags=TAG)
        cls._cleanup += [cls.disk_offering, cls.svc_offering]

    @classmethod
    def tearDownClass(cls):
        try:
            cleanup_resources(cls.apiclient, cls._cleanup)
        finally:
            try:
                cls.pool.delete(cls.apiclient)   # maintenance + delete (+ cluster-side sweep)
            except Exception:
                pass

    # ---------- sunny day ----------

    @attr(tags=["advanced"], required_hardware="true")
    def test_01_pool_is_up(self):
        pools = StoragePool.list(self.apiclient, id=self.pool.id)
        self.assertEqual(pools[0].state, "Up")
        self.assertEqual(pools[0].type, "IscsiLUN")
        self.assertTrue(pools[0].disksizetotal > 0, "pool reports capacity from cluster")

    @attr(tags=["advanced"], required_hardware="true")
    def test_02_volume_create_attach_detach_delete(self):
        vm = VirtualMachine.create(
            self.apiclient, {"name": "lb-ci-vm-" + self.run_id,
                             "displayname": "lb-ci-vm-" + self.run_id},
            zoneid=self.zone.id, templateid=self.template.id,
            serviceofferingid=self.svc_offering.id, networkids=[self.networkid],
            accountid="admin", domainid=self.domain.id, mode="default")
        try:
            time.sleep(GUEST_SETTLE_SECS)   # let the guest finish booting (see note at top)
            vol = Volume.create(self.apiclient, {"diskname": "lb-ci-data"},
                                zoneid=self.zone.id, size=8,
                                diskofferingid=self.disk_offering.id,
                                account="admin", domainid=self.domain.id)
            try:
                vm.attach_volume(self.apiclient, vol)
                attached = Volume.list(self.apiclient, id=vol.id)[0]
                self.assertEqual(attached.state, "Ready")
                self.assertEqual(attached.storage, self.pool.name)
                # the backing cluster volume must be named cs-<environment>-<uuid>
                self.assertTrue(self._cluster_has_volume("cs-%s-%s" % (self.env, vol.id)),
                                "cs-<env>-<uuid> volume present on the Lightbits cluster")
                vm.detach_volume(self.apiclient, vol)
            finally:
                vol.delete(self.apiclient)
            self.assertFalse(self._cluster_has_volume("cs-%s-%s" % (self.env, vol.id)),
                             "cluster volume removed after CloudStack delete")
        finally:
            vm.delete(self.apiclient, expunge=True)

    @attr(tags=["advanced"], required_hardware="true")
    def test_03_volume_resize(self):
        # attach so the volume is materialized on the cluster (managed storage creates the
        # backing volume at first attach), resize offline, verify both sides
        vm = VirtualMachine.create(
            self.apiclient, {"name": "lb-ci-rsz-" + self.run_id,
                             "displayname": "lb-ci-rsz-" + self.run_id},
            zoneid=self.zone.id, templateid=self.template.id,
            serviceofferingid=self.svc_offering.id, networkids=[self.networkid],
            accountid="admin", domainid=self.domain.id, mode="default")
        try:
            time.sleep(GUEST_SETTLE_SECS)   # let the guest finish booting (see note at top)
            vol = Volume.create(self.apiclient, {"diskname": "lb-ci-resize"},
                                zoneid=self.zone.id, size=8,
                                diskofferingid=self.disk_offering.id,
                                account="admin", domainid=self.domain.id)
            try:
                vm.attach_volume(self.apiclient, vol)
                vm.detach_volume(self.apiclient, vol)   # offline resize (KVM restriction)
                vol.resize(self.apiclient, size=16)
                self.assertEqual(int(Volume.list(self.apiclient, id=vol.id)[0].size),
                                 16 * 1024 ** 3)
            finally:
                vol.delete(self.apiclient)
        finally:
            vm.delete(self.apiclient, expunge=True)

    # ---------- rainy day ----------

    @attr(tags=["advanced"], required_hardware="false")
    def test_10_bad_credentials_rejected(self):
        with self.assertRaises(Exception):
            StoragePool.create(
                self.apiclient,
                {"name": "lb-bad-" + self.run_id,
                 "url": "lightbits://%s/%s?environment=bad" % (self.lb_endpoint, self.lb_project)},
                scope="cluster", provider="Lightbits",
                clusterid=Cluster.list(self.apiclient, zoneid=self.zone.id)[0].id,
                podid=Pod.list(self.apiclient, zoneid=self.zone.id)[0].id,
                zoneid=self.zone.id, details={"jwt": "not-a-valid-jwt"})

    @attr(tags=["advanced"], required_hardware="false")
    def test_11_missing_environment_rejected(self):
        with self.assertRaises(Exception):
            StoragePool.create(
                self.apiclient,
                {"name": "lb-noenv-" + self.run_id,
                 "url": "lightbits://%s/%s" % (self.lb_endpoint, self.lb_project)},
                scope="cluster", provider="Lightbits",
                clusterid=Cluster.list(self.apiclient, zoneid=self.zone.id)[0].id,
                podid=Pod.list(self.apiclient, zoneid=self.zone.id)[0].id,
                zoneid=self.zone.id, details={"jwt": self.lb_jwt})

    # ---------- leak checker ----------

    @attr(tags=["advanced"], required_hardware="true")
    def test_20_no_orphaned_cluster_volumes(self):
        # every cs-<env>-* cluster volume must correspond to a live CloudStack volume OR a
        # cached template (template_spool_ref entries are named after the template uuid)
        from marvin.lib.base import Template
        cs_ids = {v.id for v in Volume.list(self.apiclient, listall=True) or []}
        cs_ids |= {t.id for t in Template.list(self.apiclient, templatefilter="all",
                                               listall=True) or []}
        prefix = "cs-%s-" % self.env
        for name in self._cluster_volume_names():
            if name.startswith(prefix):
                self.assertIn(name[len(prefix):], cs_ids,
                              "cluster volume %s has no matching CloudStack volume/template (leak)" % name)

    # ---------- helpers ----------

    def _cluster_has_volume(self, name):
        return name in self._cluster_volume_names()

    def _cluster_volume_names(self):
        import json
        import ssl
        import urllib.request
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        req = urllib.request.Request(
            "https://%s/api/v2/volumes?project_name=%s" % (self.lb_endpoint, self.lb_project),
            headers={"Authorization": "Bearer " + self.lb_jwt})
        data = json.loads(urllib.request.urlopen(req, context=ctx, timeout=30).read())
        return [v["name"] for v in data.get("volumes", [])
                if v.get("state") not in ("Deleted", "Deleting")]
