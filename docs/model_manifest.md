# Model Manifest

PocketMind treats recommended model files as pinned artifacts, not mutable links.
Each recommended download must include an immutable upstream revision, expected
byte size, and SHA-256 digest. The app verifies downloaded recommended models
before registering them.

| ID | File | Upstream revision | Bytes | SHA-256 |
| --- | --- | --- | ---: | --- |
| `chat-e2b` | `gemma-4-E2B-it.litertlm` | `a4a831c060880f3733135ad22f10e0e9f758f45d` | `2588147712` | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `memory-embedding-300m` | `embeddinggemma-300m.litertlm` | `96fa469293abd2da72b46aeeafea3bb571468dfe` | `179159040` | `80e9596830fdd083cbc741dad666c0186439b0ba7b30112b552094650960b1cd` |
| `mobile-action-270m` | `mobile-actions_q8_ekv1024.litertlm` | `82d0f654a6270c518d16c600edce3136221b3347` | `284426240` | `92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409` |
| `chat-e4b` | `gemma-4-E4B-it.litertlm` | `65ce5ba80d8790d66ef11d82d7d079a06f3fef97` | `3659530240` | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |

Custom imported or custom URL models are allowed, but they are user-supplied
and are not covered by this manifest.
