package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryArgumentResolverTest {
    @Test
    fun resolvesHomeAddressFromExplicitChineseRecord() {
        val records = rememberAll("我家在北京市朝阳区望京西园三区", "我的血型是O型")

        val resolved = MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, records)

        assertEquals("北京市朝阳区望京西园三区", resolved?.value)
        assertEquals(MessagePrivacy.LocalOnly, resolved?.privacy)
        assertEquals(records.first().id, resolved?.recordId)
    }

    @Test
    fun resolvesHomeAddressFromOtherPhrasings() {
        assertEquals("海淀区学院路1号", homeOf("我的家庭住址是海淀区学院路1号"))
        assertEquals("望京", homeOf("我住在望京。"))
        assertEquals("Shanghai Pudong", homeOf("I live in Shanghai Pudong"))
        assertEquals("221B Baker Street", homeOf("my home address is 221B Baker Street"))
    }

    @Test
    fun resolvesWorkAddress() {
        val records = rememberAll("我的公司地址是海淀区中关村软件园二期")

        assertEquals(
            "海淀区中关村软件园二期",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.WorkAddress, records)?.value,
        )
        assertEquals("国贸三期", workOf("我在国贸三期上班"))
        assertEquals("Acme Labs", workOf("I work at Acme Labs"))
    }

    @Test
    fun resolvesRelationPhoneIncludingAliases() {
        val records = rememberAll("我老婆的电话是138-1234-5678", "我妈妈的手机号是13900001111")

        assertEquals("13812345678", phoneOf("老婆", records))
        assertEquals("13812345678", phoneOf("妻子", records))
        assertEquals("13900001111", phoneOf("妈", records))
        assertNull(phoneOf("爸爸", records))
    }

    @Test
    fun resolvesPhoneThroughRememberedRelationName() {
        val byRelation = rememberAll("我老婆叫林小雨", "我老婆的电话是13812345678")
        val byName = rememberAll("我老婆叫林小雨", "林小雨的电话是13812345678")

        assertEquals("13812345678", phoneOf("林小雨", byRelation))
        assertEquals("13812345678", phoneOf("老婆", byName))
        assertEquals(
            "林小雨",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.RelationName("老婆"), byRelation)?.value,
        )
    }

    @Test
    fun resolvesEnglishRelationPhoneAndName() {
        val records = rememberAll("my wife's name is Emily", "my wife's phone number is +1 555 0100")

        assertEquals("+15550100", phoneOf("wife", records))
        assertEquals("+15550100", phoneOf("Emily", records))
    }

    @Test
    fun conflictingValuesResolveToNull() {
        val records = listOf(
            record("a", MemoryRecordType.Preference, "用户偏好：我家在望京"),
            record("b", MemoryRecordType.Preference, "用户偏好：我住在通州"),
        )

        assertNull(MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, records))
    }

    @Test
    fun ignoresNonExplicitNonLocalAndExpiredRecords() {
        val now = 10_000L
        val records = listOf(
            record("conv", MemoryRecordType.Conversation, "用户：我家在望京"),
            record("task", MemoryRecordType.TaskState, "任务状态：我家在望京"),
            record("remote", MemoryRecordType.Preference, "用户偏好：我家在望京", privacy = MessagePrivacy.RemoteEligible),
            record("expired", MemoryRecordType.Preference, "用户偏好：我家在望京", expiresAtMillis = now),
        )

        assertNull(MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, records, nowMillis = now))
    }

    @Test
    fun storageFailureResolvesToNull() {
        val repository = MemoryRepository(recordStore = FakeMemoryRecordStore(failure = IllegalStateException("db")))

        assertNull(MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, repository))
    }

    @Test
    fun resolvesThroughRepositoryControls() {
        val repository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        remember(repository, "我家在北京市朝阳区望京西园三区")

        assertEquals(
            "北京市朝阳区望京西园三区",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, repository)?.value,
        )
    }

    @Test
    fun placeSlotForMapsOnlyGenericHomeAndWorkTargets() {
        assertEquals(MemoryArgumentSlot.HomeAddress, MemoryArgumentResolver.placeSlotFor("家"))
        assertEquals(MemoryArgumentSlot.HomeAddress, MemoryArgumentResolver.placeSlotFor("回家"))
        assertEquals(MemoryArgumentSlot.HomeAddress, MemoryArgumentResolver.placeSlotFor("我家"))
        assertEquals(MemoryArgumentSlot.HomeAddress, MemoryArgumentResolver.placeSlotFor("home"))
        assertEquals(MemoryArgumentSlot.WorkAddress, MemoryArgumentResolver.placeSlotFor("公司"))
        assertEquals(MemoryArgumentSlot.WorkAddress, MemoryArgumentResolver.placeSlotFor("office"))
        assertNull(MemoryArgumentResolver.placeSlotFor("天安门"))
        assertNull(MemoryArgumentResolver.placeSlotFor("家乐福"))
    }

    private fun homeOf(fact: String): String? =
        MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, rememberAll(fact))?.value

    private fun workOf(fact: String): String? =
        MemoryArgumentResolver.resolve(MemoryArgumentSlot.WorkAddress, rememberAll(fact))?.value

    private fun phoneOf(contact: String, records: List<PersistedMemoryRecord>): String? =
        MemoryArgumentResolver.resolve(MemoryArgumentSlot.ContactPhone(contact), records)?.value

    /** Stores [facts] through the same explicit "记住…" routing the chat path uses. */
    private fun rememberAll(vararg facts: String): List<PersistedMemoryRecord> {
        val repository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        facts.forEach { fact -> remember(repository, fact) }
        return repository.savedRecords()
    }

    private fun remember(repository: MemoryRepository, fact: String) {
        val command = "记住$fact"
        val userFact = explicitUserFactFrom(command)
        if (userFact != null) {
            repository.indexUserFact(explicitUserFactRecordId(userFact), userFact)
        } else {
            val preference = requireNotNull(explicitUserPreferenceFrom(command))
            repository.indexPreference(explicitUserPreferenceRecordId(preference), preference)
        }
    }

    private fun record(
        id: String,
        type: MemoryRecordType,
        text: String,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
        expiresAtMillis: Long? = null,
    ) = PersistedMemoryRecord(
        id = id,
        type = type,
        text = text,
        source = MemoryRecordSource.ExplicitUser,
        privacy = privacy,
        expiresAtMillis = expiresAtMillis,
    )
}
