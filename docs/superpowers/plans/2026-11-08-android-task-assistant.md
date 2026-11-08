# Android Task Assistant Implementation Plan

> Execution: implement in the current session under the user's confirmed task list.

**Goal:** Build an Android assistant for separately running four user-selected reward tasks on the connected Xiaomi device.

**Architecture:** A native app exposes controls and stores local settings/results. An accessibility service runs one bounded, cancellable workflow at a time; page-specific adapters only act on verified targets and stop on uncertainty. Best-effort scheduling uses Android-supported mechanisms.

**Tech Stack:** Java, Android SDK, Gradle; Android accessibility APIs; local preferences.

**Spec:** `docs/shipyard/android-task-assistant/hld.md`, `lld.md`, and `tasks.md`.

## Global Constraints

- Android 16 Xiaomi 15; no root or server.
- No automatic payment, order, subscription, authorization, invitation, or security challenge.
- Ads disabled by default; never bypass completion or verification.
- Unknown pages, unverified outcomes, timeouts, locked device, or missing permissions must be reported honestly.
- Actual support depends on device testing; never represent untested adapters as complete.

## Review Focus

- Wrong foreground package: no click. Test package gate.
- Sensitive action in visible content: no action. Test risk classifier.
- Stale or missing target: stop with explanation. Test state decision.
- Cancel during execution: no later operation. Test cancellation state.
- Scheduling while service unavailable: record inability rather than success. Test scheduler preconditions.

## Tasks

### 1. Execution policy and project foundation

**Files:** `settings.gradle`, `build.gradle`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/.../ExecutionPolicy.java`, `app/src/test/java/.../ExecutionPolicyTest.java`.

- [ ] Add behavior tests for allowed package, sensitive actions, unknown target, and cancellation; verify expected failures.
- [ ] Implement policy and minimal native project, then run tests and build.

### 2. Control screen and accessibility service

**Files:** `MainActivity.java`, `TaskService.java`, `res/xml/accessibility_service.xml`, resources.

- [ ] Implement four independent controls, permission state, cancellation and locally stored result records.
- [ ] Verify launch and permission flow on device; report unimplemented workflows clearly.

### 3. Page inspection and separate workflows

**Files:** `workflow/ForestWorkflow.java`, `ManorWorkflow.java`, `FarmWorkflow.java`, `ReadingWorkflow.java`, and tests for actual observed transitions.

- [ ] Inspect each target page on the user's device and document accessibility evidence.
- [ ] Implement and test only observed, unambiguous reward actions; check postconditions and stop on uncertainty.
- [ ] Validate each task with the user on the device; record unsupported views and outcomes.

### 4. Scheduling and integration

**Files:** scheduling components, settings UI and relevant tests.

- [ ] Add local opt-in schedule and availability/error reporting, respecting Android background restrictions.
- [ ] Run unit tests, build APK, install and verify controls, cancellation, each supported task, and scheduling; document remaining gaps.
