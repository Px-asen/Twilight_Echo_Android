package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** The picker retains this ticket, not whichever entity happens to be visible when it returns. */
data class DesktopEntityEditTicket(val session: Long, val scope: DesktopLibraryScope, val entityId: String)

data class DesktopEntityEditorState(
    val entity: CatalogEntity? = null, val host: String = "", val ticket: DesktopEntityEditTicket? = null,
    val metadata: EntityMetadataDetail? = null, val metadataLoading: Boolean = false,
    val metadataWritable: Boolean = false, val metadataError: String? = null,
    val introductionDraft: String = "", val introductionDirty: Boolean = false,
    val targets: List<CatalogEntity> = emptyList(), val selectedTarget: CatalogEntity? = null,
    val personalLoading: Boolean = false, val preferenceEligible: Boolean = false,
    val rating: DesktopRatingDetail? = null, val preference: DesktopPreferenceDetail? = null,
    val personalWritable: Boolean = false, val personalError: String? = null,
    val tags: List<DesktopTag> = emptyList(), val assignedTags: Set<String> = emptySet(),
    val tagsToken: JsonObject? = null, val tagsLoading: Boolean = false, val tagsWritable: Boolean = false, val tagsError: String? = null,
    val tagNameDraft: String = "", val busy: String? = null, val notice: String? = null
)

/** One owner for an editor session. Reads may be cancelled; a submitted write is never replayed. */
internal class DesktopEntityEditorPresenter(
    private val coroutineScope: CoroutineScope,
    private val client: DesktopLibraryManagementClient,
    private val metadataChanged: () -> Unit = {}
) {
    private val mutable = MutableStateFlow(DesktopEntityEditorState())
    val state = mutable.asStateFlow()
    private var session = 0L
    private var personalRequest = 0L
    private var metadataJob: Job? = null
    private var targetsJob: Job? = null
    private var personalJob: Job? = null

    fun reset() {
        session++; personalRequest++
        metadataJob?.cancel(); targetsJob?.cancel(); personalJob?.cancel()
        mutable.value = DesktopEntityEditorState()
    }

    fun close() { if (mutable.value.busy == null) reset() }

    fun open(entity: CatalogEntity, host: String) {
        if (mutable.value.busy != null) return
        reset()
        val binding = runCatching { client.scope() }
        val ticket = binding.getOrNull()?.let { DesktopEntityEditTicket(session, it, entity.id) }
        mutable.value = DesktopEntityEditorState(entity = entity, host = host, ticket = ticket,
            metadataError = binding.exceptionOrNull()?.message)
        if (ticket != null) refresh()
    }

    fun introductionDraft(value: String) {
        if (mutable.value.busy == null) mutable.update { it.copy(introductionDraft = value, introductionDirty = true) }
    }
    fun tagNameDraft(value: String) {
        if (mutable.value.busy == null) mutable.update { it.copy(tagNameDraft = value) }
    }

    fun refresh() {
        val current = mutable.value
        val ticket = current.ticket ?: return
        val entity = current.entity ?: return
        if (current.busy != null) return
        metadataJob?.cancel(); targetsJob?.cancel(); personalJob?.cancel(); personalRequest++
        mutable.update { it.copy(metadataLoading = true, metadataWritable = false, metadataError = null,
            personalLoading = true, personalWritable = false, tagsLoading = false, tagsWritable = false,
            personalError = null, tagsError = null, notice = null) }
        metadataJob = coroutineScope.launch { readMetadata(ticket) }
        targetsJob = coroutineScope.launch {
            try {
                val targets = if (entity.kind in setOf("recording", "release", "artist", "work")) {
                    val all = mutableListOf<CatalogEntity>()
                    var offset = 0
                    do {
                        val page = client.targets(ticket.scope, entity.id, rating = false, offset = offset).getOrThrow()
                        all += page.items
                        offset = page.offset + page.items.size
                        check(page.items.isNotEmpty() || offset >= page.total) { "对象列表未读完，请刷新" }
                    } while (offset < page.total)
                    all.toList()
                } else emptyList()
                if (!owns(ticket)) return@launch
                val target = targets.firstOrNull { it.id == current.selectedTarget?.id }
                    ?: targets.singleOrNull()
                    ?: entity.takeIf { it.kind in setOf("artist", "work") && targets.isEmpty() }
                mutable.update { it.copy(targets = targets, selectedTarget = target,
                    preferenceEligible = targets.any { candidate -> candidate.id == target?.id }, personalLoading = false) }
                if (target != null) loadPersonal(ticket, target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(ticket)) mutable.update {
                it.copy(personalLoading = false, personalError = e.message, tagsError = "尚未确定具体整理对象")
            } }
        }
    }

    fun selectTarget(id: String) {
        val current = mutable.value
        val ticket = current.ticket ?: return
        if (current.busy != null || current.personalLoading) return
        val target = current.targets.firstOrNull { it.id == id } ?: return
        personalRequest++; personalJob?.cancel()
        mutable.update { it.copy(selectedTarget = target, preferenceEligible = true,
            rating = null, preference = null, tags = emptyList(), assignedTags = emptySet(), tagsToken = null) }
        loadPersonal(ticket, target)
    }

    private fun loadPersonal(ticket: DesktopEntityEditTicket, target: CatalogEntity) {
        val request = ++personalRequest
        mutable.update { it.copy(personalLoading = true, personalWritable = false, tagsLoading = true, tagsWritable = false,
            personalError = null, tagsError = null) }
        personalJob = coroutineScope.launch {
            val rating = if (target.kind in setOf("recording", "release")) client.rating(ticket.scope, target.id) else null
            val preference = if (mutable.value.preferenceEligible) client.preference(ticket.scope, target.id) else null
            if (!owns(ticket) || request != personalRequest) return@launch
            mutable.update { it.copy(rating = rating?.getOrNull(), preference = preference?.getOrNull(),
                personalLoading = false,
                personalWritable = rating?.isFailure != true && preference?.isFailure != true,
                personalError = rating?.exceptionOrNull()?.message ?: preference?.exceptionOrNull()?.message) }
            try {
                // Both lists share one frozen token. Assigned tags beyond page one must not be lost.
                val assigned = allTags(ticket, target.id, null)
                val available = allTags(ticket, null, assigned.second)
                if (owns(ticket) && request == personalRequest) mutable.update {
                    it.copy(tags = available.first, assignedTags = assigned.first.mapTo(linkedSetOf()) { tag -> tag.id },
                        tagsToken = available.second, tagsWritable = true, tagsLoading = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns(ticket) && request == personalRequest) mutable.update {
                it.copy(tagsLoading = false, tagsError = e.message)
            } }
        }
    }

    private suspend fun allTags(ticket: DesktopEntityEditTicket, entityId: String?, initial: JsonObject?): Pair<List<DesktopTag>, JsonObject> {
        val items = mutableListOf<DesktopTag>()
        var token = initial
        var offset = 0
        do {
            val page = client.tags(ticket.scope, entityId, offset, token).getOrThrow()
            token = page.token
            items += page.items
            offset = page.offset + page.items.size
            check(page.items.isNotEmpty() || offset >= page.total) { "标签列表未读完，请刷新" }
        } while (offset < page.total)
        return items to token!!
    }

    private suspend fun readMetadata(ticket: DesktopEntityEditTicket) {
        val result = client.metadata(ticket.scope, ticket.entityId)
        if (!owns(ticket)) return
        result.fold({ detail -> mutable.update {
            it.copy(metadata = detail, metadataLoading = false, metadataWritable = true, metadataError = null,
                introductionDraft = if (it.introductionDirty) it.introductionDraft else
                    detail.introductionOverride?.takeIf { override -> override.mode == "value" }?.value ?: detail.introduction?.value.orEmpty())
        } }, { error -> mutable.update { it.copy(metadataLoading = false, metadataWritable = false, metadataError = error.message) } })
    }

    fun rate(steps: Int?) {
        val current = mutable.value
        val detail = current.rating?.takeIf { current.personalWritable } ?: return
        write("保存评分", Domain.PERSONAL) { ticket ->
            val result = client.rate(ticket.scope, detail, steps).getOrThrow()
            if (owns(ticket)) mutable.update { it.copy(rating = result, notice = "评分已保存到 ${it.host}") }
        }
    }

    fun prefer(value: String) {
        val current = mutable.value
        val detail = current.preference?.takeIf { current.personalWritable } ?: return
        write("保存偏好", Domain.PERSONAL) { ticket ->
            val result = client.prefer(ticket.scope, detail, value).getOrThrow()
            if (owns(ticket)) mutable.update { it.copy(preference = result, notice = "偏好已保存到 ${it.host}") }
        }
    }

    fun tag(id: String, present: Boolean) {
        val current = mutable.value
        val target = current.selectedTarget ?: return
        val tag = current.tags.firstOrNull { it.id == id }?.takeIf { current.tagsWritable } ?: return
        write("保存标签", Domain.TAGS) { ticket ->
            val result = client.tagMember(ticket.scope, tag, target.id, present).getOrThrow()
            if (owns(ticket)) applyTag(result, present, "标签已保存")
        }
    }

    fun createTag() {
        val current = mutable.value
        val target = current.selectedTarget ?: return
        val revision = current.tagsToken?.get("tagsRevision")?.jsonPrimitive?.longOrNull ?: return
        val name = current.tagNameDraft.trim().takeIf { it.length in 1..80 && current.tagsWritable } ?: return
        write("新建并添加标签", Domain.TAGS) { ticket ->
            val created = client.createTag(ticket.scope, name, revision).getOrThrow()
            if (owns(ticket)) mutable.update { it.copy(tags = (it.tags + created.tag).sortedBy { tag -> tag.name },
                tagsToken = created.token, tagNameDraft = "", notice = "「${created.tag.name}」已创建，尚未添加") }
            if (!owns(ticket)) return@write
            // A second, separate commit: failure must not cause the creation to be replayed.
            val assigned = client.tagMember(ticket.scope, created.tag, target.id, true).getOrThrow()
            if (owns(ticket)) applyTag(assigned, true, "「${created.tag.name}」已创建并添加")
        }
    }

    private fun applyTag(result: DesktopTagWrite, present: Boolean, notice: String) {
        mutable.update { it.copy(tags = it.tags.map { tag -> if (tag.id == result.tag.id) result.tag else tag },
            assignedTags = if (present) it.assignedTags + result.tag.id else it.assignedTags - result.tag.id,
            tagsToken = result.token, notice = notice) }
    }

    fun introduction(mode: String) {
        val current = mutable.value
        val detail = current.metadata?.takeIf { current.metadataWritable } ?: return
        val value = current.introductionDraft.takeIf { mode == "value" }
        metadataWrite("保存简介") { ticket -> client.introduction(ticket.scope, detail, mode, value).getOrThrow() }
    }

    fun source(provider: String?) {
        val current = mutable.value
        val detail = current.metadata?.takeIf { current.metadataWritable } ?: return
        metadataWrite("保存资料来源", resetDraft = false) { ticket -> client.metadataSource(ticket.scope, detail, provider).getOrThrow() }
    }

    fun artwork(mode: String) {
        val current = mutable.value
        val detail = current.metadata?.takeIf { current.metadataWritable } ?: return
        metadataWrite("保存图片", resetDraft = false) { ticket -> client.artwork(ticket.scope, detail, mode).getOrThrow() }
    }

    fun importArtwork(ticket: DesktopEntityEditTicket, uri: String, import: suspend (String) -> String) {
        val current = mutable.value
        if (!owns(ticket) || !current.metadataWritable) return
        val detail = current.metadata ?: return
        metadataWrite("上传图片", resetDraft = false) {
            val data = import(uri)
            check(owns(ticket)) { "资料页已改变，请重新选图" }
            client.artwork(ticket.scope, detail, "value", data).getOrThrow()
        }
    }

    private fun metadataWrite(label: String, resetDraft: Boolean = true, action: suspend (DesktopEntityEditTicket) -> Unit) =
        write(label, Domain.METADATA) { ticket ->
            action(ticket)
            if (owns(ticket)) {
                mutable.update { it.copy(metadataWritable = false, introductionDirty = if (resetDraft) false else it.introductionDirty,
                    notice = "资料已保存到 ${it.host}", metadataLoading = true) }
                metadataChanged()
                // A failed follow-up read is distinct from the successful save.
                readMetadata(ticket)
            }
        }

    private enum class Domain { PERSONAL, TAGS, METADATA }
    private fun write(label: String, domain: Domain, action: suspend (DesktopEntityEditTicket) -> Unit) {
        val current = mutable.value
        val ticket = current.ticket ?: return
        if (current.busy != null) return
        mutable.update { it.copy(busy = label, notice = null,
            personalError = if (domain == Domain.PERSONAL) null else it.personalError,
            tagsError = if (domain == Domain.TAGS) null else it.tagsError,
            metadataError = if (domain == Domain.METADATA) null else it.metadataError) }
        coroutineScope.launch {
            try { action(ticket) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owns(ticket)) {
                    val error = if ((e as? CoreRequestException)?.error?.code == CoreErrorCode.OUTCOME_UNKNOWN)
                        "结果未确认，请刷新后核对，不要重复提交。${e.message.orEmpty()}" else e.message ?: "操作失败，请刷新后再试"
                    mutable.update { when (domain) {
                        Domain.PERSONAL -> it.copy(personalError = error, personalWritable = false)
                        Domain.TAGS -> it.copy(tagsError = error, tagsWritable = false)
                        Domain.METADATA -> it.copy(metadataError = error, metadataWritable = false)
                    } }
                }
            } finally { if (owns(ticket)) mutable.update { it.copy(busy = null) } }
        }
    }

    private fun owns(ticket: DesktopEntityEditTicket) = mutable.value.ticket == ticket && session == ticket.session
}
