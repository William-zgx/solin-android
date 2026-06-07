# Model Manifest

PocketMind treats recommended model files as pinned artifacts, not mutable
links. Each recommended download must include an immutable upstream revision,
expected byte size, and SHA-256 digest. The app verifies downloaded recommended
models before registering them.

The license columns are a release-readiness checklist, not legal advice.
License names, redistribution rights, attribution, and notice requirements must
be manually checked against the upstream model repositories before a release
candidate. `VERIFY_MODEL_URLS=1` can verify availability and file metadata; it
does not verify licensing.
`scripts/collect_model_license_metadata.sh` records current Hugging Face model
card license metadata in `docs/model_license_metadata.json`, but that file is
only an input to human review. The collector derives the model list from this
manifest, not from the review record, so stale review files cannot narrow the
set of recommended models. The release gate still reads
`docs/model_license_review.json` for final approval and requires each approval
to name the manifest repository and pinned upstream revision.

| ID | File | Repository | Upstream revision | Bytes | SHA-256 | License status |
| --- | --- | --- | --- | ---: | --- | --- |
| `chat-e2b` | `gemma-4-E2B-it.litertlm` | `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm` | `a4a831c060880f3733135ad22f10e0e9f758f45d` | `2588147712` | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | Release blocker: manually verify upstream license, attribution, notice, and redistribution terms. |
| `memory-embedding-300m` | `embeddinggemma-300m.litertlm` | `https://huggingface.co/kontextdev/embeddinggemma-300m-litertlm` | `96fa469293abd2da72b46aeeafea3bb571468dfe` | `179159040` | `80e9596830fdd083cbc741dad666c0186439b0ba7b30112b552094650960b1cd` | Release blocker: manually verify upstream license, attribution, notice, and redistribution terms. |
| `mobile-action-270m` | `mobile-actions_q8_ekv1024.litertlm` | `https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm` | `82d0f654a6270c518d16c600edce3136221b3347` | `284426240` | `92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409` | Release blocker: manually verify upstream license, attribution, notice, and redistribution terms. |
| `chat-e4b` | `gemma-4-E4B-it.litertlm` | `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm` | `65ce5ba80d8790d66ef11d82d7d079a06f3fef97` | `3659530240` | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` | Release blocker: manually verify upstream license, attribution, notice, and redistribution terms. |

Custom imported or custom URL models are allowed, but they are user-supplied
and are not covered by this manifest.

For each release candidate, record the manifest repository, pinned upstream
revision, verified license name, source URL or license file path, attribution
or notice obligations, redistribution decision, reviewer, and review date in
the release checklist.
