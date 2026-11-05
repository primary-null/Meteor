"""Meteor domain-logic reference. No networking, credentials, SSH, or native UI.
Python is an executable specification, not a platform/framework decision.
All calls are serialized by the caller (one actor/event loop in production).
"""
from dataclasses import dataclass, field
from enum import StrEnum
import json
import sqlite3
import time
from typing import Callable
from uuid import uuid4


class Provider(StrEnum):
    XO = "xo-rest"
    XAPI = "xapi"


class Phase(StrEnum):
    CONNECTING = "connecting"
    READY = "ready"
    STALE = "stale"
    AUTH_REQUIRED = "authentication-required"
    TRUST_REQUIRED = "trust-required"


class Action(StrEnum):
    START = "start"
    SHUTDOWN = "clean-shutdown"


class OperationState(StrEnum):
    SUBMITTING = "submitting"
    TRACKING = "tracking"
    VERIFYING = "verifying"
    UNKNOWN = "outcome-unknown"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


UNRESOLVED = {OperationState.SUBMITTING, OperationState.TRACKING,
              OperationState.VERIFYING, OperationState.UNKNOWN}


class Blocked(Exception):
    """A machine-readable reason suitable for localized UI explanation."""


@dataclass(frozen=True)
class EntityKey:
    authority: str  # Verified infrastructure namespace, never a display name/IP.
    uuid: str


@dataclass(frozen=True)
class Vm:
    key: EntityKey
    pool_uuid: str
    name: str
    power: str
    host_uuid: str | None
    revision: str  # Local adapter snapshot revision, not a server ETag claim.
    supported: frozenset[Action] = frozenset()
    permitted: frozenset[Action] = frozenset()
    allowed_now: frozenset[Action] = frozenset()
    template: bool = False
    control_domain: bool = False


@dataclass
class Session:
    profile_id: str
    provider: Provider
    authority: str
    generation: str = field(default_factory=lambda: str(uuid4()))
    phase: Phase = Phase.CONNECTING
    trusted: bool = False
    revision: int = 0
    refreshed_at: float = 0
    vms: dict[EntityKey, Vm] = field(default_factory=dict)


@dataclass(frozen=True)
class ActionPlan:
    profile_id: str
    generation: str
    key: EntityKey
    action: Action
    vm_revision: str
    snapshot_revision: int


@dataclass(frozen=True)
class TaskTicket:
    profile_id: str
    provider: Provider
    authority: str
    stable_id: str  # Provider-normalized task UUID/ID; not a persisted XAPI ref.


class Journal:
    """Durable duplicate fence within one local installation.

    Never stores secrets. Shared server/client-wide exclusion is NOT provided.
    A process exit leaves unresolved records intact for read-only reconciliation.
    """
    def __init__(self, path=":memory:"):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("""CREATE TABLE IF NOT EXISTS operations(
            id TEXT PRIMARY KEY, profile TEXT NOT NULL, provider TEXT NOT NULL,
            authority TEXT NOT NULL, vm TEXT NOT NULL, action TEXT NOT NULL,
            generation TEXT NOT NULL, state TEXT NOT NULL, task_id TEXT,
            created REAL NOT NULL)""")
        self.db.execute("""CREATE UNIQUE INDEX IF NOT EXISTS one_pending_vm
            ON operations(authority, vm) WHERE state NOT IN ('succeeded','failed')""")
        self.db.commit()

    def get(self, op_id):
        row = self.db.execute("SELECT * FROM operations WHERE id=?", (op_id,)).fetchone()
        if row is None:
            raise Blocked("operation-not-found")
        return dict(row)

    def pending(self, key):
        return self.db.execute(
            "SELECT id FROM operations WHERE authority=? AND vm=? "
            "AND state NOT IN ('succeeded','failed')", (key.authority, key.uuid)
        ).fetchone() is not None

    def begin(self, session, plan, now):
        op_id = str(uuid4())
        try:
            with self.db:
                self.db.execute("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?)",
                                (op_id, plan.profile_id, session.provider,
                                 plan.key.authority, plan.key.uuid, plan.action,
                                 plan.generation, OperationState.SUBMITTING, None, now))
        except sqlite3.IntegrityError as exc:
            raise Blocked("vm-has-unresolved-operation") from exc
        return op_id

    def update(self, op_id, state, ticket=None):
        with self.db:
            self.db.execute(
                "UPDATE operations SET state=?, task_id=COALESCE(?,task_id) WHERE id=?",
                (state, ticket, op_id))

    def close(self):
        self.db.close()


class Engine:
    def __init__(self, journal=None, clock: Callable[[], float] = time.monotonic,
                 freshness_seconds=30):
        self.journal = journal or Journal()
        self.clock = clock
        self.freshness_seconds = freshness_seconds
        self.sessions: dict[str, Session] = {}
        self.appearances: dict[EntityKey, str] = {}  # In-memory prototype only.
        self.monitors: dict[str, str] = {}  # Operation -> current observing generation.
        self.verifications: dict[str, str] = {}  # Nonce for a new post-task read.

    def connect(self, profile_id, provider, authority, trusted):
        # Caller supplies identity only AFTER authenticated discovery/trust checks.
        session = Session(profile_id, provider, authority, trusted=trusted)
        if not trusted:
            session.phase = Phase.TRUST_REQUIRED
        self.sessions[profile_id] = session
        return session.generation

    def snapshot(self, profile_id, generation, revision, vms):
        session = self.sessions[profile_id]
        if generation != session.generation or revision <= session.revision:
            return False  # Late callbacks cannot replace current scope or newer state.
        if not session.trusted or session.phase == Phase.AUTH_REQUIRED:
            raise Blocked("connection-needs-trust-or-authentication")
        entities = list(vms)
        if any(vm.key.authority != session.authority for vm in entities):
            raise Blocked("inventory-authority-mismatch")
        if len({vm.key for vm in entities}) != len(entities):
            raise Blocked("duplicate-vm-identity")
        session.vms = {vm.key: vm for vm in entities}
        session.revision = revision
        session.refreshed_at = self.clock()
        session.phase = Phase.READY
        return True

    def invalidate(self, profile_id, reason=Phase.STALE):
        session = self.sessions[profile_id]
        session.phase = reason
        # Fence any work already in flight, even before reconnect starts.
        session.generation = str(uuid4())
        session.revision = 0
        # UI may render last-known inventory, but all mutations are blocked.

    def _vm_for_action(self, profile_id, key, action):
        session = self.sessions[profile_id]
        if not session.trusted or session.phase != Phase.READY:
            raise Blocked("connection-not-ready")
        if self.clock() - session.refreshed_at > self.freshness_seconds:
            raise Blocked("inventory-too-old")
        vm = session.vms.get(key)
        if vm is None:
            raise Blocked("vm-not-visible-in-this-profile")
        if vm.template or vm.control_domain:
            raise Blocked("not-a-regular-vm")
        if action not in vm.supported:
            raise Blocked("unsupported-by-this-provider")
        if action not in vm.permitted:
            raise Blocked("permission-denied")
        if action not in vm.allowed_now:
            raise Blocked("not-currently-allowed")
        if action == Action.START and vm.power != "Halted":
            raise Blocked("start-requires-halted-vm")
        if action == Action.SHUTDOWN and vm.power != "Running":
            raise Blocked("shutdown-requires-running-vm")
        if self.journal.pending(key):
            raise Blocked("vm-has-unresolved-operation")
        return session, vm

    def prepare(self, profile_id, key, action):
        session, vm = self._vm_for_action(profile_id, key, action)
        return ActionPlan(profile_id, session.generation, key, action,
                          vm.revision, session.revision)

    def begin_confirmed(self, plan):
        # Confirmation is bound to the exact profile/generation/object/revision.
        session, vm = self._vm_for_action(plan.profile_id, plan.key, plan.action)
        if (plan.generation != session.generation or
                plan.vm_revision != vm.revision or
                plan.snapshot_revision != session.revision):
            raise Blocked("confirmation-expired-review-again")
        # This commit MUST happen before a production adapter writes to a socket.
        op_id = self.journal.begin(session, plan, self.clock())
        self.monitors[op_id] = session.generation
        return op_id

    def _callback(self, op_id, generation):
        op = self.journal.get(op_id)
        session = self.sessions.get(op["profile"])
        if (session is None or generation != session.generation or
                self.monitors.get(op_id) != generation or
                session.authority != op["authority"] or
                session.provider != op["provider"] or session.phase != Phase.READY):
            raise Blocked("stale-or-wrong-observer")
        if op["state"] not in UNRESOLVED:
            raise Blocked("operation-already-final")
        return op, session

    def accepted(self, op_id, generation, ticket):
        op, _ = self._callback(op_id, generation)
        if op["state"] != OperationState.SUBMITTING:
            raise Blocked("receipt-in-wrong-state")
        if (ticket.profile_id != op["profile"] or ticket.provider != op["provider"]
                or ticket.authority != op["authority"] or not ticket.stable_id):
            raise Blocked("task-origin-mismatch")
        self.journal.update(op_id, OperationState.TRACKING, ticket.stable_id)

    def submission_uncertain(self, op_id, generation):
        op, _ = self._callback(op_id, generation)
        if op["state"] != OperationState.SUBMITTING:
            raise Blocked("submission-not-pending")
        self.journal.update(op_id, OperationState.UNKNOWN)

    def rejected_before_acceptance(self, op_id, generation):
        op, _ = self._callback(op_id, generation)
        if op["state"] != OperationState.SUBMITTING:
            raise Blocked("submission-not-pending")
        # Adapter may call ONLY for explicit no-acceptance proof, never a timeout/5xx.
        self.journal.update(op_id, OperationState.FAILED)

    def attach_monitor(self, op_id, profile_id):
        """After reconnect, attach read-only monitoring to the same task origin."""
        op = self.journal.get(op_id)
        session = self.sessions[profile_id]
        if (op["profile"] != profile_id or op["authority"] != session.authority or
                op["provider"] != session.provider or session.phase != Phase.READY):
            raise Blocked("task-origin-mismatch")
        if not op["task_id"] or op["state"] not in UNRESOLVED:
            raise Blocked("no-known-task-to-resume")
        self.monitors[op_id] = session.generation
        if op["state"] == OperationState.VERIFYING:
            self.verifications[op_id] = str(uuid4())
        return session.generation

    def task_result(self, op_id, generation, ticket, status):
        op, _ = self._callback(op_id, generation)
        if (ticket.profile_id != op["profile"] or ticket.provider != op["provider"] or
                ticket.authority != op["authority"] or ticket.stable_id != op["task_id"]):
            raise Blocked("task-origin-mismatch")
        if op["state"] not in {OperationState.TRACKING, OperationState.VERIFYING}:
            raise Blocked("task-not-trackable")
        if status == "success":
            self.journal.update(op_id, OperationState.VERIFYING)
            self.verifications[op_id] = str(uuid4())
        elif status == "failure":
            self.journal.update(op_id, OperationState.FAILED)
        elif status == "missing":
            self.journal.update(op_id, OperationState.UNKNOWN)
        elif status != "pending":
            raise Blocked("unknown-task-status")

    def verification_token(self, op_id):
        if self.journal.get(op_id)["state"] != OperationState.VERIFYING:
            raise Blocked("task-success-required-first")
        return self.verifications[op_id]

    def readback(self, op_id, generation, vm, request_token):
        op, session = self._callback(op_id, generation)
        if op["state"] != OperationState.VERIFYING:
            raise Blocked("task-success-required-first")
        if self.verifications.get(op_id) != request_token:
            raise Blocked("readback-request-is-obsolete")
        # Adapter must deliver a new authoritative read made AFTER task success.
        if vm.key != EntityKey(op["authority"], op["vm"]):
            raise Blocked("readback-object-mismatch")
        expected = "Running" if op["action"] == Action.START else "Halted"
        if vm.power != expected:
            return False
        if vm.key not in session.vms:
            raise Blocked("vm-no-longer-visible")
        session.vms[vm.key] = vm
        session.revision += 1
        self.journal.update(op_id, OperationState.SUCCEEDED)
        return True

    def hierarchy(self, profile_id):
        """No false placement for halted/templates/dom0 or missing host references."""
        result = {}
        for vm in self.sessions[profile_id].vms.values():
            if vm.control_domain:
                continue
            bucket = ("templates" if vm.template else
                      "unassigned" if vm.power == "Halted" else
                      vm.host_uuid or "placement-unknown")
            result.setdefault(vm.pool_uuid, {}).setdefault(bucket, []).append(vm.key)
        return result


def feature_route(feature, provider, supported, permitted):
    """Never fall through from an API denial/absence into SSH."""
    if feature not in supported:
        return "unavailable-on-this-connection"
    if feature not in permitted:
        return "permission-denied"
    if feature in {"host-terminal", "host-diagnostics"}:
        return "optional-ssh-profile"
    if feature == "vm-console":
        return "dedicated-console-adapter"
    if feature in {"backup-jobs", "backup-restore"} and provider != Provider.XO:
        return "requires-xen-orchestra"
    return provider.value

