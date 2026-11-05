"""Generate browser-review traces by executing the real reference engine."""
from dataclasses import replace
import json
from pathlib import Path
from .core import Action, Blocked, Engine, EntityKey, Phase, Provider, TaskTicket, Vm


def run_scenario(name, provider):
    now = [100.0]
    engine = Engine(clock=lambda: now[0])
    key = EntityKey("verified-lab", "vm-1")
    vm = Vm(key, "pool-1", "debian-web-01", "Halted", None, "r1",
            frozenset(Action), frozenset(Action), frozenset({Action.START}))
    generation = engine.connect("lab", provider, key.authority, True)
    op = None
    trace = []

    def record(title, explanation):
        session = engine.sessions["lab"]
        operation = engine.journal.get(op) if op else None
        trace.append({"title": title, "explanation": explanation,
                      "connection": session.phase.value,
                      "operation": operation["state"] if operation else "none",
                      "vmPower": session.vms.get(key, vm).power,
                      "locked": engine.journal.pending(key),
                      "source": provider.value})

    try:
        record("Trusted connection discovered",
               "Trust/authentication discovery is supplied by a fixture. No socket is opened.")
        engine.snapshot("lab", generation, 1, [vm])
        record("Inventory is fresh", "One normalized VM snapshot; actions are scoped to this profile.")
        if name == "stale":
            now[0] += 31
            try:
                engine.prepare("lab", key, Action.START)
            except Blocked as error:
                record("Cached screen stays read-only", str(error))
        elif name == "permission":
            engine.snapshot("lab", generation, 2, [replace(vm, permitted=frozenset())])
            try:
                engine.prepare("lab", key, Action.START)
            except Blocked as error:
                record("Action is unavailable", str(error) + "; no SSH or other profile fallback.")
        elif name == "migration":
            engine.appearances[key] = "custom-moon"
            engine.snapshot("lab", generation, 2, [
                replace(vm, power="Running", host_uuid="host-1", revision="r2")])
            record("Custom icon belongs to VM identity", "A moon icon is associated with authority + VM UUID.")
            engine.snapshot("lab", generation, 3, [
                replace(vm, power="Running", host_uuid="host-2", revision="r3")])
            record("Placement changes; icon remains", "VM now groups under host-2. Icon: " + engine.appearances[key])
        else:
            plan = engine.prepare("lab", key, Action.START)
            record("Intent prepared", "Confirmation is tied to this VM, profile, generation and inventory revision.")
            if name == "confirmation":
                engine.snapshot("lab", generation, 2, [vm])
                try:
                    engine.begin_confirmed(plan)
                except Blocked as error:
                    record("Confirmation expired", str(error) + "; review the refreshed VM before submitting.")
            else:
                op = engine.begin_confirmed(plan)
                record("Intent journaled before sending", "A local lock prevents another action on this verified VM.")
                if name == "uncertain":
                    engine.submission_uncertain(op, generation)
                    record("Reply lost after possible send", "Outcome unknown. No automatic resubmission.")
                    try:
                        engine.prepare("lab", key, Action.START)
                    except Blocked as error:
                        record("Second action blocked", str(error) + "; reconcile existing operation first.")
                else:
                    ticket = TaskTicket("lab", provider, key.authority, "normalized-task-uuid")
                    engine.accepted(op, generation, ticket)
                    record("Task accepted", "The fixture supplies a provider-owned task ID. Accepted is not completed.")
                    if name == "reconnect":
                        old_generation = generation
                        engine.invalidate("lab")
                        record("Connection lost", "Old callbacks are fenced; journal and known task identity remain.")
                        generation = engine.connect("lab", provider, key.authority, True)
                        engine.snapshot("lab", generation, 1, [vm])
                        engine.attach_monitor(op, "lab")
                        try:
                            engine.task_result(op, old_generation, ticket, "success")
                        except Blocked as error:
                            record("Late callback discarded", str(error))
                        record("Resume observing the same task", "Read-only task monitoring reconnects to the original provider; command is not sent again.")
                    if name == "missing-task":
                        engine.task_result(op, generation, ticket, "missing")
                        record("Task no longer queryable", "Missing does not mean failed. Keep unresolved; do not replay.")
                    else:
                        engine.task_result(op, generation, ticket, "success")
                        record("Task reports success", "Still verifying. Request a new authoritative VM read.")
                        nonce = engine.verification_token(op)
                        engine.readback(op, generation, replace(vm, power="Running",
                                        host_uuid="host-1", revision="r4"), nonce)
                        record("Running state confirmed", "Task success plus new VM read-back completes Start. Guest OS readiness is separate.")
    finally:
        engine.journal.close()
    return trace


def export():
    names = [("happy", "Start completes"), ("uncertain", "Reply lost"),
             ("reconnect", "Reconnect during a task"), ("missing-task", "Task disappears"),
             ("stale", "Old cached inventory"), ("permission", "Permission denied"),
             ("confirmation", "Inventory changes before confirmation"),
             ("migration", "VM moves to another host")]
    return {"kind": "executed-reference-model", "noNetwork": True,
            "scenarios": [{"id": name, "title": title,
                           "providers": {provider.value: run_scenario(name, provider)
                                         for provider in Provider}}
                          for name, title in names]}


if __name__ == "__main__":
    output = Path(__file__).resolve().parent.parent / "meteor-logic-scenarios.json"
    output.write_text(json.dumps(export(), indent=2) + "\n", encoding="utf-8")
    print(f"Generated {output.name}: 8 scenarios across 2 provider fixtures; no network.")

