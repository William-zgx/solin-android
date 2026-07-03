package com.bytedance.zgx.solin.skill

/** Supplies SkillManifest lists to the runtime. Collected from SolinModules at startup. */
fun interface SkillSource {
    fun manifests(): List<SkillManifest>
}
