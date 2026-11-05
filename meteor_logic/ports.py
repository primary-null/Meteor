"""Contracts a native networking implementation must satisfy.

These Protocols describe boundaries only; no production adapters are present.
Provider-specific TLS, auth, parsing, discovery and retries belong outside core.
"""
from dataclasses import dataclass
from typing import Protocol
from .core import ActionPlan, EntityKey, Provider, TaskTicket, Vm


@dataclass(frozen=True)
class InventorySnapshot:
    generation: str
    local_revision: int
    vms: tuple[Vm, ...]


@dataclass(frozen=True)
class Capability:
    name: str
    supported: bool | None  # None = not discovered; never infer permission.
    permitted: bool | None
    reason: str
    evidence: str  # Schema/version/permission discovery provenance, no secrets.


class InfrastructurePort(Protocol):
    provider: Provider

    async def authenticate_and_discover(self, profile_id: str) -> str:
        """Validate trust, authenticate, verify authority; return authority ID."""
        ...

    async def bootstrap(self, generation: str) -> InventorySnapshot:
        """Subscribe/buffer around bootstrap, reconcile gaps, then publish snapshot."""
        ...

    async def capabilities(self, key: EntityKey) -> tuple[Capability, ...]:
        ...

    async def submit_once(self, plan: ActionPlan) -> TaskTicket:
        """After journal begin, send once; timeout after possible send is uncertain."""
        ...

    async def task_status(self, ticket: TaskTicket) -> str:
        """Normalize pending/success/failure/missing. Preserve native detail safely."""
        ...

    async def read_vm(self, key: EntityKey, verification_nonce: str) -> Vm:
        """Fresh server request after task success; never satisfy from old cache."""
        ...

    async def close(self) -> None:
        """Close subscriptions/sockets; logout owned sessions when possible."""
        ...


class ConsolePort(Protocol):
    async def open(self, profile_id: str, key: EntityKey) -> object:
        """Resolve current placement, console record, endpoint trust and auth.

        Return a short-lived native RFB/WebSocket session handle, never a URL
        carrying credentials to the UI or an unrestricted embedded browser.
        No guaranteed XO REST console path is asserted by this prototype.
        """
        ...


class MetricsPort(Protocol):
    async def read_window(self, profile_id: str, key: EntityKey,
                          start_epoch: int, end_epoch: int) -> object:
        """Bounded samples with timestamps/units/coverage, missing values retained."""
        ...


class HostSshPort(Protocol):
    async def open_terminal(self, ssh_profile_id: str, host_uuid: str) -> object:
        """Explicit host profile and verified host key. Never an API fallback."""
        ...

