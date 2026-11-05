"""Behavioral acceptance tests for the reference domain model, not server tests."""
from dataclasses import replace
import tempfile
from pathlib import Path
import unittest
from .core import (Action, Blocked, Engine, EntityKey, Journal, OperationState,
                   Phase, Provider, TaskTicket, Vm, feature_route)


class LogicTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.engine = Engine(clock=lambda: self.now)
        self.key = EntityKey("lab-authority", "vm-1")
        self.vm = Vm(self.key, "pool-1", "debian", "Halted", None, "v1",
                     frozenset(Action), frozenset(Action), frozenset({Action.START}))
        self.epoch = self.engine.connect("xo", Provider.XO, self.key.authority, True)
        self.engine.snapshot("xo", self.epoch, 1, [self.vm])

    def tearDown(self):
        self.engine.journal.close()

    def begin(self, profile="xo"):
        return self.engine.begin_confirmed(
            self.engine.prepare(profile, self.key, Action.START))

    def ticket(self, profile="xo", provider=Provider.XO):
        return TaskTicket(profile, provider, self.key.authority, "task-1")

    def accepted(self):
        op = self.begin()
        self.engine.accepted(op, self.epoch, self.ticket())
        return op

    def success(self):
        op = self.accepted()
        self.engine.task_result(op, self.epoch, self.ticket(), "success")
        return op

    def test_task_submission_is_not_completion(self):
        op = self.accepted()
        self.assertEqual(self.engine.journal.get(op)["state"], "tracking")

    def test_task_success_requires_new_matching_readback(self):
        op = self.success()
        self.assertEqual(self.engine.journal.get(op)["state"], "verifying")
        token = self.engine.verification_token(op)
        self.assertFalse(self.engine.readback(op, self.epoch, self.vm, token))
        self.assertTrue(self.engine.readback(
            op, self.epoch, replace(self.vm, power="Running"), token))
        self.assertEqual(self.engine.journal.get(op)["state"], "succeeded")

    def test_duplicate_click_is_blocked(self):
        self.begin()
        with self.assertRaisesRegex(Blocked, "unresolved"):
            self.begin()

    def test_verified_running_vm_cannot_be_started_again(self):
        op = self.success()
        self.engine.readback(op, self.epoch, replace(self.vm, power="Running"),
                             self.engine.verification_token(op))
        with self.assertRaisesRegex(Blocked, "requires-halted"):
            self.begin()

    def test_wrong_readback_object_is_rejected(self):
        op = self.success()
        wrong = replace(self.vm, key=EntityKey(self.key.authority, "other-vm"),
                        power="Running")
        with self.assertRaisesRegex(Blocked, "object-mismatch"):
            self.engine.readback(op, self.epoch, wrong,
                                 self.engine.verification_token(op))

    def test_shutdown_has_distinct_state_verification(self):
        running = replace(self.vm, power="Running", allowed_now=frozenset({Action.SHUTDOWN}))
        self.engine.snapshot("xo", self.epoch, 2, [running])
        op = self.engine.begin_confirmed(self.engine.prepare("xo", self.key, Action.SHUTDOWN))
        self.engine.accepted(op, self.epoch, self.ticket())
        self.engine.task_result(op, self.epoch, self.ticket(), "success")
        token = self.engine.verification_token(op)
        self.assertFalse(self.engine.readback(op, self.epoch, running, token))
        self.assertTrue(self.engine.readback(op, self.epoch, self.vm, token))

    def test_failed_submission_can_release_only_with_explicit_rejection(self):
        op = self.begin()
        self.engine.rejected_before_acceptance(op, self.epoch)
        self.assertEqual(self.engine.journal.get(op)["state"], "failed")
        self.assertIsNotNone(self.begin())

    def test_reconnect_refreshes_readback_nonce(self):
        op = self.success()
        old = self.engine.verification_token(op)
        gen = self.engine.connect("xo", Provider.XO, self.key.authority, True)
        self.engine.snapshot("xo", gen, 1, [self.vm])
        self.engine.attach_monitor(op, "xo")
        with self.assertRaisesRegex(Blocked, "obsolete"):
            self.engine.readback(op, gen, replace(self.vm, power="Running"), old)

    def test_timeout_without_receipt_is_unknown_and_never_replayed(self):
        op = self.begin()
        self.engine.submission_uncertain(op, self.epoch)
        self.assertEqual(self.engine.journal.get(op)["state"], "outcome-unknown")
        with self.assertRaisesRegex(Blocked, "unresolved"):
            self.begin()

    def test_stale_inventory_is_read_only(self):
        self.now += 31
        with self.assertRaisesRegex(Blocked, "too-old"):
            self.begin()

    def test_disconnect_blocks_actions(self):
        self.engine.invalidate("xo")
        with self.assertRaisesRegex(Blocked, "not-ready"):
            self.begin()

    def test_old_snapshot_cannot_replace_new_session(self):
        new = self.engine.connect("xo", Provider.XO, self.key.authority, True)
        self.assertNotEqual(new, self.epoch)
        self.assertFalse(self.engine.snapshot("xo", self.epoch, 99, [self.vm]))

    def test_out_of_order_snapshot_rejected(self):
        self.engine.snapshot("xo", self.epoch, 2, [replace(self.vm, name="new")])
        self.assertFalse(self.engine.snapshot("xo", self.epoch, 1, [self.vm]))
        self.assertEqual(self.engine.sessions["xo"].vms[self.key].name, "new")

    def test_changed_inventory_expires_confirmation(self):
        plan = self.engine.prepare("xo", self.key, Action.START)
        self.engine.snapshot("xo", self.epoch, 2, [self.vm])
        with self.assertRaisesRegex(Blocked, "confirmation-expired"):
            self.engine.begin_confirmed(plan)

    def test_permissions_and_provider_capabilities_are_separate(self):
        self.engine.snapshot("xo", self.epoch, 2, [replace(self.vm, permitted=frozenset())])
        with self.assertRaisesRegex(Blocked, "permission-denied"):
            self.begin()
        self.engine.snapshot("xo", self.epoch, 3, [replace(self.vm, supported=frozenset())])
        with self.assertRaisesRegex(Blocked, "unsupported"):
            self.begin()

    def test_permission_revocation_removes_action_target(self):
        self.engine.snapshot("xo", self.epoch, 2, [])
        with self.assertRaisesRegex(Blocked, "not-visible"):
            self.begin()

    def test_template_and_dom0_are_not_power_action_targets(self):
        for change in [{"template": True}, {"control_domain": True}]:
            self.engine.snapshot("xo", self.epoch,
                                 self.engine.sessions["xo"].revision + 1,
                                 [replace(self.vm, **change)])
            with self.assertRaisesRegex(Blocked, "not-a-regular-vm"):
                self.begin()

    def test_wrong_task_origin_rejected(self):
        op = self.begin()
        with self.assertRaisesRegex(Blocked, "origin"):
            self.engine.accepted(op, self.epoch, self.ticket("other"))

    def test_old_callback_rejected_after_disconnect(self):
        op = self.accepted()
        self.engine.invalidate("xo")
        with self.assertRaisesRegex(Blocked, "observer"):
            self.engine.task_result(op, self.epoch, self.ticket(), "success")

    def test_task_monitor_can_resume_on_same_origin(self):
        op = self.accepted()
        new = self.engine.connect("xo", Provider.XO, self.key.authority, True)
        self.engine.snapshot("xo", new, 1, [self.vm])
        self.assertEqual(self.engine.attach_monitor(op, "xo"), new)
        self.engine.task_result(op, new, self.ticket(), "success")
        self.assertEqual(self.engine.journal.get(op)["state"], "verifying")

    def test_missing_task_stays_unresolved(self):
        op = self.accepted()
        self.engine.task_result(op, self.epoch, self.ticket(), "missing")
        with self.assertRaisesRegex(Blocked, "unresolved"):
            self.begin()

    def test_failure_does_not_report_success(self):
        op = self.accepted()
        self.engine.task_result(op, self.epoch, self.ticket(), "failure")
        self.assertEqual(self.engine.journal.get(op)["state"], "failed")

    def test_obsolete_readback_cannot_confirm_success(self):
        op = self.success()
        old_token = self.engine.verification_token(op)
        self.engine.task_result(op, self.epoch, self.ticket(), "success")
        with self.assertRaisesRegex(Blocked, "obsolete"):
            self.engine.readback(op, self.epoch,
                                 replace(self.vm, power="Running"), old_token)

    def test_cross_profile_duplicate_fence_for_known_same_authority(self):
        self.begin()
        direct = self.engine.connect("direct", Provider.XAPI, self.key.authority, True)
        self.engine.snapshot("direct", direct, 1, [self.vm])
        with self.assertRaisesRegex(Blocked, "unresolved"):
            self.begin("direct")

    def test_icon_follows_uuid_through_host_move(self):
        self.engine.appearances[self.key] = "custom-moon"
        moved = replace(self.vm, power="Running", host_uuid="host-2")
        self.engine.snapshot("xo", self.epoch, 2, [moved])
        self.assertEqual(self.engine.appearances[moved.key], "custom-moon")
        self.assertIn(self.key, self.engine.hierarchy("xo")["pool-1"]["host-2"])

    def test_halted_vm_not_falsely_assigned_to_old_host(self):
        self.engine.snapshot("xo", self.epoch, 2, [replace(self.vm, host_uuid="old-host")])
        self.assertIn("unassigned", self.engine.hierarchy("xo")["pool-1"])

    def test_same_uuid_in_distinct_authorities_has_distinct_icon(self):
        self.engine.appearances[self.key] = "moon"
        self.assertNotIn(EntityKey("other-lab", self.key.uuid), self.engine.appearances)

    def test_unknown_trust_and_authentication_block_inventory(self):
        gen = self.engine.connect("untrusted", Provider.XAPI, self.key.authority, False)
        with self.assertRaisesRegex(Blocked, "trust"):
            self.engine.snapshot("untrusted", gen, 1, [self.vm])
        self.engine.invalidate("xo", Phase.AUTH_REQUIRED)
        gen = self.engine.sessions["xo"].generation
        with self.assertRaisesRegex(Blocked, "authentication"):
            self.engine.snapshot("xo", gen, 1, [self.vm])

    def test_ssh_not_an_automatic_fallback(self):
        self.assertEqual(feature_route("vm-start", Provider.XO, set(), set()),
                         "unavailable-on-this-connection")
        self.assertEqual(feature_route("host-terminal", Provider.XAPI,
                                      {"host-terminal"}, {"host-terminal"}),
                         "optional-ssh-profile")
        self.assertEqual(feature_route("backup-jobs", Provider.XAPI,
                                      {"backup-jobs"}, {"backup-jobs"}),
                         "requires-xen-orchestra")

    def test_unresolved_operation_survives_journal_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "operations.sqlite")
            first = Journal(path)
            engine = Engine(first, clock=lambda: self.now)
            gen = engine.connect("xo", Provider.XO, self.key.authority, True)
            engine.snapshot("xo", gen, 1, [self.vm])
            op = engine.begin_confirmed(engine.prepare("xo", self.key, Action.START))
            first.close()
            second = Journal(path)
            try:
                self.assertTrue(second.pending(self.key))
                self.assertEqual(second.get(op)["state"], "submitting")
            finally:
                second.close()


if __name__ == "__main__":
    unittest.main()

