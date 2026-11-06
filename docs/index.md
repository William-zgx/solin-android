# Documentation

Start with the [English README](../README.md) or [中文 README](../README.zh-CN.md).
Use the [Guidebook](../Guidebook.md) for a short code map and [AGENTS.md](../AGENTS.md)
for the coding-agent protocol.

## Find the right guide

| I want to… | Start here |
| --- | --- |
| Build and run Solin | [README](../README.md#quick-start) |
| Understand local data and remote sends | [Privacy notice](privacy_notice.md) |
| Change the agent, tools or runtime | [Core modules](agent_core_modules.md) |
| Change module boundaries or safety gates | [Architecture goals](architecture_depth.md) |
| Test phone operations | [Device acceptance](phone_acceptance.md) |
| Understand the Mika-derived capabilities | [Phone capability record](plans/mika-phone-capabilities.md) |
| Choose or package model assets | [Model manifest](model_manifest.md) · [Bundled packages](bundled_model_package.md) |
| Assess release readiness | [Readiness](release_readiness.md) · [Checklist](release_checklist.md) |
| Find results that actually ran | [Validation report](validation_report.md) |

## Current baseline

This refresh describes main through `cb7e4918` (2026-11-04, UTC+08:00): the Mika
phone capability integration and its CI, API-compatibility and cancellation fixes
are merged. [Main CI convergence](validation_report.md#2026-11-04--main-ci-convergence)
records the successful workflow and the hosted emulator infrastructure skip.
The earlier Mika entry retains the APK hashes actually tested before commit.

Keep **implementation**, **validation**, and **release approval** distinct.
A green CI job does not turn a skipped device check into a pass, validate every
OEM, or approve publication. Historical evidence retains its original date;
pending approval records remain pending until their owners complete the review.

## Product, models and privacy

| Document | Owns |
| --- | --- |
| [Privacy notice](privacy_notice.md) | Local storage, remote sends, permissions, retention and deletion |
| [Capability matrix](capability_matrix.json) | Product/tool capabilities, privacy classes, confirmation policy and required tests |
| [Model manifest](model_manifest.md) | Pinned revisions, sizes, hashes, access and license-review limits |
| [Model capability profiles](model_capability_profiles.json) | Local/remote modality and runtime capability contract |
| [Bundled model package](bundled_model_package.md) | Internal split-package build, signing and installation |
| [Sharing draft](solin_share_draft.md) | Outreach copy; not an independent source of capability claims |
| [Artwork](assets/) | README illustrations and brand previews; conceptual art is not device evidence |

## Architecture and implementation

| Document | Owns |
| --- | --- |
| [Core modules](agent_core_modules.md) | Current responsibilities, interfaces and regression coverage |
| [Architecture depth](architecture_depth.md) | Deep-module goals, threat model, gates and structural measures |
| [Intent routing](intent_routing_skill_arbitration.md) | Skill-first arbitration and routing evidence |
| [Model-driven app control](model_driven_app_control_multi_agent_plan.md) | Observe–act contract, coordination history and acceptance limits |
| [Mika phone capabilities](plans/mika-phone-capabilities.md) | Provenance, bounded tools, route reuse and experimental background display |
| [Adaptive inference status](specs/20260718-adaptive-edge-inference/status.md) | Implemented behavior, build-type rollout and pending evidence |

Read [architecture_depth.md](architecture_depth.md) before structural changes.
The [Wave record](optimization_plan_weaknesses.md) and older [plans](plans/) contain
historical targets as well as proposals; each status section determines what is
still outstanding. The adaptive inference `brainstorm`, `research`, `spec`,
`plan`, `tasks`, and `review` files preserve design history. Its linked **status**
page owns current facts.

## Validation and release

| Document | Owns |
| --- | --- |
| [Device acceptance](phone_acceptance.md) | Emulator/physical-device commands, manual flows and evidence rules |
| [Validation report](validation_report.md) | Append-only commands, results, artifacts and known gaps |
| [AI behavior evaluation](ai_behavior_eval_plan.md) | Fixture taxonomy, boundary mapping and actual-trace requirements |
| [Phone benchmark](phone_task_benchmark.json) | Fixed phone tasks; execution rules live in device acceptance |
| [Decision-routing evaluation](decision_routing_eval.json) | Labeled cloud/edge routing requests |
| [Release readiness](release_readiness.md) | Current blockers and next owner actions |
| [Release checklist](release_checklist.md) | RC sequence and evidence requirements |
| [Blocker dashboard](release_blocker_dashboard.md) | Generated view of readiness and the [roadmap ledger](roadmap_gap_matrix.json) |
| [Performance template](perf_baseline_template.properties) | Fields for measured RC performance, not a result itself |

Regenerate the dashboard with `scripts/generate_release_blocker_dashboard.sh`.
Its generation date is not a new acceptance date. Phone-search runtime facts
belong in core modules, device commands in acceptance, and dated results in the
validation report; link between them instead of repeating the full contract.

### Machine-readable review records

| Record | Verifier |
| --- | --- |
| [Release artifacts](release_record.json) | `scripts/verify_release_record.sh` |
| [Release operations](release_operations_record.json) | `scripts/verify_release_operations_record.sh` |
| [Release validation](release_validation_record.json) | `scripts/verify_release_validation_record.sh` |
| [Privacy review](privacy_review.json) and [evidence](privacy_review_evidence/) | `scripts/verify_privacy_review.sh` |
| [Store policy](store_policy_record.json) and [evidence](store_policy_review_evidence/) | `scripts/verify_store_policy_record.sh` |
| [Model license review](model_license_review.json) and [metadata](model_license_metadata.json) | `scripts/verify_model_license_review.sh` |

JSON and `.properties` records are verifier inputs. Updating candidate hashes
keeps them aligned with the reviewed files; it does **not** supply approval,
reviewer identity, release artifacts, or physical-device evidence.

## Editing rules

1. Put a fact in its owner document, then link to it. Keep README focused on
   purpose, setup, capability boundaries and the next useful guide.
2. Keep historical results factual and dated. Do not rewrite a past failure as a
   success, or treat a proposed feature as delivered behavior.
3. When capability or privacy facts move, check the README translations, privacy
   notice, capability/profile JSON, release checklist and store-policy draft for
   consistency. Preserve pending approval decisions while refreshing hashes.
4. Do not include secrets, private endpoints, user data, sensitive screenshots,
   signing material or restricted model files.
5. Keep links relative, headings descriptive and tables compact. Use an image
   only when it adds information; give it useful alt text and distinguish
   illustrations from real application screenshots.
6. For documentation-only work, run relevant link, formatting, JSON and verifier
   checks. Build/device evidence remains a separate validation tier; do not
   imply it ran because the documentation checks passed.
